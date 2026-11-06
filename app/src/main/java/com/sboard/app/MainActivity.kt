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
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The keyboard's settings key opens sBoard directly on the Keyboard tab. */
    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.getStringExtra("sboard.open_tab") == "keyboard") pendingTab.value = Tab.KEYBOARD
    }

    companion object {
        val pendingTab = mutableStateOf<Tab?>(null)
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
    LaunchedEffect(Unit) {
        runCatching { VaultKeyManager(context).hasVault() }
            .onSuccess { onboarding = !it }
            .onFailure { startupError = it.message ?: it.javaClass.simpleName }
    }
    val navigator = remember { Navigator() }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        when {
            startupError != null -> Box(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
                com.sboard.app.ui.components.Banner(com.sboard.app.core.i18n.tr("Не удалось открыть хранилище: {0}", startupError), com.sboard.app.ui.components.Tone.DANGER)
            }
            onboarding == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = palette.accent) }
            onboarding == true -> OnboardingFlow(onFinished = { onboarding = false })
            !unlocked -> Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
                UnlockPanel(onUnlocked = {})
            }
            else -> MainShell(navigator)
        }
    }
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
            when (state) {
                Tab.HOME -> HomeScreen(nav)
                Tab.KEYBOARD -> KeyboardScreen()
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
