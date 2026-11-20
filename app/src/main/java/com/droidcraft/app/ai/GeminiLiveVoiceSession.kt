package com.droidcraft.app.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Base64
import android.util.Log
import com.droidcraft.app.model.LiveConversationStatus
import com.droidcraft.app.model.LiveVoiceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
        .connectTimeout(12, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val audioPlayer = PcmAudioPlayer()
    private val elevenLabsTtsClient = ElevenLabsTtsClient(elevenLabsApiKey)
    private val responseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val synthesisMutex = Mutex()

    @Volatile
    private var elevenLabsSynthesisJob: Job? = null

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var setupComplete = false

    @Volatile
    private var webSocketOpened = false

    @Volatile
    private var setupMessageSent = false

    @Volatile
    private var lastConnectionEvent = "Waiting for the WebSocket handshake."

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
        lastConnectionEvent = "Opening the Gemini Live WebSocket."
        Log.i(LIVE_LOG_TAG, "Starting Live session with model=$LIVE_MODEL at the v1beta BidiGenerateContent endpoint.")
        listener.onStatus(LiveConversationStatus.CONNECTING)
        listener.onEffectiveVoiceChanged(activeVoice)
        responseScope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (!setupComplete && !stopped.get()) {
                val stage = when {
                    !webSocketOpened -> "No WebSocket handshake completed."
                    !setupMessageSent -> "The socket opened, but the setup message was not accepted by the client socket."
                    else -> "The socket opened and setup was sent, but Google did not send setupComplete."
                }
                fail(
                    "Gemini Live setup timed out after ${CONNECTION_TIMEOUT_MS / 1_000} seconds for $LIVE_MODEL. " +
                        "$stage Last connection event: $lastConnectionEvent"
                )
            }
        }

        val httpsUrl = HttpUrl.Builder()
            .scheme("https")
            .host("generativelanguage.googleapis.com")
            .addPathSegments("ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
            .addQueryParameter("key", geminiApiKey)
            .build()
        // Official Gemini Live v1beta endpoint; OkHttp performs the HTTP-to-WebSocket upgrade.
        val request = Request.Builder()
            .url(httpsUrl.toString().replaceFirst("https://", "wss://"))
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocketOpened = true
                lastConnectionEvent = "WebSocket opened (HTTP ${response.code}); sending setup for $LIVE_MODEL."
                Log.i(LIVE_LOG_TAG, lastConnectionEvent)
                if (webSocket.send(buildSetupMessage())) {
                    setupMessageSent = true
                    lastConnectionEvent = "Setup message sent; waiting for Google's setupComplete response."
                } else {
                    fail("Gemini Live WebSocket opened, but the setup message send returned false for $LIVE_MODEL.")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastConnectionEvent = "Received a server message from Gemini Live."
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!stopped.get()) {
                    val responseText = runCatching { response?.body?.string().orEmpty() }.getOrDefault("")
                    val providerError = runCatching { JSONObject(responseText).optJSONObject("error") }.getOrNull()
                    val providerMessage = providerError?.optString("message")
                        ?.takeIf { it.isNotBlank() && it != "null" }
                        ?: extractProviderMessage(responseText)
                    val detail = providerMessage.ifBlank { describeThrowable(t) }
                    val providerCode = response?.code ?: providerError?.optInt("code", 0)?.takeIf { it > 0 }
                    val providerStatus = providerError?.optString("status")
                        ?.takeIf { it.isNotBlank() && it != "null" }
                    val error = if (providerCode != null || providerStatus != null) {
                        buildProviderError(providerCode, providerStatus, detail.ifBlank { describeThrowable(t) })
                    } else {
                        "Gemini Live WebSocket connection failed before setup: ${describeThrowable(t)}"
                    }
                    lastConnectionEvent = "WebSocket failure: $error"
                    fail(error)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                lastConnectionEvent = "Google is closing the Live WebSocket (code $code): ${reason.ifBlank { "no reason supplied" }}"
                Log.w(LIVE_LOG_TAG, redactSecrets(lastConnectionEvent))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped.get()) {
                    val detail = reason.takeIf { it.isNotBlank() } ?: "The provider closed the session without a reason."
                    fail("Gemini Live ended the WebSocket (code $code): $detail")
                }
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

    private fun extractProviderMessage(serialized: String): String {
        if (serialized.isBlank()) return ""
        val json = runCatching { JSONObject(serialized) }.getOrNull()
            ?: return redactSecrets(serialized.trim().take(600))
        val error = json.optJSONObject("error") ?: return redactSecrets(serialized.trim().take(600))
        val message = error.optString("message").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        val status = error.optString("status").takeIf { it.isNotBlank() && it != "null" }
        return redactSecrets(listOfNotNull(message, status?.let { "[$it]" }).joinToString(" ").take(600))
    }

    private fun buildProviderError(code: Int?, status: String?, detail: String): String {
        val normalizedStatus = status.orEmpty().uppercase()
        val normalizedDetail = detail.lowercase()
        val modelUnavailable = normalizedStatus.contains("NOT_FOUND") ||
            normalizedStatus.contains("MODEL_NOT_SUPPORTED") ||
            (normalizedDetail.contains("model") && listOf(
                "not found", "not available", "unavailable", "unsupported", "not supported", "does not support", "not enabled", "unknown"
            ).any { normalizedDetail.contains(it) })
        val codeLabel = code?.let { " (HTTP $it)" }.orEmpty()
        return when {
            code == 404 || modelUnavailable ->
                "Gemini Live model $LIVE_MODEL is unavailable to this API key or endpoint$codeLabel. Google returned: $detail"
            code == 403 || normalizedStatus.contains("PERMISSION_DENIED") ->
                "Google denied access to Gemini Live model $LIVE_MODEL$codeLabel. Check whether this API key has Live API access. Google returned: $detail"
            code == 503 || normalizedStatus.contains("UNAVAILABLE") ->
                "Gemini Live service is temporarily unavailable$codeLabel. Google returned: $detail"
            else -> "Gemini Live provider error$codeLabel${status?.let { " ($it)" }.orEmpty()}: $detail"
        }
    }

    private fun describeThrowable(throwable: Throwable): String =
        generateSequence(throwable) { it.cause }
            .take(5)
            .joinToString(" → ") { error ->
                "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            }
            .let(::redactSecrets)
            .take(800)

    private fun redactSecrets(value: String): String =
        Regex("""(?i)([?&]key=)[^&\s]+""")
            .replace(value) { match -> "${match.groupValues[1]}[redacted]" }

    private fun handleServerMessage(serialized: String) {
        if (stopped.get()) return
        val message = runCatching { JSONObject(serialized) }.getOrNull() ?: return

        message.optJSONObject("error")?.let { error ->
            val providerMessage = error.optString("message")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: "The Gemini Live provider returned an error without a message."
            val code = error.optInt("code", 0).takeIf { it > 0 }
            val status = error.optString("status").takeIf { it.isNotBlank() && it != "null" }
            fail(buildProviderError(code, status, redactSecrets(providerMessage)))
            return
        }
        if (message.has("goAway")) {
            val timeLeft = message.optJSONObject("goAway")?.optString("timeLeft")
                ?.takeIf { it.isNotBlank() }
            fail(
                if (timeLeft != null) "Gemini Live is ending this session (time left: $timeLeft). Reconnect to continue."
                else "Gemini Live is ending this session. Reconnect to continue."
            )
            return
        }
        if (message.has("setupComplete")) {
            setupComplete = true
            lastConnectionEvent = "Google acknowledged setupComplete for $LIVE_MODEL."
            Log.i(LIVE_LOG_TAG, lastConnectionEvent)
            startMicrophone()
            return
        }

        val serverContent = message.optJSONObject("serverContent") ?: return
        if (serverContent.optBoolean("interrupted", false)) {
            elevenLabsSynthesisJob?.cancel()
            audioPlayer.flush()
            currentGeminiAudio.reset()
            currentTurnHadAudio = false
            outputTranscript.setLength(0)
            modelText.setLength(0)
            listener.onAssistantDraft("")
            listener.onStatus(LiveConversationStatus.LISTENING)
        }
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
            elevenLabsSynthesisJob?.cancel()
            elevenLabsSynthesisJob = responseScope.launch {
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
                    } catch (cancelled: CancellationException) {
                        throw cancelled
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
        val surfacedMessage = redactSecrets(message)
        Log.e(LIVE_LOG_TAG, surfacedMessage)
        listener.onStatus(LiveConversationStatus.ERROR, surfacedMessage)
    }

    private companion object {
        const val LIVE_LOG_TAG = "JarvisLive"
        const val LIVE_MODEL = "models/gemini-3.8-live"
        const val CONNECTION_TIMEOUT_MS = 35_000L
        const val GEMINI_PRESET_VOICE = "Aoede"
        const val INPUT_SAMPLE_RATE = 16_000
        const val INPUT_CHUNK_BYTES = 2_048
        const val MAX_BUFFERED_FALLBACK_BYTES = 6 * 1024 * 1024
    }
}
