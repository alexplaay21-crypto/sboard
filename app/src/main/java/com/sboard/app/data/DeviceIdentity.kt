package com.sboard.app.data

import android.content.Context
import android.os.Build
import java.util.UUID

/** Random per-installation identifier used only to label sync changes and the device list. */
object DeviceIdentity {
    private const val PREFS = "sboard_device"

    fun id(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val created = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", created).apply()
        return created
    }

    fun displayName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()
    }
}
