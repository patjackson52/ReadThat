package dev.readthat.data.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase

fun getDatabaseBuilder(context: Context): RoomDatabase.Builder<AppDatabase> {
    val appContext = context.applicationContext
    return Room.databaseBuilder<AppDatabase>(
        context = appContext,
        name = appContext.getDatabasePath(AppDatabase.NAME).absolutePath,
    )
}

/** Android composition-root access to the same KMP Room database used by iOS. */
object AndroidDatabaseProvider {
    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
        instance ?: buildAppDatabase(getDatabaseBuilder(context)).also { instance = it }
    }
}
