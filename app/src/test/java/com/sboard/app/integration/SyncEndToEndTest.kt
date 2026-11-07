package com.sboard.app.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.data.CardData
import com.sboard.app.data.ItemType
import com.sboard.app.data.LoginData
import com.sboard.app.data.VaultItemPayload
import com.sboard.app.data.VaultRepository
import com.sboard.app.feature.sync.BackupFormat
import com.sboard.app.feature.sync.DriveClient
import com.sboard.app.feature.sync.SyncEngine
import com.sboard.app.feature.vault.VaultKeyManager
import com.sboard.app.feature.vault.WrongSecretException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two-device scenario against an in-memory Google Drive: device A creates the Vault and syncs,
 * device B restores it with the master password, edits/deletes/adds, and device C sees B's changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class SyncEndToEndTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val fake = FakeDrive()
    private val token = "test-token"
    private val password = "correct horse battery staple"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        simulateNewDevice(context)
        server = MockWebServer().apply { dispatcher = fake; start() }
    }

    @After fun tearDown() {
        server.shutdown()
        simulateNewDevice(context)
    }

    private fun engine() = SyncEngine(context, DriveClient(OkHttpClient(),
        api = server.url("/drive/v3").toString().trimEnd('/'),
        upload = server.url("/upload/drive/v3").toString().trimEnd('/')))

    private fun login(title: String, user: String, pass: String) = VaultItemPayload(ItemType.LOGIN, title,
        login = LoginData(username = user, password = pass, urls = listOf("github.com")))

    @Test fun twoDeviceRoundTrip() = runBlocking {
        // Device A
        VaultKeyManager(context).createVault(password.toCharArray())
        val repo = VaultRepository.get(context)
        val githubId = repo.save(null, login("GitHub", "alex", "first-Secret-1"))
        val noteId = repo.save(null, VaultItemPayload(ItemType.NOTE, "Wi-Fi", notes = "router: hunter2"))
        val first = engine().sync(token)
        assertTrue(first.toString(), first is SyncEngine.Outcome.Success)
        assertEquals(1, fake.files.size)
        val cloudBytes = fake.files.values.single().bytes
        val plain = cloudBytes.toString(Charsets.ISO_8859_1)
        assertFalse("cloud file must not contain plaintext", plain.contains("first-Secret-1") || plain.contains("hunter2") || plain.contains("GitHub"))
        BackupFormat.parse(cloudBytes) // integrity verifies without a key

        // Second sync with no changes must not create a new file nor upload again
        val versionBefore = fake.files.values.single().version
        assertTrue(engine().sync(token) is SyncEngine.Outcome.Success)
        assertEquals(versionBefore, fake.files.values.single().version)

        // Device B: fresh install, restore with the master password
        simulateNewDevice(context)
        val cloud = engine().inspectCloud(token)!!
        try { engine().restore(cloud, "wrong password"); throw AssertionError("must fail") } catch (_: WrongSecretException) {}
        assertEquals(2, engine().restore(cloud, password))
        assertTrue(VaultSessionProvider.session.isUnlocked)
        val repoB = VaultRepository.get(context)
        assertEquals("first-Secret-1", repoB.get(githubId)!!.payload.login!!.password)

        // Device B edits, deletes and adds
        repoB.save(githubId, login("GitHub", "alex", "second-Secret-2"))
        repoB.delete(noteId)
        val cardId = repoB.save(null, VaultItemPayload(ItemType.CARD, "Visa", card = CardData(number = "4111111111111111", expMonth = "12", expYear = "2030")))
        val second = engine().sync(token)
        assertTrue(second.toString(), second is SyncEngine.Outcome.Success)
        assertEquals("the backup file is updated in place", 1, fake.files.size)

        // Device C sees B's changes
        simulateNewDevice(context)
        assertEquals(2, engine().restore(engine().inspectCloud(token)!!, password))
        val repoC = VaultRepository.get(context)
        assertEquals("second-Secret-2", repoC.get(githubId)!!.payload.login!!.password)
        assertNull(repoC.get(noteId))
        assertEquals("4111111111111111", repoC.get(cardId)!!.payload.card!!.number)
    }

    @Test fun masterPasswordChangePropagates() = runBlocking {
        VaultKeyManager(context).createVault(password.toCharArray())
        VaultRepository.get(context).save(null, login("Mail", "me", "pw-123456"))
        assertTrue(engine().sync(token) is SyncEngine.Outcome.Success)
        VaultKeyManager(context).changeMasterPassword(password.toCharArray(), "a brand new passphrase".toCharArray())
        assertTrue(engine().sync(token) is SyncEngine.Outcome.Success)

        simulateNewDevice(context)
        val cloud = engine().inspectCloud(token)!!
        try { engine().restore(cloud, password); throw AssertionError("old password must not work") } catch (_: WrongSecretException) {}
        assertEquals(1, engine().restore(cloud, "a brand new passphrase"))
        // The restored device unlocks with the new password after locking
        VaultSessionProvider.session.lock()
        VaultKeyManager(context).unlockWithPassword("a brand new passphrase".toCharArray())
        assertTrue(VaultSessionProvider.session.isUnlocked)
    }

    @Test fun recoveryKeyRestoresOnNewDevice() = runBlocking {
        VaultKeyManager(context).createVault(password.toCharArray())
        VaultRepository.get(context).save(null, login("Bank", "me", "pin-998877"))
        val code = VaultKeyManager(context).createRecoveryKey()
        assertTrue(engine().sync(token) is SyncEngine.Outcome.Success)
        simulateNewDevice(context)
        assertEquals(1, engine().restore(engine().inspectCloud(token)!!, VaultKeyManager.formatRecoveryKey(code)))
    }

    @Test fun googleRecoveryOpensVaultWithoutPasswordOrRecoveryKey() = runBlocking {
        VaultKeyManager(context).createVault(password.toCharArray())
        VaultRepository.get(context).save(null, login("Shop", "me", "shop-pass-42"))
        assertTrue(engine().sync(token) is SyncEngine.Outcome.Success)
        assertFalse(engine().canRecoverWithGoogle(engine().inspectCloud(token)!!))
        VaultKeyManager(context).enableGoogleRecovery()
        assertTrue(engine().sync(token) is SyncEngine.Outcome.Success)
        assertTrue(fake.files.values.single().properties[VaultKeyManager.DRIVE_RECOVERY_PROPERTY]!!.length >= 43)

        // New phone, master password forgotten, no Recovery Key: sign in to Google and recover.
        simulateNewDevice(context)
        val cloud = engine().inspectCloud(token)!!
        assertTrue(engine().canRecoverWithGoogle(cloud))
        assertEquals(1, engine().restoreWithGoogle(cloud))
        assertTrue(VaultSessionProvider.session.isUnlocked)
        // A new master password is set without the old one and works after locking.
        VaultKeyManager(context).resetMasterPassword("my new memorable phrase".toCharArray())
        assertTrue(engine().sync(token) is SyncEngine.Outcome.Success)
        VaultSessionProvider.session.lock()
        VaultKeyManager(context).unlockWithPassword("my new memorable phrase".toCharArray())
        assertEquals("shop-pass-42", VaultRepository.get(context).snapshot().single().payload.login!!.password)
        // Recovery survives the restore (the secret is re-uploaded with the next sync).
        assertTrue(fake.files.values.single().properties[VaultKeyManager.DRIVE_RECOVERY_PROPERTY] != null)

        // Turning it off removes the secret from Drive.
        VaultKeyManager(context).disableGoogleRecovery()
        assertTrue(engine().sync(token) is SyncEngine.Outcome.Success)
        assertNull(fake.files.values.single().properties[VaultKeyManager.DRIVE_RECOVERY_PROPERTY])
    }

    @Test fun differentVaultIsDetectedAndCanBeJoined() = runBlocking {
        VaultKeyManager(context).createVault(password.toCharArray())
        VaultRepository.get(context).save(null, login("Cloud", "a", "cloud-pass-1"))
        assertTrue(engine().sync(token) is SyncEngine.Outcome.Success)

        simulateNewDevice(context)
        VaultKeyManager(context).createVault("another local passphrase".toCharArray())
        VaultRepository.get(context).save(null, login("Local", "b", "local-pass-2"))
        val outcome = engine().sync(token)
        assertTrue(outcome.toString(), outcome is SyncEngine.Outcome.Failure)

        assertEquals(1, engine().joinCloudVault(engine().inspectCloud(token)!!, password))
        assertTrue(engine().sync(token) is SyncEngine.Outcome.Success)
        val titles = VaultRepository.get(context).snapshot().map { it.title }.toSet()
        assertEquals(setOf("Cloud", "Local"), titles)
    }

    @Test fun corruptedCloudFileIsNeverOverwrittenAutomatically() = runBlocking {
        VaultKeyManager(context).createVault(password.toCharArray())
        VaultRepository.get(context).save(null, login("X", "x", "x-pass-123"))
        assertTrue(engine().sync(token) is SyncEngine.Outcome.Success)
        val file = fake.files.values.single()
        file.bytes = file.bytes.copyOf(file.bytes.size - 5)
        val damaged = file.bytes.copyOf()
        VaultRepository.get(context).save(null, login("Y", "y", "y-pass-123"))
        val outcome = engine().sync(token)
        assertTrue(outcome.toString(), outcome is SyncEngine.Outcome.Failure)
        assertTrue(damaged.contentEquals(fake.files.values.single().bytes))
    }
}
