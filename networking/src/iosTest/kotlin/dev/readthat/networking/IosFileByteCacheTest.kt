@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.readthat.networking

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import platform.posix.usleep

class IosFileByteCacheTest {
    @Test
    fun persistedAccessTimeDrivesEvictionAfterCacheRecreation() = runTest {
        val namespace = "lru-test-${Random.nextLong().toULong()}"
        val first = IosFileByteCache(namespace, maxBytes = TWO_SINGLE_BYTE_ENTRIES)

        first.write("a", CachedBytes(byteArrayOf(1), storedAtMillis = 1))
        advanceFileClock()
        first.write("b", CachedBytes(byteArrayOf(2), storedAtMillis = 2))
        advanceFileClock()

        // A read must persist recency on the file itself rather than in the cache instance.
        assertContentEquals(byteArrayOf(1), first.read("a")?.bytes)
        advanceFileClock()

        val reopened = IosFileByteCache(namespace, maxBytes = TWO_SINGLE_BYTE_ENTRIES)
        reopened.write("c", CachedBytes(byteArrayOf(3), storedAtMillis = 3))

        assertContentEquals(byteArrayOf(1), reopened.read("a")?.bytes)
        assertNull(reopened.read("b"))
        assertContentEquals(byteArrayOf(3), reopened.read("c")?.bytes)

        reopened.remove("a")
        reopened.remove("c")
    }

    private fun advanceFileClock() {
        usleep(20_000u)
    }

    private companion object {
        const val TWO_SINGLE_BYTE_ENTRIES = 18L // Two eight-byte headers plus two payload bytes.
    }
}
