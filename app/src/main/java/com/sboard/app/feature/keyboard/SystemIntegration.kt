package com.sboard.app.feature.keyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import android.view.inputmethod.InputMethodManager

/** Reads and opens the Android system settings sBoard integrates with. */
object SystemIntegration {
    fun isKeyboardEnabled(context: Context): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == context.packageName }
    }

    fun isKeyboardSelected(context: Context): Boolean {
        val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        return current.startsWith(context.packageName + "/")
    }

    fun openKeyboardSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun showKeyboardPicker(context: Context) {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    fun isAutofillEnabled(context: Context): Boolean = runCatching {
        context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
    }.getOrDefault(false)

    fun isAutofillSupported(context: Context): Boolean = runCatching {
        context.getSystemService(AutofillManager::class.java)?.isAutofillSupported == true
    }.getOrDefault(false)

    fun openAutofillSettings(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun passkeysSupported(): Boolean = Build.VERSION.SDK_INT >= 34

    /** Android 14+: opens the Passwords, passkeys & accounts screen where providers are enabled. */
    fun openCredentialProviderSettings(context: Context) {
        val intents = listOf(
            Intent("android.settings.CREDENTIAL_PROVIDER").setData(Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
