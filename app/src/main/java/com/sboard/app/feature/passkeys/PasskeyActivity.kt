package com.sboard.app.feature.passkeys

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.data.VaultRepository
import com.sboard.app.feature.vault.AuthResult
import com.sboard.app.feature.vault.Biometrics
import com.sboard.app.ui.SecureActivity
import com.sboard.app.ui.components.*
import com.sboard.app.ui.screens.UnlockPanel
import com.sboard.app.ui.theme.SBoardTheme
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Unlock, user verification, passkey creation and sign-in for the Credential Manager. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyActivity : SecureActivity() {
    private val resolver = CallingAppOriginResolver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE)
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
        setContent {
            SBoardTheme {
                val unlocked by VaultSessionProvider.session.unlocked.collectAsState()
                var verifiedByUnlock by remember { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                Box(Modifier.fillMaxSize().background(palette.background).systemBarsPadding(), contentAlignment = Alignment.Center) {
                    if (!unlocked) {
                        UnlockPanel(title = tr("Passkey в sBoard"), subtitle = tr("Разблокируйте хранилище, чтобы продолжить"),
                            onUnlocked = { verifiedByUnlock = true })
                    } else when (mode) {
                        MODE_UNLOCK -> LaunchedEffect(Unit) { finishUnlock() }
                        MODE_GET -> ConfirmCard(
                            title = tr("Вход с passkey"),
                            text = describeGet(itemId),
                            action = tr("Войти"), busy = busy, error = error,
                            onCancel = { cancelGet() },
                        ) {
                            lifecycleScope.launch {
                                busy = true; error = null
                                error = runCatching { completeGet(itemId!!, verifiedByUnlock) }.exceptionOrNull()?.message
                                busy = false
                            }
                        }
                        MODE_CREATE -> ConfirmCard(
                            title = tr("Создать passkey"),
                            text = describeCreate(),
                            action = tr("Создать"), busy = busy, error = error,
                            onCancel = { cancelCreate() },
                        ) {
                            lifecycleScope.launch {
                                busy = true; error = null
                                error = runCatching { completeCreate(verifiedByUnlock) }.exceptionOrNull()?.message
                                busy = false
                            }
                        }
                        MODE_CREATE_PASSWORD -> ConfirmCard(
                            title = tr("Сохранить пароль?"),
                            text = describeCreatePassword(),
                            action = tr("Сохранить"), busy = busy, error = error,
                            onCancel = { cancelCreate() },
                        ) {
                            lifecycleScope.launch {
                                busy = true; error = null
                                error = runCatching { completeCreatePassword() }.exceptionOrNull()?.message
                                busy = false
                            }
                        }
                        MODE_GET_PASSWORD -> ConfirmCard(
                            title = tr("Вход с паролем из sBoard"),
                            text = describeGetPassword(itemId),
                            action = tr("Войти"), busy = busy, error = error,
                            onCancel = { cancelGet() },
                        ) {
                            lifecycleScope.launch {
                                busy = true; error = null
                                error = runCatching { completeGetPassword(itemId!!, verifiedByUnlock) }.exceptionOrNull()?.message
                                busy = false
                            }
                        }
                        else -> LaunchedEffect(Unit) { finish() }
                    }
                }
            }
        }
    }

    @Composable
    private fun ConfirmCard(title: String, text: String, action: String, busy: Boolean, error: String?, onCancel: () -> Unit, onConfirm: () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Rounded.Fingerprint, size = 64.dp)
            Text(title, style = MaterialTheme.typography.headlineSmall, color = palette.text)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.textMuted)
            AnimatedBanner(error, Tone.DANGER)
            PrimaryButton(action, onConfirm, loading = busy)
            SecondaryButton(tr("Отмена"), onCancel, enabled = !busy)
        }
    }

    private fun describeGet(itemId: String?): String {
        val item = VaultRepository.get(this).items.value.firstOrNull { it.id == itemId }?.payload?.passkey ?: return tr("Подтвердите вход с passkey")
        return tr("Войти на {0} как {1}?", item.rpId, item.userName.ifBlank { item.userDisplayName })
    }

    private fun describeCreate(): String {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)?.callingRequest as? CreatePublicKeyCredentialRequest
            ?: return tr("Неверный запрос")
        val json = runCatching { JSONObject(request.requestJson) }.getOrNull() ?: return tr("Неверный запрос")
        val rp = json.optJSONObject("rp")?.optString("id").orEmpty()
        val user = json.optJSONObject("user")?.optString("name").orEmpty()
        return tr("Сохранить passkey для {0} ({1}) в хранилище sBoard? Он будет доступен на всех ваших устройствах после синхронизации.", rp, user)
    }

    /** User verification: unlocking just now counts; otherwise ask for biometrics / screen lock. */
    private suspend fun verifyUser(alreadyVerified: Boolean): Boolean {
        if (alreadyVerified) return true
        return when (val r = Biometrics.confirm(this, tr("Подтвердите passkey"), tr("sBoard использует ваш passkey только после подтверждения"))) {
            AuthResult.Success -> true
            AuthResult.Cancelled -> false
            is AuthResult.Failed -> throw IllegalStateException(r.message)
        }
    }

    private suspend fun completeGet(itemId: String, alreadyVerified: Boolean) {
        val request: ProviderGetCredentialRequest = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
            ?: throw IllegalStateException(tr("Неверный запрос"))
        val option = request.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().firstOrNull()
            ?: throw IllegalStateException(tr("Неверный запрос"))
        val item = VaultRepository.get(this).get(itemId) ?: throw IllegalStateException(tr("Passkey не найден"))
        val rpId = JSONObject(option.requestJson).optString("rpId")
        require(item.payload.passkey?.rpId == WebAuthnOriginPolicy.normalizeRpId(rpId)) { tr("Passkey не подходит для этого сайта") }
        val origin = resolver.resolveVerified(request.callingAppInfo, rpId, PrivilegedApps.allowlist(this))
        if (!verifyUser(alreadyVerified)) return
        val assertion = PasskeyStore(this).assert(item, option.requestJson, origin, option.clientDataHash)
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(result, GetCredentialResponse(PublicKeyCredential(assertion.toCredentialResponseJson())))
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private suspend fun completeCreate(alreadyVerified: Boolean) {
        val request: ProviderCreateCredentialRequest = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
            ?: throw IllegalStateException(tr("Неверный запрос"))
        val create = request.callingRequest as? CreatePublicKeyCredentialRequest ?: throw IllegalStateException(tr("Неверный запрос"))
        val rpId = JSONObject(create.requestJson).optJSONObject("rp")?.optString("id").orEmpty()
        val origin = resolver.resolveVerified(request.callingAppInfo, rpId, PrivilegedApps.allowlist(this))
        val validated = WebAuthnRegistrationPolicy.validate(create.requestJson, rpId, origin)
        if (PasskeyStore(this).hasExcluded(validated.rpId, validated.excludedCredentialIds)) {
            val result = Intent()
            PendingIntentHandler.setCreateCredentialException(result,
                CreatePublicKeyCredentialDomException(InvalidStateError(), tr("Passkey для этого аккаунта уже сохранён в sBoard")))
            setResult(Activity.RESULT_OK, result); finish(); return
        }
        if (!verifyUser(alreadyVerified)) return
        val registration = WebAuthnRegistrationEngine.create(create.requestJson, validated.rpId, origin, userVerified = true)
        PasskeyStore(this).saveRegistration(registration)
        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(result, CreatePublicKeyCredentialResponse(registration.toCredentialResponseJson()))
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun callerLabel(pkg: String?): String = pkg?.let {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrDefault(it)
    }.orEmpty()

    private fun describeCreatePassword(): String {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: return tr("Неверный запрос")
        val create = request.callingRequest as? androidx.credentials.CreatePasswordRequest ?: return tr("Неверный запрос")
        return tr("Сохранить пароль для {0} ({1}) в хранилище sBoard?", callerLabel(request.callingAppInfo.packageName), create.id)
    }

    private fun describeGetPassword(itemId: String?): String {
        val item = VaultRepository.get(this).items.value.firstOrNull { it.id == itemId } ?: return tr("Подтвердите вход")
        return tr("Войти как {0}?", item.payload.login?.username.orEmpty().ifBlank { item.title })
    }

    private suspend fun completeCreatePassword() {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: throw IllegalStateException(tr("Неверный запрос"))
        val create = request.callingRequest as? androidx.credentials.CreatePasswordRequest ?: throw IllegalStateException(tr("Неверный запрос"))
        val caller = request.callingAppInfo
        val originHost = runCatching { caller.getOrigin(PrivilegedApps.allowlist(this)) }.getOrNull()
            ?.let { com.sboard.app.data.DomainMatcher.hostOf(it) }
        val pkg = caller.packageName.takeIf { originHost == null }
        val repo = VaultRepository.get(this)
        val existing = repo.snapshot().firstOrNull { item ->
            val l = item.payload.login ?: return@firstOrNull false
            l.username.equals(create.id, true) && ((pkg != null && pkg in l.packageNames) ||
                (originHost != null && l.urls.any { com.sboard.app.data.DomainMatcher.matches(it, originHost) }))
        }
        if (existing != null) {
            repo.save(existing.id, existing.payload.copy(login = existing.payload.login!!.copy(password = create.password, passwordChangedAt = System.currentTimeMillis())))
        } else {
            repo.save(null, com.sboard.app.data.VaultItemPayload(
                type = com.sboard.app.data.ItemType.LOGIN,
                title = originHost ?: callerLabel(pkg).ifBlank { create.id },
                login = com.sboard.app.data.LoginData(username = create.id, password = create.password,
                    urls = listOfNotNull(originHost), packageNames = listOfNotNull(pkg), passwordChangedAt = System.currentTimeMillis()),
            ))
        }
        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(result, androidx.credentials.CreatePasswordResponse())
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private suspend fun completeGetPassword(itemId: String, alreadyVerified: Boolean) {
        val item = VaultRepository.get(this).get(itemId) ?: throw IllegalStateException(tr("Запись не найдена"))
        val login = item.payload.login ?: throw IllegalStateException(tr("Запись не найдена"))
        if (!verifyUser(alreadyVerified)) return
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(result, GetCredentialResponse(androidx.credentials.PasswordCredential(login.username, login.password)))
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private suspend fun finishUnlock() {
        val begin = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)
        val result = Intent()
        if (begin != null) PendingIntentHandler.setBeginGetCredentialResponse(result, PasskeyProvider.buildGetResponse(this, begin))
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun cancelGet() {
        val result = Intent()
        PendingIntentHandler.setGetCredentialException(result, GetCredentialCancellationException(tr("Отменено пользователем")))
        setResult(Activity.RESULT_OK, result); finish()
    }

    private fun cancelCreate() {
        val result = Intent()
        PendingIntentHandler.setCreateCredentialException(result, CreateCredentialCancellationException(tr("Отменено пользователем")))
        setResult(Activity.RESULT_OK, result); finish()
    }

    @Suppress("unused")
    private fun fail(message: String, create: Boolean) {
        val result = Intent()
        if (create) PendingIntentHandler.setCreateCredentialException(result, CreateCredentialUnknownException(message))
        else PendingIntentHandler.setGetCredentialException(result, GetCredentialUnknownException(message))
        setResult(Activity.RESULT_OK, result); finish()
    }

    companion object {
        const val EXTRA_MODE = "sboard.passkey.mode"
        const val EXTRA_ITEM_ID = "sboard.passkey.item"
        const val MODE_UNLOCK = "unlock"
        const val MODE_GET = "get"
        const val MODE_CREATE = "create"
        const val MODE_CREATE_PASSWORD = "create_password"
        const val MODE_GET_PASSWORD = "get_password"
    }
}
