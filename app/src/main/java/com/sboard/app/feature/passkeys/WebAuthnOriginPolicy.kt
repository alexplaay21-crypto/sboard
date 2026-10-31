package com.sboard.app.feature.passkeys

import java.net.IDN
import java.net.URI
import java.util.Locale
import java.util.Base64

/**
 * Conservative WebAuthn origin syntax and RP-ID checks shared by the engines.
 * HTTPS origins are checked against the RP ID. Android app-origin syntax is validated here,
 * but its relationship to the RP ID must be established by Digital Asset Links in the trusted
 * platform integration. This is not a Public Suffix List implementation.
 */
internal object WebAuthnOriginPolicy {
    fun normalizeRpId(value: String): String? {
        return try {
            val raw = value.trim().trimEnd('.').lowercase(Locale.ROOT)
            if (raw.isBlank() || raw.length > 253 || raw.contains('/') || raw.contains(':') || raw.contains('@')) {
                null
            } else {
                val ascii = IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
                val labels = ascii.split('.')
                if (ascii.length > 253 || labels.size < 2 || labels.all { it.all(Char::isDigit) } || labels.any { label ->
                        label.isBlank() || label.length > 63 || label.startsWith('-') || label.endsWith('-')
                    }) null else ascii
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Validates the origin syntax accepted by the local WebAuthn engines. For HTTPS origins this
     * checks the host/RP-ID relationship. Android app origins carry no hostname; their association
     * with [rpId] MUST already have been verified by Digital Asset Links by the trusted caller.
     */
    fun isValidOriginForRpId(origin: String, rpId: String): Boolean {
        if (isValidHttpsOriginForRpId(origin, rpId)) return true
        if (normalizeRpId(rpId) == null) return false
        return isValidAndroidAppOrigin(origin)
    }

    fun isValidAndroidAppOrigin(origin: String): Boolean {
        val prefix = "android:apk-key-hash:"
        if (!origin.startsWith(prefix)) return false
        val encoded = origin.removePrefix(prefix)
        if (encoded.isBlank() || encoded.contains('=') || !encoded.matches(Regex("[A-Za-z0-9_-]+"))) return false
        return try {
            val decoded = Base64.getUrlDecoder().decode(encoded)
            decoded.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == encoded
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun isValidHttpsOriginForRpId(origin: String, rpId: String): Boolean {
        return try {
            val normalizedRp = normalizeRpId(rpId) ?: return false
            val uri = URI(origin)
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null ||
                !(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") || uri.port != -1) return false
            val host = uri.host ?: return false
            val normalizedHost = IDN.toASCII(host.trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
                .lowercase(Locale.ROOT)
            normalizedHost == normalizedRp || normalizedHost.endsWith(".$normalizedRp")
        } catch (_: Exception) {
            false
        }
    }
}
