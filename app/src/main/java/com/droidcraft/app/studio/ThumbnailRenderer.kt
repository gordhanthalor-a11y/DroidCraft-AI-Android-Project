package com.droidcraft.app.studio

import android.content.Context
import android.graphics.*
import android.net.Uri
import java.io.File

/**
 * B6 — Thumbnail Text: photo पर बड़ा bold Hindi text (पीला + गहरी काली outline),
 * full-frame 9:16 (1080×1920), नीचे-बाएँ कोना खाली रहता है।
 */
object ThumbnailRenderer {

    data class Options(
        val text: String,
        val textSizePx: Float = 150f,
        val fillColor: Int = Color.YELLOW,
        val strokeColor: Int = Color.BLACK,
        val strokeWidthPx: Float = 12f,
        val bottomLeftClear: Boolean = true   // spec: नीचे-बाएँ कोना खाली
    )

    /** Image URI पर text गुज़ारकर 1080×1920 thumbnail PNG बनाओ। */
    fun render(context: Context, imageUri: Uri, opts: Options): File {
        val src = BitmapFactory.decodeStream(
            context.contentResolver.openInputStream(imageUri)
        ) ?: throw IllegalStateException("photo नहीं खुल सकी")

        val outW = 1080; val outH = 1920
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // photo full-frame cover
        val scale = maxOf(outW.toFloat() / src.width, outH.toFloat() / src.height)
        val dw = src.width * scale; val dh = src.height * scale
        val dx = (outW - dw) / 2f; val dy = (outH - dh) / 2f
        canvas.drawBitmap(src, null, RectF(dx, dy, dx + dw, dy + dh), Paint(Paint.FILTER_BITMAP_FLAG))

        // dark scrim ताकि text पढ़े
        canvas.drawColor(Color.argb(90, 0, 0, 0))

        // text paint — पीला fill + काली outline
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = opts.fillColor
            textSize = opts.textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = opts.strokeColor
            textSize = opts.textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
            strokeWidth = opts.strokeWidthPx
        }

        // multi-line wrap
        val lines = wrapText(opts.text, fill, outW - 120f)
        val lineH = opts.textSizePx * 1.25f
        // ऊपर-केंद्र की ओर रखो (नीचे-बाएँ खाली रखना है)
        var y = outH * 0.30f
        lines.forEach { line ->
            // outline पहले, फिर fill
            canvas.drawText(line, outW / 2f, y, stroke)
            canvas.drawText(line, outW / 2f, y, fill)
            y += lineH
        }

        val dir = File(context.filesDir, "thumbnails").apply { mkdirs() }
        val f = File(dir, "thumb_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
        return f
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var cur = StringBuilder()
        words.forEach { w ->
            val trial = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(trial) <= maxWidth || cur.isEmpty()) {
                cur = StringBuilder(trial)
            } else {
                lines += cur.toString()
                cur = StringBuilder(w)
            }
        }
        if (cur.isNotBlank()) lines += cur.toString()
        return lines.ifEmpty { listOf(text) }
    }
}
