package com.sboard.app.feature.sync

import android.content.Context
import java.util.UUID

/** Stable, non-secret installation identity and Lamport counter for local journal entries. */
class DeviceIdentityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        check(prefs.edit().putString(KEY_DEVICE_ID, created).commit()) { "Unable to persist device identity" }
        return created
    }

    /** Advances the local Lamport counter after observing any known remote counter. */
    @Synchronized
    fun nextRevision(observedRemoteCounter: Long = 0L): Long {
        require(observedRemoteCounter >= 0L)
        val current = prefs.getLong(KEY_COUNTER, 0L)
        val next = maxOf(current, observedRemoteCounter) + 1L
        check(next > 0L) { "Sync revision counter overflow" }
        check(prefs.edit().putLong(KEY_COUNTER, next).commit()) { "Unable to persist sync revision" }
        return next
    }

    fun currentRevision(): Long = prefs.getLong(KEY_COUNTER, 0L)

    companion object {
        private const val PREFS = "sboard_device_sync_identity"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_COUNTER = "lamport_counter"
    }
}
