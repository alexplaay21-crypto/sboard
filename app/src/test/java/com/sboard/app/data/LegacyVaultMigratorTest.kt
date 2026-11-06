package com.sboard.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyVaultMigratorTest {
    @Test fun passwordCategoryBecomesLogin() {
        val p = LegacyVaultMigrator.convert(JSONObject(mapOf("title" to "Mail", "category" to "Пароль", "username" to "a@b.c", "password" to "x", "website" to "mail.example", "notes" to "")))
        assertEquals(ItemType.LOGIN, p.type)
        assertEquals("a@b.c", p.login?.username)
        assertEquals(listOf("mail.example"), p.login?.urls)
    }

    @Test fun otpCategoryBecomesTotp() {
        val p = LegacyVaultMigrator.convert(JSONObject(mapOf("title" to "Git", "category" to "Код 2FA", "username" to "me", "password" to "JBSW Y3DP", "website" to "", "notes" to "")))
        assertEquals(ItemType.TOTP, p.type)
        assertEquals("JBSWY3DP", p.totp?.secret)
    }

    @Test fun otherCategoriesArePreservedLosslessly() {
        val p = LegacyVaultMigrator.convert(JSONObject(mapOf("title" to "Wi-Fi", "category" to "Wi-Fi", "username" to "home", "password" to "pw", "website" to "", "notes" to "n")))
        assertEquals(ItemType.NOTE, p.type)
        assertEquals("n", p.notes)
        assertEquals(true, p.customFields.first { it.value == "pw" }.hidden)
    }
}
