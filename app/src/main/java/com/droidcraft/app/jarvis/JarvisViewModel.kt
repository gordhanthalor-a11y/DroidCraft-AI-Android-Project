package com.droidcraft.app.jarvis

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.droidcraft.app.ai.SpeechSynthesisRouter
import com.droidcraft.app.ai.PcmAudioPlayer
import com.droidcraft.app.jarvis.character.Emotion
import com.droidcraft.app.jarvis.character.EmotionEngine
import com.droidcraft.app.jarvis.character.JarvisCharacter
import com.droidcraft.app.jarvis.memory.MemoryManager
import com.droidcraft.app.jarvis.voice.SpokenTextCleaner
import com.droidcraft.app.jarvis.voice.SpeechProvider
import com.droidcraft.app.jarvis.voice.VoiceMode
import com.droidcraft.app.jarvis.voice.VoiceRouter
import com.droidcraft.app.jarvis.voice.VoiceSettings
import com.droidcraft.app.activity.HomeActivityTracker
import com.droidcraft.app.jarvis.a11y.ActionSafety
import com.droidcraft.app.data.GeminiApiKeyStore
import com.droidcraft.app.data.ActionDecisionStore
import com.droidcraft.app.data.AiProviderSettings
import com.droidcraft.app.data.AiProviderTelemetry
import com.droidcraft.app.data.local.MemoryEntity
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Jarvis ka state engine — voice, chat, TTS aur device actions yahan se judte hain.
 */
class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("droidcraft", Context.MODE_PRIVATE)
    private val device = JarvisDevice(application)
    private val memory = MemoryManager(application)

    private data class PendingPhoneSpeech(
        val text: String,
        val speed: Float,
        val generation: Long,
        val activityId: String
    )

    private var tts: TextToSpeech? = null
    @Volatile private var phoneTtsInitializationFailed = false
    private var pendingPhoneSpeech: PendingPhoneSpeech? = null
    @Volatile private var activePhoneUtteranceId: String? = null
    @Volatile private var activePhoneActivityId: String? = null
    private var activeSpeechActivityId: String? = null
    private var speechJob: Job? = null
    private var pcmPlayer: PcmAudioPlayer? = null
    private val speechGeneration = java.util.concurrent.atomic.AtomicLong(0)

    private val _messages = MutableStateFlow<List<JarvisMessage>>(emptyList())
    val messages: StateFlow<List<JarvisMessage>> = _messages.asStateFlow()

    private val _assistantDraft = MutableStateFlow("")
    val assistantDraft: StateFlow<String> = _assistantDraft.asStateFlow()

    private val _needsAttention = MutableStateFlow(false)
    val needsAttention: StateFlow<Boolean> = _needsAttention.asStateFlow()

    private var workCount = 0

    private val _pendingTapConfirmation = MutableStateFlow<PendingTapConfirmation?>(null)
    val pendingTapConfirmation: StateFlow<PendingTapConfirmation?> = _pendingTapConfirmation.asStateFlow()
    private val seenLiveMessageIds = LinkedHashSet<String>()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _notes = MutableStateFlow<List<JarvisNote>>(emptyList())
    val notes: StateFlow<List<JarvisNote>> = _notes.asStateFlow()

    private val _userName = MutableStateFlow(prefs.getString("jarvis_user_name", "") ?: "")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _autoSpeak = MutableStateFlow(prefs.getBoolean("jarvis_auto_speak", true))
    val autoSpeak: StateFlow<Boolean> = _autoSpeak.asStateFlow()

    private val _apiKey = MutableStateFlow(GeminiApiKeyStore.read(application))
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

    private val _memories = MutableStateFlow<List<MemoryEntity>>(emptyList())
    val memories: StateFlow<List<MemoryEntity>> = _memories.asStateFlow()

    private val _memoryLoading = MutableStateFlow(true)
    val memoryLoading: StateFlow<Boolean> = _memoryLoading.asStateFlow()

    private val _memoryNotice = MutableStateFlow("")
    val memoryNotice: StateFlow<String> = _memoryNotice.asStateFlow()

    // ---------------- character / emotion / voice ----------------
    private val _emotion = MutableStateFlow(Emotion.NEUTRAL)
    val emotion: StateFlow<Emotion> = _emotion.asStateFlow()
    private val _voiceLabel = MutableStateFlow("")
    val voiceLabel: StateFlow<String> = _voiceLabel.asStateFlow()
    private val _voiceNotice = MutableStateFlow<String?>(null)
    val voiceNotice: StateFlow<String?> = _voiceNotice.asStateFlow()

    private var idCounter = 0L

    init {
        ActionDecisionStore.initialize(application)
        _notes.value = device.loadNotes()
        refreshMemories()
        _messages.value = listOf(
            JarvisMessage(nextId(), greetingMessage(), isUser = false, kind = JarvisMessageKind.SYSTEM)
        )
        initTts()
    }

    // ------------------------------------------------------------------ voice

    fun setListening(listening: Boolean) {
        _isListening.value = listening
    }

    fun onMicDenied() {
        appendBot("Voice ke liye microphone permission zaroori hai. Settings se allow kar dijiye.")
    }

    // ------------------------------------------------------------------ chat

    fun handleUserText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (_isThinking.value) {
            appendBot("I am still finishing the previous request. No new action was started; please try again in a moment.")
            return
        }
        stopSpeak()
        _pendingTapConfirmation.value = null
        _needsAttention.value = false
        _assistantDraft.value = ""
        _messages.value = _messages.value + JarvisMessage(nextId(), trimmed, isUser = true)

        val intent = JarvisCommandParser.parse(trimmed)
        val type = intent?.type ?: JarvisIntentType.UNKNOWN

        if (type == JarvisIntentType.MEMORY) {
            val name = intent?.args?.get("name")?.trim().orEmpty()
            if (name.isNotEmpty()) {
                persistDurableFact(trimmed)
                prefs.edit().putString("jarvis_user_name", name).apply()
                _userName.value = name
                appendBot("Bahut achha $name, ab main aapko yaad rakh lunga.")
                return
            }
        }

        if (intent != null && gateTapAction(intent)) return
        if (intent != null && type != JarvisIntentType.UNKNOWN) {
            executeDeviceIntent(intent)
        } else {
            // Screen data is fetched only through a user-authorized tool call, never by a vague
            // reference such as "this" or "here" in an ordinary chat message.
            askBrain()
        }
    }

    private fun persistDurableFact(userText: String) {
        val fact = memory.extractDurableFact(userText) ?: return
        viewModelScope.launch {
            runCatching {
                memory.remember(fact)
                _memories.value = memory.facts()
            }.onSuccess {
                val id = "memory-fact-${System.currentTimeMillis()}"
                HomeActivityTracker.begin(id, "Memory", fact.key, "Saved on device")
                HomeActivityTracker.complete(id, "Saved on device", fact.value)
            }.onFailure { error ->
                _memoryNotice.value = "Could not save a durable fact: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun gateTapAction(intent: JarvisIntent): Boolean {
        if (intent.type != JarvisIntentType.TAP) return false
        val target = intent.args["target"].orEmpty()
        val packageName = device.currentActivePackageName()
        when (val decision = ActionSafety.assessTap(target, packageName, device.currentActiveAppLabel())) {
            is ActionSafety.TapDecision.Blocked -> {
                _needsAttention.value = true
                appendBot(decision.reason)
                return true
            }
            is ActionSafety.TapDecision.RequiresConfirmation -> {
                if (packageName.isNullOrBlank()) {
                    _needsAttention.value = true
                    appendBot("I could not verify the active app, so no tap was sent. Enable Jarvis Accessibility and retry on the intended screen.")
                    return true
                }
                _pendingTapConfirmation.value = PendingTapConfirmation(target, decision.reason, packageName)
                _needsAttention.value = true
                appendBot("Confirmation is required before I tap '$target'. Review the on-screen dialog; nothing has been tapped.")
                return true
            }
            ActionSafety.TapDecision.Allowed -> return false
        }
    }

    /** Safety-checked executor used by Gemini Live's explicitly requested phone tools. */
    suspend fun executeLiveTool(toolName: String, args: JSONObject, userText: String): JarvisOutcome {
        val activityId = "live-tool-${System.currentTimeMillis()}-${toolName.hashCode()}"
        HomeActivityTracker.begin(activityId, "Phone action", toolName.replace('_', ' '), "Checking authorization")
        val intent = JarvisToolIntents.fromTool(toolName, args)
        val outcome = when {
            intent == null -> JarvisOutcome("Unsupported phone action. Nothing was changed.", false)
            !ActionSafety.wasExplicitlyRequested(userText, intent.type) ->
                JarvisOutcome("Refused: the current spoken request did not explicitly ask for this action. Nothing was changed.", false)
            intent.type == JarvisIntentType.TYPE_TEXT &&
                !ActionSafety.isUserProvidedText(userText, intent.args["text"].orEmpty()) ->
                JarvisOutcome("Refused: only text literally supplied by you may be entered. Nothing was typed.", false)
            else -> {
                HomeActivityTracker.update(activityId, "Running the requested phone action")
                try {
                    executeBrainTool(intent)
                } catch (cancelled: CancellationException) {
                    HomeActivityTracker.cancel(activityId, "Tool call cancelled by Gemini Live")
                    throw cancelled
                } catch (error: Exception) {
                    JarvisOutcome("Action failed safely: ${error.message ?: error.javaClass.simpleName}", false)
                }
            }
        }
        if (outcome.success) HomeActivityTracker.complete(activityId, "Completed", outcome.message)
        else HomeActivityTracker.fail(activityId, outcome.message)
        return outcome
    }

    private suspend fun executeBrainTool(intent: JarvisIntent): JarvisOutcome {
        if (intent.type == JarvisIntentType.TAP) {
            val (packageName, appLabel) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                device.currentActivePackageName() to device.currentActiveAppLabel()
            }
            if (packageName.isNullOrBlank()) {
                return JarvisOutcome("Accessibility is unavailable or no active app could be verified. No tap was sent.", false)
            }
            when (val decision = ActionSafety.assessTap(intent.args["target"].orEmpty(), packageName, appLabel)) {
                is ActionSafety.TapDecision.Blocked -> return JarvisOutcome(decision.reason, false)
                is ActionSafety.TapDecision.RequiresConfirmation -> {
                    _pendingTapConfirmation.value = PendingTapConfirmation(
                        intent.args["target"].orEmpty(),
                        decision.reason,
                        packageName
                    )
                    _needsAttention.value = true
                    return JarvisOutcome("Paused for on-screen confirmation. No tap was sent. ${decision.reason}", false)
                }
                ActionSafety.TapDecision.Allowed -> Unit
            }
        }
        return device.execute(intent)
    }

    fun confirmPendingTap() {
        val pending = _pendingTapConfirmation.value ?: return
        recordTapDecision(pending, approved = true)
        _pendingTapConfirmation.value = null
        if (device.currentActivePackageName() != pending.expectedPackageName) {
            _needsAttention.value = true
            appendBot("The active app changed before confirmation. The tap was cancelled and nothing was changed.")
            return
        }
        _needsAttention.value = false
        executeDeviceIntent(
            JarvisIntent(
                JarvisIntentType.TAP,
                mapOf(
                    "target" to pending.target,
                    "confirmed" to "true",
                    "expected_package" to pending.expectedPackageName
                )
            )
        )
    }

    fun dismissPendingTap() {
        val pending = _pendingTapConfirmation.value ?: return
        recordTapDecision(pending, approved = false)
        _pendingTapConfirmation.value = null
        _needsAttention.value = false
        appendBot("The tap was cancelled. Nothing was changed.")
    }

    private fun recordTapDecision(pending: PendingTapConfirmation, approved: Boolean) {
        ActionDecisionStore.record(
            getApplication(),
            actionLabel = pending.target,
            packageName = pending.expectedPackageName,
            approved = approved
        )
        val id = "tap-decision-${System.currentTimeMillis()}"
        val label = if (approved) "User approved tap" else "User rejected tap"
        HomeActivityTracker.begin(
            id = id,
            kind = "Action decision",
            title = "${pending.target} · ${pending.expectedPackageName}",
            step = label
        )
        if (approved) HomeActivityTracker.complete(id, "Approval recorded", "The active-package safety check still applies.")
        else HomeActivityTracker.cancel(id, "User rejected the requested tap; no tap was sent.")
    }

    /** Live Talk has its own microphone/audio route; only its text transcript is mirrored here. */
    fun appendLiveTranscript(id: String, text: String, isUser: Boolean) {
        if (text.isBlank() || !seenLiveMessageIds.add(id)) return
        _messages.value = _messages.value + JarvisMessage(nextId(), text.trim(), isUser)
    }

    private fun executeDeviceIntent(intent: JarvisIntent, replyText: String? = null) {
        beginWork()
        viewModelScope.launch {
            try {
                val outcome = device.execute(intent)
                _notes.value = device.loadNotes()
                if (intent.type == JarvisIntentType.MEMORY_ADD || intent.type == JarvisIntentType.MEMORY_LIST) {
                    _memories.value = memory.facts()
                }
                if (!outcome.success) _needsAttention.value = true
                val spoken = if (replyText.isNullOrBlank() || outcome.success) outcome.message
                    else "$replyText (${outcome.message})"
                appendBot(spoken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _needsAttention.value = true
                appendBot("The phone action did not complete: ${error.message ?: error.javaClass.simpleName}. No success is being claimed.")
            } finally {
                endWork()
            }
        }
    }

    private fun beginWork() {
        workCount += 1
        _isThinking.value = true
    }

    private fun endWork() {
        workCount = (workCount - 1).coerceAtLeast(0)
        _isThinking.value = workCount > 0
    }

    /** Character + memory से जुड़ा user context — Gemini को हर बार ताज़ा। */
    private fun characterContext(): JarvisCharacter.UserContext = JarvisCharacter.UserContext(
        userName = _userName.value.ifBlank { "sahab" },
        facts = _memories.value.map { it.value },
        work = "Hindi and English conversation, general questions, and safe personal phone controls"
    )

    private fun askBrain() {
        val app = getApplication<Application>()
        val providers = AiProviderSettings.configuredProviders(app)
        val geminiKey = GeminiApiKeyStore.read(app)
        _apiKey.value = geminiKey
        if (providers.isEmpty()) {
            _needsAttention.value = true
            appendBot(
                "Local phone controls can work without an AI provider. For conversation, save a Gemini key " +
                    "or configure another provider in Settings."
            )
            return
        }
        val selectedTextProvider = AiProviderSettings.activeProvider(app)
        if (providers.first().provider != selectedTextProvider) {
            appendSystemNotice(
                "${selectedTextProvider.displayName} has no saved API key; starting with ${providers.first().provider.displayName}."
            )
        }
        beginWork()
        _assistantDraft.value = ""
        viewModelScope.launch {
            val toolResults = mutableListOf<JarvisOutcome>()
            var phoneTaskActivityId: String? = null
            var phoneTaskSteps = 0
            try {
                val latestUserText = _messages.value.lastOrNull { it.isUser }?.text.orEmpty()
                val loadedFacts = try {
                    memory.extractDurableFact(latestUserText)?.let { memory.remember(it) }
                    memory.facts()
                } catch (memoryError: Exception) {
                    _memoryNotice.value = "Memory could not be loaded for this session: ${memoryError.message ?: memoryError.javaClass.simpleName}"
                    _memories.value
                }
                _memories.value = loadedFacts
                var finalReply: BrainReply? = null
                var finalError: Exception? = null
                for ((providerIndex, provider) in providers.withIndex()) {
                    if (providerIndex > 0) {
                        val switched = "${providers[providerIndex - 1].provider.displayName} did not complete the request; switching to ${provider.provider.displayName}."
                        appendSystemNotice(switched)
                        phoneTaskActivityId?.let { id ->
                            HomeActivityTracker.update(id, "Switching to ${provider.provider.displayName}", note = switched)
                        }
                    }
                    val brain = JarvisBrain(
                        providerConfig = provider,
                        userContext = characterContext()
                    )
                    val providerStartedAt = SystemClock.elapsedRealtime()
                    try {
                        finalReply = brain.reply(
                            history = _messages.value,
                            onDraft = { draft -> _assistantDraft.value = draft },
                            executeTool = { intent ->
                                if (phoneTaskActivityId == null) {
                                    phoneTaskActivityId = "phone-plan-${System.currentTimeMillis()}"
                                    HomeActivityTracker.begin(
                                        id = phoneTaskActivityId!!,
                                        kind = "Phone action",
                                        title = "Jarvis phone-control task",
                                        step = "Waiting for the requested action"
                                    )
                                }
                                phoneTaskSteps++
                                val activityId = phoneTaskActivityId!!
                                HomeActivityTracker.update(activityId, "Running phone-control step $phoneTaskSteps")
                                val outcome = executeBrainTool(intent)
                                toolResults += outcome
                                HomeActivityTracker.update(
                                    activityId,
                                    "Running phone-control step $phoneTaskSteps",
                                    note = outcome.message
                                )
                                outcome
                            }
                        )
                        AiProviderTelemetry.record(app, provider.provider, SystemClock.elapsedRealtime() - providerStartedAt, true)
                        finalError = null
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        AiProviderTelemetry.record(app, provider.provider, SystemClock.elapsedRealtime() - providerStartedAt, false)
                        finalError = error
                        _assistantDraft.value = ""
                        if (toolResults.any { it.success }) {
                            throw IllegalStateException(
                                "${provider.provider.displayName} failed after a phone action completed. To prevent duplicate actions, no provider retry was made. Verify the activity before continuing.",
                                error
                            )
                        }
                        if (providerIndex == providers.lastIndex) throw error
                    }
                }
                val completedReply = finalReply ?: throw finalError ?: IllegalStateException("No configured provider returned a reply.")
                val statusNotices = toolResults.filterNot { it.success }
                    .map { it.message.trim() }
                    .filter { it.isNotBlank() }
                _needsAttention.value = statusNotices.isNotEmpty() || _pendingTapConfirmation.value != null
                val response = buildString {
                    append(completedReply.text.ifBlank { "I did not get a final reply from the configured AI provider." })
                    if (statusNotices.isNotEmpty()) {
                        append("\n\nPhone safety status:\n")
                        append(statusNotices.joinToString("\n"))
                    }
                }
                appendBot(response)
                phoneTaskActivityId?.let { id ->
                    if (toolResults.all { it.success }) HomeActivityTracker.complete(id, "Phone-control task completed")
                    else HomeActivityTracker.fail(id, toolResults.firstOrNull { !it.success }?.message.orEmpty())
                }
            } catch (cancelled: CancellationException) {
                phoneTaskActivityId?.let { HomeActivityTracker.cancel(it, "Phone-control task cancelled") }
                throw cancelled
            } catch (error: Exception) {
                phoneTaskActivityId?.let { HomeActivityTracker.fail(it, error.message.orEmpty()) }
                _needsAttention.value = true
                val priorToolResults = toolResults.joinToString("\n") { it.message }
                    .takeIf { it.isNotBlank() }
                val detail = error.message ?: error.javaClass.simpleName
                val message = if (priorToolResults == null) {
                    "No phone action is being reported as complete. Jarvis could not finish the AI reply: $detail. Check the configured provider and retry."
                } else {
                    "The AI provider could not finish its follow-up: $detail. Phone tool result(s):\n$priorToolResults"
                }
                appendBot(message)
            } finally {
                _assistantDraft.value = ""
                endWork()
            }
        }
    }

    private fun appendSystemNotice(text: String) {
        _messages.value = _messages.value + JarvisMessage(nextId(), text, isUser = false, kind = JarvisMessageKind.SYSTEM)
    }

    private fun appendBot(text: String) {
        _messages.value = _messages.value + JarvisMessage(nextId(), text, isUser = false)
        // भावना: user की आखिरी बात + Jarvis का जवाब — दोनों से
        val lastUser = _messages.value.lastOrNull { it.isUser }?.text.orEmpty()
        val emotion = EmotionEngine.detect(lastUser, text)
        speak(text, emotion)
    }

    fun clearChat() {
        seenLiveMessageIds.clear()
        _pendingTapConfirmation.value = null
        _messages.value = listOf(
            JarvisMessage(nextId(), greetingMessage(), isUser = false, kind = JarvisMessageKind.SYSTEM)
        )
    }

    // ------------------------------------------------------------------ TTS

    private fun initTts() {
        tts = TextToSpeech(getApplication<Application>()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                val hi = engine?.setLanguage(Locale("hi", "IN")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (hi == TextToSpeech.LANG_MISSING_DATA || hi == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine?.setLanguage(Locale("en", "US"))
                }
                engine?.setSpeechRate(1.0f)
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId == activePhoneUtteranceId) {
                            _isSpeaking.value = true
                            activePhoneActivityId?.let {
                                HomeActivityTracker.update(it, "Android phone TTS is speaking")
                            }
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == activePhoneUtteranceId) {
                            activePhoneUtteranceId = null
                            _isSpeaking.value = false
                            completeSpeech(activePhoneActivityId, "Phone TTS finished")
                            activePhoneActivityId = null
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == activePhoneUtteranceId) {
                            activePhoneUtteranceId = null
                            _isSpeaking.value = false
                            failSpeech(activePhoneActivityId, "Android phone TTS could not speak this reply.")
                            activePhoneActivityId = null
                            _voiceNotice.value = "Android phone TTS could not speak this reply."
                        }
                    }
                })
                _ttsReady.value = true
                phoneTtsInitializationFailed = false
                pendingPhoneSpeech?.let { pending ->
                    pendingPhoneSpeech = null
                    if (pending.generation == speechGeneration.get()) {
                        speakPhone(pending.text, pending.speed, pending.generation, pending.activityId)
                    }
                }
            } else {
                _ttsReady.value = false
                phoneTtsInitializationFailed = true
                val pending = pendingPhoneSpeech
                pendingPhoneSpeech = null
                if (pending != null) {
                    val notice = "Android phone TTS is unavailable; no speech fallback could start."
                    _voiceNotice.value = notice
                    failSpeech(pending.activityId, notice)
                }
            }
        }
    }

    /** Routes a speech-only copy through the same voice decision used on the Jarvis tab. */
    fun speak(text: String, emotion: Emotion = _emotion.value, force: Boolean = false) {
        _emotion.value = emotion
        _voiceNotice.value = null
        val spokenText = SpokenTextCleaner.clean(text)
        if (spokenText.isBlank() || (!_autoSpeak.value && !force)) return

        val generation = speechGeneration.incrementAndGet()
        stopSpeechPlayback()
        val activityId = "jarvis-speech-$generation"
        activeSpeechActivityId = activityId
        val app = getApplication<Application>()
        val decision = VoiceRouter.decide(VoiceSettings.mode(app), emotion)

        val requestedProvider = when (decision.provider) {
            VoiceRouter.Provider.PHONE -> null
            VoiceRouter.Provider.GEMINI -> SpeechProvider.GEMINI
            VoiceRouter.Provider.ELEVENLABS -> SpeechProvider.ELEVENLABS
        }
        _voiceLabel.value = when (requestedProvider) {
            null -> "Android phone TTS"
            SpeechProvider.GEMINI -> "Gemini ${VoiceSettings.geminiVoice(app)}"
            SpeechProvider.ELEVENLABS -> "${VoiceSettings.elevenVoiceName(app)} (ElevenLabs)"
        }
        HomeActivityTracker.begin(
            id = activityId,
            kind = "TTS",
            title = "Jarvis spoken reply",
            step = "Routing speech to ${_voiceLabel.value}",
            progress = null
        )

        if (requestedProvider == null) {
            speakPhone(spokenText, decision.speed, generation, activityId)
        } else {
            speakWithProvider(spokenText, emotion, generation, activityId, requestedProvider)
        }
    }

    private fun stopSpeechPlayback(reason: String = "Stopped before playback finished") {
        activeSpeechActivityId?.let { HomeActivityTracker.cancel(it, reason) }
        activeSpeechActivityId = null
        speechJob?.cancel()
        speechJob = null
        pendingPhoneSpeech = null
        activePhoneUtteranceId = null
        activePhoneActivityId = null
        runCatching { tts?.stop() }
        pcmPlayer?.close()
        pcmPlayer = null
        _isSpeaking.value = false
    }

    private fun speakPhone(text: String, speed: Float, generation: Long, activityId: String) {
        val engine = tts
        if (engine == null || !_ttsReady.value) {
            if (phoneTtsInitializationFailed) {
                val notice = "Android phone TTS is unavailable; no speech fallback could start."
                _voiceNotice.value = notice
                failSpeech(activityId, notice)
                return
            }
            pendingPhoneSpeech = PendingPhoneSpeech(text, speed, generation, activityId)
            HomeActivityTracker.update(activityId, "Waiting for Android phone TTS")
            return
        }
        pendingPhoneSpeech = null
        val utteranceId = "jarvis_${generation}_${System.currentTimeMillis()}"
        activePhoneUtteranceId = utteranceId
        activePhoneActivityId = activityId
        HomeActivityTracker.update(activityId, "Starting Android phone TTS")
        engine.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR && activePhoneUtteranceId == utteranceId) {
            activePhoneUtteranceId = null
            activePhoneActivityId = null
            _isSpeaking.value = false
            val notice = "Android phone TTS could not start this reply."
            _voiceNotice.value = notice
            failSpeech(activityId, notice)
        }
    }

    private fun speakWithProvider(
        text: String,
        emotion: Emotion,
        generation: Long,
        activityId: String,
        requestedProvider: SpeechProvider
    ) {
        val app = getApplication<Application>()
        HomeActivityTracker.update(activityId, "Generating speech with ${requestedProvider.displayName}")
        speechJob = viewModelScope.launch {
            try {
                val result = SpeechSynthesisRouter.synthesize(
                    context = app,
                    text = text,
                    emotion = emotion,
                    requestedProvider = requestedProvider
                )
                if (generation != speechGeneration.get()) return@launch

                val actualLabel = when (result.actualProvider) {
                    SpeechProvider.GEMINI -> "Gemini ${VoiceSettings.geminiVoice(app)}"
                    SpeechProvider.ELEVENLABS -> "${VoiceSettings.elevenVoiceName(app)} (ElevenLabs)"
                }
                _voiceLabel.value = actualLabel
                result.fallbackNotice?.let { notice ->
                    _voiceNotice.value = notice
                    HomeActivityTracker.update(
                        activityId,
                        "Switching to ${result.actualProvider.displayName}",
                        note = notice
                    )
                }

                val player = PcmAudioPlayer()
                pcmPlayer = player
                _isSpeaking.value = true
                HomeActivityTracker.update(activityId, "Playing $actualLabel audio", note = result.fallbackNotice)
                player.enqueue(
                    result.pcm,
                    shouldPlay = { generation == speechGeneration.get() }
                ) {
                    if (generation == speechGeneration.get()) {
                        _isSpeaking.value = false
                        completeSpeech(activityId, "$actualLabel playback finished")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != speechGeneration.get()) return@launch
                _voiceLabel.value = "Android phone TTS fallback"
                val detail = error.message ?: "service error"
                val notice = "${requestedProvider.displayName} speech failed ($detail); using Android phone TTS as an emergency fallback."
                _voiceNotice.value = notice
                HomeActivityTracker.update(activityId, "Switching to Android phone TTS", note = notice)
                speakPhone(text, EmotionEngine.style(emotion).voiceSpeed, generation, activityId)
            }
        }
    }

    private fun completeSpeech(activityId: String?, step: String) {
        if (activityId == null) return
        HomeActivityTracker.complete(activityId, step)
        if (activeSpeechActivityId == activityId) activeSpeechActivityId = null
    }

    private fun failSpeech(activityId: String?, reason: String) {
        if (activityId == null) return
        HomeActivityTracker.fail(activityId, reason)
        if (activeSpeechActivityId == activityId) activeSpeechActivityId = null
    }

    fun dismissVoiceNotice() {
        _voiceNotice.value = null
    }

    fun stopSpeak() {
        speechGeneration.incrementAndGet()
        stopSpeechPlayback()
    }

    fun voiceMode(): VoiceMode = VoiceSettings.mode(getApplication())

    fun setVoiceMode(mode: VoiceMode) {
        if (mode == VoiceMode.PHONE_FREE) {
            appendBot("Android phone TTS is an emergency fallback and cannot be selected as Jarvis's normal voice.")
            return
        }
        VoiceSettings.setMode(getApplication(), mode)
        val providerName = when (mode) {
            VoiceMode.GEMINI_FREE -> "Gemini TTS"
            VoiceMode.ELEVENLABS -> "ElevenLabs"
            VoiceMode.PHONE_FREE -> "Android phone TTS"
        }
        appendBot("$providerName is now the selected speech provider. Android phone TTS remains an emergency fallback.")
    }

    fun toggleAutoSpeak() {
        val next = !_autoSpeak.value
        _autoSpeak.value = next
        prefs.edit().putBoolean("jarvis_auto_speak", next).apply()
        if (!next) stopSpeak()
    }

    // ------------------------------------------------------------------ settings / notes

    fun saveGeminiKey(key: String) {
        val k = key.trim()
        GeminiApiKeyStore.write(getApplication(), k)
        _apiKey.value = k
        if (k.isNotBlank()) appendBot("API key aa gayi — ab main poori tarah se taiyaar hoon, ${_userName.value.ifBlank { "sahab" }}!")
    }

    fun reloadGeminiKey() {
        _apiKey.value = GeminiApiKeyStore.read(getApplication())
    }

    fun deleteNote(id: Long) {
        val remaining = device.loadNotes().filterNot { it.id == id }
        device.saveNotes(remaining)
        _notes.value = remaining
    }

    fun refreshMemories() {
        viewModelScope.launch {
            _memoryLoading.value = true
            _memoryNotice.value = ""
            runCatching { memory.facts() }
                .onSuccess { _memories.value = it }
                .onFailure { _memoryNotice.value = "Memories could not load: ${it.message ?: "try again."}" }
            _memoryLoading.value = false
        }
    }

    fun saveMemory(id: Long?, key: String, value: String, category: String) {
        viewModelScope.launch {
            _memoryNotice.value = ""
            runCatching {
                require(value.isNotBlank()) { "Write a fact before saving." }
                memory.save(id, key, value, category)
                memory.facts()
            }.onSuccess { updated ->
                _memories.value = updated
                _memoryNotice.value = if (id == null) "Memory saved." else "Memory updated."
            }.onFailure {
                _memoryNotice.value = "Could not save memory: ${it.message ?: "try again."}"
            }
        }
    }

    fun dismissMemoryNotice() {
        _memoryNotice.value = ""
    }

    fun forgetMemory(id: Long) {
        viewModelScope.launch {
            runCatching {
                memory.forget(id)
                memory.facts()
            }.onSuccess {
                _memories.value = it
                _memoryNotice.value = "Memory deleted."
            }.onFailure {
                _memoryNotice.value = "Could not delete memory: ${it.message ?: "try again."}"
            }
        }
    }

    fun clearMemory() {
        viewModelScope.launch {
            runCatching { memory.clear() }
                .onSuccess {
                    _memories.value = emptyList()
                    _memoryNotice.value = "All memories cleared."
                    appendBot("Saari yaadein mit gayi.")
                }
                .onFailure {
                    _memoryNotice.value = "Could not clear memories: ${it.message ?: "try again."}"
                }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun nextId(): Long = ++idCounter

    private fun greetingMessage(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (hour) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
        val name = _userName.value
        val salutation = if (name.isBlank()) "sahab" else name
        return "$part $salutation! Main J.A.R.V.I.S. hoon. Boliye — main apps khol sakta hoon, alarm laga sakta hoon, mausam bata sakta hoon, notes le sakta hoon, call/SMS kar sakta hoon. Sab kuch bina key ke."
    }

    override fun onCleared() {
        stopSpeak()
        tts?.shutdown()
        tts = null
        super.onCleared()
    }

}
