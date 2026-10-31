package com.sboard.app.feature.passkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Base64

class AndroidAppOriginTest {
    @Test fun createsAndroidOriginFromSha256CertificateDigest() {
        val certificate = byteArrayOf(1, 2, 3, 4, 5)
        val expectedDigest = java.security.MessageDigest.getInstance("SHA-256").digest(certificate)
        val expected = "android:apk-key-hash:" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(expectedDigest)
        assertEquals(expected, AndroidAppOrigin.fromSigningCertificate(certificate))
    }

    @Test fun differentCertificatesProduceDifferentOrigins() {
        assertNotEquals(
            AndroidAppOrigin.fromSigningCertificate(byteArrayOf(1, 2, 3)),
            AndroidAppOrigin.fromSigningCertificate(byteArrayOf(1, 2, 4))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyCertificate() {
        AndroidAppOrigin.fromSigningCertificate(byteArrayOf())
    }
}
