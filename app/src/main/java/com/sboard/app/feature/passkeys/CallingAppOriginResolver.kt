package com.sboard.app.feature.passkeys

import androidx.credentials.provider.CallingAppInfo
import androidx.annotation.RequiresApi
import android.os.Build

/**
 * Resolves only an origin whose calling-app identity and RP association have been verified.
 * Browser-delegated origins require an explicit privileged-caller allowlist; direct Android
 * callers additionally require a matching Digital Asset Links statement on the RP domain.
 */
class CallingAppOriginResolver(
    private val assetLinksVerifier: DigitalAssetLinksVerifier = DigitalAssetLinksVerifier()
) {
    suspend fun resolveVerified(callingAppInfo: CallingAppInfo, rpId: String): String {
        require(callingAppInfo.packageName.isNotBlank()) { "Missing calling package" }
        val normalizedRpId = requireNotNull(WebAuthnOriginPolicy.normalizeRpId(rpId)) {
            "Invalid relying-party ID"
        }

        val delegatedOrigin = try {
            callingAppInfo.getOrigin(PRIVILEGED_CALLER_ALLOWLIST)
        } catch (error: Exception) {
            throw SecurityException("Untrusted delegated origin", error)
        }
        if (!delegatedOrigin.isNullOrBlank()) {
            if (!WebAuthnOriginPolicy.isValidHttpsOriginForRpId(delegatedOrigin, normalizedRpId)) {
                throw SecurityException("Delegated origin does not match the relying-party ID")
            }
            return delegatedOrigin
        }

        val certificate = currentSigningCertificate(callingAppInfo)
        val associated = assetLinksVerifier.verifyAndroidApp(
            rpId = normalizedRpId,
            packageName = callingAppInfo.packageName,
            signingCertificate = certificate
        )
        if (!associated) throw SecurityException("Digital Asset Links association was not verified")
        return AndroidAppOrigin.fromSigningCertificate(certificate)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @Suppress("DEPRECATION")
    private fun currentSigningCertificate(callingAppInfo: CallingAppInfo): ByteArray {
        val signingInfo = try {
            callingAppInfo.signingInfo
        } catch (error: Exception) {
            throw SecurityException("Missing calling-app signing information", error)
        }
        require(!signingInfo.hasMultipleSigners()) {
            "Multiple-signer apps are not supported by this development resolver"
        }
        val signers = signingInfo.apkContentsSigners
        require(signers.size == 1) { "Ambiguous calling-app signing certificates" }
        return signers.single().toByteArray()
    }

    companion object {
        // Browser delegation is intentionally disabled until release package fingerprints are reviewed.
        private const val PRIVILEGED_CALLER_ALLOWLIST = """{"apps": []}"""
    }
}
