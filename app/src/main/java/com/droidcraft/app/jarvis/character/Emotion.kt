package com.droidcraft.app.jarvis.character

/** Emotion detected from the user's message and Jarvis's reply. */
enum class Emotion {
    HAPPY,
    SAD,
    ANGRY,
    CALM,
    ENCOURAGING,
    THINKING,
    HUMOR,
    SPEAKING,
    NEUTRAL
}

data class EmotionStyle(
    val avatarExpression: String,
    val avatarColor: Long,
    val voiceSpeed: Float,
    val statusLabel: String
)

object EmotionEngine {
    fun style(emotion: Emotion): EmotionStyle = when (emotion) {
        Emotion.HAPPY -> EmotionStyle("😄", 0xFF4ADE80, 1.15f, "खुश")
        Emotion.SAD -> EmotionStyle("😔", 0xFF7C8CF8, 0.82f, "दुखी")
        Emotion.ANGRY -> EmotionStyle("😌", 0xFF7DF9FF, 0.90f, "शांत")
        Emotion.CALM -> EmotionStyle("🙂", 0xFF7DF9FF, 0.95f, "शांत")
        Emotion.ENCOURAGING -> EmotionStyle("💪", 0xFFFBBF24, 1.05f, "हिम्मत दे रहा")
        Emotion.THINKING -> EmotionStyle("🤔", 0xFF94A3B8, 1.0f, "सोच रहा")
        Emotion.HUMOR -> EmotionStyle("😄", 0xFF4ADE80, 1.04f, "मज़ाकिया")
        Emotion.SPEAKING -> EmotionStyle("🗣️", 0xFF7DF9FF, 1.0f, "बोल रहा")
        Emotion.NEUTRAL -> EmotionStyle("🙂", 0xFF7DF9FF, 1.0f, "तैयार")
    }

    /** Infer a conversational tone from the user's last message, then Jarvis's reply. */
    fun detect(userText: String, jarvisReply: String): Emotion {
        val user = userText.lowercase()
        val reply = jarvisReply.lowercase()
        return when {
            user.contains("joke") || user.contains("mazaak") || user.contains("mazak") ||
                user.contains("चुटकुल") || user.contains("मज़ाक") || user.contains("मजाक") ||
                user.contains("haha") || user.contains("हाहा") || reply.contains("haha") ||
                reply.contains("😂") || reply.contains("चुटकुल") || reply.contains("मज़ाक") || reply.contains("मजाक") -> Emotion.HUMOR

            user.contains("बुरा") || user.contains("दुख") || user.contains("दुःख") || user.contains("उदास") ||
                user.contains("रो") || user.contains("तकलीफ") || user.contains("मुसीबत") || user.contains("अकेला") ||
                user.contains("निराश") || user.contains("फेल") || user.contains("गुस्सा नहीं") ||
                user.contains("मर गया") || user.contains("बोर") || user.contains("थक") || user.contains("sad") ||
                user.contains("upset") || user.contains("hurt") || user.contains("failed") || user.contains("lost") ||
                user.contains("मरा") || user.contains("चोट") || user.contains("बीमार") -> Emotion.SAD

            user.contains("गुस्सा") || user.contains("नाराज") || user.contains("चिढ़") || user.contains("बकवास") ||
                user.contains("बेकार") || user.contains("angry") || user.contains("hate") || user.contains("stupid") ||
                user.contains("बंद कर") || user.contains("बेवकूफ") -> Emotion.ANGRY

            user.contains("बढ़िया") || user.contains("शानदार") || user.contains("बहुत अच्छा") || user.contains("वाह") ||
                user.contains("कमाल") || user.contains("मुबारक") || user.contains("पास") || user.contains("जीत") ||
                user.contains("सफल") || user.contains("अमानत") || user.contains("नौकरी मिल") || user.contains("मिल गया") ||
                user.contains("great") || user.contains("awesome") || user.contains("congrat") || user.contains("won") ||
                user.contains("passed") || user.contains("selected") || user.contains("approve") -> Emotion.HAPPY

            user.contains("डर") || user.contains("हिम्मत") || user.contains("सपना") ||
                user.contains("कोशिश") || user.contains("मेहनत") || user.contains("हार") || user.contains("अगली बार") ||
                user.contains("hope") || user.contains("scared") || user.contains("nervous") -> Emotion.ENCOURAGING

            else -> when {
                reply.contains("कोई बात नहीं") || reply.contains("हिम्मत") || reply.contains("अगली बार") -> Emotion.ENCOURAGING
                reply.contains("दुख") || reply.contains("माफ़") || reply.contains("समझ") -> Emotion.SAD
                reply.contains("मज़ा") || reply.contains("वाह") || reply.contains("बढ़िया") -> Emotion.HAPPY
                else -> Emotion.NEUTRAL
            }
        }
    }

    fun geminiInstruction(emotion: Emotion): String = when (emotion) {
        Emotion.HUMOR -> "Deliver this lightly and playfully, with a subtle smiling tone. Speak only the written reply."
        Emotion.SAD -> "Speak gently and empathetically, with a calm reassuring pace."
        Emotion.ENCOURAGING -> "Speak warmly and confidently, with natural encouragement."
        Emotion.ANGRY -> "Stay calm, respectful, and reassuring; do not sound angry."
        Emotion.HAPPY -> "Speak with friendly, positive energy without sounding exaggerated."
        else -> "Speak naturally and clearly in the language used by the text."
    }
}
