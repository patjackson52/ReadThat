package dev.readthat.auth.ui

import dev.readthat.shared.AuthForm
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedAuthScreenPolicyTest {
    @Test
    fun restoredFormStateReopensTheFormInsteadOfDiscardingInput() {
        assertFalse(shouldOpenAuthForm(AuthForm()))
        assertTrue(shouldOpenAuthForm(AuthForm(username = "reader_1")))
        assertTrue(shouldOpenAuthForm(AuthForm(error = "Try again")))
    }
}
