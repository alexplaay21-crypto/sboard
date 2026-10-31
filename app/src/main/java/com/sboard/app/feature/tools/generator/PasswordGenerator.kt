package com.sboard.app.feature.tools.generator

import java.security.SecureRandom

/** Cryptographically secure password generation with at least one character
 * from every enabled character group. */
object PasswordGenerator {
    private val secureRandom = SecureRandom()

    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{}?"

    fun generate(
        length: Int,
        uppercase: Boolean,
        lowercase: Boolean,
        numbers: Boolean,
        symbols: Boolean
    ): String {
        require(length in 8..128) { "Password length must be between 8 and 128" }

        val groups = buildList {
            if (uppercase) add(UPPER)
            if (lowercase) add(LOWER)
            if (numbers) add(DIGITS)
            if (symbols) add(SYMBOLS)
        }
        require(groups.isNotEmpty()) { "Select at least one character group" }
        require(length >= groups.size) { "Password is too short for selected groups" }

        val alphabet = groups.joinToString(separator = "")
        val result = ArrayList<Char>(length)

        // Guarantee at least one character from every selected group.
        groups.forEach { group -> result += group[secureRandom.nextInt(group.length)] }

        while (result.size < length) {
            result += alphabet[secureRandom.nextInt(alphabet.length)]
        }

        // Fisher–Yates shuffle using SecureRandom.
        for (i in result.lastIndex downTo 1) {
            val j = secureRandom.nextInt(i + 1)
            val temp = result[i]
            result[i] = result[j]
            result[j] = temp
        }

        return result.joinToString(separator = "")
    }
}
