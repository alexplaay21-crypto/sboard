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

/** Remembers which item was filled into a site/app for a few minutes (multi-step logins). */
object LastFill {
    private val entries = HashMap<String, Pair<String, Long>>()
    @Synchronized fun remember(target: String, itemId: String) { entries[target] = itemId to System.currentTimeMillis() }
    @Synchronized fun recent(target: String): String? = entries[target]?.takeIf { System.currentTimeMillis() - it.second < 5 * 60_000 }?.first
}

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

    private val genericTokens = setOf("com", "org", "net", "ru", "app", "apps", "android", "mobile", "client", "www", "lite",
        "pro", "beta", "free", "main", "official", "inc", "ltd", "llc", "global", "mobileapp", "application")

    /** Meaningful words of an Android package name, e.g. com.instagram.android → [instagram]. */
    internal fun packageTokens(packageName: String): Set<String> =
        packageName.lowercase().split('.', '_', '-').filter { it.length >= 3 && it !in genericTokens }.toSet()

    internal fun linked(item: VaultItem, domain: String?, packageName: String): Boolean {
        val login = item.payload.login
        if (LastFill.recent(domain ?: packageName) == item.id) return true
        if (domain != null) {
            val urls = login?.urls.orEmpty() + listOfNotNull(item.payload.totp?.issuer?.takeIf { it.contains('.') })
            return urls.any { DomainMatcher.matches(it, domain) }
        }
        if (isBrowser(packageName)) return false
        if (login?.packageNames?.contains(packageName) == true || login?.urls.orEmpty().any { it.removePrefix("androidapp://") == packageName }) return true
        // Heuristic for apps not linked yet: the site's name appears in the package (instagram.com ↔ com.instagram.android).
        val tokens = packageTokens(packageName)
        if (tokens.isEmpty()) return false
        val siteNames = login?.urls.orEmpty().mapNotNull { DomainMatcher.hostOf(it) }
            .map { DomainMatcher.registrableDomain(it).substringBefore('.') }
        val titleName = item.title.lowercase().filter { it.isLetterOrDigit() }
        val issuer = item.payload.totp?.issuer?.lowercase()?.filter { it.isLetterOrDigit() }
        return siteNames.any { it.length >= 3 && it in tokens } || (titleName.length >= 3 && titleName in tokens) ||
            (!issuer.isNullOrBlank() && issuer.length >= 3 && issuer in tokens)
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

    fun filledDataset(context: Context, item: VaultItem, form: ParsedForm,
                      inlineRequest: android.view.inputmethod.InlineSuggestionsRequest? = null, inlineIndex: Int = 0): Dataset? {
        val values = values(item, form)
        if (values.isEmpty()) return null
        val pres = presentation(context, item.title, item.subtitle())
        return Dataset.Builder(pres).apply {
            values.forEach { (id, v) -> setValue(id, AutofillValue.forText(v), pres) }
            if (Build.VERSION.SDK_INT >= 30) inlinePresentation(context, inlineRequest, inlineIndex, item.title, item.subtitle())?.let { setInlinePresentation(it) }
        }.build()
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
