@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.readthat.networking

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/** Stable-key application-private L2 and the sole persistent cache for shared media bytes. */
class IosFileByteCache(
    name: String,
    private val maxBytes: Long,
) : ByteCacheStore {
    private val manager = NSFileManager.defaultManager
    private val root = (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String).orEmpty() + "/ReadThat/$name"
    private val mutex = Mutex()

    init { manager.createDirectoryAtPath(root, true, null, null) }

    override suspend fun read(key: String): CachedBytes? = mutex.withLock {
        val name = stableCacheFileName(key)
        val payload = NSData.dataWithContentsOfFile(path(name))?.toByteArray() ?: return@withLock null
        if (payload.size < HEADER_BYTES) {
            manager.removeItemAtPath(path(name), null)
            return@withLock null
        }
        touch(name)
        CachedBytes(
            bytes = payload.copyOfRange(HEADER_BYTES, payload.size),
            storedAtMillis = payload.readLong(),
        )
    }

    override suspend fun write(key: String, value: CachedBytes) = mutex.withLock {
        manager.createDirectoryAtPath(root, true, null, null)
        val name = stableCacheFileName(key)
        val payload = ByteArray(HEADER_BYTES + value.bytes.size)
        payload.writeLong(value.storedAtMillis)
        value.bytes.copyInto(payload, HEADER_BYTES)
        payload.toNSData().writeToFile(path(name), atomically = true)
        trimLocked(maxBytes)
    }

    override suspend fun remove(key: String) = mutex.withLock {
        val name = stableCacheFileName(key)
        manager.removeItemAtPath(path(name), null)
        Unit
    }

    override suspend fun trimToSize(maxBytes: Long) = mutex.withLock { trimLocked(maxBytes) }

    private fun trimLocked(limit: Long) {
        val names = manager.contentsOfDirectoryAtPath(root, null)
            ?.mapNotNull { it as? String }
            .orEmpty()
        val sized = names.mapNotNull { name ->
            val attributes = manager.attributesOfItemAtPath(path(name), null) ?: return@mapNotNull null
            val size = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: return@mapNotNull null
            val modifiedAt = (attributes[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970
                ?: Double.NEGATIVE_INFINITY
            CacheFile(name, size, modifiedAt)
        }.sortedWith(compareBy(CacheFile::modifiedAt).thenBy(CacheFile::name))
        var total = sized.sumOf(CacheFile::size)
        sized.forEach { (name, size) ->
            if (total <= limit) return
            if (manager.removeItemAtPath(path(name), null)) {
                total -= size
            }
        }
    }

    /** Persist LRU order so a relaunch does not make every existing file equally old. */
    private fun touch(name: String) {
        manager.setAttributes(
            mapOf(NSFileModificationDate to NSDate()),
            ofItemAtPath = path(name),
            error = null,
        )
    }

    private fun path(name: String) = "$root/$name"

    private fun ByteArray.writeLong(value: Long) {
        repeat(HEADER_BYTES) { index -> this[index] = (value ushr (56 - index * 8)).toByte() }
    }

    private fun ByteArray.readLong(): Long {
        var value = 0L
        repeat(HEADER_BYTES) { index -> value = (value shl 8) or (this[index].toLong() and 0xffL) }
        return value
    }

    private data class CacheFile(
        val name: String,
        val size: Long,
        val modifiedAt: Double,
    )

    private companion object { const val HEADER_BYTES = 8 }
}
