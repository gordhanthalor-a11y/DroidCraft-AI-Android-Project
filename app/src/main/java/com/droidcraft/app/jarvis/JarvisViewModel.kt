package com.droidcraft.app.jarvis

import android.app.Application
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.droidcraft.app.ai.ElevenLabsException
import com.droidcraft.app.jarvis.character.Emotion
import com.droidcraft.app.jarvis.character.EmotionEngine
import com.droidcraft.app.jarvis.character.JarvisCharacter
import com.droidcraft.app.jarvis.memory.MemoryManager
import com.droidcraft.app.jarvis.voice.VoiceMode
import com.droidcraft.app.jarvis.voice.VoiceRouter
import com.droidcraft.app.jarvis.voice.VoiceSettings
import com.droidcraft.app.jarvis.voice.VoiceState
import com.droidcraft.app.jarvis.wake.WakeWordService
import com.droidcraft.app.data.GeminiApiKeyStore
import com.droidcraft.app.data.GeminiModelSettings
import com.droidcraft.app.data.local.MemoryEntity
import com.droidcraft.app.studio.tts.TtsClient
import com.droidcraft.app.studio.tts.TtsRequestException
import com.droidcraft.app.studio.tts.Mp3Player
import java.util.Calendar
import java.util.Locale

/**
 * Jarvis ka state engine — voice, chat, TTS aur device actions yahan se judte hain.
 */
class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("droidcraft", Context.MODE_PRIVATE)
    private val device = JarvisDevice(application)
    private val memory = MemoryManager(application)

    private var tts: TextToSpeech? = null
    private var pendingPhoneSpeech: Pair<String, Float>? = null

    private val _messages = MutableStateFlow<List<JarvisMessage>>(emptyList())
    val messages: StateFlow<List<JarvisMessage>> = _messages.asStateFlow()

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

    private val _handsFree = MutableStateFlow(prefs.getBoolean("jarvis_hands_free", false))
    val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()

    private val _memories = MutableStateFlow<List<MemoryEntity>>(emptyList())
    val memories: StateFlow<List<MemoryEntity>> = _memories.asStateFlow()

    // ---------------- character / emotion / voice ----------------
    private val _emotion = MutableStateFlow(Emotion.NEUTRAL)
    val emotion: StateFlow<Emotion> = _emotion.asStateFlow()
    private val _voiceLabel = MutableStateFlow("")
    val voiceLabel: StateFlow<String> = _voiceLabel.asStateFlow()
    private val _voiceNotice = MutableStateFlow<String?>(null)
    val voiceNotice: StateFlow<String?> = _voiceNotice.asStateFlow()
    private var voicePlayer: Mp3Player? = null

    private var idCounter = 0L

    init {
        _notes.value = device.loadNotes()
        viewModelScope.launch { _memories.value = memory.facts() }
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

    /** "Hey Jarvis" background wake-word listener on/off. */
    fun setHandsFree(enabled: Boolean) {
        prefs.edit().putBoolean("jarvis_hands_free", enabled).apply()
        _handsFree.value = enabled
        val app = getApplication<Application>()
        if (enabled) WakeWordService.start(app) else WakeWordService.stop(app)
    }

    // ------------------------------------------------------------------ chat

    fun handleUserText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        stopSpeak()
        _messages.value = _messages.value + JarvisMessage(nextId(), trimmed, isUser = true)

        val intent = JarvisCommandParser.parse(trimmed)
        val type = intent?.type ?: JarvisIntentType.UNKNOWN

        if (type == JarvisIntentType.MEMORY) {
            val name = intent?.args?.get("name")?.trim().orEmpty()
            if (name.isNotEmpty()) {
                prefs.edit().putString("jarvis_user_name", name).apply()
                _userName.value = name
                appendBot("Bahut achha $name, ab main aapko yaad rakh lunga.")
                return
            }
        }

        if (intent != null && type != JarvisIntentType.UNKNOWN && type != JarvisIntentType.CREATOR_HELP) {
            viewModelScope.launch {
                val outcome = device.execute(intent)
                _notes.value = device.loadNotes()
                if (intent.type == JarvisIntentType.MEMORY_ADD || intent.type == JarvisIntentType.MEMORY_LIST) {
                    _memories.value = memory.facts()
                }
                appendBot(outcome.message)
            }
        } else {
            askBrain()
        }
    }

    /** Character + memory से जुड़ा user context — Gemini को हर बार ताज़ा। */
    private fun characterContext(): JarvisCharacter.UserContext {
        val app = getApplication<Application>()
        return JarvisCharacter.UserContext(
            userName = _userName.value.ifBlank { "sahab" },
            facts = _memories.value.map { it.value },
            channels = listOf("GT Marwadi Vlogs", "GT Tech Gyaan", "GT6 Ki Duniya Hindi",
                "Mind Facts Hindi", "Kids Videos Hindi Story", "Lumo Tales", "Khanak Drops"),
            work = "6+ YouTube channels, clipping business, Hindi dubbing, product reels"
        )
    }

    private fun askBrain() {
        val key = GeminiApiKeyStore.read(getApplication())
        _apiKey.value = key
        if (key.isBlank()) {
            appendBot(
                "Main device ke kaam (app kholo, alarm, timer, weather, notes, call, SMS, flashlight) " +
                    "bina key ke bhi kar sakta hoon. Baat-cheet aur creator tasks ke liye Gemini API key " +
                    "daaliye — aistudio.google.com/apikey se free milti hai."
            )
            return
        }
        viewModelScope.launch {
            _isThinking.value = true
            try {
                val brain = JarvisBrain(
                    key,
                    characterContext(),
                    GeminiModelSettings.read(getApplication())
                )
                val reply = brain.reply(_messages.value)
                if (reply.action != null) {
                    val outcome = device.execute(reply.action)
                    _notes.value = device.loadNotes()
                    val spoken = when {
                        reply.text.isNotBlank() && outcome.success -> reply.text
                        reply.text.isNotBlank() -> "${reply.text} (${outcome.message})"
                        else -> outcome.message
                    }
                    appendBot(spoken)
                } else {
                    appendBot(reply.text.ifBlank { "(Khaali jawab mila, dobara puchiye.)" })
                }
            } catch (e: Exception) {
                appendBot("Jawab nahi mil paya: ${e.message ?: "internet check kariye"}")
            } finally {
                _isThinking.value = false
            }
        }
    }

    private fun appendBot(text: String) {
        _messages.value = _messages.value + JarvisMessage(nextId(), text, isUser = false)
        // भावना: user की आखिरी बात + Jarvis का जवाब — दोनों से
        val lastUser = _messages.value.lastOrNull { it.isUser }?.text.orEmpty()
        val emotion = EmotionEngine.detect(lastUser, text)
        speak(text, emotion)
    }

    fun clearChat() {
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
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                    }
                })
                _ttsReady.value = true
                pendingPhoneSpeech?.let { (text, speed) ->
                    pendingPhoneSpeech = null
                    speakPhone(text, speed)
                }
            }
        }
    }

    /**
     * बोलो — emotion के अनुसार voice, फिर fallback के साथ।
     * Smart mode: मज़बूत भावना → ElevenLabs; नहीं तो free phone voice।
     */
    fun speak(text: String, emotion: Emotion = _emotion.value) {
        _emotion.value = emotion
        _voiceNotice.value = null
        if (!_autoSpeak.value) return

        val app = getApplication<Application>()
        val mode = VoiceSettings.mode(app)
        val decision = VoiceRouter.decide(app, mode, emotion)

        _voiceLabel.value = when (decision.provider) {
            VoiceRouter.Provider.PHONE -> "फ़ोन की आवाज़"
            VoiceRouter.Provider.GEMINI -> "Gemini ${VoiceSettings.geminiVoice(app)}"
            VoiceRouter.Provider.ELEVENLABS -> "${VoiceSettings.voiceName(decision.voiceId)} (ElevenLabs)"
            VoiceRouter.Provider.SMALLEST -> "Smallest ${decision.voiceId.ifBlank { "clone" }}"
            VoiceRouter.Provider.HEYGEN -> "HeyGen"
        }

        when (decision.provider) {
            VoiceRouter.Provider.PHONE -> speakPhone(text, decision.speed)
            VoiceRouter.Provider.GEMINI -> speakPhone(text, decision.speed) // Live preset phone fallback
            else -> speakServer(text, decision, emotion, mode)
        }
    }

    private fun speakPhone(text: String, speed: Float) {
        val engine = tts
        if (engine == null || !_ttsReady.value) {
            pendingPhoneSpeech = text to speed
            return
        }
        pendingPhoneSpeech = null
        engine.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_${System.currentTimeMillis()}")
    }

    /** Server TTS (ElevenLabs / Smallest / HeyGen) — fail हो तो phone fallback। */
    private fun speakServer(text: String, decision: VoiceRouter.Decision, emotion: Emotion, mode: VoiceMode) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val provider = when (decision.provider) {
                VoiceRouter.Provider.ELEVENLABS -> "elevenlabs"
                VoiceRouter.Provider.SMALLEST -> "smallest"
                VoiceRouter.Provider.HEYGEN -> "heygen"
                else -> return@launch
            }
            runCatching {
                val client = TtsClient(app)
                val bytes = client.synthesize(
                    text = text,
                    provider = provider,
                    voice = decision.voiceId,
                    speed = decision.speed,
                    sentenceMode = true,
                    style = decision.elevenStyle,
                    stability = decision.elevenStability,
                    elevenKey = VoiceSettings.elevenKey(app)
                )
                val file = client.saveMp3(bytes, "reply_${System.currentTimeMillis()}.mp3")
                if (decision.provider == VoiceRouter.Provider.ELEVENLABS) {
                    VoiceState.elevenBlocked = false // credits वापस आ गए
                }
                _isSpeaking.value = true
                val player = voicePlayer ?: Mp3Player(app).also { voicePlayer = it }
                player.play(file) { _isSpeaking.value = false }
            }.onFailure { error ->
                // Never fail silently: a plan restriction or service error falls back to Android TTS.
                if (decision.provider == VoiceRouter.Provider.ELEVENLABS) {
                    VoiceState.elevenBlocked = true
                    _voiceLabel.value = "फ़ोन की मुफ़्त आवाज़"
                    _voiceNotice.value = if (isElevenLabsPaymentOrPlanError(error)) {
                        "ElevenLabs free plan par ye awaaz nahi chalegi. Phone ki free voice se jawab suna diya."
                    } else {
                        "ElevenLabs awaaz nahi chal saki (${error.message ?: "service error"}). Phone ki free voice se jawab suna diya."
                    }
                }
                speakPhone(text, decision.speed)
            }
        }
    }

    private fun isElevenLabsPaymentOrPlanError(error: Throwable): Boolean {
        if (error is ElevenLabsException && error.isPaymentOrPlanError) return true
        if (error is TtsRequestException && error.isPaymentOrPlanError) return true
        val details = generateSequence(error as Throwable?) { it.cause }
            .take(5)
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase()
        return listOf("paid_plan_required", "payment_required", "payment required", "paid plan", "plan required")
            .any { details.contains(it) } || Regex("""\b402\b""").containsMatchIn(details)
    }

    fun dismissVoiceNotice() {
        _voiceNotice.value = null
    }

    fun stopSpeak() {
        pendingPhoneSpeech = null
        tts?.stop()
        runCatching { voicePlayer?.stop() }
        _isSpeaking.value = false
    }

    fun voiceMode(): VoiceMode = VoiceSettings.mode(getApplication())

    fun setVoiceMode(mode: VoiceMode) {
        VoiceSettings.setMode(getApplication(), mode)
        appendBot("Voice mode: ${mode.name.lowercase().replace('_', ' ')} — अब Jarvis इसी आवाज़ में बोलेगा।")
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

    fun forgetMemory(id: Long) {
        viewModelScope.launch {
            memory.forget(id)
            _memories.value = memory.facts()
        }
    }

    fun clearMemory() {
        viewModelScope.launch {
            memory.clear()
            _memories.value = emptyList()
            appendBot("Saari yaadein mit gayi.")
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
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onCleared()
    }

}
