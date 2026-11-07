package com.sboard.app.integration

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.sboard.app.MainActivity
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.data.ItemType
import com.sboard.app.data.LoginData
import com.sboard.app.data.VaultItemPayload
import com.sboard.app.data.VaultRepository
import com.sboard.app.feature.vault.VaultKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/** Launches the real activity: onboarding, lock screen and the main shell must compose without crashing. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class MainActivitySmokeTest {
    private lateinit var context: Context
    @Before fun setUp() { context = ApplicationProvider.getApplicationContext(); simulateNewDevice(context) }
    @After fun tearDown() = simulateNewDevice(context)

    private fun launchAndSettle(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(25) }
        scenario.onActivity { activity ->
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            assertTrue("compose content attached", content.childCount > 0 && content.getChildAt(0).visibility == View.VISIBLE)
        }
        return scenario
    }

    @Test fun onboardingRenders() { launchAndSettle().close() }

    @Test fun lockScreenAndMainShellRender() {
        runBlocking {
            VaultKeyManager(context).createVault("smoke test passphrase".toCharArray())
            VaultRepository.get(context).save(null, VaultItemPayload(ItemType.LOGIN, "GitHub", favorite = true,
                login = LoginData("alex", "Very-Long-Pass-1", urls = listOf("github.com"))))
        }
        VaultSessionProvider.session.lock()
        launchAndSettle().use { scenario ->
            runBlocking { VaultKeyManager(context).unlockWithPassword("smoke test passphrase".toCharArray()) }
            repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(25) }
            scenario.onActivity { assertTrue(VaultSessionProvider.session.isUnlocked) }
        }
    }
}
