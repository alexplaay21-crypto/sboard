package com.sboard.app.feature.otpinbox

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.sboard.app.core.i18n.SBoardI18n
import com.sboard.app.core.security.SensitiveClipboard
import com.sboard.app.core.settings.AppSettings

/**
 * Reads incoming notifications (SMS, WhatsApp, Telegram, Gmail, banking apps…) only to detect
 * one-time codes. Message texts are never stored or logged; the detected code is kept in memory
 * for ten minutes, offered by Autofill and the keyboard, and optionally copied to the clipboard.
 * Works only after the user grants notification access in Android settings.
 */
class OtpNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName == packageName) return
        AppSettings.initialize(this); SBoardI18n.initialize(this)
        if (!AppSettings.otpFromNotifications.value) return
        val extras = notification.notification.extras ?: return
        val text = buildString {
            append(extras.getCharSequence(Notification.EXTRA_TITLE) ?: ""); append('\n')
            append(extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT) ?: "")
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull()?.let { append('\n'); append(it) }
        }
        val code = OtpExtractor.extract(text) ?: return
        if (OtpInbox.current()?.code == code) return
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(notification.packageName, 0)).toString() }
            .getOrDefault(notification.packageName)
        OtpInbox.put(OtpInbox.Code(code, label, notification.packageName, System.currentTimeMillis()))
        if (AppSettings.otpAutoCopy.value) SensitiveClipboard.copy(this, code, sensitive = true)
    }
}
