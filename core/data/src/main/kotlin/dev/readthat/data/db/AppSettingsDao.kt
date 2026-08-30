package dev.readthat.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 'global'")
    fun observe(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 'global'")
    suspend fun get(): AppSettingsEntity?

    @Upsert
    suspend fun upsert(settings: AppSettingsEntity)
}
