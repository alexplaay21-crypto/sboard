package com.sboard.app.data

import android.content.Context
import androidx.room.withTransaction
import com.sboard.app.core.crypto.AesGcm
import org.json.JSONObject

/**
 * One-time upgrade of schema ≤ v8 `vault_records` (AES-GCM, AAD = id, JSON with generic fields)
 * into typed v9 items. Runs only while unlocked; the legacy table is dropped after success.
 */
object LegacyVaultMigrator {
    suspend fun migrateIfNeeded(context: Context, key: ByteArray): Int {
        val database = VaultDatabase.get(context)
        val meta = database.meta()
        if (meta.get("legacy_records_pending") != "1") return 0
        val db = database.openHelper.writableDatabase
        val exists = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='vault_records'").use { it.moveToFirst() }
        if (!exists) { meta.delete("legacy_records_pending"); return 0 }
        val legacy = mutableListOf<Triple<String, Pair<ByteArray, ByteArray>, Pair<Long, Long>>>()
        db.query("SELECT id, iv, ciphertext, createdAt, updatedAt FROM vault_records").use { c ->
            while (c.moveToNext()) {
                legacy += Triple(c.getString(0), c.getBlob(1) to c.getBlob(2), c.getLong(3) to c.getLong(4))
            }
        }
        val deviceId = DeviceIdentity.id(context)
        var migrated = 0
        database.withTransaction {
            legacy.forEach { (id, sealed, times) ->
                val plain = runCatching { AesGcm.decrypt(key, sealed.first, sealed.second, id.toByteArray(Charsets.UTF_8)) }.getOrNull()
                    ?: return@forEach // undecryptable legacy row is kept out rather than guessed
                try {
                    val payload = convert(JSONObject(plain.toString(Charsets.UTF_8)))
                    database.items().upsert(ItemCipher.seal(key, id, times.first, maxOf(times.second, 1L), false, deviceId, payload, null))
                    migrated++
                } finally {
                    plain.fill(0)
                }
            }
            db.execSQL("DROP TABLE IF EXISTS vault_records")
            meta.delete("legacy_records_pending")
        }
        return migrated
    }

    internal fun convert(json: JSONObject): VaultItemPayload {
        val title = json.optString("title").ifBlank { "Без названия" }
        val category = json.optString("category")
        val username = json.optString("username")
        val password = json.optString("password")
        val website = json.optString("website")
        val notes = json.optString("notes")
        return when (category.lowercase()) {
            "пароль", "password", "" -> VaultItemPayload(
                type = ItemType.LOGIN, title = title, notes = notes,
                login = LoginData(username = username, password = password, urls = listOfNotNull(website.takeIf { it.isNotBlank() })),
            )
            "код 2fa", "otp", "2fa" -> VaultItemPayload(
                type = ItemType.TOTP, title = title, notes = notes,
                totp = TotpData(secret = password.replace(" ", ""), issuer = title, account = username),
            )
            else -> VaultItemPayload(
                type = ItemType.NOTE, title = title, notes = notes,
                customFields = buildList {
                    add(CustomField("Категория", category))
                    if (username.isNotBlank()) add(CustomField("Логин", username))
                    if (password.isNotBlank()) add(CustomField("Секрет", password, hidden = true))
                    if (website.isNotBlank()) add(CustomField("Сайт", website))
                },
            )
        }
    }
}
