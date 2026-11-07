package org.sboard.keyboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.florisboard.lib.snygg.ui.SnyggChip
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText
import java.util.Locale

/**
 * Voice input for the microphone key (bottom row, like Samsung Keyboard).
 *
 * Engines:
 * - "google": switches to Google voice typing (or any installed voice IME) — best quality.
 * - "builtin": dictation inside sBoard via the system [SpeechRecognizer] (on most phones this is
 *   Google's or Samsung's recognition service). Text appears live while speaking.
 * - "auto" (default): Google voice typing when it is installed and enabled, otherwise built-in.
 */
object SBoardVoice {
    data class State(
        val active: Boolean = false,
        val listening: Boolean = false,
        val partial: String = "",
        val message: String = "",
        val level: Float = 0f,
        val language: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var appContext: Context? = null

    private fun prefs(context: Context) = context.getSharedPreferences("sboard_keyboard_setup", Context.MODE_PRIVATE)

    /** "auto", "google" or "builtin". */
    fun engine(context: Context): String = prefs(context).getString("voice_engine", "auto") ?: "auto"
    fun setEngine(context: Context, value: String) { prefs(context).edit().putString("voice_engine", value).apply() }

    /** Installed + enabled voice input method (Google voice typing etc.), if any. */
    fun voiceImeAvailable(context: Context): Boolean {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { info ->
            (0 until info.subtypeCount).any { info.getSubtypeAt(it).mode == "voice" }
        }
    }

    /** Whether the microphone key is shown next to the space bar. */
    fun keyVisible(context: Context): Boolean = prefs(context).getBoolean("voice_key", true)
    fun setKeyVisible(context: Context, value: Boolean) { prefs(context).edit().putBoolean("voice_key", value).apply() }

    fun builtinAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun hasMicPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun onVoiceKey(context: Context) {
        if (_state.value.active) { stop(); return }
        val engine = engine(context)
        val useIme = when (engine) {
            "google" -> true
            "builtin" -> false
            else -> voiceImeAvailable(context)
        }
        if (useIme && voiceImeAvailable(context) && FlorisImeService.switchToVoiceInputMethod()) return
        start(context)
    }

    private fun t(key: String): String {
        val ru = Locale.getDefault().language == "ru"
        return when (key) {
            "listening" -> if (ru) "Говорите…" else "Speak now…"
            "no_service" -> if (ru) "Распознавание речи недоступно. Установите приложение Google" else "Speech recognition is unavailable. Install the Google app"
            "permission" -> if (ru) "Разрешите доступ к микрофону в sBoard" else "Allow microphone access for sBoard"
            "no_match" -> if (ru) "Не расслышал — нажмите ↻ и повторите" else "Didn't catch that — tap ↻ and try again"
            "network" -> if (ru) "Нет сети для распознавания речи" else "No network for speech recognition"
            "busy" -> if (ru) "Распознавание занято, попробуйте ещё раз" else "Recognizer busy, try again"
            else -> if (ru) "Ошибка распознавания речи" else "Speech recognition error"
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        val language = runCatching { app.subtypeManager().value.activeSubtype.primaryLocale.languageTag() }.getOrNull()
            ?: Locale.getDefault().toLanguageTag()
        if (!builtinAvailable(app)) {
            _state.value = State(active = true, message = t("no_service"), language = language); return
        }
        if (!hasMicPermission(app)) {
            _state.value = State(active = true, message = t("permission"), language = language)
            val intent = Intent().setClassName(app.packageName, "com.sboard.app.MainActivity")
                .putExtra("sboard.request_mic", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { app.startActivity(intent) }
            return
        }
        recognizer?.destroy()
        val sr = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = sr
        _state.value = State(active = true, listening = true, message = t("listening"), language = language)
        sr.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        runCatching { sr.startListening(intent) }.onFailure {
            _state.value = _state.value.copy(listening = false, message = t("error"))
        }
    }

    /** Ends dictation; text already recognised stays in the field. */
    fun stop() {
        val had = _state.value.active
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        if (had) FlorisImeService.currentInputConnection()?.finishComposingText()
        _state.value = State()
    }

    private fun commitFinal(text: String) {
        val ic = FlorisImeService.currentInputConnection() ?: return
        if (text.isBlank()) { ic.finishComposingText(); return }
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        ic.beginBatchEdit()
        ic.setComposingText("", 1)
        ic.commitText(prefix + text, 1)
        ic.endBatchEdit()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { _state.value = _state.value.copy(listening = true, message = t("listening")) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { _state.value = _state.value.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { _state.value = _state.value.copy(listening = false, level = 0f) }
        override fun onError(error: Int) {
            FlorisImeService.currentInputConnection()?.finishComposingText()
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> t("no_match")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> t("network")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> t("permission")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> t("busy")
                else -> t("error")
            }
            _state.value = _state.value.copy(listening = false, partial = "", level = 0f, message = msg)
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            commitFinal(text)
            _state.value = _state.value.copy(partial = "")
            // Continuous dictation: keep listening until the user taps stop.
            appContext?.let { if (_state.value.active) start(it) }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) return
            _state.value = _state.value.copy(partial = text)
            val ic = FlorisImeService.currentInputConnection() ?: return
            ic.setComposingText(text, 1)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

/** Dictation bar shown above the keys while built-in voice input is active. */
@Composable
fun VoiceBar() {
    val state by SBoardVoice.state.collectAsState()
    val pulse by animateFloatAsState(targetValue = 1f + state.level * 0.35f, label = "micPulse")
    SnyggRow(FlorisImeUi.ClipboardHeader.elementName,
        modifier = Modifier.fillMaxWidth().heightIn(min = FlorisImeSizing.smartbarHeight),
        verticalAlignment = Alignment.CenterVertically) {
        val size = Modifier.sizeIn(maxHeight = FlorisImeSizing.smartbarHeight).aspectRatio(1f)
        SnyggIcon(imageVector = Icons.Default.KeyboardVoice, modifier = Modifier.padding(start = 12.dp, end = 6.dp).scale(if (state.listening) pulse else 1f))
        SnyggChip(FlorisImeUi.ClipboardFilterChip.elementName, onClick = {}, text = state.language.substringBefore('-').uppercase())
        SnyggText(FlorisImeUi.ClipboardHeaderText.elementName,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            text = state.partial.ifBlank { state.message })
        if (!state.listening) {
            val context = androidx.compose.ui.platform.LocalContext.current
            SnyggIconButton(FlorisImeUi.ClipboardHeaderButton.elementName, modifier = size, onClick = { SBoardVoice.start(context) }) {
                SnyggIcon(imageVector = Icons.Default.Refresh)
            }
        }
        SnyggIconButton(FlorisImeUi.ClipboardHeaderButton.elementName, modifier = size, onClick = { SBoardVoice.stop() }) {
            SnyggIcon(imageVector = Icons.Default.Close)
        }
    }
}
