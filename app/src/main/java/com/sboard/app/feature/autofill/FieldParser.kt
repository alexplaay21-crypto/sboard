package com.sboard.app.feature.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import java.util.Locale

enum class FieldKind {
    USERNAME, EMAIL, PASSWORD, NEW_PASSWORD, OTP, PHONE,
    NAME, GIVEN_NAME, FAMILY_NAME, ADDRESS, POSTAL_CODE, CITY, REGION, COUNTRY,
    CARD_NUMBER, CARD_HOLDER, CARD_EXP_DATE, CARD_EXP_MONTH, CARD_EXP_YEAR, CARD_CVC,
}

/** The fields of one screen/form, classified for filling. Values are only read for the save flow. */
class ParsedForm(
    val fields: Map<FieldKind, List<AutofillId>>,
    val webDomain: String?,
    val packageName: String,
    val values: Map<FieldKind, String>,
) {
    val allIds: List<AutofillId> get() = fields.values.flatten().distinct()
    fun has(kind: FieldKind) = !fields[kind].isNullOrEmpty()
    val isLogin: Boolean get() = has(FieldKind.PASSWORD) || has(FieldKind.NEW_PASSWORD) ||
        ((has(FieldKind.USERNAME) || has(FieldKind.EMAIL)) && fields.size <= 2)
    val passwordIds: List<AutofillId> get() = fields[FieldKind.PASSWORD].orEmpty() + fields[FieldKind.NEW_PASSWORD].orEmpty()
    val usernameIds: List<AutofillId> get() = fields[FieldKind.USERNAME].orEmpty() + fields[FieldKind.EMAIL].orEmpty()
}

/** Heuristic field detection from autofill hints, HTML attributes, input types and RU/EN labels. */
object FieldParser {
    fun parse(structure: AssistStructure, captureValues: Boolean = false): ParsedForm {
        val fields = LinkedHashMap<FieldKind, MutableList<AutofillId>>()
        val values = HashMap<FieldKind, String>()
        var webDomain: String? = null
        var lastPlainTextBeforePassword: AutofillId? = null
        fun visit(node: AssistStructure.ViewNode) {
            if (webDomain == null) node.webDomain?.takeIf { it.isNotBlank() }?.let { webDomain = it }
            val id = node.autofillId
            if (id != null && node.visibility == View.VISIBLE && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
                val kind0 = classify(node)
                if (kind0 == null && !fields.containsKey(FieldKind.PASSWORD) && isPlainText(node)) lastPlainTextBeforePassword = id
                kind0?.let { kind ->
                    fields.getOrPut(kind) { mutableListOf() }.add(id)
                    if (captureValues) {
                        val text = node.autofillValue?.takeIf { it.isText }?.textValue?.toString()
                        if (!text.isNullOrBlank() && kind !in values) values[kind] = text
                    }
                }
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)
        // A plain text field right before the password is the username even without hints.
        val hasPassword = fields.containsKey(FieldKind.PASSWORD)
        val hasUser = fields.containsKey(FieldKind.USERNAME) || fields.containsKey(FieldKind.EMAIL) || fields.containsKey(FieldKind.PHONE)
        if (hasPassword && !hasUser && lastPlainTextBeforePassword != null) {
            fields[FieldKind.USERNAME] = mutableListOf(lastPlainTextBeforePassword!!)
        }
        return ParsedForm(fields, webDomain, structure.activityComponent?.packageName.orEmpty(), values)
    }

    private val hintMap = mapOf(
        "username" to FieldKind.USERNAME, "emailaddress" to FieldKind.EMAIL, "email" to FieldKind.EMAIL,
        "password" to FieldKind.PASSWORD, "current-password" to FieldKind.PASSWORD, "newpassword" to FieldKind.NEW_PASSWORD,
        "new-password" to FieldKind.NEW_PASSWORD, "smsotpcode" to FieldKind.OTP, "one-time-code" to FieldKind.OTP,
        "2faappotpcode" to FieldKind.OTP, "otp" to FieldKind.OTP,
        "phone" to FieldKind.PHONE, "phonenumber" to FieldKind.PHONE, "tel" to FieldKind.PHONE, "phonenational" to FieldKind.PHONE,
        "name" to FieldKind.NAME, "personname" to FieldKind.NAME, "persongivenname" to FieldKind.GIVEN_NAME,
        "given-name" to FieldKind.GIVEN_NAME, "personfamilyname" to FieldKind.FAMILY_NAME, "family-name" to FieldKind.FAMILY_NAME,
        "postaladdress" to FieldKind.ADDRESS, "street-address" to FieldKind.ADDRESS, "address-line1" to FieldKind.ADDRESS,
        "streetaddress" to FieldKind.ADDRESS, "postalcode" to FieldKind.POSTAL_CODE, "postal-code" to FieldKind.POSTAL_CODE,
        "addresslocality" to FieldKind.CITY, "address-level2" to FieldKind.CITY, "addressregion" to FieldKind.REGION,
        "address-level1" to FieldKind.REGION, "addresscountry" to FieldKind.COUNTRY, "country" to FieldKind.COUNTRY,
        "country-name" to FieldKind.COUNTRY,
        "creditcardnumber" to FieldKind.CARD_NUMBER, "cc-number" to FieldKind.CARD_NUMBER,
        "creditcardsecuritycode" to FieldKind.CARD_CVC, "cc-csc" to FieldKind.CARD_CVC,
        "creditcardexpirationdate" to FieldKind.CARD_EXP_DATE, "cc-exp" to FieldKind.CARD_EXP_DATE,
        "creditcardexpirationmonth" to FieldKind.CARD_EXP_MONTH, "cc-exp-month" to FieldKind.CARD_EXP_MONTH,
        "creditcardexpirationyear" to FieldKind.CARD_EXP_YEAR, "cc-exp-year" to FieldKind.CARD_EXP_YEAR,
        "cc-name" to FieldKind.CARD_HOLDER, "creditcardholdername" to FieldKind.CARD_HOLDER,
    )

    private val keywordRules: List<Pair<FieldKind, List<String>>> = listOf(
        FieldKind.OTP to listOf("otp", "one-time", "onetime", "verification code", "2fa", "totp", "auth code", "код подтверждения", "одноразов", "код из", "sms code", "смс", "código de verificación", "code de vérification", "bestätigungscode", "codice di verifica", "código de verificação", "kod weryfikacyjny", "код підтвердження", "doğrulama kodu", "验证码", "認証コード", "인증번호", "رمز التحقق", "sms-code"),
        FieldKind.CARD_CVC to listOf("cvv", "cvc", "csc", "security code", "код безопасности", "cvv2", "cvc2", "prüfnummer", "código de seguridad", "cryptogramme", "código de segurança", "kod cvv"),
        FieldKind.CARD_NUMBER to listOf("card number", "cardnumber", "cc-number", "ccnumber", "номер карты", "número de tarjeta", "numéro de carte", "kartennummer", "numero carta", "número do cartão", "numer karty", "номер картки", "kart numarası", "卡号", "カード番号", "카드번호", "رقم البطاقة"),
        FieldKind.CARD_EXP_DATE to listOf("expiry", "expiration", "exp date", "mm/yy", "срок действия", "fecha de caducidad", "date d'expiration", "gültig bis", "ablaufdatum", "scadenza", "validade", "data ważności", "термін дії", "son kullanma", "有效期", "有効期限", "유효기간"),
        FieldKind.CARD_HOLDER to listOf("cardholder", "name on card", "владелец карты", "titular", "titulaire", "karteninhaber", "intestatario", "posiadacz karty", "власник картки", "kart sahibi", "持卡人"),
        FieldKind.NEW_PASSWORD to listOf("new password", "newpassword", "new_password", "новый пароль", "confirm password", "повторите пароль", "nueva contraseña", "nouveau mot de passe", "neues passwort", "nuova password", "nova senha", "nowe hasło", "новий пароль", "yeni şifre", "新密码", "新しいパスワード", "새 비밀번호", "confirmar contraseña", "confirmer le mot de passe", "passwort bestätigen", "повторіть пароль"),
        FieldKind.PASSWORD to listOf("password", "passwd", "пароль", "pin", "contraseña", "mot de passe", "passwort", "kennwort", "senha", "hasło", "haslo", "şifre", "parola", "wachtwoord", "lösenord", "adgangskode", "salasana", "heslo", "jelszó", "κωδικός", "密码", "密碼", "パスワード", "비밀번호", "كلمة المرور", "סיסמה", "पासवर्ड", "mật khẩu", "kata sandi", "құпия сөз", "parol", "гасло"),
        FieldKind.EMAIL to listOf("email", "e-mail", "почта", "mail", "correo", "courriel", "e-mail-adresse", "posta elettronica", "poczta", "пошта", "e-posta", "邮箱", "メール", "이메일", "البريد"),
        FieldKind.USERNAME to listOf("username", "user name", "login", "логин", "user_id", "userid", "account", "имя пользователя", "учетн", "учётн", "usuario", "nombre de usuario", "identifiant", "nom d'utilisateur", "benutzername", "benutzer", "anmeldename", "utente", "nome utente", "usuário", "użytkownik", "логін", "користувач", "kullanıcı adı", "gebruikersnaam", "användarnamn", "käyttäjätunnus", "用户名", "帳號", "ユーザー名", "아이디", "사용자", "اسم المستخدم", "שם משתמש", "tên đăng nhập", "nama pengguna", "пайдаланушы"),
        FieldKind.PHONE to listOf("phone", "mobile", "телефон", "tel", "teléfono", "téléphone", "telefon", "telefono", "telefone", "номер телефону", "telefon numarası", "电话", "手机", "電話", "전화", "هاتف", "celular", "móvil", "handy"),
        FieldKind.FAMILY_NAME to listOf("last name", "lastname", "surname", "family name", "фамилия", "apellido", "nom de famille", "nachname", "cognome", "sobrenome", "nazwisko", "прізвище", "soyad", "achternaam", "efternamn"),
        FieldKind.GIVEN_NAME to listOf("first name", "firstname", "given name", "имя", "nombre", "prénom", "vorname", "nome", "imię", "ім'я", "voornaam", "förnamn"),
        FieldKind.POSTAL_CODE to listOf("zip", "postal", "postcode", "индекс", "código postal", "code postal", "postleitzahl", "plz", "cep", "kod pocztowy", "поштовий індекс", "posta kodu", "邮编", "郵便番号", "우편번호"),
        FieldKind.CITY to listOf("city", "town", "город", "ciudad", "ville", "stadt", "città", "cidade", "miasto", "місто", "şehir", "城市", "市区町村", "도시"),
        FieldKind.ADDRESS to listOf("address", "street", "адрес", "улица", "dirección", "adresse", "straße", "strasse", "indirizzo", "endereço", "adres", "ulica", "адреса", "вулиця", "地址", "住所", "주소", "العنوان"),
        FieldKind.COUNTRY to listOf("country", "страна", "país", "pays", "paese", "kraj", "країна", "ülke", "国家", "국가"),
    )

    private fun isPlainText(node: AssistStructure.ViewNode): Boolean {
        val html = node.htmlInfo
        if (html != null) {
            val type = html.attributes?.firstOrNull { it.first.equals("type", true) }?.second?.lowercase()
            return html.tag.equals("input", true) && (type == null || type == "text")
        }
        return (node.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
    }

    internal fun classify(node: AssistStructure.ViewNode): FieldKind? {
        node.autofillHints?.forEach { hint -> hintMap[hint.lowercase(Locale.ROOT).replace(" ", "")]?.let { return it } }
        val html = node.htmlInfo
        var htmlType: String? = null
        html?.attributes?.forEach { attr ->
            val name = attr.first?.lowercase(Locale.ROOT)
            val value = attr.second?.lowercase(Locale.ROOT)?.trim().orEmpty()
            if (name == "autocomplete") value.split(' ').forEach { token -> hintMap[token]?.let { return it } }
            if (name == "type") htmlType = value
        }
        if (html != null && html.tag?.lowercase(Locale.ROOT) != "input") return null
        when (htmlType) {
            "password" -> return FieldKind.PASSWORD
            "email" -> return FieldKind.EMAIL
            "tel" -> return FieldKind.PHONE
            "hidden", "submit", "button", "checkbox", "radio", "search" -> return null
        }
        val inputType = node.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isPasswordInput = (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val text = listOfNotNull(node.idEntry, node.hint, node.contentDescription?.toString(), node.text?.toString()?.take(40),
            html?.attributes?.firstOrNull { it.first.equals("name", true) }?.second,
            html?.attributes?.firstOrNull { it.first.equals("id", true) }?.second,
            html?.attributes?.firstOrNull { it.first.equals("placeholder", true) }?.second,
            html?.attributes?.firstOrNull { it.first.equals("aria-label", true) }?.second,
        ).joinToString(" ").lowercase(Locale.ROOT)
        if (isPasswordInput) {
            return if (keywordRules.first { it.first == FieldKind.NEW_PASSWORD }.second.any { text.contains(it) }) FieldKind.NEW_PASSWORD
            else if (keywordRules.first { it.first == FieldKind.OTP }.second.any { text.contains(it) }) FieldKind.OTP
            else FieldKind.PASSWORD
        }
        if (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) return FieldKind.EMAIL
        if (cls == InputType.TYPE_CLASS_PHONE) return FieldKind.PHONE
        if (text.isBlank()) return null
        // Custom password views that do not declare a password input type.
        val passwordWords = listOf("password", "passwd", "pwd", "пароль", "passcode", "парол", "contraseña", "mot de passe", "passwort",
            "kennwort", "senha", "hasło", "şifre", "parola", "wachtwoord", "lösenord", "密码", "パスワード", "비밀번호", "كلمة المرور", "гасло")
        if (passwordWords.any { text.contains(it) } && listOf("forgot", "забыли", "reset", "восстанов", "confirm", "olvid", "oublié", "vergessen", "esqueceu", "dimenticat").none { text.contains(it) } &&
            cls == InputType.TYPE_CLASS_TEXT) return FieldKind.PASSWORD
        for ((kind, words) in keywordRules) {
            if (kind == FieldKind.PASSWORD || kind == FieldKind.NEW_PASSWORD) continue // only on real password inputs
            if (words.any { word -> text.contains(word) }) return kind
        }
        return null
    }
}
