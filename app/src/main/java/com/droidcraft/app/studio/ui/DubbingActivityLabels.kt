package com.droidcraft.app.studio.ui

internal fun dubbingActivityStep(status: String): String = when (status.lowercase()) {
    "downloading" -> "Downloading source video"
    "extracting_audio" -> "Extracting source audio"
    "transcribing" -> "Transcribing source audio"
    "ready" -> "Transcript ready for editing"
    "failed" -> "Dubbing preparation failed on the server"
    else -> "Dubbing server status · $status"
}

internal fun dubbingRenderActivityStep(status: String): String = when (status.lowercase()) {
    "rendering" -> "Generating voice and merging dubbed video"
    "done" -> "Server render finished"
    "failed" -> "Dubbing render failed on the server"
    else -> "Dubbing render status · $status"
}
