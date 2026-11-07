package org.sboard.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * Samsung-Pass-style "save password?" for apps where Android autofill does not ask (Telegram,
 * Gmail/Google sign-in and others): when a password was typed with the sBoard keyboard and the
 * login field is left, the app offers to save it. The typed values live only in memory, are never
 * logged and are discarded after the offer (or after five minutes).
 */
object SBoardPasswordCapture {
    private class Field(val packageName: String, val password: Boolean, val usernameLike: Boolean)

    private var current: Field? = null
    private var password: String = ""
    private var username: String = ""
    private var usernamePackage: String? = null
    private var usernameAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var pendingPackage: String? = null

    @Volatile var enabled: Boolean = true

    fun load(context: Context) {
        enabled = context.getSharedPreferences("sboard_keyboard_setup", Context.MODE_PRIVATE).getBoolean("offer_save_passwords", true)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences("sboard_keyboard_setup", Context.MODE_PRIVATE).edit().putBoolean("offer_save_passwords", value).apply()
    }

    internal fun isPassword(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun isUsernameLike(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_NORMAL, 0)) ||
            cls == InputType.TYPE_CLASS_PHONE
    }

    fun onStartInput(context: Context, info: EditorInfo?) {
        val pkg = info?.packageName ?: return
        // Back in the same app's password field: the user is still typing, no offer yet.
        if (isPassword(info.inputType) && pending != null && pendingPackage == pkg) {
            pending?.let { handler.removeCallbacks(it) }; pending = null
        }
        current = if (!enabled || pkg == context.packageName) null else Field(pkg, isPassword(info.inputType), isUsernameLike(info.inputType))
        if (current?.password == true) password = ""
    }

    /** Called on every cursor/text change: remembers the field's text in memory. */
    fun onUpdate(ic: InputConnection?) {
        val f = current ?: return
        if (!f.password && !f.usernameLike) return
        val text = ic?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString() ?: return
        if (text.length > 120) return
        if (f.password) password = text
        else if (text.isNotBlank()) { username = text.trim(); usernamePackage = f.packageName; usernameAt = System.currentTimeMillis() }
    }

    /** Leaving a field: after a typed password, offer to save shortly (cancelled if the user comes back). */
    fun onFinish(context: Context) {
        val f = current ?: return
        current = null
        if (!f.password || password.length < 4) return
        val pass = password
        val user = if (usernamePackage == f.packageName && System.currentTimeMillis() - usernameAt < 5 * 60_000) username else ""
        password = ""
        pending?.let { handler.removeCallbacks(it) }
        val app = context.applicationContext
        val task = Runnable {
            pending = null
            SBoardVaultBridge.provider?.offerPasswordSave(app, f.packageName, user, pass)
        }
        pending = task
        pendingPackage = f.packageName
        handler.postDelayed(task, 1500)
    }
}
