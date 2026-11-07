package com.sboard.app.feature.keyboard

import android.content.Context
import android.content.Intent
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.core.settings.AppSettings
import com.sboard.app.data.ItemType
import com.sboard.app.data.VaultItem
import com.sboard.app.data.VaultRepository
import com.sboard.app.feature.otp.TotpCodes
import com.sboard.app.feature.vault.Biometrics
import kotlinx.coroutines.flow.StateFlow
import org.sboard.keyboard.KeyboardVaultEntry
import org.sboard.keyboard.KeyboardVaultField
import org.sboard.keyboard.VaultKeyboardProvider

/** Supplies Vault data to the keyboard's sBoard Pass panel. */
class KeyboardVaultProviderImpl(private val context: Context) : VaultKeyboardProvider {
    private val repo by lazy { VaultRepository.get(context) }
    override val unlocked: StateFlow<Boolean> get() = VaultSessionProvider.session.unlocked
    override val enabled: Boolean get() = AppSettings.keyboardVaultBar.value

    override suspend fun entries(targetPackage: String?, query: String): List<KeyboardVaultEntry> {
        val q = query.trim().lowercase()
        return repo.snapshot().asSequence()
            .filter { it.type in setOf(ItemType.LOGIN, ItemType.IDENTITY, ItemType.CARD, ItemType.TOTP) }
            .filter { q.isEmpty() || it.payload.searchText().contains(q) }
            .map { item -> item to (targetPackage != null && item.payload.login?.packageNames?.contains(targetPackage) == true) }
            .sortedWith(compareByDescending<Pair<VaultItem, Boolean>> { it.second }.thenByDescending { it.first.favorite }.thenBy { it.first.title.lowercase() })
            .map { (item, suggested) -> KeyboardVaultEntry(item.id, item.title, item.subtitle(), suggested, fields(item)) }
            .filter { it.fields.isNotEmpty() }
            .take(40).toList()
    }

    private fun fields(item: VaultItem): List<KeyboardVaultField> = buildList {
        val p = item.payload
        p.login?.let { l ->
            if (l.username.isNotBlank()) add(KeyboardVaultField(KEY_USERNAME, if (l.username.contains('@')) "Email" else tr("Логин"), false))
            if (l.password.isNotBlank()) add(KeyboardVaultField(KEY_PASSWORD, tr("Пароль"), true))
            if (l.totp != null) add(KeyboardVaultField(KEY_OTP, tr("Код 2FA"), true))
        }
        p.identity?.let { i ->
            if (i.fullName.isNotBlank()) add(KeyboardVaultField(KEY_NAME, tr("Имя"), false))
            if (i.email.isNotBlank()) add(KeyboardVaultField(KEY_EMAIL, "Email", false))
            if (i.phone.isNotBlank()) add(KeyboardVaultField(KEY_PHONE, tr("Телефон"), false))
            if (i.fullAddress.isNotBlank()) add(KeyboardVaultField(KEY_ADDRESS, tr("Адрес"), false))
            if (i.postalCode.isNotBlank()) add(KeyboardVaultField(KEY_POSTAL, tr("Индекс"), false))
            if (i.documentNumber.isNotBlank()) add(KeyboardVaultField(KEY_DOCUMENT, tr("Документ"), true))
        }
        p.card?.let { c ->
            if (c.number.isNotBlank()) add(KeyboardVaultField(KEY_CARD_NUMBER, tr("Номер карты"), true))
            if (c.cardholder.isNotBlank()) add(KeyboardVaultField(KEY_CARD_HOLDER, tr("Владелец"), false))
            if (c.expMonth.isNotBlank()) add(KeyboardVaultField(KEY_CARD_EXP, tr("Срок"), true))
            if (c.cvv.isNotBlank()) add(KeyboardVaultField(KEY_CARD_CVV, "CVV", true))
        }
        if (item.type == ItemType.TOTP && p.totp != null) add(KeyboardVaultField(KEY_OTP, tr("Код 2FA"), true))
    }

    override suspend fun value(entryId: String, fieldKey: String): String? = valueOf(repo.get(entryId), fieldKey)

    override fun requiresStrongConfirmation(): Boolean = AppSettings.biometricForSensitive.value

    override fun requestUnlock(context: Context) {
        context.startActivity(KeyboardAuthActivity.intent(context, KeyboardAuthActivity.MODE_UNLOCK, null, null, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun requestConfirmedInsert(context: Context, entryId: String, fieldKey: String, targetPackage: String?) {
        context.startActivity(KeyboardAuthActivity.intent(context, KeyboardAuthActivity.MODE_CONFIRM, entryId, fieldKey, targetPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun lock() = VaultSessionProvider.session.lock()

    override fun offerPasswordSave(context: Context, packageName: String, username: String, password: String) =
        com.sboard.app.feature.autofill.KeyboardSaveOffer.offer(context, packageName, username, password)

    override fun text(key: String): String = when (key) {
        "disabled_title" -> tr("sPass выключен")
        "disabled_message" -> tr("Включите его в приложении sBoard → Клавиатура")
        "locked_title" -> tr("Хранилище заблокировано")
        "locked_message" -> tr("Разблокируйте sBoard, чтобы вставлять данные")
        "unlock" -> tr("Разблокировать")
        "loading" -> tr("Загрузка…")
        "empty_title" -> tr("Нет данных для вставки")
        "empty_message" -> tr("Добавьте логины, личные данные или карты в sBoard")
        "confirm_insert" -> tr("Вставить «{0}» из «{1}»?")
        "insert" -> tr("Вставить")
        "cancel" -> tr("Отмена")
        "empty_value" -> tr("Поле пустое")
        else -> key
    }

    companion object {
        const val KEY_USERNAME = "username"; const val KEY_PASSWORD = "password"; const val KEY_OTP = "otp"
        const val KEY_NAME = "name"; const val KEY_EMAIL = "email"; const val KEY_PHONE = "phone"; const val KEY_ADDRESS = "address"
        const val KEY_POSTAL = "postal"; const val KEY_DOCUMENT = "document"
        const val KEY_CARD_NUMBER = "card_number"; const val KEY_CARD_HOLDER = "card_holder"; const val KEY_CARD_EXP = "card_exp"; const val KEY_CARD_CVV = "card_cvv"

        fun valueOf(item: VaultItem?, key: String): String? {
            val p = item?.payload ?: return null
            return when (key) {
                KEY_USERNAME -> p.login?.username
                KEY_PASSWORD -> p.login?.password
                KEY_OTP -> (p.totp ?: p.login?.totp)?.let { TotpCodes.current(it)?.value }
                KEY_NAME -> p.identity?.fullName
                KEY_EMAIL -> p.identity?.email
                KEY_PHONE -> p.identity?.phone
                KEY_ADDRESS -> p.identity?.fullAddress
                KEY_POSTAL -> p.identity?.postalCode
                KEY_DOCUMENT -> p.identity?.documentNumber
                KEY_CARD_NUMBER -> p.card?.number?.filter { it.isDigit() }
                KEY_CARD_HOLDER -> p.card?.cardholder
                KEY_CARD_EXP -> p.card?.let { "${it.expMonth.padStart(2, '0')}/${it.expYear.takeLast(2)}" }
                KEY_CARD_CVV -> p.card?.cvv
                else -> null
            }?.takeIf { it.isNotEmpty() }
        }
    }
}
