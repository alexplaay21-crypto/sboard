package com.sboard.app.keyboard

import android.content.Context

/** Preferences shared by the sBoard settings screen and its system IME. */
object KeyboardSettings {
    private const val PREFS = "sboard_keyboard"
    const val KEY_LAYOUT = "layout"
    const val KEY_NUMBER_ROW = "number_row"
    const val KEY_HAPTICS = "haptics"
    const val KEY_THEME = "theme"
    const val KEY_NETWORK_SPEECH = "network_speech_allowed"

    fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
