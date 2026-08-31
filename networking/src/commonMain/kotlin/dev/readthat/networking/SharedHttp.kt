package dev.readthat.networking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class HttpPurpose { Api, Image, VideoPreview, Telemetry }

enum class CacheTier { Network, Memory, Disk }

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val timeoutMillis: Long = 20_000,
    val purpose: HttpPurpose = HttpPurpose.Api,
    /** Stable media/version identity is preferred over a rotating signed URL. */
    val cacheKey: String? = null,
    val maxAgeMillis: Long = 0,
    val staleIfError: Boolean = true,
    val allowsExpensiveAccess: Boolean = true,
    val allowsConstrainedAccess: Boolean = true,
    val knownHttp3Origin: Boolean = false,
)

data class HttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val protocol: String,
    val sentAtMillis: Long,
    val receivedAtMillis: Long,
    val cacheTier: CacheTier = CacheTier.Network,
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.lastOrNull()
}

/** Platform transports are process-scoped and reused by every repository and image request. */
fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/**
 * HTTPS is mandatory except for an explicitly enabled loopback-only development
 * origin. Keeping this check above the platform stack prevents an ATS or
 * Network Security Config exception from becoming a general cleartext bypass.
 */
class TransportSecurityPolicy(
    val allowLocalDevelopmentHttp: Boolean = false,
) {
    fun permits(url: String): Boolean {
        val parsed = parseHttpUrl(url) ?: return false
        return parsed.scheme == "https" ||
            (allowLocalDevelopmentHttp && parsed.scheme == "http" && parsed.host.isLoopbackHost())
    }

    fun isHttps(url: String): Boolean = parseHttpUrl(url)?.scheme == "https"

    fun host(url: String): String? = parseHttpUrl(url)?.host

    fun requirePermitted(url: String) {
        require(permits(url)) {
            if (url.startsWith("http://", ignoreCase = true)) {
                "Cleartext HTTP is allowed only for a loopback host in an explicitly enabled development build; use HTTPS otherwise"
            } else {
                "Only a valid HTTPS URL is allowed"
            }
        }
    }
}

private data class ParsedHttpUrl(val scheme: String, val host: String)

private fun parseHttpUrl(value: String): ParsedHttpUrl? {
    if (value.isBlank() || value != value.trim() || value.any { it <= ' ' || it == '\\' }) return null
    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = value.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val authorityStart = schemeEnd + 3
    val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        .let { if (it < 0) value.length else it }
    val authority = value.substring(authorityStart, authorityEnd)
    if (authority.isBlank() || '@' in authority) return null
    val host: String
    val port: String?
    if (authority.startsWith("[")) {
        val bracket = authority.indexOf(']')
        if (bracket <= 1) return null
        host = authority.substring(1, bracket)
        port = authority.substring(bracket + 1).takeIf(String::isNotEmpty)?.removePrefix(":")
        if (authority.substring(bracket + 1).isNotEmpty() && !authority.substring(bracket + 1).startsWith(":")) {
            return null
        }
    } else {
        if (authority.count { it == ':' } > 1) return null
        host = authority.substringBefore(':')
        port = authority.substringAfter(':', missingDelimiterValue = "").takeIf(String::isNotEmpty)
    }
    if (host.isBlank() || '%' in host) return null
    if (port != null && (port.any { !it.isDigit() } || port.toIntOrNull() !in 1..65_535)) return null
    return ParsedHttpUrl(scheme, host.lowercase())
}

private fun String.isLoopbackHost(): Boolean {
    if (this == "localhost" || this == "ip6-localhost" || this == "::1" || this == "0:0:0:0:0:0:0:1") {
        return true
    }
    val octets = split('.')
    return octets.size == 4 && octets.all { part ->
        part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
    } && octets.first() == "127"
}

data class CachedBytes(val bytes: ByteArray, val storedAtMillis: Long)

interface ByteCacheStore {
    suspend fun read(key: String): CachedBytes?
    suspend fun write(key: String, value: CachedBytes)
    suspend fun remove(key: String)
    suspend fun trimToSize(maxBytes: Long)
}

object NoopByteCacheStore : ByteCacheStore {
    override suspend fun read(key: String): CachedBytes? = null
    override suspend fun write(key: String, value: CachedBytes) = Unit
    override suspend fun remove(key: String) = Unit
    override suspend fun trimToSize(maxBytes: Long) = Unit
}

class MemoryByteCache(private val maxBytes: Long) : ByteCacheStore {
    init { require(maxBytes >= 0) }

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, CachedBytes>()
    private val recency = ArrayDeque<String>()
    private var sizeBytes = 0L

    override suspend fun read(key: String): CachedBytes? = mutex.withLock {
        entries[key]?.also { touch(key) }
    }

    override suspend fun write(key: String, value: CachedBytes) = mutex.withLock {
        entries.remove(key)?.let { sizeBytes -= it.bytes.size }
        recency.remove(key)
        entries[key] = value
        recency.addLast(key)
        sizeBytes += value.bytes.size
        trimLocked(maxBytes)
    }

    override suspend fun remove(key: String) = mutex.withLock {
        entries.remove(key)?.let { sizeBytes -= it.bytes.size }
        recency.remove(key)
        Unit
    }

    override suspend fun trimToSize(maxBytes: Long) = mutex.withLock { trimLocked(maxBytes) }

    private fun touch(key: String) {
        recency.remove(key)
        recency.addLast(key)
    }

    private fun trimLocked(limit: Long) {
        while (sizeBytes > limit && recency.isNotEmpty()) {
            entries.remove(recency.removeFirst())?.let { sizeBytes -= it.bytes.size }
        }
    }
}

/** L1 memory + L2 disk. Disk hits are promoted; stale reads are opt-in on network failure. */
class TwoTierByteCache(
    private val memory: ByteCacheStore,
    private val disk: ByteCacheStore,
) {
    suspend fun read(key: String, maxAgeMillis: Long, nowMillis: Long): Pair<CachedBytes, CacheTier>? {
        memory.read(key)?.takeIf { it.isFresh(maxAgeMillis, nowMillis) }?.let {
            return it to CacheTier.Memory
        }
        disk.read(key)?.takeIf { it.isFresh(maxAgeMillis, nowMillis) }?.let {
            memory.write(key, it)
            return it to CacheTier.Disk
        }
        return null
    }

    suspend fun readStale(key: String): Pair<CachedBytes, CacheTier>? {
        memory.read(key)?.let { return it to CacheTier.Memory }
        disk.read(key)?.let {
            memory.write(key, it)
            return it to CacheTier.Disk
        }
        return null
    }

    suspend fun write(key: String, value: CachedBytes) {
        memory.write(key, value)
        disk.write(key, value)
    }

    private fun CachedBytes.isFresh(maxAgeMillis: Long, nowMillis: Long): Boolean =
        maxAgeMillis <= 0 || nowMillis - storedAtMillis <= maxAgeMillis
}

class CachingHttpClient(
    private val transport: HttpTransport,
    private val cache: TwoTierByteCache,
    private val nowMillis: () -> Long,
    private val securityPolicy: TransportSecurityPolicy = TransportSecurityPolicy(),
) : HttpTransport {
    private val keyLocksMutex = Mutex()
    private val keyLocks = mutableMapOf<String, KeyLock>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        securityPolicy.requirePermitted(request.url)
        val key = request.cacheKey?.takeIf { request.method.equals("GET", ignoreCase = true) }
        if (key != null) {
            cache.read(key, request.maxAgeMillis, nowMillis())?.let { (value, tier) ->
                return cachedResponse(value, tier)
            }
        }

        return if (key == null) {
            executeNetwork(request, null)
        } else {
            withKeyLock(key) {
                // A preceding caller may have populated either tier while this request waited.
                cache.read(key, request.maxAgeMillis, nowMillis())?.let { (value, tier) ->
                    return@withKeyLock cachedResponse(value, tier)
                }
                executeNetwork(request, key)
            }
        }
    }

    private suspend fun executeNetwork(request: HttpRequest, key: String?): HttpResponse =
        try {
            transport.execute(request).also { response ->
                if (key != null && response.status in 200..299) {
                    cache.write(key, CachedBytes(response.body, nowMillis()))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val stale = key?.takeIf { request.staleIfError }?.let { cache.readStale(it) }
            stale?.let { (value, tier) -> cachedResponse(value, tier) } ?: throw error
        }

    /** Serializes only equal stable cache identities; unrelated media remains fully concurrent. */
    private suspend fun <T> withKeyLock(key: String, block: suspend () -> T): T {
        val entry = keyLocksMutex.withLock {
            keyLocks.getOrPut(key) { KeyLock() }.also { it.users += 1 }
        }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            keyLocksMutex.withLock {
                entry.users -= 1
                if (entry.users == 0 && keyLocks[key] === entry) keyLocks.remove(key)
            }
        }
    }

    private fun cachedResponse(value: CachedBytes, tier: CacheTier) = HttpResponse(
        status = 200,
        headers = emptyMap(),
        body = value.bytes,
        protocol = "cache",
        sentAtMillis = value.storedAtMillis,
        receivedAtMillis = value.storedAtMillis,
        cacheTier = tier,
    )

    private class KeyLock(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )
}

internal fun stableCacheFileName(key: String): String {
    var hash = -0x340d631b7bdddcdbL
    key.encodeToByteArray().forEach { byte ->
        hash = hash xor (byte.toLong() and 0xff)
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}
