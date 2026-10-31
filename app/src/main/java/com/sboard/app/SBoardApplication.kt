package com.sboard.app

import android.app.Application
import android.content.Context
import androidx.room.InvalidationTracker
import com.sboard.app.feature.backup.BackupAutomationScheduler
import com.sboard.app.feature.backup.RestoreCrashRecovery
import com.sboard.app.feature.backup.BackupAutomationSettingsStore
import com.sboard.app.feature.backup.DeviceBoundBackupKeyStore
import com.sboard.app.feature.backup.DriveBackupAutomationExecutor
import com.sboard.app.feature.backup.GoogleDriveSilentOAuthTokenProvider
import com.sboard.app.feature.backup.KeystoreBackupCredentialProvider
import com.sboard.app.feature.backup.BackupAutomationRuntime
import com.sboard.app.feature.vault.VaultDatabase
import com.sboard.app.feature.sync.SyncAutomationScheduler
import com.sboard.app.feature.sync.SyncAutomationRuntime
import com.sboard.app.feature.sync.GoogleDriveSyncAutomationExecutor
import com.sboard.app.feature.vault.VaultSessionProvider

/** Installs a Room observer and reattaches it if a restore replaces the Room singleton. */
object SBoardDatabaseBackupObserver {
    @Volatile private var observedDatabase: VaultDatabase? = null

    fun install(context: Context) {
        val app = context.applicationContext
        val database = VaultDatabase.get(app)
        if (observedDatabase === database) return
        synchronized(this) {
            if (observedDatabase === database) return
            database.invalidationTracker.addObserver(object : InvalidationTracker.Observer(
                "vault_records",
                "passkey_records",
                "passkey_secure_metadata",
                "vault_config",
                "recovery_envelope",
                "biometric_envelope",
                "sync_change_journal"
            ) {
                override fun onInvalidated(tables: Set<String>) {
                    // Called after Room commits a write. Scheduler debounces rapid edit bursts.
                    BackupAutomationScheduler(app).requestAfterImportantChange()
                    if ("sync_change_journal" in tables) SyncAutomationScheduler(app).requestAfterLocalChange()
                }
            })
            observedDatabase = database
        }
    }

    /** Rebinds change observers after an authenticated restore replaces the Room database file. */
    fun onDatabaseReplaced(context: Context) {
        synchronized(this) { observedDatabase = null }
        install(context)
    }
}

/** Process-wide wiring for durable, event-driven encrypted backups. */
class SBoardApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Must run before any Room singleton is opened: finish/undo a restore interrupted by a crash.
        RestoreCrashRecovery.recover(applicationContext)

        SyncAutomationRuntime.install(
            GoogleDriveSyncAutomationExecutor(VaultSessionProvider.session)
        )

        BackupAutomationRuntime.install(
            DriveBackupAutomationExecutor(
                credentialProvider = KeystoreBackupCredentialProvider(
                    context = applicationContext,
                    tokenProvider = GoogleDriveSilentOAuthTokenProvider()
                )
            )
        )

        val settings = BackupAutomationSettingsStore(applicationContext)
        val keyStore = DeviceBoundBackupKeyStore(applicationContext)
        if (settings.isAutomationDesired() && settings.isDriveConnected() && keyStore.isConfigured()) {
            BackupAutomationScheduler(applicationContext).enableChangeBased()
        }

        SBoardDatabaseBackupObserver.install(applicationContext)
    }
}
