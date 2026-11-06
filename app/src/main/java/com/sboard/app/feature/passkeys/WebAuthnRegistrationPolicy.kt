package com.sboard.app.feature.passkeys

import org.json.JSONObject
import java.util.Base64

/** Strict allow-list for the subset of WebAuthn creation requests implemented by sBoard. */
object WebAuthnRegistrationPolicy {
    data class ValidatedRequest(
        val rpId: String,
        val rpName: String,
        val userId: ByteArray,
        val userName: String,
        val displayName: String,
        val challenge: String,
        val excludedCredentialIds: List<ByteArray>,
        /** True when the RP asked for the credProps extension (we report rk = true). */
        val credPropsRequested: Boolean = false,
    )

    fun validate(requestJson: String, expectedRpId: String, origin: String): ValidatedRequest {
        require(requestJson.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Creation request is too large" }
        val request = JSONObject(requestJson)
        val rp = request.optJSONObject("rp") ?: throw IllegalArgumentException("Missing RP entity")
        val user = request.optJSONObject("user") ?: throw IllegalArgumentException("Missing user entity")
        val rpId = requireNotNull(WebAuthnOriginPolicy.normalizeRpId(rp.optString("id"))) { "Invalid RP ID" }
        val expected = requireNotNull(WebAuthnOriginPolicy.normalizeRpId(expectedRpId)) { "Invalid expected RP ID" }
        require(rpId == expected) { "RP ID mismatch" }
        require(WebAuthnOriginPolicy.isValidOriginForRpId(origin, rpId)) { "Origin does not match RP ID" }

        val challenge = request.optString("challenge")
        require(challenge.matches(Regex("^[A-Za-z0-9_-]{22,1366}$"))) { "Invalid challenge encoding or length" }
        val challengeBytes = runCatching { Base64.getUrlDecoder().decode(challenge) }.getOrElse {
            throw IllegalArgumentException("Invalid challenge")
        }
        require(challengeBytes.size in 16..1024) { "Challenge must contain 16 to 1024 bytes" }

        val userIdEncoded = user.optString("id")
        require(userIdEncoded.matches(Regex("^[A-Za-z0-9_-]{2,86}$"))) { "Invalid user ID encoding" }
        val userId = Base64.getUrlDecoder().decode(userIdEncoded)
        require(userId.isNotEmpty() && userId.size <= 64) { "User ID must contain 1 to 64 bytes" }

        val rpName = rp.optString("name").trim()
        val userName = user.optString("name").trim()
        val displayName = user.optString("displayName", userName).trim().ifBlank { userName }
        require(rpName.length <= 256) { "Invalid RP name" }
        require(userName.isNotBlank() && userName.length <= 256) { "Invalid user name" }
        require(displayName.length <= 256) { "Display name is too long" }

        val params = request.optJSONArray("pubKeyCredParams")
            ?: throw IllegalArgumentException("Missing pubKeyCredParams")
        val supportsEs256 = (0 until params.length()).any { index ->
            val item = params.optJSONObject(index)
            item != null && item.optString("type") == "public-key" && item.optInt("alg", Int.MIN_VALUE) == -7
        }
        require(supportsEs256) { "ES256 (-7) is required" }

        val selection = request.optJSONObject("authenticatorSelection")
        if (selection != null) {
            val attachment = selection.optString("authenticatorAttachment")
            require(attachment.isBlank() || attachment == "platform") { "Only platform credentials are supported" }
            val residentKey = selection.optString("residentKey")
            require(residentKey.isBlank() || residentKey in setOf("discouraged", "preferred", "required")) {
                "Invalid resident-key requirement"
            }
            val requireResident = selection.optBoolean("requireResidentKey", false)
            require(!requireResident || residentKey.isBlank() || residentKey == "required") {
                "Conflicting resident-key requirements"
            }
            require(selection.optString("userVerification").let { it.isBlank() || it in setOf("required", "preferred") }) {
                "Unsupported user-verification requirement"
            }
        }
        // Attestation conveyance is a preference; sBoard always answers with "none" (no device attestation).
        require(request.optString("attestation", "none") in setOf("none", "indirect", "direct", "enterprise")) { "Invalid attestation preference" }
        // Unknown client extensions are ignored as permitted by WebAuthn; credProps is answered.
        val credProps = request.optJSONObject("extensions")?.optBoolean("credProps", false) == true

        val exclusions = ArrayList<ByteArray>()
        val exclude = request.optJSONArray("excludeCredentials")
        if (exclude != null) {
            require(exclude.length() <= 128) { "Too many excluded credentials" }
            for (i in 0 until exclude.length()) {
                val item = exclude.optJSONObject(i) ?: throw IllegalArgumentException("Invalid excludeCredentials entry")
                require(item.optString("type") == "public-key") { "Invalid excluded credential type" }
                val encoded = item.optString("id")
                require(encoded.matches(Regex("^[A-Za-z0-9_-]{1,2048}$"))) { "Invalid excluded credential ID" }
                exclusions += Base64.getUrlDecoder().decode(encoded).also {
                    require(it.isNotEmpty() && it.size <= 1024) { "Invalid excluded credential ID length" }
                }
            }
        }
        return ValidatedRequest(rpId, rpName.ifBlank { rpId }, userId, userName, displayName, challenge, exclusions, credProps)
    }
}
