package com.sboard.app.feature.passkeys

import android.content.Context
import com.sboard.app.core.crypto.B64
import com.sboard.app.data.ItemType
import com.sboard.app.data.PasskeyData
import com.sboard.app.data.VaultItem
import com.sboard.app.data.VaultItemPayload
import com.sboard.app.data.VaultRepository
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec

/** Passkeys are regular encrypted Vault items, so they sync and restore with the Vault. */
class PasskeyStore(context: Context) {
    private val repo = VaultRepository.get(context)

    suspend fun forRelyingParty(rpId: String, allowedCredentialIdsHex: Set<String>? = null): List<VaultItem> {
        val normalized = WebAuthnOriginPolicy.normalizeRpId(rpId) ?: return emptyList()
        return repo.snapshot().filter { item ->
            val pk = item.payload.passkey ?: return@filter false
            item.type == ItemType.PASSKEY && pk.rpId == normalized &&
                PasskeyDiscoveryPolicy.isAllowed(B64.fromUrl(pk.credentialId), allowedCredentialIdsHex)
        }.sortedByDescending { it.payload.passkey?.lastUsedAt ?: 0 }
    }

    suspend fun hasExcluded(rpId: String, excluded: List<ByteArray>): Boolean {
        if (excluded.isEmpty()) return false
        val ids = excluded.map { B64.url(it) }.toSet()
        return repo.snapshot().any { it.payload.passkey?.let { pk -> pk.rpId == rpId && pk.credentialId in ids } == true }
    }

    suspend fun saveRegistration(registration: WebAuthnRegistrationEngine.Registration): String {
        val data = PasskeyData(
            rpId = registration.rpId, rpName = registration.rpName, userName = registration.userName,
            userDisplayName = registration.displayName, userHandle = B64.url(registration.userHandle),
            credentialId = B64.url(registration.credentialId), privateKeyPkcs8 = B64.url(registration.privateKeyPkcs8),
            publicKeyCose = B64.url(registration.publicKeyCose), signCount = 0, lastUsedAt = System.currentTimeMillis(),
        )
        registration.privateKeyPkcs8.fill(0)
        return repo.save(null, VaultItemPayload(type = ItemType.PASSKEY, title = registration.rpName.ifBlank { registration.rpId }, passkey = data))
    }

    /** Signs a WebAuthn assertion. Call only after user verification. */
    suspend fun assert(item: VaultItem, requestJson: String, origin: String, clientDataHash: ByteArray?): WebAuthnAssertionEngine.Assertion {
        val pk = requireNotNull(item.payload.passkey) { "Not a passkey" }
        val keyBytes = B64.fromUrl(pk.privateKeyPkcs8)
        val privateKey = try {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        } finally { keyBytes.fill(0) }
        val assertion = WebAuthnAssertionEngine.create(
            requestJson = requestJson, expectedRpId = pk.rpId, origin = origin,
            credentialId = B64.fromUrl(pk.credentialId), userHandle = B64.fromUrl(pk.userHandle),
            privateKey = privateKey, currentSignCount = 0, userVerified = true, clientDataHash = clientDataHash,
        )
        repo.save(item.id, item.payload.copy(passkey = pk.copy(lastUsedAt = System.currentTimeMillis())), snapshot = false)
        return assertion
    }
}
