package org.sboard.keyboard

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.core.SubtypeJsonConfig
import dev.patrickgold.florisboard.ime.keyboard.extCoreLayout
import dev.patrickgold.florisboard.ime.landscapeinput.LandscapeInputUiMode
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionArrangement
import dev.patrickgold.florisboard.ime.text.key.KeyHintMode
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.subtypeManager
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

    /** Restores the Samsung-style toolbar order. */
    suspend fun resetToolbar() = prefs.smartbar.actionArrangement.set(QuickActionArrangement.Default)

    // ---- Typing -----------------------------------------------------------------------------

    fun swipeTyping(): Boolean = prefs.glide.enabled.get()
    suspend fun setSwipeTyping(value: Boolean) = prefs.glide.enabled.set(value)
    fun swipeTrail(): Boolean = prefs.glide.showTrail.get()
    suspend fun setSwipeTrail(value: Boolean) = prefs.glide.showTrail.set(value)
    fun autoCapitalization(): Boolean = prefs.correction.autoCapitalization.get()
    suspend fun setAutoCapitalization(value: Boolean) = prefs.correction.autoCapitalization.set(value)
    fun doubleSpacePeriod(): Boolean = prefs.correction.doubleSpacePeriod.get()
    suspend fun setDoubleSpacePeriod(value: Boolean) = prefs.correction.doubleSpacePeriod.set(value)
    fun autoSpacePunctuation(): Boolean = prefs.correction.autoSpacePunctuation.get()
    suspend fun setAutoSpacePunctuation(value: Boolean) = prefs.correction.autoSpacePunctuation.set(value)
    fun symbolHints(): Boolean = prefs.keyboard.hintedSymbolsEnabled.get()
    suspend fun setSymbolHints(value: Boolean) = prefs.keyboard.hintedSymbolsEnabled.set(value)
    fun keyPopup(): Boolean = prefs.keyboard.popupEnabled.get()
    suspend fun setKeyPopup(value: Boolean) = prefs.keyboard.popupEnabled.set(value)
    fun longPressDelay(): Int = prefs.keyboard.longPressDelay.get()
    suspend fun setLongPressDelay(value: Int) = prefs.keyboard.longPressDelay.set(value.coerceIn(150, 800))
    fun emojiSuggestions(): Boolean = prefs.emoji.suggestionEnabled.get()
    suspend fun setEmojiSuggestions(value: Boolean) = prefs.emoji.suggestionEnabled.set(value)
    fun clipboardHistory(): Boolean = prefs.clipboard.historyEnabled.get()
    suspend fun setClipboardHistory(value: Boolean) = prefs.clipboard.historyEnabled.set(value)

    // ---- Size & look --------------------------------------------------------------------------

    fun landscapeHeightPercent(): Int = prefs.keyboard.heightFactorLandscape.get()
    suspend fun setLandscapeHeightPercent(value: Int) = prefs.keyboard.heightFactorLandscape.set(value.coerceIn(55, 120))
    fun fontSizePercent(): Int = prefs.keyboard.fontSizeMultiplierPortrait.get()
    suspend fun setFontSizePercent(value: Int) {
        prefs.keyboard.fontSizeMultiplierPortrait.set(value.coerceIn(70, 140))
        prefs.keyboard.fontSizeMultiplierLandscape.set(value.coerceIn(70, 140))
    }
    fun keySpacing(): Float = prefs.keyboard.keySpacingHorizontal.get()
    suspend fun setKeySpacing(value: Float) {
        prefs.keyboard.keySpacingHorizontal.set(value.coerceIn(0f, 8f))
        prefs.keyboard.keySpacingVertical.set((value * 2.5f).coerceIn(0f, 12f))
    }

    /** Theme ids bundled in assets/ime/theme/org.sboard.themes. */
    val themes: List<Pair<String, String>> = listOf(
        "sboard_dark" to "Graphite", "sboard_light" to "Light", "sboard_amoled" to "AMOLED",
        "sboard_outline_blue" to "Neon Outline", "sboard_outline_gold" to "Gold Outline", "sboard_ocean" to "Ocean",
        "sboard_forest" to "Forest", "sboard_rose" to "Rose", "sboard_minimal_light" to "Minimal Light",
        "sboard_minimal_dark" to "Minimal Dark", "sboard_contrast" to "High Contrast",
    )
    fun dayTheme(): String = prefs.theme.dayThemeId.get().componentId
    fun nightTheme(): String = prefs.theme.nightThemeId.get().componentId
    suspend fun setDayTheme(id: String) = prefs.theme.dayThemeId.set(ExtensionComponentName("org.sboard.themes", id))
    suspend fun setNightTheme(id: String) = prefs.theme.nightThemeId.set(ExtensionComponentName("org.sboard.themes", id))

    // ---- Languages ----------------------------------------------------------------------------

    data class Language(val tag: String, val name: String, val enabled: Boolean)

    /** All languages with a built-in layout preset, enabled ones first. */
    fun languages(context: Context): List<Language> {
        val presets = context.keyboardManager().value.resources.subtypePresets.value.orEmpty()
        val enabled = context.subtypeManager().value.subtypes.map { it.primaryLocale.languageTag() }.toSet()
        return presets.map { Language(it.locale.languageTag(), it.locale.displayName(FlorisLocale.default()).replaceFirstChar { c -> c.uppercase() }, it.locale.languageTag() in enabled) }
            .distinctBy { it.tag }
            .sortedWith(compareByDescending<Language> { it.enabled }.thenBy { it.name })
    }

    fun setLanguageEnabled(context: Context, tag: String, enabled: Boolean): Boolean {
        val manager = context.subtypeManager().value
        val current = manager.subtypes
        if (enabled) {
            if (current.any { it.primaryLocale.languageTag() == tag }) return true
            val preset = context.keyboardManager().value.resources.subtypePresets.value.orEmpty().firstOrNull { it.locale.languageTag() == tag } ?: return false
            return manager.addSubtype(samsungStyle(preset.toSubtype()))
        } else {
            if (current.size <= 1) return false // keep at least one language
            current.filter { it.primaryLocale.languageTag() == tag }.forEach { manager.removeSubtype(it) }
            return true
        }
    }

    private fun samsungStyle(subtype: Subtype): Subtype {
        val latinOrCyrillic = subtype.layoutMap.symbols.componentId == "western" || subtype.layoutMap.symbols.componentId == "eastern"
        if (!latinOrCyrillic && subtype.layoutMap.symbols.componentId != "western_samsung") return subtype
        return subtype.copy(layoutMap = subtype.layoutMap.copy(symbols = extCoreLayout("western_samsung"), symbols2 = extCoreLayout("western_samsung")))
    }

    /**
     * One-time upgrade of existing installations to the Samsung-like setup: Samsung symbol pages
     * (which also become the long-press hints on letters), number row, swipe typing, no fullscreen
     * editor in landscape and the new toolbar order.
     */
    suspend fun migrateToSamsungStyle(context: Context) {
        val marker = context.getSharedPreferences("sboard_keyboard_setup", Context.MODE_PRIVATE)
        if (marker.getBoolean("samsung_style_v1", false)) return
        val raw = prefs.localization.subtypes.get()
        if (raw.isNotBlank() && raw != "[]") {
            val list = SubtypeJsonConfig.decodeFromString<List<Subtype>>(raw).map(::samsungStyle)
            prefs.localization.subtypes.set(SubtypeJsonConfig.encodeToString(list))
        } else return // the first-run seeding has not happened yet; try again next start
        prefs.keyboard.numberRow.set(true)
        prefs.keyboard.hintedNumberRowEnabled.set(false)
        prefs.keyboard.hintedSymbolsEnabled.set(true)
        prefs.keyboard.hintedSymbolsMode.set(KeyHintMode.HINT_PRIORITY)
        prefs.keyboard.landscapeInputUiMode.set(LandscapeInputUiMode.NEVER_SHOW)
        prefs.keyboard.heightFactorLandscape.set(78)
        prefs.glide.enabled.set(true)
        prefs.suggestion.enabled.set(true)
        prefs.smartbar.actionArrangement.set(QuickActionArrangement.Default)
        marker.edit().putBoolean("samsung_style_v1", true).apply()
    }
}
