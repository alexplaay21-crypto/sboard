package com.sboard.app.feature.autofill

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sboard.app.R
import com.sboard.app.core.i18n.SBoardI18n
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.data.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Save password to sPass?" after a password was typed with the sBoard keyboard in an app where
 * Android autofill did not ask (Telegram, Google sign-in, games...). Shown as a notification; the
 * password itself stays in memory ([PendingSaves], max. five minutes) and is never shown in it.
 */
object KeyboardSaveOffer {
    private const val CHANNEL = "sboard_save_password"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Packages for which Android autofill just asked to save (avoids two prompts). */
    private val autofillAsked = HashMap<String, Long>()

    @Synchronized fun markAutofillSaveRequested(packageName: String?) { if (packageName != null) autofillAsked[packageName] = System.currentTimeMillis() }
    @Synchronized private fun autofillAskedRecently(packageName: String) = System.currentTimeMillis() - (autofillAsked[packageName] ?: 0L) < 90_000

    fun offer(context: Context, packageName: String, username: String, password: String) {
        if (autofillAskedRecently(packageName)) return
        val app = context.applicationContext
        scope.launch {
            SBoardI18n.initialize(app)
            // Already saved? (only checkable while sPass is open)
            if (VaultSessionProvider.session.isUnlocked) {
                val known = VaultRepository.get(app).snapshot().any { item ->
                    val login = item.payload.login ?: return@any false
                    login.password == password && (packageName in login.packageNames || login.username.equals(username, true))
                }
                if (known) return@launch
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@launch
            val token = PendingSaves.put(PendingSaves.Entry(username = username, password = password, webDomain = null, packageName = packageName))
            val manager = app.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, tr("Сохранение паролей"), NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false) })
            val label = runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
            val open = PendingIntent.getActivity(app, token.hashCode(), AutofillSaveActivity.intent(app, token),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_autofill_key)
                .setContentTitle(tr("Сохранить пароль в sPass?"))
                .setContentText(if (username.isNotBlank()) "$label · $username" else label)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setTimeoutAfter(5 * 60_000L)
                .setContentIntent(open)
                .addAction(0, tr("Сохранить"), open)
                .build()
            runCatching { NotificationManagerCompat.from(app).notify(4711, notification) }
        }
    }
}
