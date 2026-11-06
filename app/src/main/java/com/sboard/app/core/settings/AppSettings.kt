package com.sboard.app.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/** Non-secret application preferences. Secrets never go here. */
object AppSettings {
    private const val PREFS = "sboard_settings"
    private lateinit var prefs: SharedPreferences

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    val themeMode: MutableState<String> = mutableStateOf(THEME_SYSTEM)
    /** Seconds in background before the Vault locks. 0 = immediately, -1 = only when the process ends. */
    val autoLockSeconds: MutableState<Int> = mutableStateOf(300)
    /** Seconds before copied secrets are wiped from the clipboard. 0 = never. */
    val clipboardClearSeconds: MutableState<Int> = mutableStateOf(45)
    val biometricForSensitive: MutableState<Boolean> = mutableStateOf(true)
    val screenSecurity: MutableState<Boolean> = mutableStateOf(true)
    val autoSync: MutableState<Boolean> = mutableStateOf(true)
    val syncOnUnmeteredOnly: MutableState<Boolean> = mutableStateOf(false)
    val keyboardVaultBar: MutableState<Boolean> = mutableStateOf(true)

    @Synchronized
    fun initialize(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        themeMode.value = prefs.getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM
        autoLockSeconds.value = prefs.getInt("auto_lock_seconds", 300)
        clipboardClearSeconds.value = prefs.getInt("clipboard_clear_seconds", 45)
        biometricForSensitive.value = prefs.getBoolean("biometric_sensitive", true)
        screenSecurity.value = prefs.getBoolean("screen_security", true)
        autoSync.value = prefs.getBoolean("auto_sync", true)
        syncOnUnmeteredOnly.value = prefs.getBoolean("sync_unmetered_only", false)
        keyboardVaultBar.value = prefs.getBoolean("keyboard_vault_bar", true)
    }

    fun setTheme(value: String) { themeMode.value = value; prefs.edit().putString("theme", value).apply() }
    fun setAutoLock(value: Int) { autoLockSeconds.value = value; prefs.edit().putInt("auto_lock_seconds", value).apply() }
    fun setClipboardClear(value: Int) { clipboardClearSeconds.value = value; prefs.edit().putInt("clipboard_clear_seconds", value).apply() }
    fun setBiometricForSensitive(value: Boolean) { biometricForSensitive.value = value; prefs.edit().putBoolean("biometric_sensitive", value).apply() }
    fun setScreenSecurity(value: Boolean) { screenSecurity.value = value; prefs.edit().putBoolean("screen_security", value).apply() }
    fun setAutoSync(value: Boolean) { autoSync.value = value; prefs.edit().putBoolean("auto_sync", value).apply() }
    fun setSyncOnUnmeteredOnly(value: Boolean) { syncOnUnmeteredOnly.value = value; prefs.edit().putBoolean("sync_unmetered_only", value).apply() }
    fun setKeyboardVaultBar(value: Boolean) { keyboardVaultBar.value = value; prefs.edit().putBoolean("keyboard_vault_bar", value).apply() }
}
