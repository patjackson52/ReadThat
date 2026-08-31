package dev.readthat.data.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
fun getDatabaseBuilder(): RoomDatabase.Builder<AppDatabase> {
    val directory = requireNotNull(
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )?.path,
    )
    return Room.databaseBuilder<AppDatabase>(name = "$directory/${AppDatabase.NAME}")
}

/** Process-wide iOS database. Its Room flows are the source of truth for shared MVVM state. */
object IosDatabaseProvider {
    val database: AppDatabase by lazy { buildAppDatabase(getDatabaseBuilder()) }
}
