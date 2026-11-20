package com.droidcraft.app.studio.tts

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * MP3 player + share — Voice Studio का playback और save/share।
 */
class Mp3Player(private val context: Context) {

    companion object {
        private const val TAG = "Mp3Player"
    }

    private var player: MediaPlayer? = null
    @Volatile
    var isPlaying: Boolean = false
        private set

    fun play(file: File, onDone: () -> Unit = {}) {
        stop()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    isPlaying = false
                    onDone()
                }
                prepare()
                start()
            }
            isPlaying = true
        }.onFailure {
            Log.e(TAG, "play fail", it)
            isPlaying = false
            onDone()
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        isPlaying = false
    }

    /** MP3 share करो (WhatsApp / Drive / kahीं भी)। */
    fun share(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Awaaz share करो")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun release() = stop()
}
