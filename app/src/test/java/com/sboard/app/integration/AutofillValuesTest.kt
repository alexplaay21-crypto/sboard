package com.sboard.app.integration

import android.view.View
import android.view.autofill.AutofillId
import androidx.test.core.app.ApplicationProvider
import com.sboard.app.data.*
import com.sboard.app.feature.autofill.AutofillDatasets
import com.sboard.app.feature.autofill.FieldKind
import com.sboard.app.feature.autofill.ParsedForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class AutofillValuesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun id(): AutofillId = View(context).autofillId
    private fun item(p: VaultItemPayload) = VaultItem("i", 1, 2, p)
    private fun form(vararg kinds: FieldKind, domain: String? = null, pkg: String = "com.example") =
        ParsedForm(kinds.associateWith { listOf(id()) }, domain, pkg, emptyMap())

    @Test fun loginFillsUsernameAndPassword() {
        val f = form(FieldKind.USERNAME, FieldKind.PASSWORD)
        val v = AutofillDatasets.values(item(VaultItemPayload(ItemType.LOGIN, "Mail", login = LoginData("alex", "S3cret!pass"))), f)
        assertEquals("alex", v[f.fields[FieldKind.USERNAME]!!.single()])
        assertEquals("S3cret!pass", v[f.fields[FieldKind.PASSWORD]!!.single()])
    }

    @Test fun cardFillsNumberExpiryAndCvv() {
        val f = form(FieldKind.CARD_NUMBER, FieldKind.CARD_EXP_DATE, FieldKind.CARD_CVC, FieldKind.CARD_HOLDER)
        val v = AutofillDatasets.values(item(VaultItemPayload(ItemType.CARD, "Visa",
            card = CardData("ALEX P", "4111 1111 1111 1111", "3", "2031", "123"))), f)
        assertEquals("4111111111111111", v[f.fields[FieldKind.CARD_NUMBER]!!.single()])
        assertEquals("03/31", v[f.fields[FieldKind.CARD_EXP_DATE]!!.single()])
        assertEquals("123", v[f.fields[FieldKind.CARD_CVC]!!.single()])
        assertEquals("ALEX P", v[f.fields[FieldKind.CARD_HOLDER]!!.single()])
    }

    @Test fun identityFillsPersonalData() {
        val f = form(FieldKind.GIVEN_NAME, FieldKind.FAMILY_NAME, FieldKind.PHONE, FieldKind.EMAIL, FieldKind.ADDRESS, FieldKind.POSTAL_CODE, FieldKind.CITY)
        val v = AutofillDatasets.values(item(VaultItemPayload(ItemType.IDENTITY, "Me", identity = IdentityData(
            firstName = "Иван", lastName = "Петров", email = "ivan@mail.ru", phone = "+79001234567",
            address1 = "ул. Ленина, 1", city = "Москва", postalCode = "101000"))), f)
        assertEquals("Иван", v[f.fields[FieldKind.GIVEN_NAME]!!.single()])
        assertEquals("Петров", v[f.fields[FieldKind.FAMILY_NAME]!!.single()])
        assertEquals("+79001234567", v[f.fields[FieldKind.PHONE]!!.single()])
        assertEquals("ivan@mail.ru", v[f.fields[FieldKind.EMAIL]!!.single()])
        assertEquals("101000", v[f.fields[FieldKind.POSTAL_CODE]!!.single()])
        assertEquals("Москва", v[f.fields[FieldKind.CITY]!!.single()])
    }

    @Test fun appIsMatchedToItsWebsite() {
        val insta = item(VaultItemPayload(ItemType.LOGIN, "Instagram", login = LoginData("a", "b", urls = listOf("https://www.instagram.com"))))
        val loginForm = form(FieldKind.USERNAME, FieldKind.PASSWORD, pkg = "com.instagram.android")
        assertEquals(1, AutofillDatasets.matching(listOf(insta), loginForm).size)
        assertTrue(AutofillDatasets.matching(listOf(insta), form(FieldKind.USERNAME, FieldKind.PASSWORD, pkg = "com.example.shop")).isEmpty())
        // A browser without a web domain never gets app-name guesses.
        assertTrue(AutofillDatasets.matching(listOf(insta), form(FieldKind.USERNAME, FieldKind.PASSWORD, pkg = "com.android.chrome")).isEmpty())
        // Website forms match by registrable domain only.
        assertEquals(1, AutofillDatasets.matching(listOf(insta), form(FieldKind.PASSWORD, domain = "accounts.instagram.com")).size)
        assertFalse(AutofillDatasets.matching(listOf(insta), form(FieldKind.PASSWORD, domain = "instagram.com.evil.net")).isNotEmpty())
    }
}
