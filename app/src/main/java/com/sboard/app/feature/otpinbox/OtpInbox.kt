package com.sboard.app.feature.otpinbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The most recent one-time code, kept only in memory for a few minutes. */
object OtpInbox {
    data class Code(val code: String, val sourceApp: String, val sourcePackage: String, val receivedAt: Long)

    private const val TTL_MS = 10 * 60_000L
    private val _latest = MutableStateFlow<Code?>(null)
    val latest: StateFlow<Code?> = _latest.asStateFlow()

    fun current(now: Long = System.currentTimeMillis()): Code? = _latest.value?.takeIf { now - it.receivedAt < TTL_MS }

    fun put(code: Code) { _latest.value = code }
    fun clear() { _latest.value = null }

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun openAccessSettings(context: Context) {
        val component = ComponentName(context, OtpNotificationListener::class.java).flattenToString()
        val detail = Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
            .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(detail) }.isFailure) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
