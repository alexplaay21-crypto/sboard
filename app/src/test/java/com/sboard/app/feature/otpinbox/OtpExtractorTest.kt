package com.sboard.app.feature.otpinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpExtractorTest {
    @Test fun russianSms() = assertEquals("123456", OtpExtractor.extract("Ваш код подтверждения: 123456. Никому не сообщайте"))
    @Test fun googleFormat() = assertEquals("482913", OtpExtractor.extract("G-482913 is your Google verification code."))
    @Test fun telegramLogin() = assertEquals("54821", OtpExtractor.extract("Login code: 54821. Do not give this code to anyone"))
    @Test fun spacedCode() = assertEquals("123456", OtpExtractor.extract("Код для входа 123 456"))
    @Test fun whatsapp() = assertEquals("719204", OtpExtractor.extract("Ваш код WhatsApp: 719-204"))
    @Test fun bankWithAmount() = assertEquals("4821", OtpExtractor.extract("Покупка 1500 ₽ в MAGAZIN. Код: 4821"))
    @Test fun noKeyword() = assertNull(OtpExtractor.extract("Встречаемся в 1830 у входа"))
    @Test fun moneyOnly() = assertNull(OtpExtractor.extract("Списание 25000 руб. Баланс 120000 руб"))
    @Test fun ignoresPlainChat() = assertNull(OtpExtractor.extract("Привет! Как дела?"))
}
