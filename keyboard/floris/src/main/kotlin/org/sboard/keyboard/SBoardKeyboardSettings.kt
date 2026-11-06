package org.sboard.keyboard

import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.ThemeMode

/** Narrow facade so the sBoard settings UI can read/write keyboard preferences. */
object SBoardKeyboardSettings {
    private val prefs by FlorisPreferenceStore

    fun heightPercent(): Int = prefs.keyboard.heightFactorPortrait.get()
    suspend fun setHeightPercent(value: Int) {
        prefs.keyboard.heightFactorPortrait.set(value.coerceIn(70, 140))
        prefs.keyboard.heightFactorLandscape.set(value.coerceIn(70, 140))
    }

    fun soundEnabled(): Boolean = prefs.inputFeedback.audioEnabled.get()
    suspend fun setSoundEnabled(value: Boolean) = prefs.inputFeedback.audioEnabled.set(value)
    fun soundVolume(): Int = prefs.inputFeedback.audioVolume.get()
    suspend fun setSoundVolume(value: Int) = prefs.inputFeedback.audioVolume.set(value.coerceIn(1, 100))

    fun vibrationEnabled(): Boolean = prefs.inputFeedback.hapticEnabled.get()
    suspend fun setVibrationEnabled(value: Boolean) = prefs.inputFeedback.hapticEnabled.set(value)

    fun numberRow(): Boolean = prefs.keyboard.numberRow.get()
    suspend fun setNumberRow(value: Boolean) = prefs.keyboard.numberRow.set(value)

    fun suggestions(): Boolean = prefs.suggestion.enabled.get()
    suspend fun setSuggestions(value: Boolean) = prefs.suggestion.enabled.set(value)

    fun smartbar(): Boolean = prefs.smartbar.enabled.get()
    suspend fun setSmartbar(value: Boolean) = prefs.smartbar.enabled.set(value)

    fun oneHanded(): Boolean = prefs.keyboard.oneHandedModeEnabled.get()
    suspend fun setOneHanded(value: Boolean) = prefs.keyboard.oneHandedModeEnabled.set(value)

    /** "system", "light" or "dark". */
    fun theme(): String = when (prefs.theme.mode.get()) {
        ThemeMode.ALWAYS_DAY -> "light"
        ThemeMode.ALWAYS_NIGHT -> "dark"
        else -> "system"
    }
    suspend fun setTheme(value: String) = prefs.theme.mode.set(
        when (value) { "light" -> ThemeMode.ALWAYS_DAY; "dark" -> ThemeMode.ALWAYS_NIGHT; else -> ThemeMode.FOLLOW_SYSTEM }
    )

    /** Makes sure the sBoard Pass action is present on existing installations' Smart Bar. */
    suspend fun ensureVaultAction() {
        val vault = QuickAction.InsertKey(TextKeyData.IME_UI_MODE_VAULT)
        val arrangement = prefs.smartbar.actionArrangement.get()
        if (vault in arrangement) return
        val newDynamic = listOfNotNull(arrangement.stickyAction) + arrangement.dynamicActions
        prefs.smartbar.actionArrangement.set(arrangement.copy(stickyAction = vault, dynamicActions = newDynamic).distinct())
    }
}
