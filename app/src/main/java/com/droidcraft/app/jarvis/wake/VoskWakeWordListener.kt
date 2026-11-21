package com.droidcraft.app.jarvis.wake

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * On-device, wake-phrase-only listener. Both compact Vosk models share one microphone stream;
 * no transcript is retained or sent to a network service.
 */
internal class VoskWakeWordListener(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onWakeWord: () -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private val appContext = context.applicationContext
    private val stopped = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DroidCraft-Vosk-WakeWord").apply { isDaemon = true }
    }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var activeDownload: Call? = null

    fun start() {
        worker.execute { listen() }
    }

    fun stop() {
        stopped.set(true)
        activeDownload?.cancel()
        runCatching { audioRecord?.stop() }
        worker.shutdown()
    }

    private fun listen() {
        var recorder: AudioRecord? = null
        try {
            if (stopped.get()) return
            onStatus("Preparing compact Hindi and Indian English models…")
            val modelRepository = VoskModelRepository(appContext, httpClient, onStatus) { activeDownload = it }
            val englishDirectory = modelRepository.ensure(ENGLISH_MODEL) { stopped.get() }
            val hindiDirectory = modelRepository.ensure(HINDI_MODEL) { stopped.get() }
            if (stopped.get()) return

            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(englishDirectory.absolutePath).use { englishModel ->
                Model(hindiDirectory.absolutePath).use { hindiModel ->
                    Recognizer(englishModel, SAMPLE_RATE.toFloat(), ENGLISH_WAKE_GRAMMAR).use { englishRecognizer ->
                        Recognizer(hindiModel, SAMPLE_RATE.toFloat(), HINDI_WAKE_GRAMMAR).use { hindiRecognizer ->
                            if (stopped.get()) return
                            val activeRecorder = createAudioRecord()
                            recorder = activeRecorder
                            audioRecord = activeRecorder
                            activeRecorder.startRecording()
                            if (activeRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                                throw IOException("Android did not start microphone recording.")
                            }
                            onStatus("Listening offline for ‘Hey Jarvis’ in Hindi and English")

                            val audio = ByteArray(AUDIO_BUFFER_BYTES)
                            while (!stopped.get()) {
                                val byteCount = activeRecorder.read(
                                    audio,
                                    0,
                                    audio.size,
                                    AudioRecord.READ_BLOCKING
                                )
                                if (byteCount > 0) {
                                    if (matchesWakeWord(englishRecognizer, audio, byteCount) ||
                                        matchesWakeWord(hindiRecognizer, audio, byteCount)
                                    ) {
                                        stopped.set(true)
                                        onWakeWord()
                                        break
                                    }
                                } else if (byteCount < 0 && !stopped.get()) {
                                    throw IOException("Microphone read failed (AudioRecord error $byteCount).")
                                }
                            }
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            if (!stopped.get()) onFailure(failure)
        } finally {
            audioRecord = null
            recorder?.let { record ->
                runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
                runCatching { record.release() }
            }
        }
    }

    private fun createAudioRecord(): AudioRecord {
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimumBuffer <= 0) throw IOException("The device does not support 16 kHz microphone input.")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimumBuffer, AUDIO_BUFFER_BYTES * 2)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("Could not open the microphone for offline wake detection.")
        }
        return recorder
    }

    private fun matchesWakeWord(recognizer: Recognizer, audio: ByteArray, byteCount: Int): Boolean {
        val isFinal = recognizer.acceptWaveForm(audio, byteCount)
        val payload = JSONObject(if (isFinal) recognizer.result else recognizer.partialResult)
        val recognized = payload.optString(if (isFinal) "text" else "partial")
        return containsWakePhrase(recognized)
    }

    private fun containsWakePhrase(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT)
            .replace('\u200c', ' ')
            .replace('\u200d', ' ')
            .replace(Regex("[\\p{P}\\p{S}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return ENGLISH_WAKE_PATTERN.containsMatchIn(normalized) ||
            HINDI_WAKE_PATTERN.containsMatchIn(normalized)
    }

    private class VoskModelRepository(
        context: Context,
        private val httpClient: OkHttpClient,
        private val onStatus: (String) -> Unit,
        private val onActiveCall: (Call?) -> Unit
    ) {
        private val modelDirectory = File(context.filesDir, "vosk-models").apply { mkdirs() }

        fun ensure(spec: ModelSpec, isCancelled: () -> Boolean): File {
            val destination = File(modelDirectory, spec.directoryName)
            if (isValidModel(destination)) return destination
            destination.deleteRecursively()

            val archive = File(modelDirectory, spec.archiveName)
            if (!archive.isFile || sha256(archive) != spec.sha256) {
                archive.delete()
                download(spec, archive, isCancelled)
            }
            if (isCancelled()) throw InterruptedIOException("Wake-word setup was stopped.")
            if (sha256(archive) != spec.sha256) {
                archive.delete()
                throw IOException("The downloaded ${spec.label} model failed its SHA-256 check.")
            }

            val staging = File(modelDirectory, "${spec.directoryName}.extracting")
            staging.deleteRecursively()
            if (!staging.mkdirs()) throw IOException("Could not prepare model storage.")
            var installed = false
            try {
                onStatus("Installing offline ${spec.label} model…")
                extractSafely(archive, staging, isCancelled)
                val unpackedModel = File(staging, spec.directoryName)
                if (!isValidModel(unpackedModel)) {
                    throw IOException("The ${spec.label} model archive did not contain a usable Vosk model.")
                }
                if (!unpackedModel.renameTo(destination)) {
                    throw IOException("Could not install the ${spec.label} Vosk model.")
                }
                installed = true
                archive.delete()
                staging.deleteRecursively()
                return destination
            } finally {
                if (!installed) staging.deleteRecursively()
            }
        }

        private fun download(spec: ModelSpec, destination: File, isCancelled: () -> Boolean) {
            val partial = File(destination.parentFile, "${destination.name}.part")
            partial.delete()
            onStatus("Downloading ${spec.label} Vosk model once for offline use…")
            val call = httpClient.newCall(Request.Builder().url(spec.url).build())
            onActiveCall(call)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Could not download the ${spec.label} Vosk model (HTTP ${response.code}).")
                    }
                    val body = response.body ?: throw IOException("The ${spec.label} model download was empty.")
                    val totalBytes = body.contentLength()
                    var nextProgress = 10
                    var downloaded = 0L
                    body.byteStream().use { input ->
                        BufferedOutputStream(FileOutputStream(partial)).use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            while (true) {
                                if (isCancelled()) throw InterruptedIOException("Wake-word setup was stopped.")
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                output.write(buffer, 0, count)
                                downloaded += count
                                if (totalBytes > 0) {
                                    val progress = ((downloaded * 100) / totalBytes).toInt()
                                    if (progress >= nextProgress) {
                                        onStatus("Downloading ${spec.label} Vosk model… $progress%")
                                        nextProgress = progress + 10
                                    }
                                }
                            }
                        }
                    }
                }
                if (isCancelled()) throw InterruptedIOException("Wake-word setup was stopped.")
                if (!partial.renameTo(destination)) {
                    throw IOException("Could not finish saving the ${spec.label} model download.")
                }
            } catch (failure: Throwable) {
                partial.delete()
                throw failure
            } finally {
                onActiveCall(null)
            }
        }

        private fun extractSafely(archive: File, destination: File, isCancelled: () -> Boolean) {
            val destinationPath = destination.canonicalFile.toPath()
            var extractedBytes = 0L
            ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    if (isCancelled()) throw InterruptedIOException("Wake-word setup was stopped.")
                    val entry = zip.nextEntry ?: break
                    val output = File(destination, entry.name)
                    val outputPath = output.canonicalFile.toPath()
                    if (!outputPath.startsWith(destinationPath)) {
                        throw IOException("The Vosk model archive contains an invalid path.")
                    }
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(output)).use { fileOutput ->
                            while (true) {
                                if (isCancelled()) throw InterruptedIOException("Wake-word setup was stopped.")
                                val count = zip.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                extractedBytes += count
                                if (extractedBytes > MAX_MODEL_UNPACKED_BYTES) {
                                    throw IOException("The downloaded Vosk model is larger than expected.")
                                }
                                fileOutput.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }

        private fun isValidModel(directory: File): Boolean =
            File(directory, "am/final.mdl").isFile &&
                File(directory, "conf/model.conf").isFile &&
                File(directory, "graph/HCLr.fst").isFile

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    private data class ModelSpec(
        val label: String,
        val directoryName: String,
        val archiveName: String,
        val url: String,
        val sha256: String
    )

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val AUDIO_BUFFER_BYTES = 4_096
        const val DOWNLOAD_BUFFER_BYTES = 32 * 1_024
        const val MAX_MODEL_UNPACKED_BYTES = 300L * 1_024 * 1_024

        val ENGLISH_MODEL = ModelSpec(
            label = "Indian English",
            directoryName = "vosk-model-small-en-in-0.4",
            archiveName = "vosk-model-small-en-in-0.4.zip",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip",
            sha256 = "20663dcac4d5cb783a579c54d98339344a688e4ec6e1b4a4b059fd1235454cc7"
        )
        val HINDI_MODEL = ModelSpec(
            label = "Hindi",
            directoryName = "vosk-model-small-hi-0.22",
            archiveName = "vosk-model-small-hi-0.22.zip",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
            sha256 = "7c50a10866889f0ac21d912c20537a055a597ed09fc1d3e5bcd798f9f0017e48"
        )
        val ENGLISH_WAKE_GRAMMAR = JSONArray()
            .put("hey jarvis")
            .put("[unk]")
            .toString()
        val HINDI_WAKE_GRAMMAR = JSONArray()
            .put("hey jarvis")
            .put("हे जार्विस")
            .put("हाय जार्विस")
            .put("[unk]")
            .toString()
        val ENGLISH_WAKE_PATTERN = Regex("(?:^| )hey jarvis(?: |$)")
        val HINDI_WAKE_PATTERN = Regex("(?:^| )(?:हे|हाय) (?:जार्विस|जर्विस)(?: |$)")
    }
}
