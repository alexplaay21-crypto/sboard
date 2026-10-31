package com.sboard.app.feature.passkeys

import androidx.credentials.provider.CallingAppInfo

/**
 * Narrow boundary for an origin that has been authenticated against the current calling app.
 * Implementations must not read origin values from request JSON, intent extras, or user input.
 */
fun interface TrustedOriginProvider {
    suspend fun resolve(rpId: String): String
}

/** Adapter that binds Android Credential Manager's calling-app identity to the RP verifier. */
class CallingAppTrustedOriginProvider(
    private val callingAppInfo: CallingAppInfo,
    private val resolver: CallingAppOriginResolver = CallingAppOriginResolver()
) : TrustedOriginProvider {
    override suspend fun resolve(rpId: String): String =
        resolver.resolveVerified(callingAppInfo, rpId)
}

/**
 * Single entry point for a selected get-assertion operation. It resolves the trusted origin first,
 * validates the origin/RP relationship again at this boundary, then delegates to the pipeline.
 * The pipeline consumes the one-shot biometric authorization before decrypting the private key.
 */
class TrustedPasskeyAssertionCoordinator(
    private val originProvider: TrustedOriginProvider,
    private val assertionPipeline: PasskeyAssertionPipeline
) {
    suspend fun create(
        recordId: String,
        requestJson: String,
        rpId: String,
        authorization: PasskeyAuthorization
    ): WebAuthnAssertionEngine.Assertion {
        require(recordId.isNotBlank()) { "Missing selected credential" }
        val normalizedRpId = requireNotNull(WebAuthnOriginPolicy.normalizeRpId(rpId)) {
            "Invalid relying-party ID"
        }
        val trustedOrigin = originProvider.resolve(normalizedRpId)
        require(WebAuthnOriginPolicy.isValidOriginForRpId(trustedOrigin, normalizedRpId)) {
            "Trusted origin is not valid for the relying-party ID"
        }
        return assertionPipeline.create(
            recordId = recordId,
            requestJson = requestJson,
            trustedOrigin = trustedOrigin,
            authorization = authorization
        )
    }
}
