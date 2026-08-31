package dev.readthat.shell.ui

import dev.readthat.navigation.AppDestination
import dev.readthat.navigation.AppNavigationPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedBottomNavigationPolicyTest {
    @Test
    fun canonicalOrderKeepsCreateBetweenHomeAndActivity() {
        assertEquals(
            listOf(
                AppDestination.Feed,
                AppDestination.CreatePost(),
                AppDestination.Activity,
                AppDestination.Profile,
            ),
            AppNavigationPolicy.primaryNavigationDestinations,
        )
    }
}
