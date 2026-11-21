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
import com.droidcraft.app.jarvis.character.EmotionEngine
import com.droidcraft.app.jarvis.voice.VoiceSettings
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
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal interface LiveVoiceSessionListener {
    fun onStatus(status: LiveConversationStatus, message: String? = null)
    fun onUserTranscript(text: String, isFinal: Boolean)
    fun onAssistantDraft(text: String)
    fun onAssistantMessage(text: String)
    fun onEffectiveVoiceChanged(mode: LiveVoiceMode, message: String? = null)
    fun onNotice(message: String)
    fun onAssistantInterrupted()
    fun onPhoneTtsFallback(text: String, reason: String)
}

/**
 * Gemini Live is the conversation brain. Microphone frames are held locally until setupComplete;
 * replies are spoken with the selected provider, with Gemini TTS as the free voice path.
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
    private val elevenLabsTtsClient = ElevenLabsTtsClient(
        elevenLabsApiKey,
        VoiceSettings.elevenVoiceId(appContext)
    )
    private val geminiTtsClient = GeminiTtsClient(
        geminiApiKey,
        VoiceSettings.geminiVoice(appContext)
    )
    private val responseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val synthesisMutex = Mutex()

    @Volatile
    private var speechSynthesisJob: Job? = null

    private val speechGeneration = AtomicLong(0)

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var setupComplete = false

    @Volatile
    private var webSocketOpened = false

    @Volatile
    private var setupMessageSent = false

    @Volatile
    private var connectionAttempt = 0

    @Volatile
    private var retryScheduled = false

    @Volatile
    private var firstAttemptFailure: String? = null

    @Volatile
    private var lastConnectionEvent = "Waiting for the WebSocket handshake."

    @Volatile
    private var activeVoice = if (requestedVoice == LiveVoiceMode.ELEVENLABS && elevenLabsApiKey.isNotBlank()) {
        LiveVoiceMode.ELEVENLABS
    } else {
        LiveVoiceMode.GEMINI_LIVE
    }

    // The OkHttp callback thread owns transcript/output buffers; the mic queue has its own lock.
    private val inputTranscript = StringBuilder()
    private val outputTranscript = StringBuilder()
    private val modelText = StringBuilder()
    private val currentGeminiAudio = ByteArrayOutputStream()
    private var currentTurnHadAudio = false
    private val microphoneAudioLock = Any()
    private val pendingMicrophoneAudio = ArrayDeque<ByteArray>()
    private var pendingMicrophoneBytes = 0
    private var microphoneQueueOverflowLogged = false

    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var microphoneThread: Thread? = null
    @Volatile
    private var echoCanceler: AcousticEchoCanceler? = null

    fun start() {
        if (stopped.get()) return
        Log.i(LIVE_LOG_TAG, "Starting Live session with model=$LIVE_MODEL at the v1beta BidiGenerateContent endpoint.")
        listener.onStatus(LiveConversationStatus.CONNECTING)
        listener.onEffectiveVoiceChanged(activeVoice)
        // Begin capture now, but queue frames locally. No realtimeInput is sent before setupComplete.
        startMicrophone()
        if (!stopped.get()) openConnection(1)
    }

    private fun openConnection(attempt: Int) {
        if (stopped.get()) return
        connectionAttempt = attempt
        retryScheduled = false
        setupComplete = false
        webSocketOpened = false
        setupMessageSent = false
        lastConnectionEvent = "Opening Gemini Live connection attempt $attempt of $MAX_CONNECTION_ATTEMPTS."
        Log.i(LIVE_LOG_TAG, redactSecrets(lastConnectionEvent))
        if (attempt > 1) {
            listener.onStatus(LiveConversationStatus.CONNECTING, "Retrying Gemini Live once (attempt $attempt of $MAX_CONNECTION_ATTEMPTS)…")
        }

        val httpsUrl = HttpUrl.Builder()
            .scheme("https")
            .host("generativelanguage.googleapis.com")
            .addPathSegments("ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
            .addQueryParameter("key", geminiApiKey)
            .build()
        val request = Request.Builder()
            .url(httpsUrl.toString().replaceFirst("https://", "wss://"))
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrentAttempt(attempt)) {
                    webSocket.cancel()
                    return
                }
                this@GeminiLiveVoiceSession.webSocket = webSocket
                webSocketOpened = true
                lastConnectionEvent = "WebSocket opened (HTTP ${response.code}) on attempt $attempt; sending setup for $LIVE_MODEL."
                Log.i(LIVE_LOG_TAG, redactSecrets(lastConnectionEvent))
                if (webSocket.send(buildSetupMessage())) {
                    setupMessageSent = true
                    lastConnectionEvent = "Setup message sent on attempt $attempt; waiting for Google's setupComplete response."
                    Log.i(LIVE_LOG_TAG, redactSecrets(lastConnectionEvent))
                } else {
                    handleAttemptFailure(attempt, "Gemini Live WebSocket opened, but the setup message send returned false for $LIVE_MODEL.")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrentAttempt(attempt)) return
                lastConnectionEvent = "Received a server message from Gemini Live."
                handleServerMessage(text, attempt)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrentAttempt(attempt) || stopped.get()) return
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
                    "Gemini Live WebSocket connection failed: ${describeThrowable(t)}" +
                        responseText.takeIf { it.isNotBlank() }?.let { " · server response: ${redactSecrets(it.trim()).take(500)}" }.orEmpty()
                }
                lastConnectionEvent = "WebSocket failure on attempt $attempt: $error"
                Log.e(LIVE_LOG_TAG, redactSecrets(lastConnectionEvent))
                handleAttemptFailure(attempt, error)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                lastConnectionEvent = "Gemini Live is closing the WebSocket (code $code): ${reason.ifBlank { "no reason supplied" }}"
                Log.w(LIVE_LOG_TAG, redactSecrets(lastConnectionEvent))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrentAttempt(attempt) || stopped.get()) return
                val detail = reason.takeIf { it.isNotBlank() } ?: "The provider closed the session without a reason."
                handleAttemptFailure(attempt, "Gemini Live ended the WebSocket (code $code): $detail")
            }
        })
        webSocket = socket

        responseScope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (isCurrentAttempt(attempt) && !setupComplete && !stopped.get()) {
                val stage = when {
                    !webSocketOpened -> "No WebSocket handshake completed."
                    !setupMessageSent -> "The socket opened, but the setup message was not accepted by the client socket."
                    else -> "The socket opened and setup was sent, but Google did not send setupComplete."
                }
                handleAttemptFailure(
                    attempt,
                    "Gemini Live setup timed out after ${CONNECTION_TIMEOUT_MS / 1_000} seconds for $LIVE_MODEL. $stage"
                )
            }
        }
    }

    private fun isCurrentAttempt(attempt: Int): Boolean =
        !stopped.get() && attempt == connectionAttempt

    private fun handleAttemptFailure(attempt: Int, rawMessage: String) {
        if (stopped.get() || attempt != connectionAttempt) return
        // onFailure/onClosed can both report the same socket; ignore duplicates while retrying.
        if (retryScheduled && attempt < MAX_CONNECTION_ATTEMPTS) return
        val message = redactSecrets(rawMessage).take(1_200)
        lastConnectionEvent = message
        Log.w(LIVE_LOG_TAG, "Attempt $attempt failed: $message")

        if (setupComplete) {
            failFinal(message)
            return
        }

        if (attempt < MAX_CONNECTION_ATTEMPTS && !retryScheduled) {
            firstAttemptFailure = message
            retryScheduled = true
            listener.onStatus(
                LiveConversationStatus.CONNECTING,
                "Gemini Live connection failed on attempt 1: $message Retrying once…"
            )
            listener.onNotice("Gemini Live connection failed on attempt 1: $message Retrying once…")
            runCatching { webSocket?.cancel() }
            responseScope.launch {
                delay(RETRY_DELAY_MS)
                if (!stopped.get() && !setupComplete && connectionAttempt == attempt && retryScheduled) {
                    openConnection(attempt + 1)
                }
            }
            return
        }

        val first = firstAttemptFailure?.let { " First attempt: $it" }.orEmpty()
        failFinal("Gemini Live setup failed after $MAX_CONNECTION_ATTEMPTS attempts. Second attempt: $message$first")
    }

    fun sendText(text: String): Boolean {
        if (stopped.get() || text.isBlank()) return false
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
        return synchronized(microphoneAudioLock) {
            if (!setupComplete || stopped.get()) false else webSocket?.send(message) == true
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        speechGeneration.incrementAndGet()
        speechSynthesisJob?.cancel()
        val sendStreamEnd = setupComplete
        synchronized(microphoneAudioLock) {
            setupComplete = false
            pendingMicrophoneAudio.clear()
            pendingMicrophoneBytes = 0
        }
        stopMicrophone()
        // realtimeInput is also gated: even audioStreamEnd is not sent before setupComplete.
        if (sendStreamEnd) {
            runCatching {
                webSocket?.send(
                    JSONObject()
                        .put("realtimeInput", JSONObject().put("audioStreamEnd", true))
                        .toString()
                )
            }
        }
        runCatching { webSocket?.close(1000, "Conversation ended") }
        webSocket = null
        responseScope.cancel()
        audioPlayer.close()
        runCatching { client.dispatcher.executorService.shutdown() }
        listener.onStatus(LiveConversationStatus.IDLE)
    }

    /** Keep this verified setup shape stable; only the selected voice name is configurable. */
    private fun buildSetupMessage(): String {
        val speechConfig = JSONObject()
            .put(
                "voiceConfig",
                JSONObject().put(
                    "prebuiltVoiceConfig",
                    JSONObject().put("voiceName", VoiceSettings.geminiVoice(appContext))
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

    private fun handleServerMessage(serialized: String, attempt: Int) {
        if (stopped.get() || attempt != connectionAttempt) return
        val message = runCatching { JSONObject(serialized) }.getOrNull()
        if (message == null) {
            val detail = redactSecrets(serialized.trim()).take(600).ifBlank { "empty server response" }
            Log.e(LIVE_LOG_TAG, "Non-JSON server message before setupComplete: $detail")
            if (!setupComplete) handleAttemptFailure(attempt, "Gemini Live returned a non-JSON server message before setupComplete: $detail")
            else listener.onNotice("Gemini Live sent an unreadable message: $detail")
            return
        }

        message.optJSONObject("error")?.let { error ->
            val providerMessage = error.optString("message")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: "The Gemini Live provider returned an error without a message."
            val code = error.optInt("code", 0).takeIf { it > 0 }
            val status = error.optString("status").takeIf { it.isNotBlank() && it != "null" }
            val providerError = buildProviderError(code, status, redactSecrets(providerMessage))
            Log.e(LIVE_LOG_TAG, "Gemini Live server error before/while streaming: $providerError")
            handleAttemptFailure(attempt, providerError)
            return
        }
        if (message.has("goAway")) {
            val timeLeft = message.optJSONObject("goAway")?.optString("timeLeft")
                ?.takeIf { it.isNotBlank() }
            val reason = if (timeLeft != null) {
                "Gemini Live is ending this session (time left: $timeLeft)."
            } else {
                "Gemini Live is ending this session."
            }
            Log.w(LIVE_LOG_TAG, reason)
            handleAttemptFailure(attempt, "$reason Reconnect to continue.")
            return
        }
        if (message.has("setupComplete")) {
            completeSetup(attempt)
            return
        }

        val serverContent = message.optJSONObject("serverContent")
        if (!setupComplete) {
            // Preserve the provider's real early response instead of turning it into a generic timeout.
            val early = redactSecrets(serialized.trim()).take(800)
            Log.e(LIVE_LOG_TAG, "Unexpected early Gemini Live server message: $early")
            handleAttemptFailure(attempt, "Gemini Live sent an unexpected message before setupComplete: $early")
            return
        }
        if (serverContent == null) {
            Log.d(LIVE_LOG_TAG, "Received a non-content Gemini Live protocol message after setupComplete.")
            return
        }

        if (serverContent.optBoolean("interrupted", false)) {
            listener.onAssistantInterrupted()
            speechGeneration.incrementAndGet()
            speechSynthesisJob?.cancel()
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

    private fun completeSetup(attempt: Int) {
        if (stopped.get() || attempt != connectionAttempt) return
        var sent = true
        synchronized(microphoneAudioLock) {
            setupComplete = true
            while (pendingMicrophoneAudio.isNotEmpty()) {
                val queued = pendingMicrophoneAudio.removeFirst()
                pendingMicrophoneBytes -= queued.size
                if (!sendMicrophoneAudioLocked(queued)) {
                    sent = false
                    break
                }
            }
        }
        if (!sent) {
            handleAttemptFailure(attempt, "Gemini Live accepted setupComplete, but queued microphone audio could not be sent.")
            return
        }
        lastConnectionEvent = "Google acknowledged setupComplete for $LIVE_MODEL; queued microphone audio is now streaming."
        Log.i(LIVE_LOG_TAG, redactSecrets(lastConnectionEvent))
        listener.onStatus(LiveConversationStatus.LISTENING)
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
        if (currentGeminiAudio.size() + pcm.size <= MAX_BUFFERED_FALLBACK_BYTES) {
            currentGeminiAudio.write(pcm)
        }
    }

    private fun finishAssistantTurn() {
        val generation = speechGeneration.incrementAndGet()
        val userText = inputTranscript.toString().trim()
        val assistantText = (outputTranscript.takeIf { it.isNotEmpty() } ?: modelText).toString().trim()
        val replyEmotion = EmotionEngine.detect(userText, assistantText)
        val nativeAudio = currentGeminiAudio.toByteArray()
        val hadAudio = currentTurnHadAudio

        inputTranscript.setLength(0)
        outputTranscript.setLength(0)
        modelText.setLength(0)
        currentGeminiAudio.reset()
        currentTurnHadAudio = false

        if (userText.isNotBlank()) listener.onUserTranscript(userText, true)
        if (assistantText.isNotBlank()) listener.onAssistantMessage(assistantText)

        if (assistantText.isBlank() && nativeAudio.isEmpty()) {
            listener.onStatus(LiveConversationStatus.LISTENING)
            return
        }

        listener.onStatus(LiveConversationStatus.SPEAKING)
        speechSynthesisJob?.cancel()
        speechSynthesisJob = responseScope.launch {
            synthesisMutex.withLock {
                if (!isSpeechGenerationActive(generation)) return@withLock
                if (activeVoice == LiveVoiceMode.ELEVENLABS && assistantText.isNotBlank()) {
                    try {
                        val style = EmotionEngine.style(replyEmotion)
                        val speech = elevenLabsTtsClient.synthesizePcm(
                            assistantText,
                            style = style.elevenStyle,
                            stability = style.elevenStability
                        )
                        if (isSpeechGenerationActive(generation)) {
                            audioPlayer.enqueue(
                                speech,
                                shouldPlay = { isSpeechGenerationActive(generation) }
                            ) { reportListeningIfActive(generation) }
                        }
                        return@withLock
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (!isSpeechGenerationActive(generation)) return@withLock
                        val reason = if (error is ElevenLabsException) {
                            if (error.isPaymentOrPlanError) {
                                "ElevenLabs plan/credits error (${error.providerStatus ?: "HTTP ${error.httpStatus}"}): ${error.message}"
                            } else {
                                "ElevenLabs voice failed (HTTP ${error.httpStatus}): ${error.message}"
                            }
                        } else {
                            "ElevenLabs voice failed: ${error.message ?: error.javaClass.simpleName}"
                        }
                        Log.w(LIVE_LOG_TAG, "$reason; switching this conversation to Gemini TTS.")
                        switchToGemini("$reason. Gemini TTS is speaking this reply now.")
                        speakGeminiReply(assistantText, replyEmotion, nativeAudio, hadAudio, generation)
                        return@withLock
                    }
                }
                speakGeminiReply(assistantText, replyEmotion, nativeAudio, hadAudio, generation)
            }
        }
    }

    private suspend fun speakGeminiReply(
        text: String,
        emotion: com.droidcraft.app.jarvis.character.Emotion,
        nativeAudio: ByteArray,
        hadAudio: Boolean,
        generation: Long
    ) {
        if (!isSpeechGenerationActive(generation)) return
        if (text.isBlank()) {
            if (nativeAudio.isNotEmpty()) {
                val note = "Gemini Live did not provide reply text; playing its native audio as a last-resort fallback."
                Log.w(LIVE_LOG_TAG, note)
                listener.onNotice(note)
                audioPlayer.enqueue(
                    nativeAudio,
                    shouldPlay = { isSpeechGenerationActive(generation) }
                ) { reportListeningIfActive(generation) }
            } else if (hadAudio) {
                audioPlayer.afterQueuedAudio { reportListeningIfActive(generation) }
            } else {
                listener.onStatus(LiveConversationStatus.LISTENING)
            }
            return
        }
        try {
            val pcm = geminiTtsClient.synthesizePcm(text, EmotionEngine.geminiInstruction(emotion))
            if (isSpeechGenerationActive(generation)) {
                audioPlayer.enqueue(
                    pcm,
                    shouldPlay = { isSpeechGenerationActive(generation) }
                ) { reportListeningIfActive(generation) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!isSpeechGenerationActive(generation)) return
            val reason = "Gemini TTS failed: ${error.message ?: error.javaClass.simpleName}"
            val notice = "$reason Android phone TTS will speak this reply as a last-resort fallback."
            Log.e(LIVE_LOG_TAG, redactSecrets(notice), error)
            listener.onNotice(notice)
            listener.onPhoneTtsFallback(text, notice)
        }
    }

    private fun switchToGemini(message: String) {
        activeVoice = LiveVoiceMode.GEMINI_LIVE
        listener.onEffectiveVoiceChanged(LiveVoiceMode.GEMINI_LIVE, message)
        listener.onNotice(message)
    }

    private fun isSpeechGenerationActive(generation: Long): Boolean =
        !stopped.get() && generation == speechGeneration.get()

    private fun reportListeningIfActive(generation: Long) {
        if (isSpeechGenerationActive(generation)) listener.onStatus(LiveConversationStatus.LISTENING)
    }

    private fun startMicrophone() {
        if (stopped.get()) return
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failFinal("Microphone permission is required for Live Talk.")
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

            microphoneThread = Thread({ streamMicrophone(recorder) }, "droidcraft-live-microphone").apply {
                isDaemon = true
                start()
            }
        } catch (error: SecurityException) {
            failFinal("Microphone permission is required for Live Talk: ${error.message ?: "access denied"}")
        } catch (error: Exception) {
            failFinal("Could not start the microphone: ${error.message ?: "check microphone access and try again."}")
        }
    }

    private fun streamMicrophone(recorder: AudioRecord) {
        val input = ByteArray(INPUT_CHUNK_BYTES)
        while (!stopped.get() && !Thread.currentThread().isInterrupted) {
            val read = try {
                recorder.read(input, 0, input.size, AudioRecord.READ_BLOCKING)
            } catch (error: Exception) {
                if (!stopped.get()) Log.w(LIVE_LOG_TAG, "Microphone read stopped: ${error.message}")
                break
            }
            if (read > 0) {
                queueOrSendMicrophoneAudio(input.copyOf(read))
            } else if (read < 0 && !stopped.get()) {
                failFinal("Microphone audio read failed with AudioRecord code $read.")
                break
            }
        }
    }

    private fun queueOrSendMicrophoneAudio(frame: ByteArray) {
        var sendFailure: String? = null
        synchronized(microphoneAudioLock) {
            if (stopped.get()) return
            if (!setupComplete) {
                while (pendingMicrophoneBytes + frame.size > MAX_QUEUED_MIC_AUDIO_BYTES && pendingMicrophoneAudio.isNotEmpty()) {
                    pendingMicrophoneBytes -= pendingMicrophoneAudio.removeFirst().size
                    if (!microphoneQueueOverflowLogged) {
                        microphoneQueueOverflowLogged = true
                        Log.w(LIVE_LOG_TAG, "Pre-setup microphone queue reached its limit; dropping the oldest queued audio frames.")
                    }
                }
                if (frame.size <= MAX_QUEUED_MIC_AUDIO_BYTES) {
                    pendingMicrophoneAudio.addLast(frame)
                    pendingMicrophoneBytes += frame.size
                }
            } else {
                while (pendingMicrophoneAudio.isNotEmpty()) {
                    val queued = pendingMicrophoneAudio.removeFirst()
                    pendingMicrophoneBytes -= queued.size
                    if (!sendMicrophoneAudioLocked(queued)) {
                        sendFailure = "Gemini Live WebSocket rejected queued microphone audio after setupComplete."
                        break
                    }
                }
                if (sendFailure == null && !sendMicrophoneAudioLocked(frame)) {
                    sendFailure = "Gemini Live WebSocket rejected microphone audio after setupComplete."
                }
            }
        }
        sendFailure?.let { handleAttemptFailure(connectionAttempt, it) }
    }

    private fun sendMicrophoneAudioLocked(frame: ByteArray): Boolean {
        if (!setupComplete || stopped.get()) return false
        val encoded = Base64.encodeToString(frame, Base64.NO_WRAP)
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
        return webSocket?.send(payload) == true
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

    private fun failFinal(message: String) {
        if (!stopped.compareAndSet(false, true)) return
        speechGeneration.incrementAndGet()
        speechSynthesisJob?.cancel()
        synchronized(microphoneAudioLock) {
            setupComplete = false
            pendingMicrophoneAudio.clear()
            pendingMicrophoneBytes = 0
        }
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
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val RETRY_DELAY_MS = 350L
        const val MAX_CONNECTION_ATTEMPTS = 2
        const val INPUT_SAMPLE_RATE = 16_000
        const val INPUT_CHUNK_BYTES = 2_048
        const val MAX_BUFFERED_FALLBACK_BYTES = 6 * 1024 * 1024
        const val MAX_QUEUED_MIC_AUDIO_BYTES = 256 * 1024
    }
}
