package dev.readthat.settings.ui

import dev.readthat.client.SettingsPreference
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedSettingsPolicyTest {
    @Test
    fun everyPersistedPreferenceHasExactlyOneStableRow() {
        assertEquals(SettingsPreference.entries, settingsPreferenceOrder)
        assertEquals(settingsPreferenceOrder.size, settingsPreferenceOrder.distinct().size)
    }
}
