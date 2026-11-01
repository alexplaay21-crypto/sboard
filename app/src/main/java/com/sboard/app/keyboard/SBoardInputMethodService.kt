package com.sboard.app.keyboard

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Typed input stays local. Speech is sent only to the Android recognizer after an explicit tap. */
class SBoardInputMethodService : InputMethodService() {

    private enum class Layout { RU, EN, SYMBOLS, EMOJI }

    private var layout = Layout.RU
    private var alphabetLayout = Layout.RU
    private var shifted = false
    private var symbolPage = false
    private var keyboardRoot: LinearLayout? = null
    private var isPasswordField = false
    private var isNumericField = false
    private var showNumberRow = true
    private var hapticsEnabled = true
    private var lightTheme = false
    private var allowNetworkSpeech = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceStatus: String? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = KeyboardSettings.preferences(this)
        layout = if (prefs.getString(KeyboardSettings.KEY_LAYOUT, "ru") == "en") Layout.EN else Layout.RU
        alphabetLayout = layout
        loadSettings()
    }

    override fun onCreateInputView(): View {
        keyboardRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
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
        if (isPasswordField) releaseSpeechRecognizer(cancel = true)
        renderKeyboard()
    }

    override fun onFinishInput() {
        releaseSpeechRecognizer(cancel = true)
        super.onFinishInput()
    }

    override fun onDestroy() {
        releaseSpeechRecognizer(cancel = true)
        super.onDestroy()
    }

    private fun loadSettings() {
        val prefs = KeyboardSettings.preferences(this)
        showNumberRow = prefs.getBoolean(KeyboardSettings.KEY_NUMBER_ROW, true)
        hapticsEnabled = prefs.getBoolean(KeyboardSettings.KEY_HAPTICS, true)
        allowNetworkSpeech = prefs.getBoolean(KeyboardSettings.KEY_NETWORK_SPEECH, false)
        lightTheme = when (prefs.getString(KeyboardSettings.KEY_THEME, "system")) {
            "light" -> true
            "dark" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_NO
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
        val brand = TextView(this).apply {
            text = if (isPasswordField) "🔒  sBoard" else "sBoard"
            setTextColor(if (lightTheme) Color.rgb(80, 83, 90) else Color.rgb(174, 178, 187))
            setTextSize(12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = if (isPasswordField) getString(com.sboard.app.R.string.ime_private_input) else "sBoard"
        }
        top.addView(brand, LinearLayout.LayoutParams(0, dp(36), 1f))
        top.addView(makeKey(if (alphabetLayout == Layout.EN) "EN" else "RU", KeyStyle.ACCENT, 54) {
            if (layout == Layout.SYMBOLS) {
                alphabetLayout = if (alphabetLayout == Layout.RU) Layout.EN else Layout.RU
            } else {
                layout = if (layout == Layout.RU) Layout.EN else Layout.RU
                alphabetLayout = layout
            }
            saveAlphabetLayout()
            shifted = false
            renderKeyboard()
        })
        if (!isPasswordField && !isNumericField) {
            top.addView(makeKey("📋", KeyStyle.TOOL, 42) { pasteFromClipboard() }.apply {
                contentDescription = getString(com.sboard.app.R.string.ime_paste_from_clipboard)
            })
            val micLabel = if (speechRecognizer == null) "🎙" else "■"
            top.addView(makeKey(micLabel, if (speechRecognizer == null) KeyStyle.TOOL else KeyStyle.ACCENT, 48) {
                if (speechRecognizer == null) startVoiceInput() else stopVoiceInput()
            }.apply {
                contentDescription = getString(if (speechRecognizer == null) com.sboard.app.R.string.ime_voice_input else com.sboard.app.R.string.ime_stop_voice_input)
            })
        }
        root.addView(top)

        voiceStatus?.let { status ->
            val statusView = TextView(this).apply {
                text = status
                setTextColor(if (lightTheme) Color.rgb(75, 78, 85) else Color.rgb(185, 189, 198))
                setTextSize(11f)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                setPadding(dp(9), 0, dp(9), 0)
            }
            root.addView(statusView, LinearLayout.LayoutParams(-1, dp(23)))
        }

        if (isNumericField) {
            listOf("1 2 3", "4 5 6", "7 8 9").forEach { addKeyRow(root, it.split(" ")) }
            val numericBottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            numericBottom.addView(makeKey("−", KeyStyle.TOOL) { commit("-") }, weightParams(1f))
            numericBottom.addView(makeKey("0") { commit("0") }, weightParams(1f))
            numericBottom.addView(makeKey(",", KeyStyle.TOOL) { commit(",") }, weightParams(1f))
            numericBottom.addView(makeKey("⌫", KeyStyle.TOOL) { deleteOne() }, weightParams(1f))
            numericBottom.addView(makeKey("↵", KeyStyle.ACCENT) { performEnter() }, weightParams(1f))
            root.addView(numericBottom)
            return
        }

        if (showNumberRow && layout != Layout.SYMBOLS && layout != Layout.EMOJI) addKeyRow(root, "1 2 3 4 5 6 7 8 9 0".split(" "))

        if (layout == Layout.EMOJI) {
            listOf(
                "😀 😃 😄 😁 😆 😅 😂 🙂 🙃 😉",
                "😊 😍 🥰 😘 😗 😙 😚 😋 😛 😜",
                "😎 🤩 🥳 😭 😢 😡 🤔 🤗 🙏 💯",
                "👍 👎 👏 🙌 🤝 👌 ✌️ 🤟 👀 ❤️",
                "💛 💚 💙 💜 🖤 🤍 ✨ 🔥 🌸 🎉"
            ).forEach { addKeyRow(root, it.split(" ")) { emoji -> commitEmoji(emoji) } }
        } else if (layout == Layout.SYMBOLS) {
            addKeyRow(root, "1 2 3 4 5 6 7 8 9 0".split(" "))
            addKeyRow(root, "@ # $ % & - + ( ) /".split(" "))
            addSymbolRow(root, if (symbolPage) "\\ | < > [ ] { } = ⌫" else "* \" ' : ; ! ? ⌫")
        } else {
            val rows = if (layout == Layout.RU) {
                listOf("й ц у к е н г ш щ з х", "ф ы в а п р о л д ж э", "я ч с м и т ь б ю")
            } else {
                listOf("q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m")
            }
            rows.forEachIndexed { index, row ->
                val keys = row.split(" ").map { if (shifted) it.uppercase(Locale.ROOT) else it }
                if (index == 2) {
                    val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    line.addView(makeKey("⇧", if (shifted) KeyStyle.ACCENT else KeyStyle.TOOL) { shifted = !shifted; renderKeyboard() }, weightParams(1.25f))
                    keys.forEach { key -> line.addView(makeKey(key) { commit(key) }, weightParams(1f)) }
                    line.addView(makeKey("⌫", KeyStyle.TOOL) { deleteOne() }, weightParams(1.25f))
                    root.addView(line)
                } else addKeyRow(root, keys)
            }
        }

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(makeKey(if (layout == Layout.SYMBOLS || layout == Layout.EMOJI) "ABC" else "?123", KeyStyle.TOOL) {
            layout = if (layout == Layout.SYMBOLS || layout == Layout.EMOJI) alphabetLayout else Layout.SYMBOLS
            symbolPage = false
            renderKeyboard()
        }, weightParams(1.2f))
        if (layout == Layout.EMOJI) {
            bottom.addView(makeKey("⌫", KeyStyle.TOOL) { deleteOne() }, weightParams(1f))
            bottom.addView(makeKey(if (alphabetLayout == Layout.EN) "Space" else "Пробел", KeyStyle.LETTER) { commit(" ") }, weightParams(3f))
            bottom.addView(makeKey("↵", KeyStyle.ACCENT) { performEnter() }, weightParams(1.2f))
        } else {
            bottom.addView(makeKey("😊", KeyStyle.TOOL) {
                alphabetLayout = layout
                layout = Layout.EMOJI
                renderKeyboard()
            }, weightParams(0.9f))
            bottom.addView(makeKey("🌐", KeyStyle.TOOL) { toggleLanguage() }, weightParams(0.9f))
            bottom.addView(makeKey(",", KeyStyle.TOOL) { commit(",") }, weightParams(0.7f))
            bottom.addView(makeKey(if (layout == Layout.EN) "Space" else "Пробел") { commit(" ") }, weightParams(3.1f))
            bottom.addView(makeKey(".", KeyStyle.TOOL) { commit(".") }, weightParams(0.7f))
            bottom.addView(makeKey("↵", KeyStyle.ACCENT) { performEnter() }, weightParams(1.1f))
        }
        root.addView(bottom)
    }

    private enum class KeyStyle { LETTER, TOOL, ACCENT }

    private fun addKeyRow(root: LinearLayout, keys: List<String>, onKey: (String) -> Unit = { commit(it) }) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.forEach { key -> row.addView(makeKey(key) { onKey(key) }, weightParams(1f)) }
        root.addView(row)
    }

    private fun addSymbolRow(root: LinearLayout, keys: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.split(" ").forEach { key ->
            if (key == "⌫") row.addView(makeKey(key, KeyStyle.TOOL) { deleteOne() }, weightParams(1f))
            else row.addView(makeKey(key) { commit(key) }, weightParams(1f))
        }
        root.addView(row)
    }

    private fun makeKey(
        label: String,
        style: KeyStyle = KeyStyle.LETTER,
        widthDp: Int? = null,
        action: () -> Unit
    ): TextView {
        val fillColor = when (style) {
            KeyStyle.ACCENT -> Color.rgb(213, 185, 75)
            KeyStyle.TOOL -> if (lightTheme) Color.rgb(220, 223, 229) else Color.rgb(57, 60, 67)
            KeyStyle.LETTER -> if (lightTheme) Color.WHITE else Color.rgb(45, 48, 54)
        }
        val textColor = if (style == KeyStyle.ACCENT || lightTheme) Color.rgb(29, 31, 35) else Color.rgb(245, 245, 247)
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(if (style == KeyStyle.LETTER) 10 else 11).toFloat()
            setColor(fillColor)
        }
        val ripple = RippleDrawable(
            ColorStateList.valueOf(if (lightTheme) 0x22000000 else 0x33FFFFFF),
            shape,
            null
        )
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextSize(if (label.length > 4) 12f else if (label.length > 1) 14f else 18f)
            setTextColor(textColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = ripple
            isClickable = true
            isFocusable = true
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(1), 0, dp(1), 0)
            contentDescription = label
            layoutParams = LinearLayout.LayoutParams(widthDp?.let(::dp) ?: 0, dp(keyHeightDp()), if (widthDp == null) 1f else 0f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            setOnClickListener {
                if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                action()
            }
        }
    }

    private fun keyHeightDp() = if (showNumberRow && !isNumericField && layout != Layout.SYMBOLS) 39 else 43

    private fun weightParams(weight: Float) = LinearLayout.LayoutParams(0, dp(keyHeightDp()), weight).apply {
        setMargins(dp(2), dp(2), dp(2), dp(2))
    }

    private fun startVoiceInput() {
        if (isPasswordField || currentInputConnection == null) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            voiceStatus = getString(com.sboard.app.R.string.ime_grant_microphone_in_app)
            renderKeyboard()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else if (allowNetworkSpeech && SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        } else {
            voiceStatus = getString(com.sboard.app.R.string.ime_offline_speech_unavailable)
            renderKeyboard()
            return
        }

        val recognizer = speechRecognizer ?: return
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                voiceStatus = getString(com.sboard.app.R.string.ime_listening)
                renderKeyboard()
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                voiceStatus = getString(com.sboard.app.R.string.ime_recognizing)
                renderKeyboard()
            }
            override fun onError(error: Int) {
                voiceStatus = getString(com.sboard.app.R.string.ime_speech_failed)
                releaseSpeechRecognizer(cancel = false)
                renderKeyboard()
            }
            override fun onResults(results: Bundle?) {
                val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!phrase.isNullOrBlank() && !isPasswordField) {
                    currentInputConnection?.commitText(phrase, 1)
                    voiceStatus = null
                } else {
                    voiceStatus = getString(com.sboard.app.R.string.ime_no_speech_result)
                }
                releaseSpeechRecognizer(cancel = false)
                renderKeyboard()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val phrase = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!phrase.isNullOrBlank()) {
                    voiceStatus = "🎙 $phrase"
                    renderKeyboard()
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (alphabetLayout == Layout.EN) "en-US" else "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (speechRecognizer != null && allowNetworkSpeech &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this@SBoardInputMethodService))) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        voiceStatus = getString(com.sboard.app.R.string.ime_listening)
        renderKeyboard()
        runCatching { recognizer.startListening(speechIntent) }.onFailure {
            releaseSpeechRecognizer(cancel = true)
            voiceStatus = getString(com.sboard.app.R.string.ime_speech_failed)
            renderKeyboard()
        }
    }

    private fun stopVoiceInput() {
        voiceStatus = getString(com.sboard.app.R.string.ime_recognizing)
        renderKeyboard()
        runCatching { speechRecognizer?.stopListening() }.onFailure {
            releaseSpeechRecognizer(cancel = true)
            voiceStatus = getString(com.sboard.app.R.string.ime_speech_failed)
            renderKeyboard()
        }
    }

    private fun releaseSpeechRecognizer(cancel: Boolean) {
        val recognizer = speechRecognizer ?: return
        speechRecognizer = null
        runCatching { if (cancel) recognizer.cancel() }
        runCatching { recognizer.destroy() }
    }

    private fun commitEmoji(value: String) {
        currentInputConnection?.commitText(value, 1)
        layout = alphabetLayout
        renderKeyboard()
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val value = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        if (value.isNotEmpty()) currentInputConnection?.commitText(value, 1)
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
        if (handled != true) {
            val connection = currentInputConnection ?: return
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun toggleLanguage() {
        alphabetLayout = if (alphabetLayout == Layout.RU) Layout.EN else Layout.RU
        if (layout != Layout.SYMBOLS) layout = alphabetLayout
        saveAlphabetLayout()
        shifted = false
        renderKeyboard()
    }

    private fun saveAlphabetLayout() {
        KeyboardSettings.preferences(this).edit()
            .putString(KeyboardSettings.KEY_LAYOUT, if (alphabetLayout == Layout.EN) "en" else "ru").apply()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
