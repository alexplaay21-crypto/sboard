package com.sboard.app.keyboard

import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue

/**
 * First functional sBoard IME foundation.
 * This keyboard intentionally has no network access, key logging, or learning store.
 * Password fields receive the same local key input but no suggestions are shown or retained.
 */
class SBoardInputMethodService : InputMethodService() {

    private enum class Layout { RU, EN, SYMBOLS }

    private var layout = Layout.RU
    private var shifted = false
    private var symbolPage = false
    private var keyboardRoot: LinearLayout? = null
    private var isPasswordField = false
    private var isNumericField = false
    private var showNumberRow = true
    private var hapticsEnabled = true
    private var lightTheme = false

    override fun onCreate() {
        super.onCreate()
        val prefs = KeyboardSettings.preferences(this)
        layout = if (prefs.getString(KeyboardSettings.KEY_LAYOUT, "ru") == "en") Layout.EN else Layout.RU
        loadSettings()
    }

    override fun onCreateInputView(): View {
        keyboardRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(7), dp(5), dp(7))
            setBackgroundColor(if (lightTheme) Color.rgb(235, 237, 241) else Color.rgb(25, 27, 30))
        }
        renderKeyboard()
        return keyboardRoot!!
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        loadSettings()
        val inputType = attribute?.inputType ?: 0
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val typeClass = inputType and android.text.InputType.TYPE_MASK_CLASS
        isNumericField = typeClass == android.text.InputType.TYPE_CLASS_NUMBER
        isPasswordField = (typeClass == android.text.InputType.TYPE_CLASS_TEXT &&
            variation in setOf(
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )) || (typeClass == android.text.InputType.TYPE_CLASS_NUMBER &&
            variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        renderKeyboard()
    }

    private fun loadSettings() {
        val prefs = KeyboardSettings.preferences(this)
        showNumberRow = prefs.getBoolean(KeyboardSettings.KEY_NUMBER_ROW, true)
        hapticsEnabled = prefs.getBoolean(KeyboardSettings.KEY_HAPTICS, true)
        lightTheme = when (prefs.getString(KeyboardSettings.KEY_THEME, "system")) {
            "light" -> true
            "dark" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_NO
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // No text-learning or suggestion history is maintained by this IME.
    }

    private fun renderKeyboard() {
        val root = keyboardRoot ?: return
        root.removeAllViews()
        root.setBackgroundColor(if (lightTheme) Color.rgb(235, 237, 241) else Color.rgb(25, 27, 30))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val languageLabel = when (layout) {
            Layout.RU -> "РУССКИЙ"
            Layout.EN -> "ENGLISH"
            Layout.SYMBOLS -> "СИМВОЛЫ"
        }
        val privacy = TextView(this).apply {
            text = if (isPasswordField) getString(com.sboard.app.R.string.ime_private_input) else "sBoard"
            setTextColor(Color.rgb(190, 190, 190))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(7), dp(2), dp(7), dp(5))
        }
        top.addView(privacy, LinearLayout.LayoutParams(0, dp(25), 1f))
        val lang = makeKey(languageLabel, accent = true) {
            layout = when (layout) {
                Layout.RU -> Layout.EN
                Layout.EN -> Layout.RU
                Layout.SYMBOLS -> if (symbolPage) Layout.SYMBOLS else Layout.RU
            }
            if (layout != Layout.SYMBOLS) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_LAYOUT, if (layout == Layout.EN) "en" else "ru").apply()
            }
            shifted = false
            renderKeyboard()
        }
        top.addView(lang, LinearLayout.LayoutParams(dp(94), dp(30)))
        root.addView(top)

        if (isNumericField) {
            listOf("1 2 3", "4 5 6", "7 8 9").forEach { addKeyRow(root, it.split(" ")) }
            val numericBottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            numericBottom.addView(makeKey("-", accent = true) { commit("-") }, weightParams(1f))
            numericBottom.addView(makeKey("0") { commit("0") }, weightParams(1f))
            numericBottom.addView(makeKey(",") { commit(",") }, weightParams(1f))
            numericBottom.addView(makeKey("⌫", accent = true) { deleteOne() }, weightParams(1f))
            numericBottom.addView(makeKey("↵", accent = true) { performEnter() }, weightParams(1f))
            root.addView(numericBottom)
            return
        }

        if (showNumberRow && layout != Layout.SYMBOLS) addKeyRow(root, "1 2 3 4 5 6 7 8 9 0".split(" "))

        if (layout == Layout.SYMBOLS) {
            val rows = listOf(
                "1 2 3 4 5 6 7 8 9 0",
                "@ # $ % & - + ( ) /",
                "* \" ' : ; ! ? ⌫"
            )
            rows.forEach { addKeyRow(root, it.split(" ")) }
        } else {
            val rows = if (layout == Layout.RU) {
                listOf("й ц у к е н г ш щ з х", "ф ы в а п р о л д ж э", "я ч с м и т ь б ю")
            } else {
                listOf("q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m")
            }
            rows.forEachIndexed { index, row ->
                val keys = row.split(" ").map { if (shifted) it.uppercase() else it }
                if (index == 2) {
                    val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    line.addView(makeKey("⇧", accent = shifted) { shifted = !shifted; renderKeyboard() }, weightParams(1.25f))
                    keys.forEach { key -> line.addView(makeKey(key) { commit(key) }, weightParams(1f)) }
                    line.addView(makeKey("⌫", accent = true) { deleteOne() }, weightParams(1.25f))
                    root.addView(line)
                } else addKeyRow(root, keys)
            }
        }

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(makeKey("?123", accent = true) {
            layout = if (layout == Layout.SYMBOLS) Layout.RU else Layout.SYMBOLS
            symbolPage = !symbolPage
            renderKeyboard()
        }, weightParams(1.25f))
        bottom.addView(makeKey("🌐") { toggleLanguage() }, weightParams(1f))
        bottom.addView(makeKey(",") { commit(",") }, weightParams(0.8f))
        bottom.addView(makeKey("Пробел") { commit(" ") }, weightParams(3.4f))
        bottom.addView(makeKey(".") { commit(".") }, weightParams(0.8f))
        bottom.addView(makeKey("↵", accent = true) { performEnter() }, weightParams(1.25f))
        root.addView(bottom)
    }

    private fun addKeyRow(root: LinearLayout, keys: List<String>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.forEach { key -> row.addView(makeKey(key) { commit(key) }, weightParams(1f)) }
        root.addView(row)
    }

    private fun makeKey(label: String, accent: Boolean = false, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 3) 10f else 17f)
            setTextColor(if (accent || lightTheme) Color.rgb(28, 30, 34) else Color.rgb(244, 243, 238))
            setTypeface(typeface, Typeface.NORMAL)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                if (accent) Color.rgb(213, 185, 75) else if (lightTheme) Color.WHITE else Color.rgb(48, 50, 54)
            ))
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(1), 0, dp(1), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(47), 1f).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            setOnClickListener {
                if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                action()
            }
        }
    }

    private fun weightParams(weight: Float) = LinearLayout.LayoutParams(0, dp(47), weight).apply {
        setMargins(dp(2), dp(3), dp(2), dp(3))
    }

    private fun commit(value: String) {
        currentInputConnection?.commitText(value, 1)
        if (shifted) { shifted = false; renderKeyboard() }
    }

    private fun deleteOne() {
        val connection = currentInputConnection ?: return
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }

    private fun performEnter() {
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val handled = when (action) {
            EditorInfo.IME_ACTION_GO -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_GO)
            EditorInfo.IME_ACTION_SEARCH -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
            EditorInfo.IME_ACTION_SEND -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND)
            EditorInfo.IME_ACTION_NEXT -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_NEXT)
            EditorInfo.IME_ACTION_DONE -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_DONE)
            EditorInfo.IME_ACTION_PREVIOUS -> currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_PREVIOUS)
            else -> null
        }
        if (handled != true) currentInputConnection?.sendKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        )
    }

    private fun toggleLanguage() {
        layout = if (layout == Layout.RU) Layout.EN else Layout.RU
        KeyboardSettings.preferences(this).edit()
            .putString(KeyboardSettings.KEY_LAYOUT, if (layout == Layout.EN) "en" else "ru").apply()
        shifted = false
        renderKeyboard()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
