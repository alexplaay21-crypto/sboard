package com.sboard.app.feature.tools.generator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGeneratorTest {
    @Test
    fun generatedPasswordHasRequestedLength() {
        val password = PasswordGenerator.generate(32, true, true, true, true)
        assertEquals(32, password.length)
    }

    @Test
    fun generatedPasswordContainsEveryEnabledGroup() {
        repeat(500) {
            val password = PasswordGenerator.generate(24, true, true, true, true)
            assertTrue(password.any { it in "ABCDEFGHJKLMNPQRSTUVWXYZ" })
            assertTrue(password.any { it in "abcdefghijkmnopqrstuvwxyz" })
            assertTrue(password.any { it in "23456789" })
            assertTrue(password.any { it in "!@#$%^&*()-_=+[]{}?" })
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWhenNoCharacterGroupSelected() {
        PasswordGenerator.generate(16, false, false, false, false)
    }
}
