package com.sboard.app.feature.passkeys

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail

class PasskeyAssertionPipelineTest {
    @Test
    fun createsAssertionOnlyForAllowedCredentialAndPersistsCounter() = runBlocking {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val credentialId = byteArrayOf(1, 2, 3, 4)
        val userHandle = byteArrayOf(8, 9)
        val store = FakeStore(record(credentialId, userHandle, pair.private.encoded))
        val allowId = Base64.getUrlEncoder().withoutPadding().encodeToString(credentialId)
        val request = JSONObject()
            .put("rpId", "example.com")
            .put("challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(7, 6, 5)))
            .put("allowCredentials", org.json.JSONArray().put(JSONObject().put("type", "public-key").put("id", allowId)))
            .toString()

        val assertion = PasskeyAssertionPipeline(store).create(
            "record-1", request, "https://login.example.com",
            PasskeyAuthorization.issue("record-1")
        )
        assertEquals(credentialId.toList(), assertion.credentialId.toList())
        assertEquals(0L, store.persistedCounter) // counterless credential remains at zero
        val signed = assertion.authenticatorData + java.security.MessageDigest.getInstance("SHA-256").digest(assertion.clientDataJson)
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public); update(signed); verify(assertion.signature)
        })
    }

    @Test
    fun rejectsCredentialNotInAllowListBeforeOpeningPrivateKey() = runBlocking {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val store = FakeStore(record(byteArrayOf(1), byteArrayOf(2), pair.private.encoded))
        val request = JSONObject()
            .put("rpId", "example.com")
            .put("challenge", "AQID")
            .put("allowCredentials", org.json.JSONArray().put(JSONObject().put("type", "public-key").put("id", "BAUG")))
            .toString()
        try {
            PasskeyAssertionPipeline(store).create(
                "record-1", request, "https://example.com", PasskeyAuthorization.issue("record-1")
            )
            fail("Expected allowCredentials rejection")
        } catch (expected: IllegalArgumentException) {
            assertEquals(false, store.privateKeyOpened)
        }
    }

    private fun record(id: ByteArray, userHandle: ByteArray, privateKey: ByteArray) = PasskeyRecordEntity(
        id = "record-1", rpId = "example.com", rpName = "Example", userName = "user",
        displayName = "User", userHandle = userHandle, credentialId = id, publicKeyCose = byteArrayOf(1),
        encryptedPrivateKey = byteArrayOf(1), iv = byteArrayOf(1), signCount = 0,
        createdAt = 1, lastUsedAt = 0
    ).also { privateKeys["record-1"] = privateKey }

    private val privateKeys = mutableMapOf<String, ByteArray>()

    private inner class FakeStore(private val metadata: PasskeyRecordEntity) : PasskeyAssertionStore {
        var privateKeyOpened = false
        var persistedCounter = -1L
        override suspend fun getMetadata(recordId: String) = metadata.takeIf { it.id == recordId }
        override suspend fun openPrivateKey(recordId: String, authorization: PasskeyAuthorization): ByteArray {
            assertTrue(authorization.consumeFor(recordId))
            privateKeyOpened = true
            return privateKeys.getValue(recordId).copyOf()
        }
        override suspend fun markUsed(recordId: String, newSignCount: Long) { persistedCounter = newSignCount }
    }
}
