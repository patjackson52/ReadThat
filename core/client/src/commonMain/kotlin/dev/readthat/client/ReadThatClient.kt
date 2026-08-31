package dev.readthat.client

import dev.readthat.data.db.AccountEntity
import dev.readthat.data.db.AppDatabase
import dev.readthat.networking.CacheTier
import dev.readthat.networking.HttpPurpose
import dev.readthat.networking.HttpRequest
import dev.readthat.networking.HttpResponse
import dev.readthat.networking.HttpTransport
import dev.readthat.networking.TransportSecurityPolicy
import dev.readthat.observability.PerformanceEvent
import dev.readthat.observability.PerformanceBatch
import dev.readthat.observability.PerformanceWireFormat
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceOutcome
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.ProductAnalyticsBatch
import dev.readthat.observability.ProductAnalyticsWireFormat
import dev.readthat.observability.performanceTimer
import dev.readthat.shared.SessionState
import dev.readthat.shared.UserProfile
import dev.readthat.sharing.SharePayload
import dev.readthat.sharing.SharePayloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ClientConfiguration(
    val baseUrl: String,
    val appVersion: String,
    val platform: String,
    val demoUsername: String = "",
    val demoPassword: String = "",
    val allowLocalDevelopmentHttp: Boolean = false,
) {
    val normalizedBaseUrl = baseUrl.trimEnd('/')
    val transportSecurity = TransportSecurityPolicy(allowLocalDevelopmentHttp)
    val enabled: Boolean get() = transportSecurity.permits(normalizedBaseUrl)

    fun isKnownHttp3Origin(url: String): Boolean {
        if (!transportSecurity.isHttps(url)) return false
        val host = transportSecurity.host(url) ?: return false
        val apiHost = transportSecurity.host(normalizedBaseUrl)
        return host == apiHost || host == "imagedelivery.net" ||
            host.endsWith(".videodelivery.net") || host.endsWith(".cloudflarestream.com")
    }
}

class ReadThatHttpException(val status: Int, message: String) : Exception(message)

sealed interface ReadThatConditionalResponse {
    data object NotModified : ReadThatConditionalResponse
    data class Body(val body: JsonElement, val validator: String?) : ReadThatConditionalResponse
}

/** One authenticated client, transport pool, token set and D1 consistency bookmark. */
class ReadThatClient(
    val configuration: ClientConfiguration,
    private val transport: HttpTransport,
    private val database: AppDatabase,
    private val sessionStore: SecureSessionStore,
) {
    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        explicitNulls = false
        encodeDefaults = true
    }

    private val authMutex = Mutex()
    private val mutableSession = MutableStateFlow<SessionState>(SessionState.Restoring)
    val session: StateFlow<SessionState> = mutableSession.asStateFlow()
    val enabled: Boolean get() = configuration.enabled
    val activeAccountId: String?
        get() = (session.value as? SessionState.SignedIn)?.user?.id
    fun publicPostUrl(postId: String): String =
        "${configuration.normalizedBaseUrl}/post/${encodePathSegment(postId)}"
    fun postSharePayload(postId: String, title: String): SharePayload =
        SharePayloads.post(title, publicPostUrl(postId))

    suspend fun restoreSession(): UserProfile? = try {
        restoreSessionInternal()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        // A corrupt local database or unavailable Keychain must never strand the
        // application in its initial Restoring state. Sign-in remains available
        // while the original error is propagated for observability.
        mutableSession.value = SessionState.SignedOut
        throw error
    }

    private suspend fun restoreSessionInternal(): UserProfile? {
        if (!enabled) {
            mutableSession.value = SessionState.SignedOut
            return null
        }
        val cachedUser = database.accountDao().active()?.toProfile()
        val stored = sessionStore.readSession()
        if (stored == null) {
            sessionStore.clearBookmark()
            database.accountDao().deactivateAll()
            mutableSession.value = SessionState.SignedOut
            return null
        }
        cachedUser?.let { mutableSession.value = SessionState.SignedIn(it) }
        return try {
            currentUser().also { mutableSession.value = SessionState.SignedIn(it) }
        } catch (error: ReadThatHttpException) {
            if (error.status == 401) {
                sessionStore.clearSession()
                sessionStore.clearBookmark()
                database.accountDao().deactivateAll()
                mutableSession.value = SessionState.SignedOut
                null
            } else cachedUser ?: throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            cachedUser ?: throw error
        }
    }

    suspend fun register(username: String, password: String, displayName: String): UserProfile =
        authenticate("/v1/auth/register", buildJsonObject {
            put("username", username.trim())
            put("password", password)
            put("displayName", displayName.trim())
        })

    suspend fun login(username: String, password: String): UserProfile =
        authenticate("/v1/auth/login", buildJsonObject {
            put("username", username.trim())
            put("password", password)
        })

    suspend fun logout() {
        try {
            if (sessionStore.readSession() != null) {
                requestJson("POST", "/v1/auth/logout", requireAuthentication = true)
            }
        } finally {
            sessionStore.clearSession()
            sessionStore.clearBookmark()
            database.accountDao().deactivateAll()
            mutableSession.value = SessionState.SignedOut
        }
    }

    suspend fun currentUser(): UserProfile {
        val user = decodeUser(requestJson("GET", "/v1/me", requireAuthentication = true, allowCached = false))
        cacheActiveUser(user)
        return user
    }

    suspend fun user(username: String): UserProfile = decodeUser(
        requestJson("GET", "/v1/users/${encodePathSegment(username.removePrefix("u/").lowercase())}"),
    )

    suspend fun updateProfile(
        displayName: String,
        bio: String,
        avatarMediaId: String?,
        updateAvatar: Boolean,
    ): UserProfile {
        val user = decodeUser(requestJson(
            method = "PATCH",
            path = "/v1/me",
            body = buildJsonObject {
                put("displayName", displayName.trim())
                put("bio", bio.trim())
                if (updateAvatar) {
                    if (avatarMediaId == null) put("avatarMediaId", JsonNull)
                    else put("avatarMediaId", avatarMediaId)
                }
            },
            requireAuthentication = true,
        ))
        cacheActiveUser(user)
        return user
    }

    suspend fun requestJson(
        method: String,
        path: String,
        body: JsonElement? = null,
        requireAuthentication: Boolean = false,
        allowCached: Boolean = method == "GET",
    ): JsonElement {
        requireApiEnabled()
        var stored = authenticatedSession()
        if (requireAuthentication && stored == null) throw ReadThatHttpException(401, "Sign in to continue")
        return try {
            rawJson(method, path, body, stored?.accessToken, allowCached)
        } catch (error: ReadThatHttpException) {
            if (error.status != 401 || stored == null) throw error
            stored = authMutex.withLock { refreshOrLogin(stored, force = true) }
            if (requireAuthentication && stored == null) throw error
            rawJson(method, path, body, stored?.accessToken, allowCached)
        }
    }

    suspend fun requestConditionalJson(
        path: String,
        validator: String?,
    ): ReadThatConditionalResponse {
        requireApiEnabled()
        var stored = authenticatedSession() ?: throw ReadThatHttpException(401, "Sign in to continue")
        return try {
            rawConditionalJson(path, validator, stored.accessToken)
        } catch (error: ReadThatHttpException) {
            if (error.status != 401) throw error
            stored = authMutex.withLock { refreshOrLogin(stored, force = true) } ?: throw error
            rawConditionalJson(path, validator, stored.accessToken)
        }
    }

    suspend fun requestBytes(
        method: String,
        path: String,
        bytes: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement {
        var stored = authenticatedSession() ?: throw ReadThatHttpException(401, "Sign in to continue")
        return try {
            rawBytes(method, path, bytes, contentType, headers, stored.accessToken)
        } catch (error: ReadThatHttpException) {
            if (error.status != 401) throw error
            stored = authMutex.withLock { refreshOrLogin(stored, force = true) } ?: throw error
            rawBytes(method, path, bytes, contentType, headers, stored.accessToken)
        }
    }

    /** Images and video previews use the exact same process-scoped transport as API data. */
    suspend fun mediaBytes(
        url: String,
        cacheKey: String,
        videoPreview: Boolean = false,
        maxAgeMillis: Long = 7L * 24 * 60 * 60 * 1_000,
        allowsExpensiveAccess: Boolean = true,
        allowsConstrainedAccess: Boolean = true,
    ): ByteArray = executeObserved(HttpRequest(
        url = url,
        purpose = if (videoPreview) HttpPurpose.VideoPreview else HttpPurpose.Image,
        cacheKey = (if (videoPreview) "preview:" else "image:") + cacheKey,
        maxAgeMillis = maxAgeMillis,
        staleIfError = true,
        allowsExpensiveAccess = allowsExpensiveAccess,
        allowsConstrainedAccess = allowsConstrainedAccess,
        knownHttp3Origin = configuration.isKnownHttp3Origin(url),
    )).also(::throwUnlessSuccessful).body

    /** Telemetry never restores authentication or attaches account identifiers. */
    suspend fun sendPerformanceBatch(batch: PerformanceBatch) {
        requireApiEnabled()
        rawJson(
            "POST",
            "/v1/telemetry/performance",
            PerformanceWireFormat.encode(batch),
            accessToken = null,
            allowCached = false,
            purpose = HttpPurpose.Telemetry,
        )
    }

    /**
     * Product events retain the principal captured when they were queued. A token is attached only
     * when that same account is still active; signed-out and stale-account batches remain anonymous
     * and are attributed by the server to the pseudonymized installation id.
     */
    suspend fun sendProductAnalyticsBatch(batch: ProductAnalyticsBatch, expectedAccountId: String?) {
        requireApiEnabled()
        val accessToken = productAnalyticsAccessToken(expectedAccountId)
        rawJson(
            "POST",
            "/v1/telemetry/product",
            ProductAnalyticsWireFormat.encode(batch),
            accessToken = accessToken,
            allowCached = false,
            purpose = HttpPurpose.Telemetry,
        )
    }

    private suspend fun productAnalyticsAccessToken(expectedAccountId: String?): String? {
        if (expectedAccountId == null || database.accountDao().active()?.id != expectedAccountId) return null
        val stored = authenticatedSession() ?: return null
        return stored.accessToken.takeIf { database.accountDao().active()?.id == expectedAccountId }
    }

    private suspend fun authenticate(path: String, body: JsonElement): UserProfile = authMutex.withLock {
        requireApiEnabled()
        sessionStore.clearBookmark()
        val response = rawJson("POST", path, body, null, allowCached = false)
        sessionStore.writeSession(decodeSession(response))
        val user = decodeUser(response)
        cacheActiveUser(user)
        user
    }

    private suspend fun authenticatedSession(): StoredSession? = authMutex.withLock {
        val current = sessionStore.readSession()
        if (current != null && current.accessExpiresAt > platformEpochMillis() + EXPIRY_SKEW_MS) current
        else refreshOrLogin(current, force = false)
    }

    private suspend fun refreshOrLogin(current: StoredSession?, force: Boolean): StoredSession? {
        val now = platformEpochMillis()
        if (!force && current != null && current.accessExpiresAt > now + EXPIRY_SKEW_MS) return current
        if (current != null && current.refreshExpiresAt > now + EXPIRY_SKEW_MS) {
            try {
                val response = rawJson("POST", "/v1/auth/refresh", buildJsonObject {
                    put("refreshToken", current.refreshToken)
                }, null, allowCached = false)
                return decodeSession(response).also { sessionStore.writeSession(it) }
            } catch (error: ReadThatHttpException) {
                if (error.status != 401) throw error
            }
        }
        sessionStore.clearSession()
        sessionStore.clearBookmark()
        if (configuration.demoUsername.isBlank() || configuration.demoPassword.isBlank()) return null
        return try {
            val response = rawJson("POST", "/v1/auth/login", buildJsonObject {
                put("username", configuration.demoUsername)
                put("password", configuration.demoPassword)
            }, null, allowCached = false)
            decodeSession(response).also {
                sessionStore.writeSession(it)
                cacheActiveUser(decodeUser(response))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun rawJson(
        method: String,
        path: String,
        body: JsonElement?,
        accessToken: String?,
        allowCached: Boolean,
        purpose: HttpPurpose = HttpPurpose.Api,
    ): JsonElement {
        val response = execute(
            method = method,
            path = path,
            accessToken = accessToken,
            body = body?.toString()?.encodeToByteArray(),
            contentType = body?.let { "application/json" },
            headers = mapOf("X-Client-Schema" to "feed/1"),
            timeoutMillis = 20_000,
            allowCached = allowCached,
            purpose = purpose,
        )
        return decodeResponse(response)
    }

    private suspend fun rawBytes(
        method: String,
        path: String,
        bytes: ByteArray,
        contentType: String,
        headers: Map<String, String>,
        accessToken: String,
    ): JsonElement = decodeResponse(execute(
        method, path, accessToken, bytes, contentType, headers, 120_000, allowCached = false,
    ))

    private suspend fun rawConditionalJson(
        path: String,
        validator: String?,
        accessToken: String,
    ): ReadThatConditionalResponse {
        val response = execute(
            method = "GET",
            path = path,
            accessToken = accessToken,
            body = null,
            contentType = null,
            headers = buildMap {
                put("X-Client-Schema", "community-drawer/1")
                validator?.let { put("If-None-Match", it) }
            },
            timeoutMillis = 20_000,
            allowCached = false,
        )
        if (response.status == 304) return ReadThatConditionalResponse.NotModified
        return ReadThatConditionalResponse.Body(
            body = decodeResponse(response),
            validator = response.header("ETag"),
        )
    }

    private suspend fun execute(
        method: String,
        path: String,
        accessToken: String?,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String>,
        timeoutMillis: Long,
        allowCached: Boolean,
        purpose: HttpPurpose = HttpPurpose.Api,
    ): HttpResponse {
        val accountScope = activeAccountId ?: "guest"
        val request = HttpRequest(
            url = configuration.normalizedBaseUrl + path,
            method = method,
            headers = buildMap {
                put("Accept", "application/json")
                put("User-Agent", "ReadThat/${configuration.appVersion} (${configuration.platform})")
                accessToken?.let {
                    put("Authorization", "Bearer $it")
                    sessionStore.readBookmark()?.let { bookmark -> put("X-D1-Bookmark", bookmark) }
                }
                putAll(headers)
            },
            body = body,
            contentType = contentType,
            timeoutMillis = timeoutMillis,
            purpose = purpose,
            cacheKey = if (allowCached && method == "GET") "api:$accountScope:$path" else null,
            maxAgeMillis = 30_000,
            staleIfError = allowCached,
            knownHttp3Origin = configuration.isKnownHttp3Origin(configuration.normalizedBaseUrl),
        )
        val response = try {
            executeObserved(request)
        } catch (first: Throwable) {
            if (first is CancellationException || method != "GET") throw first
            executeObserved(request)
        }
        response.header("X-D1-Bookmark")?.takeIf(String::isNotBlank)?.let {
            sessionStore.writeBookmark(it)
        }
        return response
    }

    private suspend fun executeObserved(request: HttpRequest): HttpResponse {
        // Observing the exporter transport recursively creates another event for every batch.
        if (request.purpose == HttpPurpose.Telemetry) return transport.execute(request)
        val timer = performanceTimer()
        return try {
            transport.execute(request).also { response ->
                PerformanceTelemetry.duration(
                    PerformanceMetric.NETWORK_REQUEST,
                    timer,
                    surface = PerformanceSurface.BACKGROUND,
                    outcome = if (response.status in 200..399) {
                        PerformanceOutcome.SUCCESS
                    } else {
                        PerformanceOutcome.FAILURE
                    },
                    attributes = mapOf(
                        "route" to request.purpose.name.lowercase(),
                        "status_class" to "${response.status / 100}xx",
                        "protocol" to response.protocol.take(16),
                        "cache_tier" to response.cacheTier.name.lowercase(),
                    ),
                    measurements = mapOf(
                        "bytes_in" to response.body.size.toDouble(),
                        "bytes_out" to (request.body?.size?.toDouble() ?: 0.0),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            PerformanceTelemetry.duration(
                PerformanceMetric.NETWORK_REQUEST,
                timer,
                surface = PerformanceSurface.BACKGROUND,
                outcome = PerformanceOutcome.FAILURE,
                attributes = mapOf(
                    "route" to request.purpose.name.lowercase(),
                    "status_class" to "transport",
                    "protocol" to "unknown",
                    "cache_tier" to "network",
                ),
            )
            throw error
        }
    }

    private fun decodeResponse(response: HttpResponse): JsonElement {
        throwUnlessSuccessful(response)
        val text = response.body.decodeToString()
        return if (text.isBlank()) buildJsonObject {} else json.parseToJsonElement(text)
    }

    private fun throwUnlessSuccessful(response: HttpResponse) {
        if (response.status in 200..299) return
        val text = response.body.decodeToString()
        val message = runCatching {
            json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull() ?: "Backend request failed (${response.status})"
        throw ReadThatHttpException(response.status, message)
    }

    private fun requireApiEnabled() {
        check(enabled) {
            "ReadThat API base URL must use HTTPS; Debug builds may explicitly allow only localhost or an IP loopback address"
        }
    }

    private fun decodeSession(response: JsonElement): StoredSession =
        json.decodeFromJsonElement(StoredSession.serializer(), response.jsonObject.getValue("session"))

    private fun decodeUser(response: JsonElement): UserProfile =
        json.decodeFromJsonElement(UserProfile.serializer(), response.jsonObject.getValue("user"))

    private suspend fun cacheActiveUser(user: UserProfile) {
        database.accountDao().activate(user.toEntity(platformEpochMillis()))
        mutableSession.value = SessionState.SignedIn(user)
    }

    private companion object { const val EXPIRY_SKEW_MS = 30_000L }
}

private fun AccountEntity.toProfile() = UserProfile(
    id, username, displayName, bio, avatarUrl, karma, createdAt, updatedAt,
)

private fun UserProfile.toEntity(lastAuthenticatedAt: Long) = AccountEntity(
    id, username, displayName, bio, avatarUrl, karma, createdAt, updatedAt,
    lastAuthenticatedAt = lastAuthenticatedAt,
    isActive = true,
)

internal fun encodePathSegment(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        val safe = unsigned in 'a'.code..'z'.code || unsigned in 'A'.code..'Z'.code ||
            unsigned in '0'.code..'9'.code || unsigned in setOf('-'.code, '_'.code, '.'.code, '~'.code)
        if (safe) append(unsigned.toChar())
        else append('%').append(unsigned.toString(16).uppercase().padStart(2, '0'))
    }
}
