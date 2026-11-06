package com.sboard.app.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.sboard.app.core.i18n.SBoardI18n
import com.sboard.app.core.settings.AppSettings

/** Base activity: edge-to-edge, and FLAG_SECURE (no screenshots / recents preview) when enabled. */
abstract class SecureActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppSettings.initialize(this)
        SBoardI18n.initialize(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyScreenSecurity()
    }

    override fun onResume() {
        super.onResume()
        applyScreenSecurity()
    }

    fun applyScreenSecurity() {
        if (AppSettings.screenSecurity.value) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
