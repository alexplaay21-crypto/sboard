package com.sboard.app.data

import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * Conservative website matching for Autofill and the keyboard bar.
 * A saved site matches the same host, its subdomains, or another host under the same
 * registrable domain (e.g. login.example.com ↔ example.com). Multi-label public suffixes
 * that are common in RU/EN markets are recognised so that example.co.uk ≠ other.co.uk.
 */
object DomainMatcher {
    private val multiLabelSuffixes = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "com.au", "net.au", "org.au", "co.nz",
        "com.br", "com.ru", "net.ru", "org.ru", "pp.ru", "msk.ru", "spb.ru", "com.ua", "kiev.ua",
        "co.jp", "co.kr", "com.cn", "com.tr", "com.mx", "co.in", "co.za", "com.sg", "com.hk",
        "github.io", "appspot.com", "blogspot.com", "herokuapp.com", "vercel.app", "netlify.app",
        "pages.dev", "web.app", "firebaseapp.com"
    )

    fun hostOf(value: String): String? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        return runCatching {
            val withScheme = if (raw.contains("://")) raw else "https://$raw"
            val host = URI(withScheme).host ?: return null
            normalizeHost(host)
        }.getOrNull()
    }

    fun normalizeHost(host: String): String? = runCatching {
        val ascii = IDN.toASCII(host.trim().trimEnd('.'), IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
        ascii.removePrefix("www.").takeIf { it.contains('.') && !it.startsWith('.') }
    }.getOrNull()

    fun registrableDomain(host: String): String {
        val labels = host.split('.')
        if (labels.size <= 2) return host
        if (labels.all { it.all(Char::isDigit) }) return host // IPv4
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in multiLabelSuffixes) labels.takeLast(3).joinToString(".") else lastTwo
    }

    fun matches(savedUrlOrHost: String, requestHost: String): Boolean {
        val saved = hostOf(savedUrlOrHost) ?: return false
        val request = normalizeHost(requestHost) ?: return false
        if (saved == request) return true
        return registrableDomain(saved) == registrableDomain(request)
    }
}
