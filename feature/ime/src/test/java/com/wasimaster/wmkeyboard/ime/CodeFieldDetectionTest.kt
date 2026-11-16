package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeFieldDetectionTest {

    private fun codeField(vararg names: String?) =
        looksLikeCodeField(FieldKind.TEXT, *names)

    @Test
    fun `a number field is a code box on its shape alone`() {
        assertTrue(looksLikeCodeField(FieldKind.NUMBER))
        assertTrue(looksLikeCodeField(FieldKind.NUMBER, "Amount"))
    }

    @Test
    fun `a plain text field with nothing to say is not one`() {
        assertFalse(looksLikeCodeField(FieldKind.TEXT))
        assertFalse(codeField(null, "", "Message"))
        assertFalse(codeField("Search"))
    }

    @Test
    fun `the field's own words name it`() {
        assertTrue(codeField("Enter OTP"))
        assertTrue(codeField("Verification code"))
        assertTrue(codeField("One-time passcode"))
        assertTrue(codeField("Enter the 6-digit PIN"))
        assertTrue(codeField("2FA"))
    }

    @Test
    fun `resource ids and camel case come apart the same way`() {
        assertTrue(codeField("com.bank:id/otp_edit_text"))
        assertTrue(codeField("otpInput"))
        assertTrue(codeField("verificationCodeField"))
        // "OTP" stays one word rather than splitting at every capital.
        assertTrue(codeField("OTPField"))
    }

    @Test
    fun `any one of the three names is enough`() {
        assertTrue(codeField(null, null, "smsCode"))
        assertTrue(codeField(null, "One-time code", null))
    }

    @Test
    fun `the other kinds of code are not code boxes`() {
        assertFalse(codeField("ZIP code"))
        assertFalse(codeField("Postal code"))
        assertFalse(codeField("Country code"))
        assertFalse(codeField("Promo code"))
        assertFalse(codeField("Referral code"))
        assertFalse(codeField("com.shop:id/coupon_code"))
        assertFalse(codeField("Language code"))
    }

    @Test
    fun `a deny word only silences the code beside it`() {
        // Both in one label: the verification code still wins.
        assertTrue(codeField("ZIP code and verification code"))
        // The deny list never touches the unambiguous words.
        assertTrue(codeField("Postal OTP"))
    }

    @Test
    fun `a box that names a password or PIN is a secret one (#1404)`() {
        assertTrue(looksLikeSecretField("Enter your 4-digit PIN"))
        assertTrue(looksLikeSecretField(null, null, "loginPassword"))
        assertTrue(looksLikeSecretField("Mot de passe"))
        assertTrue(looksLikeSecretField("请输入密码"))
        assertTrue(looksLikeSecretField("CVV"))
        assertFalse(looksLikeSecretField("Message"))
        assertFalse(looksLikeSecretField("Pinned notes"))
        assertFalse(looksLikeSecretField(null, "", " "))
    }
}
