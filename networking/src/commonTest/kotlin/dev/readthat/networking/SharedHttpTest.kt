package dev.readthat.networking

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest

class SharedHttpTest {
    @Test
    fun productionPolicyAllowsOnlyHttps() {
        val policy = TransportSecurityPolicy()

        assertTrue(policy.permits("https://api.example.test"))
        assertFalse(policy.permits("http://api.example.test"))
        assertFalse(policy.permits("http://127.0.0.1:8787"))
    }

    @Test
    fun developmentPolicyLimitsCleartextToExactLoopbackHosts() {
        val policy = TransportSecurityPolicy(allowLocalDevelopmentHttp = true)

        assertTrue(policy.permits("http://localhost:8787"))
        assertTrue(policy.permits("http://127.0.0.1:8787"))
        assertTrue(policy.permits("http://127.20.30.40:8787"))
        assertTrue(policy.permits("http://[::1]:8787"))
        assertFalse(policy.permits("http://localhost.example.com:8787"))
        assertFalse(policy.permits("http://127.0.0.1.example.com:8787"))
        assertFalse(policy.permits("http://192.168.1.20:8787"))
        assertFalse(policy.permits("http://user@localhost:8787"))
    }

    @Test
    fun cacheClientEnforcesTransportPolicyBeforeCallingNetwork() = runTest {
        var calls = 0
        val client = CachingHttpClient(
            transport = HttpTransport { calls += 1; error("should not run") },
            cache = TwoTierByteCache(MemoryByteCache(1_024), NoopByteCacheStore),
            nowMillis = { 1 },
            securityPolicy = TransportSecurityPolicy(allowLocalDevelopmentHttp = true),
        )

        assertFailsWith<IllegalArgumentException> {
            client.execute(HttpRequest("http://example.test"))
        }
        assertEquals(0, calls)
    }

    @Test
    fun memoryHitAvoidsSecondNetworkRequest() = runTest {
        var calls = 0
        val transport = HttpTransport {
            calls += 1
            HttpResponse(200, emptyMap(), byteArrayOf(1, 2, 3), "test", 1, 2)
        }
        val client = CachingHttpClient(
            transport,
            TwoTierByteCache(MemoryByteCache(1_024), NoopByteCacheStore),
            nowMillis = { 10 },
        )
        val request = HttpRequest(
            url = "https://example.test/image",
            purpose = HttpPurpose.Image,
            cacheKey = "image:v1",
            maxAgeMillis = 1_000,
        )

        client.execute(request)
        val cached = client.execute(request)

        assertEquals(1, calls)
        assertEquals(CacheTier.Memory, cached.cacheTier)
        assertContentEquals(byteArrayOf(1, 2, 3), cached.body)
    }

    @Test
    fun stableMediaIdentitySurvivesSignedUrlRotationWithoutAnotherNetworkCopy() = runTest {
        var calls = 0
        val client = CachingHttpClient(
            transport = HttpTransport {
                calls += 1
                HttpResponse(200, emptyMap(), byteArrayOf(7, 8, 9), "h3", 1, 2)
            },
            cache = TwoTierByteCache(MemoryByteCache(1_024), NoopByteCacheStore),
            nowMillis = { 10 },
        )

        val first = client.execute(HttpRequest(
            url = "https://cdn.example.test/image?token=old",
            purpose = HttpPurpose.Image,
            cacheKey = "image:asset-42:feed:v3",
            maxAgeMillis = 1_000,
        ))
        val rotated = client.execute(HttpRequest(
            url = "https://cdn.example.test/image?token=new",
            purpose = HttpPurpose.Image,
            cacheKey = "image:asset-42:feed:v3",
            maxAgeMillis = 1_000,
        ))

        assertEquals(CacheTier.Network, first.cacheTier)
        assertEquals(CacheTier.Memory, rotated.cacheTier)
        assertContentEquals(first.body, rotated.body)
        assertEquals(1, calls)
    }

    @Test
    fun concurrentEqualCacheMissesShareOneNetworkRequest() = runTest {
        var calls = 0
        val release = CompletableDeferred<Unit>()
        val client = CachingHttpClient(
            transport = HttpTransport {
                calls += 1
                release.await()
                HttpResponse(200, emptyMap(), byteArrayOf(4, 5, 6), "test", 1, 2)
            },
            cache = TwoTierByteCache(MemoryByteCache(1_024), NoopByteCacheStore),
            nowMillis = { 10 },
        )
        val request = HttpRequest(
            url = "https://example.test/image",
            purpose = HttpPurpose.Image,
            cacheKey = "same-image:v1",
            maxAgeMillis = 1_000,
        )

        val first = async { client.execute(request) }
        val second = async { client.execute(request) }
        yield()
        assertEquals(1, calls)

        release.complete(Unit)
        assertContentEquals(byteArrayOf(4, 5, 6), first.await().body)
        assertContentEquals(byteArrayOf(4, 5, 6), second.await().body)
        assertEquals(1, calls)
    }

    @Test
    fun staleL2IsReturnedWhenOffline() = runTest {
        var now = 100L
        val disk = MemoryByteCache(1_024)
        disk.write("preview", CachedBytes(byteArrayOf(9), storedAtMillis = 1))
        val client = CachingHttpClient(
            transport = HttpTransport { error("offline") },
            cache = TwoTierByteCache(MemoryByteCache(1_024), disk),
            nowMillis = { now },
        )

        val result = client.execute(
            HttpRequest(
                url = "https://example.test/preview",
                cacheKey = "preview",
                maxAgeMillis = 1,
            ),
        )

        assertEquals(CacheTier.Disk, result.cacheTier)
        assertContentEquals(byteArrayOf(9), result.body)
    }

    @Test
    fun cancellationIsNeverConvertedIntoAStaleCacheHit() = runTest {
        val disk = MemoryByteCache(1_024)
        disk.write("preview", CachedBytes(byteArrayOf(9), storedAtMillis = 1))
        val client = CachingHttpClient(
            transport = HttpTransport { throw CancellationException("viewport changed") },
            cache = TwoTierByteCache(MemoryByteCache(1_024), disk),
            nowMillis = { 100 },
        )

        assertFailsWith<CancellationException> {
            client.execute(HttpRequest(
                url = "https://example.test/preview",
                cacheKey = "preview",
                maxAgeMillis = 1,
            ))
        }
    }
}
