package com.droidcraft.app.jarvis.character

/**
 * Emotion engine — बात से Jarvis की भावना निकालता है, और वह भावना
 * तीन जगह दिखती है: शब्द, आवाज़ का टोन, HUD avatar।
 */
enum class Emotion {
    HAPPY,      // खुशी, उत्साह
    SAD,        // दुख, नरमी
    ANGRY,      // गुस्सा (user का) — Jarvis शांत
    CALM,       // शांत, समझाना
    ENCOURAGING,// हिम्मत, प्रोत्साहन
    THINKING,   // सोच रहा
    HUMOR,      // हल्का-फुल्का, मज़ाक
    SPEAKING,   // बोल रहा
    NEUTRAL     // सामान्य
}

data class EmotionStyle(
    val avatarExpression: String,   // HUD पर चेहरा
    val avatarColor: Long,          // ARGB
    val voiceSpeed: Float,          // TTS speed multiplier
    val elevenStyle: Float,         // ElevenLabs style (0-1)
    val elevenStability: Float,     // ElevenLabs stability (0-1)
    val statusLabel: String
)

object EmotionEngine {

    /** हर भावना की delivery style। */
    fun style(e: Emotion): EmotionStyle = when (e) {
        Emotion.HAPPY -> EmotionStyle("😄", 0xFF4ADE80, 1.15f, 0.85f, 0.35f, "खुश")
        Emotion.SAD -> EmotionStyle("😔", 0xFF7C8CF8, 0.82f, 0.15f, 0.80f, "दुखी")
        Emotion.ANGRY -> EmotionStyle("😌", 0xFF7DF9FF, 0.90f, 0.20f, 0.70f, "शांत")
        Emotion.CALM -> EmotionStyle("🙂", 0xFF7DF9FF, 0.95f, 0.25f, 0.65f, "शांत")
        Emotion.ENCOURAGING -> EmotionStyle("💪", 0xFFFBBF24, 1.05f, 0.60f, 0.45f, "हिम्मत दे रहा")
        Emotion.THINKING -> EmotionStyle("🤔", 0xFF94A3B8, 1.0f, 0.30f, 0.50f, "सोच रहा")
        Emotion.HUMOR -> EmotionStyle("😄", 0xFF4ADE80, 1.04f, 0.72f, 0.42f, "मज़ाकिया")
        Emotion.SPEAKING -> EmotionStyle("🗣️", 0xFF7DF9FF, 1.0f, 0.40f, 0.50f, "बोल रहा")
        Emotion.NEUTRAL -> EmotionStyle("🙂", 0xFF7DF9FF, 1.0f, 0.35f, 0.50f, "तैयार")
    }

    /**
     * बात से भावना निकालो।
     * user की अंतिम बात और Jarvis की प्रतिक्रिया दोनों देखता है।
     */
    fun detect(userText: String, jarvisReply: String): Emotion {
        val u = userText.lowercase()
        val j = jarvisReply.lowercase()

        // user की भावना सबसे पहले
        return when {
            // Laughter and jokes deliberately use Gemini TTS rather than emotional synthesis.
            u.contains("joke") || u.contains("mazaak") || u.contains("mazak") ||
                u.contains("चुटकुल") || u.contains("मज़ाक") || u.contains("मजाक") ||
                u.contains("haha") || u.contains("हाहा") || j.contains("haha") ||
                j.contains("😂") || j.contains("चुटकुल") || j.contains("मज़ाक") || j.contains("मजाक") -> Emotion.HUMOR

            // निराशा / दुख
            u.contains("बुरा") || u.contains("दुख") || u.contains("दुःख") || u.contains("उदास") ||
                u.contains("रो") || u.contains("तकलीफ") || u.contains("मुसीबत") || u.contains("अकेला") ||
                u.contains("निराश") || u.contains("फेल") || u.contains("फेल हो") || u.contains("गुस्सा नहीं") ||
                u.contains("मर गया") || u.contains("बोर") || u.contains("थक") || u.contains("sad") ||
                u.contains("upset") || u.contains("hurt") || u.contains("failed") || u.contains("lost") ||
                u.contains("मरा") || u.contains("चोट") || u.contains("बीमार") -> Emotion.SAD

            // गुस्सा
            u.contains("गुस्सा") || u.contains("नाराज") || u.contains("चिढ़") || u.contains("बकवास") ||
                u.contains("बेकार") || u.contains("angry") || u.contains("hate") || u.contains("stupid") ||
                u.contains("बंद कर") || u.contains("बेवकूफ") -> Emotion.ANGRY

            // खुशी / अच्छी खबर
            u.contains("बढ़िया") || u.contains("शानदार") || u.contains("बहुत अच्छा") || u.contains("वाह") ||
                u.contains("कमाल") || u.contains("मुबारक") || u.contains("पास") || u.contains("जीत") ||
                u.contains("सफल") || u.contains("अमानत") || u.contains("नौकरी मिल") || u.contains("मिल गया") ||
                u.contains("great") || u.contains("awesome") || u.contains("congrat") || u.contains("won") ||
                u.contains("passed") || u.contains("selected") || u.contains("approve") -> Emotion.HAPPY

            // हिम्मत चाहिए
            u.contains("डर") || u.contains("कान घुट") || u.contains("हिम्मत") || u.contains("सपना") ||
                u.contains("कोशिश") || u.contains("मेहनत") || u.contains("हार") || u.contains("अगली बार") ||
                u.contains("hope") || u.contains("scared") || u.contains("nervous") -> Emotion.ENCOURAGING

            else -> {
                // Jarvis के जवाब से भी समझो
                when {
                    j.contains("कोई बात नहीं") || j.contains("हिम्मत") || j.contains("अगली बार") -> Emotion.ENCOURAGING
                    j.contains("दुख") || j.contains("माफ़") || j.contains("समझ") -> Emotion.SAD
                    j.contains("मज़ा") || j.contains("वाह") || j.contains("बढ़िया") -> Emotion.HAPPY
                    else -> Emotion.NEUTRAL
                }
            }
        }
    }

    /** Strong responses use ElevenLabs first in SMART mode when a key is available. */
    fun isStrong(e: Emotion): Boolean = e == Emotion.HAPPY || e == Emotion.SAD ||
        e == Emotion.ENCOURAGING || e == Emotion.ANGRY

    /** VoxCPM2 is for emotional delivery only; neutral and humour are kept on Gemini TTS. */
    fun isEmotional(e: Emotion): Boolean = when (e) {
        Emotion.HAPPY, Emotion.SAD, Emotion.ANGRY, Emotion.CALM, Emotion.ENCOURAGING, Emotion.THINKING -> true
        Emotion.HUMOR, Emotion.SPEAKING, Emotion.NEUTRAL -> false
    }

    fun voxcpmInstruction(e: Emotion): String = when (e) {
        Emotion.HAPPY -> "Speak with bright, joyful energy and natural enthusiasm; do not shout."
        Emotion.SAD -> "Use a gentle, empathetic, reassuring tone with a slightly slower pace."
        Emotion.ANGRY -> "Stay calm, warm, and de-escalating; do not sound angry."
        Emotion.CALM -> "Use a calm, grounded, clear conversational voice."
        Emotion.ENCOURAGING -> "Sound encouraging and confident, with warm energy and clear emphasis."
        Emotion.THINKING -> "Use a thoughtful, measured, natural conversational delivery."
        Emotion.HUMOR -> "Use a light, playful, smiling delivery; keep the joke natural."
        Emotion.SPEAKING, Emotion.NEUTRAL -> "Use a natural, clear conversational delivery."
    }

    fun geminiInstruction(e: Emotion): String = when (e) {
        Emotion.HUMOR -> "Deliver this lightly and playfully, with a subtle smiling tone. Speak only the written reply."
        Emotion.SAD -> "Speak gently and empathetically, with a calm reassuring pace."
        Emotion.ENCOURAGING -> "Speak warmly and confidently, with natural encouragement."
        Emotion.ANGRY -> "Stay calm, respectful, and reassuring; do not sound angry."
        Emotion.HAPPY -> "Speak with friendly, positive energy without sounding exaggerated."
        else -> "Speak naturally and clearly in the language used by the text."
    }
}
