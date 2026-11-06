package com.sboard.app.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sboard.app.core.security.VaultLockedException
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.data.ItemType
import com.sboard.app.data.LoginData
import com.sboard.app.data.VaultDatabase
import com.sboard.app.data.VaultItemPayload
import com.sboard.app.data.VaultRepository
import com.sboard.app.feature.vault.VaultKeyManager
import com.sboard.app.feature.vault.WrongSecretException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class VaultFlowTest {
    private lateinit var context: Context
    @Before fun setUp() { context = ApplicationProvider.getApplicationContext(); simulateNewDevice(context) }
    @After fun tearDown() = simulateNewDevice(context)

    @Test fun createLockUnlockEditHistoryAndRestore() = runBlocking {
        val keys = VaultKeyManager(context)
        keys.createVault("my master passphrase".toCharArray())
        val repo = VaultRepository.get(context)
        val id = repo.save(null, VaultItemPayload(ItemType.LOGIN, "Shop", login = LoginData("u", "one-1-One")))
        repo.save(id, VaultItemPayload(ItemType.LOGIN, "Shop", login = LoginData("u", "two-2-Two")))
        repo.setFavorite(id, true)
        assertEquals(1, repo.history(id).size) // favorite toggle does not create history

        // Nothing readable while locked
        VaultSessionProvider.session.lock()
        try { repo.get(id); throw AssertionError("must be locked") } catch (_: VaultLockedException) {}
        val raw = VaultDatabase.get(context).items().getAll().single()
        assertFalse(raw.ciphertext.toString(Charsets.ISO_8859_1).contains("two-2-Two"))

        try { keys.unlockWithPassword("wrong".toCharArray()); throw AssertionError() } catch (_: WrongSecretException) {}
        keys.unlockWithPassword("my master passphrase".toCharArray())
        val item = repo.get(id)!!
        assertEquals("two-2-Two", item.payload.login!!.password)
        assertTrue(item.favorite)

        repo.delete(id)
        assertEquals(null, repo.get(id))
        val deleted = repo.recentlyDeleted().single()
        repo.restoreFromHistory(deleted.second.historyId)
        assertEquals("two-2-Two", repo.get(id)!!.payload.login!!.password)
        assertEquals(1, VaultDatabase.get(context).items().pendingCount())
    }

    @Test fun recoveryKeyUnlocksAndRejectsWrongKeys() = runBlocking {
        val keys = VaultKeyManager(context)
        keys.createVault("my master passphrase".toCharArray())
        val code = keys.createRecoveryKey()
        VaultSessionProvider.session.lock()
        try { keys.unlockWithRecoveryKey("A".repeat(43)); throw AssertionError() } catch (_: WrongSecretException) {}
        keys.unlockWithRecoveryKey(VaultKeyManager.formatRecoveryKey(code))
        assertTrue(VaultSessionProvider.session.isUnlocked)
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortMasterPasswordIsRejected() = runBlocking { VaultKeyManager(context).createVault("short".toCharArray()) }
}
