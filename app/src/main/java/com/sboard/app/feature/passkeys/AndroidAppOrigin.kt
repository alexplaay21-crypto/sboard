package com.sboard.app.feature.passkeys

import java.security.MessageDigest
import java.util.Base64

/** Pure formatter for the WebAuthn Android application-origin form. */
internal object AndroidAppOrigin {
    fun fromSigningCertificate(certificateBytes: ByteArray): String {
        require(certificateBytes.isNotEmpty()) { "Signing certificate is empty" }
        val digest = MessageDigest.getInstance("SHA-256").digest(certificateBytes)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return "android:apk-key-hash:$encoded"
    }
}
