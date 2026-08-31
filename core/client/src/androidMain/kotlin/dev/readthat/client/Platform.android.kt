package dev.readthat.client

import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun platformEpochMillis(): Long = System.currentTimeMillis()
internal actual fun platformElapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
internal actual fun platformMutationId(prefix: String): String = "$prefix:${UUID.randomUUID()}"

internal actual suspend fun readStagedMedia(path: String, offset: Long, byteCount: Int): ByteArray =
    withContext(Dispatchers.IO) {
        RandomAccessFile(path, "r").use { file ->
            file.seek(offset)
            val result = ByteArray(byteCount)
            var read = 0
            while (read < byteCount) {
                val count = file.read(result, read, byteCount - read)
                if (count < 0) error("Staged media ended before its recorded size")
                read += count
            }
            result
        }
    }

internal actual suspend fun deleteStagedMedia(path: String) {
    withContext(Dispatchers.IO) { File(path).delete() }
}
