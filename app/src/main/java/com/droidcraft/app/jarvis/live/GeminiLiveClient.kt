package com.droidcraft.app.jarvis.live

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini Live (BidiGenerateContent WebSocket) — real-time voice.
 * Mic PCM bhejta hai, server ka 24kHz PCM wapas aata hai, barge-in sambhalta hai.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val systemPrompt: String,
    private val voiceName: String = "Puck",
    private val listener: Listener
) {

    interface Listener {
        fun onStatus(status: String)
        fun onText(text: String)
        fun onAudio(pcm: ByteArray)
        fun onInterrupted()
        fun onTurnCompleted()
        fun onToolCall(name: String, args: JSONObject)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val DEFAULT_MODEL = "models/gemini-3.1-flash-live-preview"
        const val FALLBACK_MODEL = "models/gemini-2.5-flash-native-audio-preview"

        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private var webSocket: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private var stoppedByUser = false

    fun connect() {
        stoppedByUser = false
        listener.onStatus("CONNECTING")
        val request = Request.Builder()
            .url("$WS_URL?key=$apiKey")
            .build()
        webSocket = sharedClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true)
                listener.onStatus("CONFIGURING")
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleMessage(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false)
                ready.set(false)
                if (!stoppedByUser) listener.onError("Connection band ho gayi ($code).")
                listener.onStatus("OFFLINE")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false)
                ready.set(false)
                val msg = t.message ?: "toot gaya"
                if (!stoppedByUser) listener.onError("Live connection fail: $msg")
                listener.onStatus("OFFLINE")
            }
        })
    }

    private fun sendSetup(ws: WebSocket) {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", DEFAULT_MODEL)
                put(
                    "system_instruction",
                    JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                    }
                )
                put(
                    "generation_config",
                    JSONObject().apply {
                        put("response_modalities", JSONArray().put("TEXT").put("AUDIO"))
                        put(
                            "speech_config",
                            JSONObject().apply {
                                put(
                                    "voice_config",
                                    JSONObject().apply {
                                        put(
                                            "prebuilt_voice_config",
                                            JSONObject().put("voice_name", voiceName)
                                        )
                                    }
                                )
                            }
                        )
                        put("temperature", 0.6)
                        put("thinking_config", JSONObject().put("thinking_budget", 0))
                    }
                )
            })
        }
        ws.send(setup.toString())
    }

    private fun handleMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return

        if (json.has("setupComplete")) {
            ready.set(true)
            listener.onStatus("LIVE")
            return
        }

        json.optJSONObject("error")?.let { err ->
            val msg = err.optString("message", "server error")
            if (msg.contains("model", ignoreCase = true) && msg.contains("not found", ignoreCase = true)) {
                listener.onError("Model uplabdh nahi hai: $msg")
            } else {
                listener.onError("Server error: $msg")
            }
            return
        }

        if (json.has("goAway")) {
            listener.onError("Server ne session band kiya (goAway).")
            return
        }

        val serverContent = json.optJSONObject("serverContent") ?: return

        if (serverContent.has("interrupted")) {
            listener.onInterrupted()
            return
        }

        if (serverContent.optBoolean("turnComplete", false)) {
            listener.onTurnCompleted()
        }

        val modelTurn = serverContent.optJSONObject("modelTurn")
        if (modelTurn != null) {
            val parts = modelTurn.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    part.optString("text").takeIf { it.isNotBlank() }?.let { listener.onText(it) }
                    part.optJSONObject("inlineData")?.let { inline ->
                        val data = inline.optString("data")
                        if (data.isNotBlank()) {
                            runCatching {
                                listener.onAudio(Base64.decode(data, Base64.NO_WRAP))
                            }
                        }
                    }
                }
            }
        }

        val toolCall = json.optJSONArray("toolCall")
        if (toolCall != null) {
            for (i in 0 until toolCall.length()) {
                val fn = toolCall.optJSONObject(i)?.optJSONObject("functionCall") ?: continue
                listener.onToolCall(fn.optString("name"), fn.optJSONObject("args") ?: JSONObject())
            }
        }
    }

    /** Mic ka 16kHz PCM chunk bhejo. */
    fun sendAudio(pcm: ByteArray) {
        if (!ready.get()) return
        val base64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put(
                "realtimeInput",
                JSONObject().apply {
                    put(
                        "audio",
                        JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", base64)
                        }
                    )
                }
            )
        }
        runCatching { webSocket?.send(payload.toString()) }
    }

    /** Barge-in: local audio flush, sunna jari rahega. */
    fun interrupt() {
        listener.onInterrupted()
    }

    fun disconnect() {
        stoppedByUser = true
        connected.set(false)
        ready.set(false)
        runCatching { webSocket?.close(1000, "user stopped") }
        webSocket = null
        listener.onStatus("OFFLINE")
    }

    fun isReady(): Boolean = ready.get()
}
