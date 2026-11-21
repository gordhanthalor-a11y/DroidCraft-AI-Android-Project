package com.droidcraft.app.ai

/** Edge resampling for the Live wire contract: mono signed PCM16, 16 kHz in and 24 kHz out. */
internal object Pcm16Resampler {
    fun convert(pcm: ByteArray, byteCount: Int, inputRate: Int, outputRate: Int): ByteArray {
        val count = byteCount.coerceIn(0, pcm.size) and -2
        if (count == 0) return ByteArray(0)
        require(inputRate > 0 && outputRate > 0) { "Audio sample rates must be positive." }
        if (inputRate == outputRate) return pcm.copyOf(count)

        val inputSamples = ShortArray(count / 2) { index ->
            ((pcm[index * 2].toInt() and 0xff) or (pcm[index * 2 + 1].toInt() shl 8)).toShort()
        }
        if (inputSamples.size == 1) return shortArrayToPcm16(shortArrayOf(inputSamples[0]))
        val outputCount = ((inputSamples.size.toLong() * outputRate) / inputRate)
            .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val output = ShortArray(outputCount)
        for (index in output.indices) {
            val position = index.toDouble() * inputRate / outputRate
            val leftIndex = position.toInt().coerceIn(0, inputSamples.lastIndex)
            val rightIndex = (leftIndex + 1).coerceAtMost(inputSamples.lastIndex)
            val fraction = (position - leftIndex).toFloat().coerceIn(0f, 1f)
            val left = inputSamples[leftIndex].toInt()
            val right = inputSamples[rightIndex].toInt()
            output[index] = (left + (right - left) * fraction).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return shortArrayToPcm16(output)
    }

    private fun shortArrayToPcm16(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { output ->
        samples.forEachIndexed { index, sample ->
            output[index * 2] = sample.toByte()
            output[index * 2 + 1] = (sample.toInt() ushr 8).toByte()
        }
    }

}
