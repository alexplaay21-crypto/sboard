package com.sboard.app.feature.otp

import org.junit.Assert.assertEquals
import org.junit.Test

class OtpAuthUriTest {
    @Test fun parsesStandardUri() {
        val data = OtpAuthUri.parse("otpauth://totp/ACME%20Co:john@example.com?secret=JBSWY3DPEHPK3PXP&issuer=ACME%20Co&algorithm=SHA1&digits=6&period=30")
        assertEquals("ACME Co", data.issuer)
        assertEquals("john@example.com", data.account)
        assertEquals("JBSWY3DPEHPK3PXP", data.secret)
        assertEquals(6, data.digits)
    }

    @Test fun issuerFromLabelWhenParameterMissing() {
        val data = OtpAuthUri.parse("otpauth://totp/GitHub:me?secret=JBSWY3DPEHPK3PXP")
        assertEquals("GitHub", data.issuer)
        assertEquals("me", data.account)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHotp() { OtpAuthUri.parse("otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP&counter=1") }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidSecret() { OtpAuthUri.parse("otpauth://totp/x?secret=not-base32!") }
}
