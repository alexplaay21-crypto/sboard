package com.sboard.app.feature.otpinbox

/**
 * Finds one-time codes in notification text (RU/EN). Only the code is kept — never the message.
 * A code must sit near a keyword such as "код", "code", "OTP", "PIN", "пароль", "verification".
 */
object OtpExtractor {
    private val keywords = Regex(
        "(?iu)(код|code|otp|pin|пароль|password|passcode|verification|подтвержд|одноразов|token|токен|vérification|código|kod|驗證)"
    )
    // G-123456, 123456, 123-456, 123 456, 12 34 56 (4–8 digits after joining)
    private val candidate = Regex("(?<![\\d\\w])(?:G-)?(\\d{2,4}[ \\-]?\\d{2,4}(?:[ \\-]?\\d{2})?)(?![\\d\\w])")

    fun extract(text: String): String? {
        if (text.isBlank() || !keywords.containsMatchIn(text)) return null
        val keywordPositions = keywords.findAll(text).map { it.range.first }.toList()
        return candidate.findAll(text)
            .map { m -> m to m.groupValues[1].filter(Char::isDigit) }
            .filter { (_, digits) -> digits.length in 4..8 }
            .filterNot { (m, digits) -> looksLikeNoise(text, m.range, digits) }
            .minByOrNull { (m, _) -> keywordPositions.minOf { kotlin.math.abs(it - m.range.first) } }
            ?.second
    }

    private fun looksLikeNoise(text: String, range: IntRange, digits: String): Boolean {
        val before = text.substring(maxOf(0, range.first - 3), range.first)
        val after = text.substring(range.last + 1, minOf(text.length, range.last + 4))
        // Money, phone numbers, years, times and dates are not codes.
        if (before.contains('+') || before.contains('$') || after.trim().startsWith("₽") || after.trim().startsWith("руб") ||
            after.startsWith(":") || before.endsWith(":") || after.startsWith(".") && after.length > 1 && after[1].isDigit()) return true
        if (digits.length == 4 && digits.toInt() in 1950..2100 && !text.contains("код", true) && !text.contains("code", true)) return true
        return false
    }
}
