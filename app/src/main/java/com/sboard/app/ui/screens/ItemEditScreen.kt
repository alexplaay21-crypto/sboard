package com.sboard.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sboard.app.core.i18n.tr
import com.sboard.app.data.*
import com.sboard.app.feature.otp.OtpAuthUri
import com.sboard.app.feature.tools.PasswordStrength
import com.sboard.app.feature.tools.generator.PasswordGenerator
import com.sboard.app.ui.Navigator
import com.sboard.app.ui.components.*
import com.sboard.app.ui.rememberQrScanner
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch

@Composable
fun ItemEditScreen(nav: Navigator, id: String?, type: ItemType, prefillTotpUri: String?) {
    val context = LocalContext.current
    val repo = remember { VaultRepository.get(context) }
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(id == null) }
    var original by remember { mutableStateOf<VaultItemPayload?>(null) }

    var title by remember { mutableStateOf("") }
    var favorite by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    // login
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val urls = remember { mutableStateListOf<String>() }
    val packages = remember { mutableStateListOf<String>() }
    var loginTotp by remember { mutableStateOf("") }
    // card
    var card by remember { mutableStateOf(CardData()) }
    // identity
    var identity by remember { mutableStateOf(IdentityData()) }
    // totp
    var totp by remember { mutableStateOf(TotpData()) }
    val custom = remember { mutableStateListOf<CustomField>() }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showGenerator by remember { mutableStateOf(false) }

    fun applyTotpUri(uri: String) {
        runCatching { OtpAuthUri.parse(uri) }.onSuccess { data ->
            if (type == ItemType.LOGIN) loginTotp = data.secret else {
                totp = data
                if (title.isBlank()) title = data.issuer.ifBlank { data.account }
            }
            error = null
        }.onFailure { error = tr("QR-код не похож на код 2FA (otpauth://totp)") }
    }
    val scan = rememberQrScanner { applyTotpUri(it) }

    LaunchedEffect(id) {
        if (id != null) {
            repo.get(id)?.payload?.let { p ->
                original = p
                title = p.title; favorite = p.favorite; notes = p.notes
                p.login?.let { l -> username = l.username; password = l.password; urls.addAll(l.urls); packages.addAll(l.packageNames); loginTotp = l.totp?.secret.orEmpty() }
                p.card?.let { card = it }
                p.identity?.let { identity = it }
                p.totp?.let { totp = it }
                custom.addAll(p.customFields)
            }
            loaded = true
        } else {
            if (type == ItemType.LOGIN && urls.isEmpty()) urls.add("")
            prefillTotpUri?.let(::applyTotpUri)
        }
    }

    fun buildPayload(): VaultItemPayload? {
        val cleanUrls = urls.map { it.trim() }.filter { it.isNotEmpty() }
        val resolvedTitle = title.trim().ifBlank {
            when (type) {
                ItemType.LOGIN -> cleanUrls.firstOrNull()?.let { DomainMatcher.hostOf(it) } ?: username
                ItemType.TOTP -> totp.issuer.ifBlank { totp.account }
                ItemType.CARD -> card.brand.ifBlank { tr("Карта") + " " + card.number.takeLast(4) }
                ItemType.IDENTITY -> identity.fullName
                else -> ""
            }
        }.trim()
        if (resolvedTitle.isBlank()) { error = tr("Укажите название"); return null }
        val previousLogin = original?.login
        val login = if (type == ItemType.LOGIN) {
            val secret = loginTotp.replace(" ", "").uppercase()
            if (secret.isNotEmpty() && runCatching { OtpAuthUri.validate(TotpData(secret = secret)) }.isFailure) { error = tr("Неверный секретный ключ 2FA"); return null }
            LoginData(
                username = username.trim(), password = password, urls = cleanUrls, packageNames = packages.toList(),
                totp = secret.takeIf { it.isNotEmpty() }?.let { (previousLogin?.totp ?: TotpData()).copy(secret = it) },
                passwordChangedAt = if (previousLogin?.password == password) previousLogin.passwordChangedAt else System.currentTimeMillis(),
            )
        } else null
        val totpData = if (type == ItemType.TOTP) {
            val t = totp.copy(secret = totp.secret.replace(" ", "").uppercase())
            if (runCatching { OtpAuthUri.validate(t) }.isFailure) { error = tr("Неверный секретный ключ 2FA"); return null }
            t
        } else original?.totp
        return VaultItemPayload(
            type = type, title = resolvedTitle, favorite = favorite, notes = notes,
            login = login, card = if (type == ItemType.CARD) card else null,
            identity = if (type == ItemType.IDENTITY) identity else null,
            totp = totpData, passkey = original?.passkey, customFields = custom.filter { it.name.isNotBlank() },
        )
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(if (id == null) type.label() else tr("Изменить"), onBack = { nav.pop() }) {
            TextButton(enabled = !saving && loaded, onClick = {
                val payload = buildPayload() ?: return@TextButton
                scope.launch {
                    saving = true
                    try { repo.save(id, payload); nav.pop() } catch (e: Exception) { error = e.message ?: tr("Не удалось сохранить") }
                    saving = false
                }
            }) { Text(tr("Сохранить"), color = palette.accent, style = MaterialTheme.typography.labelLarge) }
        }
        if (!loaded) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = palette.accent) }; return@Column }
        ScreenList(bottomPadding = 60.dp) {
            item { AnimatedBanner(error, Tone.DANGER) }
            item {
                SBCard {
                    SBTextField(title, { title = it; error = null }, tr("Название"))
                    SwitchRow(tr("Избранное"), null, favorite, Icons.Rounded.Star) { favorite = it }
                }
            }
            when (type) {
                ItemType.LOGIN -> {
                    item {
                        SBCard {
                            SBTextField(username, { username = it }, tr("Логин или email"), keyboardType = KeyboardType.Email)
                            SBTextField(password, { password = it }, tr("Пароль"), password = true, monospace = true)
                            if (password.isNotEmpty()) {
                                val s = remember(password) { PasswordStrength.evaluate(password, listOf(username, title)) }
                                StrengthBar(s.score, "${s.label} · ${s.crackTime}")
                            }
                            SecondaryButton(tr("Сгенерировать пароль"), { showGenerator = true }, icon = Icons.Rounded.AutoFixHigh)
                        }
                    }
                    item {
                        SBCard {
                            SectionTitle(tr("Сайты"))
                            urls.forEachIndexed { index, value ->
                                SBTextField(value, { urls[index] = it }, tr("Адрес сайта"), keyboardType = KeyboardType.Uri,
                                    trailing = { IconButton(onClick = { urls.removeAt(index) }) { Icon(Icons.Rounded.Close, tr("Удалить"), tint = palette.textMuted) } })
                            }
                            TextButton(onClick = { urls.add("") }) { Icon(Icons.Rounded.Add, null, tint = palette.accent); Text(tr("Добавить сайт"), color = palette.accent) }
                            if (packages.isNotEmpty()) {
                                SectionTitle(tr("Связанные приложения"))
                                packages.toList().forEach { pkg ->
                                    ListRow(pkg, null, icon = Icons.Rounded.Android, trailing = {
                                        IconButton(onClick = { packages.remove(pkg) }) { Icon(Icons.Rounded.Close, tr("Удалить"), tint = palette.textMuted) }
                                    })
                                }
                            }
                        }
                    }
                    item {
                        SBCard {
                            SectionTitle(tr("Двухфакторная аутентификация"))
                            SBTextField(loginTotp, { loginTotp = it }, tr("Секретный ключ 2FA (необязательно)"), monospace = true,
                                trailing = { IconButton(onClick = scan) { Icon(Icons.Rounded.QrCodeScanner, tr("Сканировать QR"), tint = palette.accent) } })
                        }
                    }
                }
                ItemType.CARD -> item {
                    SBCard {
                        SBTextField(card.cardholder, { card = card.copy(cardholder = it) }, tr("Владелец карты"))
                        SBTextField(card.number, { v -> card = card.copy(number = v.filter { it.isDigit() || it == ' ' }.take(23)) }, tr("Номер карты"), keyboardType = KeyboardType.Number, monospace = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SBTextField(card.expMonth, { v -> card = card.copy(expMonth = v.filter(Char::isDigit).take(2)) }, tr("Месяц"), Modifier.weight(1f), keyboardType = KeyboardType.Number)
                            SBTextField(card.expYear, { v -> card = card.copy(expYear = v.filter(Char::isDigit).take(4)) }, tr("Год"), Modifier.weight(1f), keyboardType = KeyboardType.Number)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SBTextField(card.cvv, { v -> card = card.copy(cvv = v.filter(Char::isDigit).take(4)) }, "CVV", Modifier.weight(1f), password = true, keyboardType = KeyboardType.NumberPassword)
                            SBTextField(card.pin, { v -> card = card.copy(pin = v.filter(Char::isDigit).take(8)) }, "PIN", Modifier.weight(1f), password = true, keyboardType = KeyboardType.NumberPassword)
                        }
                        SBTextField(card.brand, { card = card.copy(brand = it) }, tr("Платёжная система (Visa, Mastercard, МИР…)"))
                    }
                }
                ItemType.IDENTITY -> item {
                    SBCard {
                        SBTextField(identity.lastName, { identity = identity.copy(lastName = it) }, tr("Фамилия"))
                        SBTextField(identity.firstName, { identity = identity.copy(firstName = it) }, tr("Имя"))
                        SBTextField(identity.middleName, { identity = identity.copy(middleName = it) }, tr("Отчество"))
                        SBTextField(identity.email, { identity = identity.copy(email = it) }, "Email", keyboardType = KeyboardType.Email)
                        SBTextField(identity.phone, { identity = identity.copy(phone = it) }, tr("Телефон"), keyboardType = KeyboardType.Phone)
                        SBTextField(identity.company, { identity = identity.copy(company = it) }, tr("Компания"))
                        SBTextField(identity.birthDate, { identity = identity.copy(birthDate = it) }, tr("Дата рождения"))
                        SBTextField(identity.documentNumber, { identity = identity.copy(documentNumber = it) }, tr("Номер документа"), password = true)
                    }
                }
                ItemType.TOTP -> item {
                    SBCard {
                        PrimaryButton(tr("Сканировать QR-код"), scan, icon = Icons.Rounded.QrCodeScanner)
                        SBTextField(totp.issuer, { totp = totp.copy(issuer = it) }, tr("Сервис"))
                        SBTextField(totp.account, { totp = totp.copy(account = it) }, tr("Аккаунт"))
                        SBTextField(totp.secret, { totp = totp.copy(secret = it) }, tr("Секретный ключ (Base32)"), monospace = true)
                        SectionTitle(tr("Дополнительно"))
                        SegmentedControl(listOf("SHA1" to "SHA-1", "SHA256" to "SHA-256", "SHA512" to "SHA-512"), totp.algorithm, { totp = totp.copy(algorithm = it) })
                        SegmentedControl(listOf("6" to tr("6 цифр"), "7" to tr("7 цифр"), "8" to tr("8 цифр")), totp.digits.toString(), { totp = totp.copy(digits = it.toInt()) })
                        SegmentedControl(listOf("30" to "30 s", "60" to "60 s"), totp.period.toString(), { totp = totp.copy(period = it.toInt()) })
                    }
                }
                ItemType.NOTE, ItemType.PASSKEY -> Unit
            }
            if (type == ItemType.IDENTITY) item {
                SBCard {
                    SectionTitle(tr("Адрес"))
                    SBTextField(identity.address1, { identity = identity.copy(address1 = it) }, tr("Улица, дом"))
                    SBTextField(identity.address2, { identity = identity.copy(address2 = it) }, tr("Квартира, офис"))
                    SBTextField(identity.city, { identity = identity.copy(city = it) }, tr("Город"))
                    SBTextField(identity.region, { identity = identity.copy(region = it) }, tr("Регион"))
                    SBTextField(identity.postalCode, { identity = identity.copy(postalCode = it) }, tr("Индекс"), keyboardType = KeyboardType.Number)
                    SBTextField(identity.country, { identity = identity.copy(country = it) }, tr("Страна"))
                }
            }
            item {
                SBCard {
                    SBTextField(notes, { notes = it }, if (type == ItemType.NOTE) tr("Текст заметки") else tr("Заметки"), singleLine = false, minLines = if (type == ItemType.NOTE) 6 else 2)
                }
            }
            item {
                SBCard {
                    SectionTitle(tr("Дополнительные поля"))
                    custom.forEachIndexed { index, field ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SBTextField(field.name, { custom[index] = field.copy(name = it) }, tr("Название"), Modifier.weight(0.4f))
                            SBTextField(field.value, { custom[index] = field.copy(value = it) }, tr("Значение"), Modifier.weight(0.6f), password = field.hidden)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(field.hidden, { custom[index] = field.copy(hidden = it) }, colors = CheckboxDefaults.colors(checkedColor = palette.accent, checkmarkColor = palette.onAccent))
                            Text(tr("Скрывать значение"), style = MaterialTheme.typography.bodySmall, color = palette.textMuted, modifier = Modifier.weight(1f))
                            TextButton(onClick = { custom.removeAt(index) }) { Text(tr("Удалить"), color = palette.danger) }
                        }
                    }
                    TextButton(onClick = { custom.add(CustomField("", "")) }) { Icon(Icons.Rounded.Add, null, tint = palette.accent); Text(tr("Добавить поле"), color = palette.accent) }
                }
            }
        }
    }
    if (showGenerator) {
        var length by remember { mutableFloatStateOf(20f) }
        var candidate by remember { mutableStateOf(PasswordGenerator.generate(20, true, true, true, true)) }
        AlertDialog(
            onDismissRequest = { showGenerator = false }, containerColor = palette.surface,
            title = { Text(tr("Новый пароль")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(candidate, style = MaterialTheme.typography.titleMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = palette.text)
                    Text(tr("Длина: {0}", length.toInt()), color = palette.textMuted)
                    Slider(length, { length = it; candidate = PasswordGenerator.generate(it.toInt(), true, true, true, true) }, valueRange = 12f..64f,
                        colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent))
                    TextButton(onClick = { candidate = PasswordGenerator.generate(length.toInt(), true, true, true, true) }) { Text(tr("Ещё вариант"), color = palette.accent) }
                }
            },
            confirmButton = { TextButton(onClick = { password = candidate; showGenerator = false }) { Text(tr("Использовать"), color = palette.accent) } },
            dismissButton = { TextButton(onClick = { showGenerator = false }) { Text(tr("Отмена"), color = palette.textMuted) } },
        )
    }
}
