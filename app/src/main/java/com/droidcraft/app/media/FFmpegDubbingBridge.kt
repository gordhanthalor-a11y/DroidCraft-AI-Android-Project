package com.droidcraft.app.media

import android.content.Context
import android.util.Log

object FFmpegDubbingBridge {
    private const val TAG = "FFmpegDubbingBridge"

    fun buildFilterGraph(
        hasOcrMask: Boolean,
        maskMode: String,
        audioDuckingDb: Int = -12
    ): String {
        Log.i(TAG, "Building media filter graph: maskMode=$maskMode, ducking=${audioDuckingDb}dB")
        val audioFilter = "volume=${audioDuckingDb}dB"
        val videoFilter = if (hasOcrMask && maskMode == "blur") {
            "boxblur=luma_radius=15:luma_power=3"
        } else {
            "null"
        }
        return "[$videoFilter];[$audioFilter]"
    }
}