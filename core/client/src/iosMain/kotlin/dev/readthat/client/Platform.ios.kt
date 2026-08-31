package dev.readthat.client

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUUID
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.gettimeofday
import platform.posix.open
import platform.posix.pread
import platform.posix.timeval

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformEpochMillis(): Long = memScoped {
    val time = alloc<timeval>()
    gettimeofday(time.ptr, null)
    time.tv_sec * 1_000L + time.tv_usec / 1_000L
}
internal actual fun platformElapsedRealtimeMillis(): Long =
    (NSProcessInfo.processInfo.systemUptime * 1_000.0).toLong()
internal actual fun platformMutationId(prefix: String): String = "$prefix:${NSUUID().UUIDString}"

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun readStagedMedia(path: String, offset: Long, byteCount: Int): ByteArray =
    withContext(Dispatchers.Default) {
        val descriptor = open(path, O_RDONLY)
        require(descriptor >= 0) { "Staged media is unavailable" }
        try {
            ByteArray(byteCount).also { result ->
                val read = result.usePinned { pinned ->
                    pread(descriptor, pinned.addressOf(0), byteCount.toULong(), offset)
                }
                require(read == byteCount.toLong()) { "Staged media ended before its recorded size" }
            }
        } finally {
            close(descriptor)
        }
    }

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun deleteStagedMedia(path: String) {
    withContext(Dispatchers.Default) { NSFileManager.defaultManager.removeItemAtPath(path, null) }
}
