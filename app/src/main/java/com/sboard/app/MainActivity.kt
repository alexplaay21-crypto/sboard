package com.sboard.app

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.feature.sync.SyncScheduler
import com.sboard.app.feature.vault.VaultKeyManager
import com.sboard.app.ui.Navigator
import com.sboard.app.ui.Route
import com.sboard.app.ui.SecureActivity
import com.sboard.app.ui.Tab
import com.sboard.app.ui.components.FloatingNavBar
import com.sboard.app.ui.screens.*
import com.sboard.app.ui.theme.SBoardTheme
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch

class MainActivity : SecureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { SBoardTheme { AppRoot() } }
        // Needed for the "save password to sPass?" offer; asked once.
        val prefs = getSharedPreferences("sboard_settings", MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notifications", false) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The keyboard's settings key opens sBoard directly on the Keyboard tab. */
    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.getStringExtra("sboard.open_tab") == "keyboard") pendingTab.value = Tab.KEYBOARD
        // The keyboard's microphone key asks for the permission through the app (IMEs cannot).
        if (intent?.getBooleanExtra("sboard.request_mic", false) == true &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Back to the app that was being typed in; the next tap on the microphone starts dictation.
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) moveTaskToBack(true)
    }

    companion object {
        val pendingTab = mutableStateOf<Tab?>(null)
        private const val REQUEST_MIC = 41
    }

    override fun onStart() {
        super.onStart()
        if (VaultSessionProvider.session.isUnlocked) SyncScheduler.requestSoon(applicationContext)
        lifecycleScope.launch { runCatching { org.sboard.keyboard.SBoardKeyboardSettings.ensureVaultAction() } }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val unlocked by VaultSessionProvider.session.unlocked.collectAsState()
    // null = loading, true = first run (no Vault on this device yet), false = Vault exists.
    var onboarding by remember { mutableStateOf<Boolean?>(null) }
    var startupError by remember { mutableStateOf<String?>(null) }
    // Interface in the system language: download its language pack once if it is not built in.
    LaunchedEffect(com.sboard.app.core.i18n.SBoardI18n.currentMode()) {
        com.sboard.app.core.i18n.SBoardI18n.missingPack()?.let { lang ->
            if (lang in com.sboard.app.feature.tools.Translator.CODES) runCatching { com.sboard.app.core.i18n.LanguagePacks.download(context, lang) }
        }
    }
    LaunchedEffect(Unit) {
        runCatching { VaultKeyManager(context).hasVault() }
            .onSuccess { onboarding = !it }
            .onFailure { startupError = it.message ?: it.javaClass.simpleName }
    }
    val navigator = remember { Navigator() }

    val top = if (palette.isDark) androidx.compose.ui.graphics.lerp(palette.background, palette.accent, 0.10f) else androidx.compose.ui.graphics.lerp(palette.background, palette.accent, 0.06f)
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(top, palette.background, palette.background)))) {
        when {
            startupError != null -> Box(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
                com.sboard.app.ui.components.Banner(com.sboard.app.core.i18n.tr("Не удалось открыть хранилище: {0}", startupError), com.sboard.app.ui.components.Tone.DANGER)
            }
            onboarding == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = palette.accent) }
            onboarding == true -> OnboardingFlow(onFinished = { onboarding = false })
            // The app opens without a password; sPass and Profile ask for it (see MainShell).
            else -> MainShell(navigator)
        }
    }
}

/** Screens that show Vault contents or account/security settings need the unlocked Vault. */
private fun needsUnlock(state: Any): Boolean = when (state) {
    Tab.VAULT, Tab.PROFILE -> true
    is Route.ItemDetail, is Route.ItemEdit, is Route.ItemHistory -> true
    Route.Trash, Route.Security, Route.Sync, Route.Devices, Route.PasswordHealth -> true
    else -> false
}

@Composable
private fun MainShell(nav: Navigator) {
    val requested by MainActivity.pendingTab
    LaunchedEffect(requested) { requested?.let { nav.select(it); MainActivity.pendingTab.value = null } }
    BackHandler(enabled = nav.stack.isNotEmpty() || nav.tab != Tab.HOME) {
        if (!nav.pop()) nav.select(Tab.HOME)
    }
    Box(Modifier.fillMaxSize()) {
        val target: Any = nav.current ?: nav.tab
        AnimatedContent(
            targetState = target,
            transitionSpec = {
                val forward = targetState is Route && (initialState is Tab || nav.stack.size > 0)
                if (targetState is Tab && initialState is Tab) {
                    (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.985f)) togetherWith fadeOut(tween(140))
                } else if (forward) {
                    (slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(260)) { -it / 10 } + fadeOut(tween(160)))
                } else {
                    (slideInHorizontally(tween(260)) { -it / 10 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(260)) { it / 6 } + fadeOut(tween(160)))
                }
            },
            label = "nav",
            modifier = Modifier.fillMaxSize(),
        ) { state ->
            val unlockedNow by VaultSessionProvider.session.unlocked.collectAsState()
            if (!unlockedNow && needsUnlock(state)) {
                Box(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = 90.dp), contentAlignment = Alignment.Center) {
                    UnlockPanel(title = com.sboard.app.core.i18n.tr("sPass"),
                        subtitle = com.sboard.app.core.i18n.tr("Подтвердите, что это вы, чтобы открыть пароли и профиль"),
                        onUnlocked = {})
                }
            } else when (state) {
                Tab.HOME -> HomeScreen(nav)
                Tab.KEYBOARD -> KeyboardScreen(nav)
                Tab.VAULT -> VaultScreen(nav)
                Tab.TOOLS -> ToolsScreen(nav)
                Tab.PROFILE -> ProfileScreen(nav)
                is Route.ItemDetail -> ItemDetailScreen(nav, state.id)
                is Route.ItemEdit -> ItemEditScreen(nav, state.id, state.type, state.prefillTotpUri)
                is Route.ItemHistory -> ItemHistoryScreen(nav, state.id)
                Route.Trash -> TrashScreen(nav)
                Route.Generator -> GeneratorScreen(nav)
                Route.Strength -> StrengthScreen(nav)
                Route.Translator -> TranslatorScreen(nav)
                Route.Clipboard -> ClipboardScreen(nav)
                Route.Security -> SecurityScreen(nav)
                Route.Sync -> SyncScreen(nav)
                Route.Devices -> DevicesScreen(nav)
                Route.Appearance -> AppearanceScreen(nav)
                Route.About -> AboutScreen(nav)
                Route.PasswordHealth -> PasswordHealthScreen(nav)
                Route.KeyboardTheme -> KeyboardThemeEditor(nav)
                is Route.KeyboardSection -> KeyboardSectionScreen(nav, state.id)
                Route.AppLanguage -> AppLanguageScreen(nav)
            }
        }
        AnimatedVisibility(
            visible = nav.stack.isEmpty(),
            enter = slideInVertically(tween(260)) { it } + fadeIn(),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            FloatingNavBar(nav.tab) { nav.select(it) }
        }
    }
}
