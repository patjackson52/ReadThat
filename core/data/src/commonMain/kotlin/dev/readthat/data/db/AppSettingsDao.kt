package dev.readthat.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 'global'")
    fun observe(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 'global'")
    suspend fun get(): AppSettingsEntity?

    /** Upgrade shims may seed preferences, but must never overwrite a newer in-app write. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(settings: AppSettingsEntity): Long

    @Upsert
    suspend fun upsert(settings: AppSettingsEntity)
}
