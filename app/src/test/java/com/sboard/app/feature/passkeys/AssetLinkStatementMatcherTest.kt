package com.sboard.app.feature.passkeys

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetLinkStatementMatcherTest {
    private val fingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"

    @Test
    fun acceptsExactAndroidPackageRelationAndFingerprint() {
        val statements = listOf(
            AssetLinkStatement(
                namespace = "android_app",
                packageName = "com.example.client",
                relations = setOf("delegate_permission/common.get_login_creds"),
                certificateFingerprints = setOf(fingerprint.lowercase())
            )
        )
        assertTrue(
            AssetLinkStatementMatcher.matches(
                statements,
                "com.example.client",
                fingerprint.replace(":", "")
            )
        )
    }

    @Test
    fun rejectsWrongPackage() {
        assertFalse(
            AssetLinkStatementMatcher.matches(
                listOf(statement()),
                "com.example.other",
                fingerprint
            )
        )
    }

    @Test
    fun rejectsWrongRelation() {
        assertFalse(
            AssetLinkStatementMatcher.matches(
                listOf(statement(relations = setOf("delegate_permission/common.handle_all_urls"))),
                "com.example.client",
                fingerprint
            )
        )
    }

    @Test
    fun rejectsWrongCertificate() {
        assertFalse(
            AssetLinkStatementMatcher.matches(
                listOf(statement()),
                "com.example.client",
                "00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00"
            )
        )
    }

    private fun statement(
        relations: Set<String> = setOf("delegate_permission/common.get_login_creds")
    ) = AssetLinkStatement("android_app", "com.example.client", relations, setOf(fingerprint))
}
