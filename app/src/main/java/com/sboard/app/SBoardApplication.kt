package com.sboard.app

import com.sboard.app.core.i18n.SBoardI18n
import com.sboard.app.core.security.AutoLock
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.core.settings.AppSettings
import com.sboard.app.data.VaultRepository
import com.sboard.app.feature.keyboard.KeyboardVaultProviderImpl
import com.sboard.app.feature.sync.GoogleAccount
import com.sboard.app.feature.sync.SyncScheduler
import com.sboard.app.feature.sync.SyncStatus
import dev.patrickgold.florisboard.FlorisApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.sboard.keyboard.SBoardVaultBridge

/** Process wiring: keyboard runtime (FlorisBoard), settings, auto-lock, sync and the keyboard Vault bridge. */
class SBoardApplication : FlorisApplication() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppSettings.initialize(this)
        SBoardI18n.initialize(this)
        GoogleAccount.load(this)
        SyncStatus.load(this)
        AutoLock.install(this)
        VaultRepository.get(this) // starts the decrypted item flow (empty while locked)
        SBoardVaultBridge.provider = KeyboardVaultProviderImpl(this)
        SBoardVaultBridge.translator = com.sboard.app.feature.keyboard.KeyboardTranslatorImpl(this)
        appScope.launch { runCatching { org.sboard.keyboard.SBoardKeyboardSettings.migrateToSamsungStyle(this@SBoardApplication) } }
        SyncScheduler.ensurePeriodic(this)
        // Every unlock (app, autofill, keyboard, passkey) triggers a sync of pending changes.
        appScope.launch {
            VaultSessionProvider.session.unlocked.drop(1).filter { it }.collect { SyncScheduler.requestSoon(this@SBoardApplication) }
        }
    }
}
