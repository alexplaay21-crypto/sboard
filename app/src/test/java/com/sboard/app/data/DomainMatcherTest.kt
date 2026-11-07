package com.sboard.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {
    @Test fun normalizesUrls() {
        assertEquals("example.com", DomainMatcher.hostOf("https://www.example.com/login?x=1"))
        assertEquals("example.com", DomainMatcher.hostOf("example.com"))
    }

    @Test fun matchesSameRegistrableDomain() {
        assertTrue(DomainMatcher.matches("example.com", "login.example.com"))
        assertTrue(DomainMatcher.matches("https://accounts.example.com", "example.com"))
    }

    @Test fun rejectsLookalikes() {
        assertFalse(DomainMatcher.matches("example.com", "example.com.evil.net"))
        assertFalse(DomainMatcher.matches("example.com", "notexample.com"))
        assertFalse(DomainMatcher.matches("shop.co.uk", "other.co.uk"))
        assertFalse(DomainMatcher.matches("mail.ru", "mail.ru.phish.com"))
    }

    @Test fun multiLabelSuffixes() {
        assertEquals("shop.co.uk", DomainMatcher.registrableDomain("www.shop.co.uk"))
        assertEquals("site.com.ru", DomainMatcher.registrableDomain("a.site.com.ru"))
    }
}
