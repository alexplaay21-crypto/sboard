package com.sboard.app.feature.passkeys

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAuthnOriginPolicyTest {
    @Test fun acceptsRpHostAndSubdomains() {
        assertTrue(WebAuthnOriginPolicy.isValidHttpsOriginForRpId("https://example.com", "example.com"))
        assertTrue(WebAuthnOriginPolicy.isValidHttpsOriginForRpId("https://login.example.com/", "example.com"))
    }

    @Test fun rejectsLookalikeAndUnrelatedHosts() {
        assertFalse(WebAuthnOriginPolicy.isValidHttpsOriginForRpId("https://example.com.attacker.test", "example.com"))
        assertFalse(WebAuthnOriginPolicy.isValidHttpsOriginForRpId("https://attacker.test", "example.com"))
    }

    @Test fun rejectsNonHttpsAndNonOriginUrls() {
        assertFalse(WebAuthnOriginPolicy.isValidHttpsOriginForRpId("http://example.com", "example.com"))
        assertFalse(WebAuthnOriginPolicy.isValidHttpsOriginForRpId("https://user@example.com", "example.com"))
        assertFalse(WebAuthnOriginPolicy.isValidHttpsOriginForRpId("https://example.com/login", "example.com"))
        assertFalse(WebAuthnOriginPolicy.isValidHttpsOriginForRpId("https://example.com:8443", "example.com"))
    }

    @Test fun acceptsWellFormedAndroidAppOriginAfterExternalDalVerification() {
        val hash = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val origin = "android:apk-key-hash:$hash"
        assertTrue(WebAuthnOriginPolicy.isValidAndroidAppOrigin(origin))
        assertTrue(WebAuthnOriginPolicy.isValidOriginForRpId(origin, "example.com"))
    }

    @Test fun rejectsMalformedAndroidAppOrigins() {
        assertFalse(WebAuthnOriginPolicy.isValidAndroidAppOrigin("android:apk-key-hash:short"))
        assertFalse(WebAuthnOriginPolicy.isValidAndroidAppOrigin("android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        assertFalse(WebAuthnOriginPolicy.isValidAndroidAppOrigin("android:apk-key-hash:../bad"))
    }

    @Test fun rejectsSingleLabelAndMalformedRpIds() {
        assertNull(WebAuthnOriginPolicy.normalizeRpId("com"))
        assertNull(WebAuthnOriginPolicy.normalizeRpId("bad..example.com"))
        assertNull(WebAuthnOriginPolicy.normalizeRpId("https://example.com"))
        assertNull(WebAuthnOriginPolicy.normalizeRpId("127.0.0.1"))
    }
}
