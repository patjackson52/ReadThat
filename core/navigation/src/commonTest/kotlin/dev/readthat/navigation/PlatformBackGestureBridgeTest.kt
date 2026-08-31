package dev.readthat.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformBackGestureBridgeTest {
    @Test
    fun nativeRequestsAreAcceptedOnlyWhenSharedNavigationEnablesBack() {
        val bridge = PlatformBackGestureBridge()

        assertFalse(bridge.isEnabled)
        assertFalse(bridge.request())

        bridge.setEnabled(true)
        assertTrue(bridge.isEnabled)
        assertTrue(bridge.request())

        bridge.setEnabled(false)
        assertFalse(bridge.request())
    }
}
