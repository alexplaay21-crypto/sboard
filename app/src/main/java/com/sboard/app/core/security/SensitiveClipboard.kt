package com.sboard.app.core.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import com.sboard.app.core.crypto.Sha256
import com.sboard.app.core.crypto.constantTimeEquals
import com.sboard.app.core.settings.AppSettings

/**
 * Copies user-requested values. Secrets are flagged as sensitive (hidden from clipboard previews
 * and keyboard history on Android 13+) and wiped after the configured timeout if still present.
 */
object SensitiveClipboard {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingClear: Runnable? = null

    fun copy(context: Context, text: String, sensitive: Boolean) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(if (sensitive) "sBoard secret" else "sBoard", text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        pendingClear?.let(handler::removeCallbacks)
        val seconds = AppSettings.clipboardClearSeconds.value
        if (sensitive && seconds > 0) {
            val fingerprint = Sha256.of(text.toByteArray())
            val app = context.applicationContext
            val runnable = Runnable { clearIfUnchanged(app, fingerprint) }
            pendingClear = runnable
            handler.postDelayed(runnable, seconds * 1000L)
        }
    }

    fun clearNow(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
        else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    private fun clearIfUnchanged(context: Context, fingerprint: ByteArray) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val current = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        // If Android hides the clipboard from us (background), clear anyway: we placed the secret there.
        if (current == null || constantTimeEquals(Sha256.of(current.toByteArray()), fingerprint)) clearNow(context)
    }
}
