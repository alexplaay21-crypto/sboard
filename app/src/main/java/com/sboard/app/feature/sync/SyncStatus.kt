package com.sboard.app.feature.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SyncState { IDLE, RUNNING, SUCCESS, PENDING_LOCKED, NEEDS_AUTH, OFFLINE, ERROR, VAULT_MISMATCH, CORRUPTED, REVOKED }

data class SyncSnapshot(
    val state: SyncState = SyncState.IDLE,
    val lastSuccessAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val message: String? = null,
    val pulled: Int = 0,
    val pushed: Int = 0,
    val conflicts: Int = 0,
    val remoteItems: Int = 0,
    val remoteGeneration: Long = 0L,
    val devices: List<SyncDevice> = emptyList(),
)

/** Persisted, non-secret sync diagnostics shown in Profile → Sync. */
object SyncStatus {
    private const val PREFS = "sboard_sync_status"
    private val _snapshot = MutableStateFlow(SyncSnapshot())
    val snapshot: StateFlow<SyncSnapshot> = _snapshot.asStateFlow()
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _snapshot.value = SyncSnapshot(
            state = runCatching { SyncState.valueOf(p.getString("state", "IDLE")!!) }.getOrDefault(SyncState.IDLE)
                .let { if (it == SyncState.RUNNING) SyncState.IDLE else it },
            lastSuccessAt = p.getLong("last_success", 0L),
            lastAttemptAt = p.getLong("last_attempt", 0L),
            message = p.getString("message", null),
            conflicts = p.getInt("conflicts", 0),
            remoteItems = p.getInt("remote_items", 0),
            remoteGeneration = p.getLong("generation", 0L),
            devices = runCatching {
                p.getString("devices", null)?.let { kotlinx.serialization.json.Json.decodeFromString<List<SyncDevice>>(it) }
            }.getOrNull().orEmpty(),
        )
        loaded = true
    }

    @Synchronized
    fun update(context: Context, transform: (SyncSnapshot) -> SyncSnapshot) {
        load(context)
        val next = transform(_snapshot.value)
        _snapshot.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("state", next.state.name)
            .putLong("last_success", next.lastSuccessAt)
            .putLong("last_attempt", next.lastAttemptAt)
            .putString("message", next.message)
            .putInt("conflicts", next.conflicts)
            .putInt("remote_items", next.remoteItems)
            .putLong("generation", next.remoteGeneration)
            .putString("devices", kotlinx.serialization.json.Json.encodeToString(next.devices))
            .apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        _snapshot.value = SyncSnapshot()
    }
}
