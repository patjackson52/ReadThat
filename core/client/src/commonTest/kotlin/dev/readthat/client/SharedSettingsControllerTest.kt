package dev.readthat.client

import dev.readthat.shared.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SharedSettingsControllerTest {
    @Test
    fun rapidPreferencesPersistAsOneCompleteLatestSnapshot() = runTest {
        val source = FakeSettingsDataSource()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = SharedSettingsController(source, scope)

        controller.setPreference(SettingsPreference.DarkTheme, true)
        controller.setPreference(SettingsPreference.CompactPosts, true)
        controller.setPreference(SettingsPreference.AutoplayVideo, false)
        advanceUntilIdle()

        assertTrue(source.settings.value.darkTheme)
        assertTrue(source.settings.value.compactPosts)
        assertFalse(source.settings.value.autoplayVideo)
        assertEquals(source.settings.value, controller.state.value.settings)
        assertFalse(controller.state.value.saving)
        scope.cancel()
    }

    @Test
    fun failedWriteRollsBackToCommittedRoomSnapshot() = runTest {
        val committed = AppSettings(darkTheme = false, compactPosts = true)
        val source = FakeSettingsDataSource(committed).apply { failure = IllegalStateException("disk full") }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = SharedSettingsController(source, scope)

        controller.setPreference(SettingsPreference.DarkTheme, true)
        assertTrue(controller.state.value.settings.darkTheme)
        advanceUntilIdle()

        assertEquals(committed, controller.state.value.settings)
        assertEquals("disk full", controller.state.value.error)
        assertFalse(controller.state.value.saving)
        scope.cancel()
    }
}

private class FakeSettingsDataSource(
    initial: AppSettings = AppSettings(),
) : SharedSettingsDataSource {
    private val mutableSettings = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = mutableSettings
    var failure: Throwable? = null

    override suspend fun replaceSettings(settings: AppSettings) {
        failure?.let { throw it }
        mutableSettings.value = settings
    }
}
