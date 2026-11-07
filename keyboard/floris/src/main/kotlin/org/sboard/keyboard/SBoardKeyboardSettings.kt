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
        val editing = QuickAction.InsertKey(TextKeyData.IME_UI_MODE_EDITING)
        var arrangement = prefs.smartbar.actionArrangement.get()
        if (editing !in arrangement) {
            // Older installs: add the text editing panel to the toolbar.
            arrangement = arrangement.copy(dynamicActions = listOf(editing) + arrangement.dynamicActions).distinct()
            prefs.smartbar.actionArrangement.set(arrangement)
        }
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

    fun vibrationStrength(): Int = prefs.inputFeedback.hapticVibrationStrength.get()
    suspend fun setVibrationStrength(value: Int) = prefs.inputFeedback.hapticVibrationStrength.set(value.coerceIn(1, 100))
    fun vibrationDuration(): Int = prefs.inputFeedback.hapticVibrationDuration.get()
    suspend fun setVibrationDuration(value: Int) = prefs.inputFeedback.hapticVibrationDuration.set(value.coerceIn(5, 120))

    /** "cursor" (Samsung default: drag the space bar to move the cursor) or "language". */
    fun spaceSwipe(): String = if (prefs.gestures.spaceBarSwipeLeft.get() == dev.patrickgold.florisboard.ime.text.gestures.SwipeAction.SWITCH_TO_PREV_SUBTYPE) "language" else "cursor"
    suspend fun setSpaceSwipe(value: String) {
        val language = value == "language"
        prefs.gestures.spaceBarSwipeLeft.set(if (language) dev.patrickgold.florisboard.ime.text.gestures.SwipeAction.SWITCH_TO_PREV_SUBTYPE else dev.patrickgold.florisboard.ime.text.gestures.SwipeAction.MOVE_CURSOR_LEFT)
        prefs.gestures.spaceBarSwipeRight.set(if (language) dev.patrickgold.florisboard.ime.text.gestures.SwipeAction.SWITCH_TO_NEXT_SUBTYPE else dev.patrickgold.florisboard.ime.text.gestures.SwipeAction.MOVE_CURSOR_RIGHT)
    }
    /** Long-press on space: "none" (hold and drag moves the cursor), "languages" or "keyboards". */
    fun spaceLongPress(): String = when (prefs.gestures.spaceBarLongPress.get()) {
        dev.patrickgold.florisboard.ime.text.gestures.SwipeAction.SHOW_SUBTYPE_PICKER -> "languages"
        dev.patrickgold.florisboard.ime.text.gestures.SwipeAction.SHOW_INPUT_METHOD_PICKER -> "keyboards"
        else -> "none"
    }
    suspend fun setSpaceLongPress(value: String) = prefs.gestures.spaceBarLongPress.set(when (value) {
        "languages" -> dev.patrickgold.florisboard.ime.text.gestures.SwipeAction.SHOW_SUBTYPE_PICKER
        "keyboards" -> dev.patrickgold.florisboard.ime.text.gestures.SwipeAction.SHOW_INPUT_METHOD_PICKER
        else -> dev.patrickgold.florisboard.ime.text.gestures.SwipeAction.NO_ACTION
    })
    /** Swipe left on Delete selects and deletes whole words. */
    fun deleteSwipeWords(): Boolean = prefs.gestures.deleteKeySwipeLeft.get() == dev.patrickgold.florisboard.ime.text.gestures.SwipeAction.DELETE_WORDS_PRECISELY
    suspend fun setDeleteSwipeWords(value: Boolean) = prefs.gestures.deleteKeySwipeLeft.set(
        if (value) dev.patrickgold.florisboard.ime.text.gestures.SwipeAction.DELETE_WORDS_PRECISELY else dev.patrickgold.florisboard.ime.text.gestures.SwipeAction.DELETE_CHARACTERS_PRECISELY)
    fun capsLockMemory(): Boolean = prefs.correction.rememberCapsLockState.get()
    suspend fun setCapsLockMemory(value: Boolean) = prefs.correction.rememberCapsLockState.set(value)

    // ---- Clipboard ------------------------------------------------------------------------------

    fun clipboardKeepMinutes(): Int = if (prefs.clipboard.historyAutoCleanOldEnabled.get()) prefs.clipboard.historyAutoCleanOldAfter.get() else 0
    /** 0 = keep until deleted. */
    suspend fun setClipboardKeepMinutes(value: Int) {
        prefs.clipboard.historyAutoCleanOldEnabled.set(value > 0)
        if (value > 0) prefs.clipboard.historyAutoCleanOldAfter.set(value)
    }
    fun clipboardLimit(): Int = if (prefs.clipboard.historySizeLimitEnabled.get()) prefs.clipboard.historySizeLimit.get() else 0
    suspend fun setClipboardLimit(value: Int) {
        prefs.clipboard.historySizeLimitEnabled.set(value > 0)
        if (value > 0) prefs.clipboard.historySizeLimit.set(value)
    }

    // ---- Modes ----------------------------------------------------------------------------------

    /** Width of the keyboard in one-handed mode (percent of the screen). */
    fun oneHandedWidth(): Int = prefs.keyboard.oneHandedModeScaleFactor.get()
    suspend fun setOneHandedWidth(value: Int) = prefs.keyboard.oneHandedModeScaleFactor.set(value.coerceIn(70, 90))
    fun oneHandedRight(): Boolean = prefs.keyboard.oneHandedMode.get() == dev.patrickgold.florisboard.ime.onehanded.OneHandedMode.END
    suspend fun setOneHandedRight(value: Boolean) = prefs.keyboard.oneHandedMode.set(
        if (value) dev.patrickgold.florisboard.ime.onehanded.OneHandedMode.END else dev.patrickgold.florisboard.ime.onehanded.OneHandedMode.START)

    // ---- Personal dictionary --------------------------------------------------------------------

    data class DictionaryWord(val id: Long, val word: String, val shortcut: String?, val locale: String?)

    private fun dictionaryDao() = dev.patrickgold.florisboard.ime.dictionary.DictionaryManager.default().let {
        it.loadUserDictionariesIfNecessary(); it.florisUserDictionaryDao()
    }

    suspend fun dictionaryWords(): List<DictionaryWord> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        dictionaryDao()?.queryAll().orEmpty().map { DictionaryWord(it.id, it.word, it.shortcut, it.locale) }.sortedBy { it.word.lowercase() }
    }

    suspend fun addDictionaryWord(word: String, shortcut: String?, locale: String?) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val clean = word.trim()
        require(clean.isNotEmpty())
        dictionaryDao()?.insert(dev.patrickgold.florisboard.ime.dictionary.UserDictionaryEntry(0, clean, 200, locale?.takeIf { it.isNotBlank() }, shortcut?.trim()?.takeIf { it.isNotEmpty() }))
    }

    suspend fun deleteDictionaryWord(id: Long) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dao = dictionaryDao() ?: return@withContext
        dao.queryAll().firstOrNull { it.id == id }?.let { dao.delete(it) }
    }

    /** Keyboard language tags for the dictionary language selector. */
    fun enabledLanguageTags(context: Context): List<String> = context.subtypeManager().value.subtypes.map { it.primaryLocale.languageTag() }.distinct()

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

    /** Points both day and night keyboard themes at the editable custom themes. */
    suspend fun useCustomThemes() {
        prefs.theme.dayThemeId.set(ExtensionComponentName(SBoardThemeStore.EXTENSION, SBoardThemeStore.CUSTOM_DAY))
        prefs.theme.nightThemeId.set(ExtensionComponentName(SBoardThemeStore.EXTENSION, SBoardThemeStore.CUSTOM_NIGHT))
    }

    // ---- Languages ----------------------------------------------------------------------------

    data class Language(val tag: String, val name: String, val enabled: Boolean)

    /** All languages with a built-in layout preset, enabled ones first. */
    fun languages(context: Context): List<Language> {
        val presets = context.keyboardManager().value.resources.subtypePresets.value.orEmpty()
        val enabled = context.subtypeManager().value.subtypes.map { it.primaryLocale.languageTag() }.toSet()
        return presets.map { Language(it.locale.languageTag(), SBoardLocaleNames.short(it.locale.languageTag()), it.locale.languageTag() in enabled) }
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
        if (!marker.getBoolean("custom_theme_v1", false)) {
            useCustomThemes() // removed preset themes are replaced by the editable theme
            marker.edit().putBoolean("custom_theme_v1", true).apply()
        }
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
