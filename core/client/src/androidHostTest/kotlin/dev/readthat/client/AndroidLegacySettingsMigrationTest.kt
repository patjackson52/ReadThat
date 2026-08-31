package dev.readthat.client

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.AppSettingsEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidLegacySettingsMigrationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `legacy preferences seed the shared Room row and are retired`() = runTest {
        val legacy = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        legacy.edit()
            .putBoolean("dark_theme", true)
            .putBoolean("compact_posts", true)
            .putBoolean("autoplay_video", false)
            .commit()

        migrateLegacyAndroidSettings(context, database)

        val migrated = requireNotNull(database.appSettingsDao().get())
        assertEquals(true, migrated.darkTheme)
        assertEquals(true, migrated.compactPosts)
        assertEquals(false, migrated.autoplayVideo)
        assertFalse(legacy.contains("dark_theme"))
    }

    @Test
    fun `legacy migration never overwrites a shared settings write`() = runTest {
        val committed = settings(darkTheme = false, updatedAt = 200L)
        database.appSettingsDao().upsert(committed)
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("dark_theme", true)
            .commit()

        migrateLegacyAndroidSettings(context, database)

        assertEquals(committed, database.appSettingsDao().get())
    }
}

private fun settings(darkTheme: Boolean, updatedAt: Long) = AppSettingsEntity(
    darkTheme = darkTheme,
    compactPosts = false,
    autoplayVideo = true,
    autoplayOnMetered = false,
    reduceDataOnMetered = true,
    reduceAnimations = false,
    blurMatureMedia = true,
    updatedAt = updatedAt,
)
