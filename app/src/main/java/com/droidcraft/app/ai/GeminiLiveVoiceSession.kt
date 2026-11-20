package com.droidcraft.app.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Base64
import com.droidcraft.app.model.LiveConversationStatus
import com.droidcraft.app.model.LiveVoiceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface LiveVoiceSessionListener {
    fun onStatus(status: LiveConversationStatus, message: String? = null)
    fun onUserTranscript(text: String, isFinal: Boolean)
    fun onAssistantDraft(text: String)
    fun onAssistantMessage(text: String)
    fun onEffectiveVoiceChanged(mode: LiveVoiceMode, message: String? = null)
    fun onNotice(message: String)
}

/**
 * A single Gemini Live WebSocket session. Gemini remains the live conversation brain and
 * always supplies its own PCM audio as a fallback. In ElevenLabs mode that audio is buffered
 * (not played) while ElevenLabs renders each reply in the selected voice.
 */
internal class GeminiLiveVoiceSession(
    context: Context,
    private val geminiApiKey: String,
    requestedVoice: LiveVoiceMode,
    private val elevenLabsApiKey: String,
    private val listener: LiveVoiceSessionListener
) {
    private val appContext = context.applicationContext
    private val stopped = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val audioPlayer = PcmAudioPlayer()
    private val elevenLabsTtsClient = ElevenLabsTtsClient(elevenLabsApiKey)
    private val responseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val synthesisMutex = Mutex()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var setupComplete = false

    @Volatile
    private var activeVoice = if (requestedVoice == LiveVoiceMode.ELEVENLABS && elevenLabsApiKey.isNotBlank()) {
        LiveVoiceMode.ELEVENLABS
    } else {
        LiveVoiceMode.GEMINI_LIVE
    }

    // WebSocket messages are delivered serially by OkHttp; only that callback thread mutates these.
    private val inputTranscript = StringBuilder()
    private val outputTranscript = StringBuilder()
    private val modelText = StringBuilder()
    private val currentGeminiAudio = ByteArrayOutputStream()
    private var currentTurnHadAudio = false

    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var microphoneThread: Thread? = null
    @Volatile
    private var echoCanceler: AcousticEchoCanceler? = null

    fun start() {
        if (stopped.get()) return
        listener.onStatus(LiveConversationStatus.CONNECTING)
        listener.onEffectiveVoiceChanged(activeVoice)

        val httpsUrl = HttpUrl.Builder()
            .scheme("https")
            .host("generativelanguage.googleapis.com")
            .addPathSegments("ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
            .addQueryParameter("key", geminiApiKey)
            .build()
        // OkHttp's WebSocket client accepts the standard wss URL and manages the upgrade.
        val request = Request.Builder()
            .url(httpsUrl.toString().replaceFirst("https://", "wss://"))
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(buildSetupMessage())) {
                    fail("Gemini Live could not start. Check your network and API key.")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!stopped.get()) {
                    fail("Gemini Live connection failed. Check the API key, network, and Live API access.")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped.get()) fail("Gemini Live ended the connection. Start a new conversation to reconnect.")
            }
        })
    }

    fun sendText(text: String): Boolean {
        if (!setupComplete || stopped.get() || text.isBlank()) return false
        val message = JSONObject()
            .put(
                "clientContent",
                JSONObject()
                    .put(
                        "turns",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put("parts", JSONArray().put(JSONObject().put("text", text)))
                        )
                    )
                    .put("turnComplete", true)
            )
            .toString()
        return webSocket?.send(message) == true
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        setupComplete = false
        stopMicrophone()
        runCatching {
            webSocket?.send(
                JSONObject()
                    .put("realtimeInput", JSONObject().put("audioStreamEnd", true))
                    .toString()
            )
        }
        runCatching { webSocket?.close(1000, "Conversation ended") }
        webSocket = null
        responseScope.cancel()
        audioPlayer.close()
        runCatching { client.dispatcher.executorService.shutdown() }
        listener.onStatus(LiveConversationStatus.IDLE)
    }

    private fun buildSetupMessage(): String {
        val speechConfig = JSONObject()
            .put(
                "voiceConfig",
                JSONObject().put(
                    "prebuiltVoiceConfig",
                    JSONObject().put("voiceName", GEMINI_PRESET_VOICE)
                )
            )
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", speechConfig)
        val systemInstruction = JSONObject().put(
            "parts",
            JSONArray().put(
                JSONObject().put(
                    "text",
                    "You are Jarvis, a helpful live voice assistant for a Hindi and English creator. " +
                        "Reply naturally in the language the user speaks (Hindi, Hinglish, or English). " +
                        "Keep spoken answers concise, warm, and emotionally expressive. Use clear conversational " +
                        "sentences, not markdown or stage directions."
                )
            )
        )
        return JSONObject()
            .put(
                "setup",
                JSONObject()
                    .put("model", LIVE_MODEL)
                    .put("generationConfig", generationConfig)
                    .put("systemInstruction", systemInstruction)
                    .put("inputAudioTranscription", JSONObject())
                    .put("outputAudioTranscription", JSONObject())
            )
            .toString()
    }

    private fun handleServerMessage(serialized: String) {
        if (stopped.get()) return
        val message = runCatching { JSONObject(serialized) }.getOrNull() ?: return

        if (message.has("error")) {
            fail("Gemini Live could not continue this conversation. Check your API key and Live API access.")
            return
        }
        if (message.has("setupComplete")) {
            setupComplete = true
            startMicrophone()
            return
        }

        val serverContent = message.optJSONObject("serverContent") ?: return
        val inputText = serverContent.optJSONObject("inputTranscription")?.optString("text").orEmpty()
        if (inputText.isNotBlank()) {
            appendTranscript(inputTranscript, inputText)
            listener.onUserTranscript(inputTranscript.toString(), false)
        }

        val outputText = serverContent.optJSONObject("outputTranscription")?.optString("text").orEmpty()
        if (outputText.isNotBlank()) {
            appendTranscript(outputTranscript, outputText)
            listener.onAssistantDraft(outputTranscript.toString())
        }

        val modelTurn = serverContent.optJSONObject("modelTurn")
        val parts = modelTurn?.optJSONArray("parts")
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                val text = part.optString("text").orEmpty()
                if (text.isNotBlank()) {
                    appendTranscript(modelText, text)
                    if (outputTranscript.isEmpty()) listener.onAssistantDraft(modelText.toString())
                }
                val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                val encodedAudio = inlineData?.optString("data").orEmpty()
                if (encodedAudio.isNotBlank()) {
                    val pcm = runCatching { Base64.decode(encodedAudio, Base64.DEFAULT) }.getOrNull()
                    if (pcm != null && pcm.isNotEmpty()) onGeminiAudio(pcm)
                }
            }
        }

        if (serverContent.optBoolean("turnComplete")) finishAssistantTurn()
    }

    /** Accepts either incremental text chunks or cumulative transcription snapshots. */
    private fun appendTranscript(target: StringBuilder, incoming: String) {
        val current = target.toString()
        if (incoming == current) return
        if (incoming.length > current.length && incoming.startsWith(current)) {
            target.setLength(0)
            target.append(incoming)
        } else {
            target.append(incoming)
        }
    }

    private fun onGeminiAudio(pcm: ByteArray) {
        currentTurnHadAudio = true
        if (activeVoice == LiveVoiceMode.ELEVENLABS) {
            if (currentGeminiAudio.size() + pcm.size <= MAX_BUFFERED_FALLBACK_BYTES) {
                currentGeminiAudio.write(pcm)
            }
        } else {
            listener.onStatus(LiveConversationStatus.SPEAKING)
            audioPlayer.enqueue(pcm)
        }
    }

    private fun finishAssistantTurn() {
        val userText = inputTranscript.toString().trim()
        val assistantText = (outputTranscript.takeIf { it.isNotEmpty() } ?: modelText).toString().trim()
        val nativeAudio = currentGeminiAudio.toByteArray()
        val hadAudio = currentTurnHadAudio

        inputTranscript.setLength(0)
        outputTranscript.setLength(0)
        modelText.setLength(0)
        currentGeminiAudio.reset()
        currentTurnHadAudio = false

        if (userText.isNotBlank()) listener.onUserTranscript(userText, true)
        if (assistantText.isNotBlank()) listener.onAssistantMessage(assistantText)

        if (activeVoice == LiveVoiceMode.ELEVENLABS) {
            listener.onStatus(LiveConversationStatus.SPEAKING)
            responseScope.launch {
                synthesisMutex.withLock {
                    if (stopped.get()) return@withLock
                    if (activeVoice != LiveVoiceMode.ELEVENLABS) {
                        playGeminiFallback(nativeAudio, hadAudio)
                        return@withLock
                    }
                    if (assistantText.isBlank()) {
                        switchToGemini("A live transcript was unavailable; continuing with Gemini's free voice.")
                        playGeminiFallback(nativeAudio, hadAudio)
                        return@withLock
                    }
                    try {
                        val speech = elevenLabsTtsClient.synthesizePcm(assistantText)
                        if (!stopped.get()) {
                            audioPlayer.enqueue(speech) { reportListeningIfActive() }
                        }
                    } catch (_: Exception) {
                        if (!stopped.get()) {
                            switchToGemini(
                                "ElevenLabs is unavailable (for example, credits may be exhausted). " +
                                    "Gemini Live (free) is continuing this conversation."
                            )
                            playGeminiFallback(nativeAudio, hadAudio)
                        }
                    }
                }
            }
        } else if (hadAudio) {
            audioPlayer.afterQueuedAudio { reportListeningIfActive() }
        } else {
            listener.onStatus(LiveConversationStatus.LISTENING)
        }
    }

    private fun playGeminiFallback(pcm: ByteArray, hadAudio: Boolean) {
        if (stopped.get()) return
        if (pcm.isNotEmpty()) {
            audioPlayer.enqueue(pcm) { reportListeningIfActive() }
        } else if (hadAudio) {
            audioPlayer.afterQueuedAudio { reportListeningIfActive() }
        } else {
            listener.onStatus(LiveConversationStatus.LISTENING)
        }
    }

    private fun switchToGemini(message: String) {
        activeVoice = LiveVoiceMode.GEMINI_LIVE
        listener.onEffectiveVoiceChanged(LiveVoiceMode.GEMINI_LIVE, message)
        listener.onNotice(message)
    }

    private fun reportListeningIfActive() {
        if (!stopped.get()) listener.onStatus(LiveConversationStatus.LISTENING)
    }

    private fun startMicrophone() {
        if (stopped.get()) return
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is required for Live Talk.")
            return
        }

        try {
            val minimumBuffer = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimumBuffer > 0) { "This device does not support 16 kHz audio input." }
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimumBuffer * 2, INPUT_CHUNK_BYTES * 2)
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized." }
            recorder.startRecording()
            audioRecord = recorder
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = runCatching {
                    AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { setEnabled(true) }
                }.getOrNull()
            }

            listener.onStatus(LiveConversationStatus.LISTENING)
            microphoneThread = Thread({ streamMicrophone(recorder) }, "droidcraft-live-microphone").apply {
                isDaemon = true
                start()
            }
        } catch (_: SecurityException) {
            fail("Microphone permission is required for Live Talk.")
        } catch (_: Exception) {
            fail("Could not start the microphone. Check microphone access and try again.")
        }
    }

    private fun streamMicrophone(recorder: AudioRecord) {
        val input = ByteArray(INPUT_CHUNK_BYTES)
        while (!stopped.get() && !Thread.currentThread().isInterrupted) {
            val read = try {
                recorder.read(input, 0, input.size, AudioRecord.READ_BLOCKING)
            } catch (_: Exception) {
                break
            }
            if (read > 0) {
                val encoded = Base64.encodeToString(input, 0, read, Base64.NO_WRAP)
                val payload = JSONObject()
                    .put(
                        "realtimeInput",
                        JSONObject().put(
                            "audio",
                            JSONObject()
                                .put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
                                .put("data", encoded)
                        )
                    )
                    .toString()
                if (webSocket?.send(payload) != true && !stopped.get()) {
                    fail("Gemini Live connection was interrupted. Start a new conversation to reconnect.")
                    break
                }
            } else if (read < 0 && !stopped.get()) {
                fail("Microphone audio could not be read. Check microphone access and try again.")
                break
            }
        }
    }

    private fun stopMicrophone() {
        microphoneThread?.interrupt()
        microphoneThread = null
        val recorder = audioRecord
        audioRecord = null
        runCatching {
            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        }
        runCatching { recorder?.release() }
        runCatching { echoCanceler?.release() }
        echoCanceler = null
    }

    private fun fail(message: String) {
        if (!stopped.compareAndSet(false, true)) return
        setupComplete = false
        stopMicrophone()
        runCatching { webSocket?.cancel() }
        webSocket = null
        responseScope.cancel()
        audioPlayer.close()
        runCatching { client.dispatcher.executorService.shutdown() }
        listener.onStatus(LiveConversationStatus.ERROR, message)
    }

    private companion object {
        const val LIVE_MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        const val GEMINI_PRESET_VOICE = "Aoede"
        const val INPUT_SAMPLE_RATE = 16_000
        const val INPUT_CHUNK_BYTES = 2_048
        const val MAX_BUFFERED_FALLBACK_BYTES = 6 * 1024 * 1024
    }
}
