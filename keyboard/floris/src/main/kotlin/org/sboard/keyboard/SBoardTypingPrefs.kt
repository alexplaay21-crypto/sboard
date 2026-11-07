package org.sboard.keyboard

import android.content.Context

/** Small sBoard-only typing preferences read on hot paths (cached in memory). */
object SBoardTypingPrefs {
    @Volatile var backspaceSpeed: Int = 100
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("sboard_keyboard_setup", Context.MODE_PRIVATE)

    fun load(context: Context) {
        backspaceSpeed = prefs(context).getInt("backspace_speed", 100)
        SBoardClipboard.excludeCodes = prefs(context).getBoolean("clip_exclude_codes", true)
    }

    fun setExcludeCodes(context: Context, value: Boolean) {
        SBoardClipboard.excludeCodes = value
        prefs(context).edit().putBoolean("clip_exclude_codes", value).apply()
    }

    /** 50 (slow) … 200 (fast) percent. */
    fun setBackspaceSpeed(context: Context, value: Int) {
        backspaceSpeed = value.coerceIn(50, 200)
        prefs(context).edit().putInt("backspace_speed", backspaceSpeed).apply()
    }
}
