package com.sboard.app.feature.vault

import android.content.Context
import androidx.room.withTransaction
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.data.VaultDatabase
import com.sboard.app.feature.sync.GoogleAccount
import com.sboard.app.feature.sync.SyncScheduler
import com.sboard.app.feature.sync.SyncStatus

/** Erases the local Vault (keys, items, history) on explicit user request. The cloud copy is untouched. */
object VaultWiper {
    suspend fun wipeLocal(context: Context) {
        val app = context.applicationContext
        VaultSessionProvider.session.lock()
        SyncScheduler.cancelAll(app)
        val db = VaultDatabase.get(app)
        db.withTransaction {
            db.items().clear(); db.history().clear(); db.config().clear(); db.recovery().delete(); db.biometric().delete()
            db.meta().delete(VaultKeyManager.META_VAULT_ID); db.meta().delete(VaultKeyManager.META_KEY_UPDATED)
            db.meta().delete("legacy_records_pending")
        }
        db.openHelper.writableDatabase.execSQL("DROP TABLE IF EXISTS vault_records")
        Biometrics.disable(app)
        GoogleAccount.revoke(app)
        SyncStatus.reset(app)
    }
}
