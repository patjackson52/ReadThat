package dev.readthat.client

import android.content.Context
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.AppSettingsEntity

/**
 * One-way upgrade bridge from the mature Android app's pre-Room preferences.
 *
 * Product settings are owned by the shared Room/controller stack. This platform shim only reads
 * the retired Android storage format. [insertIfAbsent] makes the migration race-safe if a user
 * changes a shared setting while this small background upgrade is still running.
 */
internal suspend fun migrateLegacyAndroidSettings(
    context: Context,
    database: AppDatabase,
) {
    val legacy = context.applicationContext.getSharedPreferences(
        LEGACY_SETTINGS_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    if (LEGACY_SETTINGS_KEYS.none(legacy::contains)) return

    database.appSettingsDao().insertIfAbsent(
        AppSettingsEntity(
            darkTheme = legacy.getBoolean("dark_theme", false),
            compactPosts = legacy.getBoolean("compact_posts", false),
            autoplayVideo = legacy.getBoolean("autoplay_video", true),
            autoplayOnMetered = legacy.getBoolean("autoplay_metered", false),
            reduceDataOnMetered = legacy.getBoolean("reduce_data_metered", true),
            reduceAnimations = legacy.getBoolean("reduce_animations", false),
            blurMatureMedia = legacy.getBoolean("blur_mature_media", true),
            updatedAt = platformEpochMillis(),
        ),
    )
    legacy.edit().clear().apply()
}

private const val LEGACY_SETTINGS_PREFERENCES = "app_settings"
private val LEGACY_SETTINGS_KEYS = setOf(
    "dark_theme",
    "compact_posts",
    "autoplay_video",
    "autoplay_metered",
    "reduce_data_metered",
    "reduce_animations",
    "blur_mature_media",
)
