package com.sboard.app.feature.otp

import com.sboard.app.data.TotpData
import java.net.URI
import java.net.URLDecoder

/** Parser for Key URI Format (`otpauth://totp/Issuer:account?secret=...`) used by QR codes. */
object OtpAuthUri {
    fun parse(value: String): TotpData {
        val uri = URI(value.trim())
        require(uri.scheme.equals("otpauth", ignoreCase = true)) { "Not an otpauth URI" }
        require(uri.host.equals("totp", ignoreCase = true)) { "Only TOTP codes are supported" }
        val label = URLDecoder.decode(uri.rawPath.orEmpty().removePrefix("/"), "UTF-8")
        val params = uri.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=').lowercase() to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val secret = params["secret"]?.replace(" ", "")?.uppercase() ?: throw IllegalArgumentException("Missing secret")
        val labelIssuer = label.substringBefore(':', "").trim()
        val account = if (label.contains(':')) label.substringAfter(':').trim() else label.trim()
        val data = TotpData(
            secret = secret,
            issuer = params["issuer"]?.trim().takeUnless { it.isNullOrBlank() } ?: labelIssuer,
            account = account,
            algorithm = (params["algorithm"] ?: "SHA1").uppercase(),
            digits = params["digits"]?.toIntOrNull() ?: 6,
            period = params["period"]?.toIntOrNull() ?: 30,
        )
        validate(data)
        return data
    }

    fun validate(data: TotpData) {
        require(data.digits in 6..8) { "Unsupported digits" }
        require(data.period in 15..120) { "Unsupported period" }
        require(data.algorithm in setOf("SHA1", "SHA256", "SHA512")) { "Unsupported algorithm" }
        TotpGenerator.decodeBase32(data.secret).fill(0)
    }
}

object TotpCodes {
    data class Code(val value: String, val secondsLeft: Int, val period: Int)

    fun current(data: TotpData, now: Long = System.currentTimeMillis()): Code? = runCatching {
        val value = TotpGenerator.generate(data.secret, now, data.digits, data.period, data.algorithm)
        val left = data.period - ((now / 1000) % data.period).toInt()
        Code(value, left, data.period)
    }.getOrNull()

    fun format(code: String): String = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code
}
