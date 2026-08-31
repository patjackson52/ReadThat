package dev.readthat.profile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedProfilePolicyTest {
    @Test
    fun `account age uses bounded calendar-scale labels`() {
        val day = 86_400_000L
        assertEquals("New", accountAgeLabel(0L, 10L * day))
        assertEquals("Today", accountAgeLabel(10L * day, 10L * day))
        assertEquals("29d", accountAgeLabel(0L + day, 30L * day))
        assertEquals("2mo", accountAgeLabel(day, 61L * day))
        assertEquals("2y", accountAgeLabel(day, 731L * day))
        assertEquals("New", accountAgeLabel(5L * day, 4L * day))
    }

    @Test
    fun `editor save policy is shared by both hosts`() {
        assertTrue(ProfileEditorUiState("Reader", "About").canSave)
        assertFalse(ProfileEditorUiState("", "About").canSave)
        assertFalse(ProfileEditorUiState("Reader", "About", saving = true).canSave)
        assertFalse(ProfileEditorUiState("x".repeat(51), "About").canSave)
        assertFalse(ProfileEditorUiState("Reader", "x".repeat(501)).canSave)
    }
}
