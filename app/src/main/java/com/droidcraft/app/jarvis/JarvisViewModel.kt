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
import com.droidcraft.app.jarvis.memory.MemoryManager
import com.droidcraft.app.jarvis.live.GeminiLiveClient
import com.droidcraft.app.jarvis.live.LiveMic
import com.droidcraft.app.jarvis.live.LiveSpeaker
import com.droidcraft.app.jarvis.wake.WakeWordService
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

    private var tts: TextToSpeech? = null

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

    private val _apiKey = MutableStateFlow(prefs.getString("gemini_key", "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

    private val _handsFree = MutableStateFlow(prefs.getBoolean("jarvis_hands_free", false))
    val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()

    private val _memories = MutableStateFlow<List<MemoryEntity>>(emptyList())
    val memories: StateFlow<List<MemoryEntity>> = _memories.asStateFlow()

    // ---------------- Gemini Live (realtime voice) ----------------
    private val _liveOn = MutableStateFlow(false)
    val liveOn: StateFlow<Boolean> = _liveOn.asStateFlow()
    private val _liveStatus = MutableStateFlow("OFFLINE")
    val liveStatus: StateFlow<String> = _liveStatus.asStateFlow()
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()
    private var liveClient: GeminiLiveClient? = null
    private var liveMic: LiveMic? = null
    private var liveSpeaker: LiveSpeaker? = null

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

    private fun askBrain() {
        val key = _apiKey.value
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
                val brain = JarvisBrain(key)
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
        speak(text)
    }

    fun startLive() {
        val key = _apiKey.value
        if (key.isBlank()) {
            appendBot("Live voice ke liye pehle Gemini API key daaliye (⚙️ icon).")
            return
        }
        if (_liveOn.value) return
        stopSpeak()
        val speaker = LiveSpeaker().also { it.start() }
        liveSpeaker = speaker
        val mic = LiveMic()
        mic.onPcm = { pcm -> liveClient?.sendAudio(pcm) }
        mic.start()
        liveMic = mic
        val client = GeminiLiveClient(
            apiKey = key,
            systemPrompt = LIVE_PROMPT,
            listener = object : GeminiLiveClient.Listener {
                override fun onStatus(status: String) {
                    _liveStatus.value = status
                }

                override fun onText(text: String) {
                    _liveText.value = _liveText.value + text
                }

                override fun onAudio(pcm: ByteArray) {
                    speaker.write(pcm)
                }

                override fun onInterrupted() {
                    speaker.flush()
                    _liveStatus.value = "LISTENING"
                }

                override fun onTurnCompleted() {
                    val said = _liveText.value.trim()
                    if (said.isNotBlank()) {
                        _messages.value = _messages.value +
                            JarvisMessage(nextId(), said, isUser = false)
                        speak(said)
                    }
                    _liveText.value = ""
                }

                override fun onToolCall(name: String, args: JSONObject) {
                    handleLiveTool(name, args)
                }

                override fun onError(message: String) {
                    appendBot("Live: $message")
                }
            }
        )
        liveClient = client
        _liveOn.value = true
        _liveStatus.value = "CONNECTING"
        appendBot("Live voice on — seedha boliye, main sun raha hoon.")
        client.connect()
    }

    fun stopLive() {
        liveMic?.stop()
        liveMic = null
        liveSpeaker?.stop()
        liveSpeaker = null
        liveClient?.disconnect()
        liveClient = null
        _liveOn.value = false
        _liveStatus.value = "OFFLINE"
        _liveText.value = ""
    }

    private fun handleLiveTool(name: String, args: JSONObject) {
        val intent = when (name) {
            "open_app" -> JarvisIntent(
                JarvisIntentType.OPEN_APP,
                mapOf("query" to args.optString("app_name"))
            )
            "open_website", "open_url" -> JarvisIntent(
                JarvisIntentType.OPEN_URL,
                mapOf("url" to args.optString("url").ifBlank { args.optString("urls") })
            )
            else -> null
        } ?: return
        viewModelScope.launch { device.execute(intent) }
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
            }
        }
    }

    fun speak(text: String) {
        if (!_autoSpeak.value) return
        if (_liveOn.value) return // Live mode mein audio WebSocket se aata hai
        val engine = tts ?: return
        if (!_ttsReady.value) return
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_${System.currentTimeMillis()}")
    }

    fun stopSpeak() {
        tts?.stop()
        _isSpeaking.value = false
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
        prefs.edit().putString("gemini_key", k).apply()
        _apiKey.value = k
        if (k.isNotBlank()) appendBot("API key aa gayi — ab main poori tarah se taiyaar hoon, ${_userName.value.ifBlank { "sahab" }}!")
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
        stopLive()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onCleared()
    }

    companion object {
        private val LIVE_PROMPT = """
            Tum J.A.R.V.I.S. ho (Just A Rather Very Intelligent System) — ek real-time voice assistant. 
            Hindi, Hinglish aur English mein natural tarah baat karo. Jawab chhota, bolne layak (2-3 line) rakho — 
            kabhi bhi bullet points, markdown ya code mat likhna. Device actions ke liye function calling karo.
            Tum ek YouTube creator "GT Marwadi Vlogs" ke saath bhi kaam karte ho.
        """.trimIndent()
    }
}
