package com.droidcraft.app.jarvis.voice

/**
 * Produces a speech-only copy of assistant text. Callers keep the original message for display.
 */
object SpokenTextCleaner {
    private val jarvisDottedName = Regex(
        "(?i)\\bJ\\s*\\.\\s*A\\s*\\.\\s*R\\s*\\.\\s*V\\s*\\.\\s*I\\s*\\.\\s*S\\s*\\.?"
    )
    private val url = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>]+")
    private val markdownLink = Regex("!?\\[([^]]+)]\\([^)]*\\)")
    private val fencedCodeBlock = Regex("(?s)`{3,}.*?`{3,}")
    private val fencedCodeMarker = Regex("(?m)^\\s*`{3,}[^\\n]*$")
    private val markdownListMarker = Regex("(?m)^\\s*(?:[-+]\\s+|\\d+[.)]\\s+)")
    private val markdownMarkers = Regex("[*_`~#>|{}\\[\\]•·]")
    private val dottedShortForm = Regex("(?i)\\b(?:[a-z]\\.){2,}[a-z]?\\.?(?=\\b|\\s|$)")
    private val trailingAbbreviationDot = Regex("(?i)\\b(mr|mrs|ms|dr|prof|sr|jr|vs|etc)\\.(?=\\s|$)")
    private val emojiAndJoiners = Regex(
        "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}\\x{1F3FB}-\\x{1F3FF}\\u200D\\uFE0E\\uFE0F\\u20E3]"
    )
    private val repeatedWhitespace = Regex("\\s+")

    fun clean(text: String): String {
        if (text.isBlank()) return ""
        return text
            .replace(jarvisDottedName, "Jarvis")
            .replace(url, " ")
            .replace(markdownLink) { it.groupValues[1] }
            .replace(fencedCodeBlock, " ")
            .replace(fencedCodeMarker, " ")
            .replace(dottedShortForm) { match -> match.value.filter(Char::isLetter) }
            .replace(trailingAbbreviationDot) { match -> match.value.dropLast(1) }
            .replace(emojiAndJoiners, " ")
            .replace(markdownListMarker, "")
            .replace(markdownMarkers, " ")
            .replace(repeatedWhitespace, " ")
            .trim()
    }
}
