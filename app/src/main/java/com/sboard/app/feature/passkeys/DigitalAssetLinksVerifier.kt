package com.sboard.app.feature.passkeys

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Verifies the Android-app -> web-RP association published by the relying party.
 * Redirects are deliberately disabled: the statement must be served by the RP host itself.
 */
class DigitalAssetLinksVerifier(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    suspend fun verifyAndroidApp(
        rpId: String,
        packageName: String,
        signingCertificate: ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedRpId = WebAuthnOriginPolicy.normalizeRpId(rpId) ?: return@withContext false
        if (packageName.isBlank() || signingCertificate.isEmpty()) return@withContext false

        val request = Request.Builder()
            .url("https://$normalizedRpId/.well-known/assetlinks.json")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.code != 200) return@withContext false
                val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
                if (!contentType.equals("application/json", ignoreCase = true)) return@withContext false
                val body = response.body ?: return@withContext false
                if (body.contentLength() > MAX_DOCUMENT_BYTES) return@withContext false
                val bytes = body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_DOCUMENT_BYTES) return@withContext false
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                val json = bytes.toString(Charsets.UTF_8)
                val statements = parseStatements(json)
                val fingerprint = sha256Fingerprint(signingCertificate)
                AssetLinkStatementMatcher.matches(
                    statements = statements,
                    packageName = packageName,
                    certificateFingerprint = fingerprint
                )
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun parseStatements(json: String): List<AssetLinkStatement> {
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val target = item.optJSONObject("target") ?: continue
                val relationArray = item.optJSONArray("relation") ?: continue
                val fingerprintsArray = target.optJSONArray("sha256_cert_fingerprints") ?: JSONArray()
                val relations = buildSet {
                    for (i in 0 until relationArray.length()) {
                        relationArray.optString(i).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
                val fingerprints = buildSet {
                    for (i in 0 until fingerprintsArray.length()) {
                        normalizeFingerprint(fingerprintsArray.optString(i))?.let(::add)
                    }
                }
                add(
                    AssetLinkStatement(
                        namespace = target.optString("namespace"),
                        packageName = target.optString("package_name"),
                        relations = relations,
                        certificateFingerprints = fingerprints
                    )
                )
            }
        }
    }

    private fun sha256Fingerprint(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate).joinToString(":") {
            "%02X".format(Locale.ROOT, it.toInt() and 0xff)
        }

    private fun normalizeFingerprint(value: String): String? {
        val normalized = value.replace(":", "").replace(" ", "").uppercase(Locale.ROOT)
        if (normalized.length != 64 || normalized.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        return normalized.chunked(2).joinToString(":")
    }

    companion object {
        private const val MAX_DOCUMENT_BYTES = 256 * 1024
    }
}

internal data class AssetLinkStatement(
    val namespace: String,
    val packageName: String,
    val relations: Set<String>,
    val certificateFingerprints: Set<String>
)

/** Pure matcher kept separate so security rules can be unit-tested without network access. */
internal object AssetLinkStatementMatcher {
    private const val ANDROID_NAMESPACE = "android_app"
    private const val GET_LOGIN_CREDENTIALS = "delegate_permission/common.get_login_creds"

    fun matches(
        statements: List<AssetLinkStatement>,
        packageName: String,
        certificateFingerprint: String
    ): Boolean {
        val expectedFingerprint = normalizeFingerprint(certificateFingerprint) ?: return false
        return statements.any { statement ->
            statement.namespace == ANDROID_NAMESPACE &&
                statement.packageName == packageName &&
                GET_LOGIN_CREDENTIALS in statement.relations &&
                statement.certificateFingerprints.any { normalizeFingerprint(it) == expectedFingerprint }
        }
    }

    private fun normalizeFingerprint(value: String): String? {
        val normalized = value.replace(":", "").replace(" ", "").uppercase(Locale.ROOT)
        if (normalized.length != 64 || normalized.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        return normalized.chunked(2).joinToString(":")
    }
}
