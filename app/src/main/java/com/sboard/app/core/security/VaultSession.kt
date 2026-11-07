package com.sboard.app.core.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.sboard.app.core.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the unwrapped 256-bit Vault key only in process memory.
 * Every consumer receives a copy that it must wipe after use.
 */
class VaultSession {
    private val monitor = Any()
    private var key: ByteArray? = null
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    val isUnlocked: Boolean get() = synchronized(monitor) { key != null }

    fun unlock(vaultKey: ByteArray) {
        require(vaultKey.size == 32) { "Invalid vault key" }
        synchronized(monitor) {
            key?.fill(0)
            key = vaultKey.copyOf()
        }
        _unlocked.value = true
        lastUnlockAt = System.currentTimeMillis()
    }

    fun copyKey(): ByteArray = synchronized(monitor) { (key ?: throw VaultLockedException()).copyOf() }

    inline fun <T> withKey(block: (ByteArray) -> T): T {
        val k = copyKey()
        try { return block(k) } finally { k.fill(0) }
    }

    fun lock() {
        synchronized(monitor) {
            key?.fill(0)
            key = null
        }
        _unlocked.value = false
    }

    @Volatile var lastUnlockAt: Long = 0L
        private set
}

class VaultLockedException : IllegalStateException("Vault is locked")

/** Process-wide session shared by UI, Autofill, Credential Provider and the keyboard. */
object VaultSessionProvider {
    val session = VaultSession()
}

/**
 * Locks the Vault after the configured time in background and immediately when the screen turns off
 * (unless auto-lock is set to "never").
 */
object AutoLock {
    private val handler = Handler(Looper.getMainLooper())
    private val lockRunnable = Runnable { VaultSessionProvider.session.lock() }
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { handler.removeCallbacks(lockRunnable) }
            override fun onStop(owner: LifecycleOwner) { scheduleBackgroundLock() }
        })
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (AppSettings.autoLockSeconds.value >= 0) VaultSessionProvider.session.lock()
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.applicationContext.registerReceiver(receiver, filter)
        }
    }

    /** Called when the app UI goes to background, and by keyboard/autofill flows when they finish. */
    fun scheduleBackgroundLock() {
        handler.removeCallbacks(lockRunnable)
        val seconds = AppSettings.autoLockSeconds.value
        when {
            seconds < 0 -> Unit
            seconds == 0 -> VaultSessionProvider.session.lock()
            else -> handler.postDelayed(lockRunnable, seconds * 1000L)
        }
    }
}
