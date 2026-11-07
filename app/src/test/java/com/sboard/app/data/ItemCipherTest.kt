package com.sboard.app.data

import com.sboard.app.core.crypto.randomBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.AEADBadTagException

class ItemCipherTest {
    private val key = randomBytes(32)
    private val payload = VaultItemPayload(type = ItemType.LOGIN, title = "GitHub",
        login = LoginData(username = "alex", password = "p@ss", urls = listOf("github.com")))

    @Test fun sealAndOpen() {
        val e = ItemCipher.seal(key, "id1", 1, 2, false, "dev", payload, null)
        assertEquals(payload, ItemCipher.open(key, e))
        assertEquals(ItemCipher.contentHash(e.iv, e.ciphertext), e.contentHash)
    }

    @Test fun tombstoneIsAuthenticated() {
        val e = ItemCipher.seal(key, "id1", 1, 5, true, "dev", null, null)
        assertNull(ItemCipher.open(key, e))
    }

    @Test(expected = AEADBadTagException::class)
    fun metadataIsBoundToCiphertext() {
        val e = ItemCipher.seal(key, "id1", 1, 2, false, "dev", payload, null)
        ItemCipher.open(key, e.copy(updatedAt = 3))
    }

    @Test(expected = AEADBadTagException::class)
    fun cannotTurnItemIntoTombstone() {
        val e = ItemCipher.seal(key, "id1", 1, 2, false, "dev", payload, null)
        ItemCipher.open(key, e.copy(deleted = true))
    }

    @Test(expected = AEADBadTagException::class)
    fun cannotMoveCiphertextToAnotherId() {
        val e = ItemCipher.seal(key, "id1", 1, 2, false, "dev", payload, null)
        ItemCipher.open(key, e.copy(id = "id2"))
    }

    @Test fun historyUsesSeparateContext() {
        val sealed = ItemCipher.sealHistory(key, "id1", payload)
        assertEquals(payload, ItemCipher.openHistory(key, "id1", sealed.iv, sealed.ciphertext))
    }

    @Test fun payloadJsonIgnoresUnknownFields() {
        val json = """{"type":"note","title":"T","notes":"n","futureField":42}"""
        val decoded = VaultItemPayload.decode(json.toByteArray())
        assertEquals(ItemType.NOTE, decoded.type)
        assertEquals("n", decoded.notes)
    }
}
