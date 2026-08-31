package dev.readthat.data.db

import androidx.room3.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Exercises the real Room 3 + bundled SQLite stack used by the iOS application. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosRoomPersistenceTest {
    @Test
    fun documentsSurviveReopenAndRemainAccountScoped() = runTest {
        val path = NSTemporaryDirectory() + "readthat-room-${NSUUID().UUIDString}.db"
        val document = CachedDocumentEntity(
            accountId = "account-a",
            cacheKey = "comments:post-1",
            payloadJson = "{\"roots\":[]}",
            updatedAt = 42L,
            validator = "etag-1",
        )
        var database: AppDatabase? = null
        try {
            database = openDatabase(path)
            database.cachedDocumentDao().upsert(document)
            assertEquals(
                document,
                database.cachedDocumentDao().observe(document.accountId, document.cacheKey).first(),
            )
            database.close()
            database = null

            database = openDatabase(path)
            assertEquals(
                document,
                database.cachedDocumentDao().get(document.accountId, document.cacheKey),
            )
            assertNull(database.cachedDocumentDao().get("account-b", document.cacheKey))
        } finally {
            database?.close()
            removeDatabaseFiles(path)
        }
    }

    @Test
    fun pendingPostOutboxSurvivesReopenAndResumesProgress() = runTest {
        val path = NSTemporaryDirectory() + "readthat-room-${NSUUID().UUIDString}.db"
        val pending = PendingPostEntity(
            mutationId = "mutation-1",
            accountId = "account-a",
            subreddit = "readthateng",
            kind = "image",
            title = "Offline draft",
            body = "Queued without a network",
            linkUrl = "",
            localPath = "/private/offline/image.jpg",
            contentType = "image/jpeg",
            byteSize = 1_024L,
            width = 320,
            height = 240,
            durationSeconds = null,
            mediaId = null,
            state = "queued",
            remotePostId = null,
            lastError = null,
            createdAt = 42L,
        )
        var database: AppDatabase? = null
        try {
            database = openDatabase(path)
            database.postOutboxDao().upsert(pending)
            database.close()
            database = null

            database = openDatabase(path)
            assertEquals(listOf(pending), database.postOutboxDao().resumable(pending.accountId))
            database.postOutboxDao().updateProgress(
                mutationId = pending.mutationId,
                state = "uploading",
                mediaId = "media-1",
                lastError = null,
            )
            database.close()
            database = null

            database = openDatabase(path)
            assertEquals(
                pending.copy(state = "uploading", mediaId = "media-1"),
                database.postOutboxDao().get(pending.mutationId),
            )
        } finally {
            database?.close()
            removeDatabaseFiles(path)
        }
    }

    private fun openDatabase(path: String): AppDatabase = buildAppDatabase(
        Room.databaseBuilder<AppDatabase>(name = path),
    )

    private fun removeDatabaseFiles(path: String) {
        val files = NSFileManager.defaultManager
        listOf(path, "$path-shm", "$path-wal").forEach { candidate ->
            if (files.fileExistsAtPath(candidate)) files.removeItemAtPath(candidate, null)
        }
    }
}
