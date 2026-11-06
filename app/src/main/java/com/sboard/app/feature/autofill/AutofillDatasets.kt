package com.sboard.app.feature.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.sboard.app.R
import com.sboard.app.data.DomainMatcher
import com.sboard.app.data.ItemType
import com.sboard.app.data.VaultItem
import com.sboard.app.feature.otp.TotpCodes

/** Builds Autofill datasets. Values are only attached after the user confirmed in [AutofillActivity]. */
@Suppress("DEPRECATION")
object AutofillDatasets {
    private val browsers = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "org.chromium.chrome",
        "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta", "org.mozilla.firefox", "org.mozilla.firefox_beta",
        "org.mozilla.focus", "com.opera.browser", "com.opera.mini.native", "com.microsoft.emmx", "com.brave.browser",
        "com.yandex.browser", "com.vivaldi.browser", "com.duckduckgo.mobile.android", "com.kiwibrowser.browser",
        "com.android.browser", "com.mi.globalbrowser", "com.huawei.browser",
    )

    fun isBrowser(packageName: String) = packageName in browsers

    fun presentation(context: Context, title: String, subtitle: String?, iconRes: Int = R.drawable.ic_autofill_key): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_subtitle, subtitle.orEmpty())
            setImageViewResource(R.id.autofill_icon, iconRes)
        }

    /** Items relevant for this form: same site (registrable domain) or a linked Android app. */
    fun matching(items: List<VaultItem>, form: ParsedForm): List<VaultItem> {
        val wantsLogin = form.isLogin || form.has(FieldKind.OTP)
        val wantsCard = form.has(FieldKind.CARD_NUMBER)
        val wantsIdentity = listOf(FieldKind.NAME, FieldKind.GIVEN_NAME, FieldKind.ADDRESS, FieldKind.PHONE, FieldKind.POSTAL_CODE).any(form::has)
        val domain = form.webDomain
        return items.filter { item ->
            when (item.type) {
                ItemType.LOGIN -> wantsLogin && linked(item, domain, form.packageName)
                ItemType.TOTP -> form.has(FieldKind.OTP) && linked(item, domain, form.packageName)
                ItemType.CARD -> wantsCard
                ItemType.IDENTITY -> wantsIdentity || (form.has(FieldKind.EMAIL) && !form.isLogin)
                else -> false
            }
        }.take(20)
    }

    private fun linked(item: VaultItem, domain: String?, packageName: String): Boolean {
        val login = item.payload.login
        if (domain != null) {
            val urls = login?.urls.orEmpty() + listOfNotNull(item.payload.totp?.issuer?.takeIf { it.contains('.') })
            return urls.any { DomainMatcher.matches(it, domain) }
        }
        if (isBrowser(packageName)) return false
        return login?.packageNames?.contains(packageName) == true ||
            login?.urls.orEmpty().any { it.removePrefix("androidapp://") == packageName }
    }

    /** Field values to fill for [item] on [form]. */
    fun values(item: VaultItem, form: ParsedForm): Map<AutofillId, String> {
        val out = LinkedHashMap<AutofillId, String>()
        fun put(kind: FieldKind, value: String?) {
            if (value.isNullOrBlank()) return
            form.fields[kind]?.forEach { out[it] = value }
        }
        val p = item.payload
        p.login?.let { login ->
            put(FieldKind.USERNAME, login.username)
            put(FieldKind.EMAIL, login.username.takeIf { it.contains('@') } ?: login.username)
            put(FieldKind.PASSWORD, login.password)
            login.totp?.let { put(FieldKind.OTP, TotpCodes.current(it)?.value) }
        }
        p.totp?.let { put(FieldKind.OTP, TotpCodes.current(it)?.value) }
        p.identity?.let { id ->
            put(FieldKind.NAME, id.fullName); put(FieldKind.GIVEN_NAME, id.firstName); put(FieldKind.FAMILY_NAME, id.lastName)
            put(FieldKind.EMAIL, id.email); put(FieldKind.PHONE, id.phone)
            put(FieldKind.ADDRESS, listOf(id.address1, id.address2).filter { it.isNotBlank() }.joinToString(", "))
            put(FieldKind.POSTAL_CODE, id.postalCode); put(FieldKind.CITY, id.city); put(FieldKind.REGION, id.region); put(FieldKind.COUNTRY, id.country)
            if (p.login == null) put(FieldKind.USERNAME, id.email)
        }
        p.card?.let { c ->
            put(FieldKind.CARD_NUMBER, c.number.filter { it.isDigit() }); put(FieldKind.CARD_HOLDER, c.cardholder)
            put(FieldKind.CARD_CVC, c.cvv); put(FieldKind.CARD_EXP_MONTH, c.expMonth); put(FieldKind.CARD_EXP_YEAR, c.expYear)
            if (c.expMonth.isNotBlank() && c.expYear.isNotBlank()) put(FieldKind.CARD_EXP_DATE, "${c.expMonth.padStart(2, '0')}/${c.expYear.takeLast(2)}")
        }
        return out
    }

    fun filledDataset(context: Context, item: VaultItem, form: ParsedForm): Dataset? {
        val values = values(item, form)
        if (values.isEmpty()) return null
        val pres = presentation(context, item.title, item.subtitle())
        return Dataset.Builder(pres).apply { values.forEach { (id, v) -> setValue(id, AutofillValue.forText(v), pres) } }.build()
    }

    /** Dataset whose values are released only after confirmation in [AutofillActivity]. */
    fun lockedDataset(context: Context, item: VaultItem, form: ParsedForm, requestCode: Int,
                      inlineRequest: android.view.inputmethod.InlineSuggestionsRequest? = null, inlineIndex: Int = 0): Dataset? {
        val ids = values(item, form).keys
        if (ids.isEmpty()) return null
        val pres = presentation(context, item.title, item.subtitle())
        val sender = AutofillActivity.intentSender(context, AutofillActivity.MODE_CONFIRM, item.id, requestCode)
        return Dataset.Builder(pres).apply {
            ids.forEach { setValue(it, null, pres) }
            setAuthentication(sender)
            if (Build.VERSION.SDK_INT >= 30) inlinePresentation(context, inlineRequest, inlineIndex, item.title, item.subtitle())?.let { setInlinePresentation(it) }
        }.build()
    }

    /** Android 11+: suggestion chip shown inside the keyboard's suggestion strip. */
    fun inlinePresentation(context: Context, request: android.view.inputmethod.InlineSuggestionsRequest?, index: Int, title: String, subtitle: String?): android.service.autofill.InlinePresentation? {
        if (Build.VERSION.SDK_INT < 30 || request == null) return null
        if (index >= request.maxSuggestionCount) return null
        val specs = request.inlinePresentationSpecs
        if (specs.isEmpty()) return null
        val spec = specs[minOf(index, specs.size - 1)]
        if (!androidx.autofill.inline.UiVersions.getVersions(spec.style).contains(androidx.autofill.inline.UiVersions.INLINE_UI_VERSION_1)) return null
        val attribution = PendingIntent.getActivity(context, 0, Intent(context, com.sboard.app.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val content = androidx.autofill.inline.v1.InlineSuggestionUi.newContentBuilder(attribution)
            .setTitle(title)
            .apply { if (!subtitle.isNullOrBlank()) setSubtitle(subtitle) }
            .setStartIcon(android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_autofill_key))
            .build()
        return android.service.autofill.InlinePresentation(content.slice, spec, false)
    }

    fun pendingIntentSender(context: Context, intent: Intent, requestCode: Int): IntentSender =
        PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender
}
