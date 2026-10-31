package com.sboard.app.feature.tools.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/** Writes user-requested content to Android's system clipboard. */
class SBoardClipboardWriter(context: Context) {
    private val clipboard = context.getSystemService(
        Context.CLIPBOARD_SERVICE
    ) as ClipboardManager

    fun copy(text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText("sBoard", text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
    }
}
