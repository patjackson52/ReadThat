package dev.readthat.data.backend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.readthat.BuildConfig
import dev.readthat.shared.SessionState
import dev.readthat.shared.UserProfile
import dev.readthat.data.db.AccountEntity
import dev.readthat.data.db.AppDatabase
import dev.readthat.networking.RepeatableBody
import dev.readthat.networking.TransportRequest
import dev.readthat.networking.TransportResponse
import dev.readthat.networking.UnifiedTransport
import dev.readthat.observability.PerformanceBatch
import dev.readthat.observability.PerformanceWireFormat
import dev.readthat.observability.ProductAnalyticsBatch
import dev.readthat.observability.ProductAnalyticsWireFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small transport client shared by feed and detail. */
class BackendClient(context: Context) {
    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        explicitNulls = false
    }
    private val baseUrl = BuildConfig.READTHAT_API_BASE_URL.trimEnd('/')
    private val username = BuildConfig.READTHAT_DEMO_USERNAME
    private val password = BuildConfig.READTHAT_DEMO_PASSWORD
    private val sessionStore = SecureSessionStore(context.applicationContext, json)
    private val accountDao = AppDatabase.get(context.applicationContext).accountDao()
    private val applicationContext = context.applicationContext
    private val authMutex = Mutex()
    private val mutableSessionState = MutableStateFlow<SessionState>(SessionState.Restoring)

    val enabled: Boolean get() = baseUrl.startsWith("https://")
    val sessionState: StateFlow<SessionState> = mutableSessionState.asStateFlow()

    suspend fun restoreSession(): UserProfile? {
        if (!enabled) {
            mutableSessionState.value = SessionState.SignedOut
            return null
        }
        val cachedUser = withContext(Dispatchers.IO) { accountDao.active()?.toProfile() }
        val storedSession = readStoredSession()
        if (storedSession == null) {
            withContext(Dispatchers.IO) { accountDao.deactivateAll() }
            mutableSessionState.value = SessionState.SignedOut
            return null
        }
        // Cached identity is display state, not authorization. It lets Room-backed
        // content render immediately while token/profile validation stays in the
        // background. Only a definitive 401 is allowed to clear it.
        cachedUser?.let { mutableSessionState.value = SessionState.SignedIn(it) }
        return try {
            currentUser().also { mutableSessionState.value = SessionState.SignedIn(it) }
        } catch (error: BackendHttpException) {
            if (error.status == 401) {
                clearStoredSession()
                withContext(Dispatchers.IO) { accountDao.deactivateAll() }
                mutableSessionState.value = SessionState.SignedOut
                null
            } else {
                cachedUser ?: throw error
            }
        } catch (error: Throwable) {
            // Offline, timeout, and 5xx keep the last locally authenticated user.
            cachedUser ?: throw error
        }
    }

    suspend fun register(username: String, password: String, displayName: String): UserProfile =
        authenticate(
            "/v1/auth/register",
            buildJsonObject {
                put("username", username.trim())
                put("password", password)
                put("displayName", displayName.trim())
            },
        )

    suspend fun login(username: String, password: String): UserProfile =
        authenticate(
            "/v1/auth/login",
            buildJsonObject {
                put("username", username.trim())
                put("password", password)
            },
        )

    suspend fun logout() {
        try {
            if (readStoredSession() != null) requestJson("POST", "/v1/auth/logout", requireAuthentication = true)
        } finally {
            clearStoredSession()
            withContext(Dispatchers.IO) { accountDao.deactivateAll() }
            mutableSessionState.value = SessionState.SignedOut
        }
    }

    suspend fun currentUser(): UserProfile {
        val response = requestJson("GET", "/v1/me", requireAuthentication = true)
        return decodeUser(response).also { user ->
            cacheActiveUser(user)
            mutableSessionState.value = SessionState.SignedIn(user)
        }
    }

    suspend fun user(username: String): UserProfile = decodeUser(
        requestJson("GET", "/v1/users/${username.trim().removePrefix("u/").lowercase()}"),
    )

    suspend fun updateProfile(
        displayName: String,
        bio: String,
        avatarMediaId: String?,
        updateAvatar: Boolean,
    ): UserProfile {
        val response = requestJson(
            "PATCH",
            "/v1/me",
            buildJsonObject {
                put("displayName", displayName.trim())
                put("bio", bio.trim())
                if (updateAvatar) {
                    if (avatarMediaId == null) put("avatarMediaId", JsonNull)
                    else put("avatarMediaId", avatarMediaId)
                }
            },
            requireAuthentication = true,
        )
        return decodeUser(response).also { user ->
            cacheActiveUser(user)
            mutableSessionState.value = SessionState.SignedIn(user)
        }
    }

    suspend fun requestJson(
        method: String,
        path: String,
        body: JsonElement? = null,
        requireAuthentication: Boolean = false,
    ): JsonElement {
        check(enabled) { "READTHAT_API_BASE_URL must be an HTTPS URL" }
        var session = authenticatedSession()
        if (requireAuthentication && session == null) {
            throw BackendHttpException(401, "The demo backend account is not configured or could not sign in")
        }
        return try {
            rawJson(method, path, body, session?.accessToken)
        } catch (error: BackendHttpException) {
            if (error.status != 401 || session == null) throw error
            session = authMutex.withLock { refreshOrLogin(session, force = true) }
            if (requireAuthentication && session == null) throw error
            rawJson(method, path, body, session?.accessToken)
        }
    }

    suspend fun requestConditionalJson(path: String, validator: String?): BackendConditionalResponse {
        check(enabled) { "READTHAT_API_BASE_URL must be an HTTPS URL" }
        var session = authenticatedSession()
            ?: throw BackendHttpException(401, "Sign in to continue")
        return try {
            rawConditionalJson(path, validator, session.accessToken)
        } catch (error: BackendHttpException) {
            if (error.status != 401) throw error
            session = authMutex.withLock { refreshOrLogin(session, force = true) } ?: throw error
            rawConditionalJson(path, validator, session.accessToken)
        }
    }

    suspend fun requestBytes(
        method: String,
        path: String,
        bytes: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement {
        check(enabled) { "READTHAT_API_BASE_URL must be an HTTPS URL" }
        var session = authenticatedSession()
            ?: throw BackendHttpException(401, "Sign in to continue")
        return try {
            rawBytes(method, path, bytes, contentType, headers, session.accessToken)
        } catch (error: BackendHttpException) {
            if (error.status != 401) throw error
            session = authMutex.withLock { refreshOrLogin(session, force = true) }
                ?: throw error
            rawBytes(method, path, bytes, contentType, headers, session.accessToken)
        }
    }

    /**
     * Uploads a repeatable body without materializing it in memory. [openBody]
     * may be invoked twice when an expired access token is refreshed, so callers
     * must open a fresh input stream for every invocation.
     */
    suspend fun requestStream(
        method: String,
        path: String,
        byteCount: Long,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        openBody: () -> InputStream,
    ): JsonElement {
        check(enabled) { "READTHAT_API_BASE_URL must be an HTTPS URL" }
        var session = authenticatedSession()
            ?: throw BackendHttpException(401, "Sign in to continue")
        return try {
            rawStream(method, path, byteCount, contentType, headers, session.accessToken, openBody)
        } catch (error: BackendHttpException) {
            if (error.status != 401) throw error
            session = authMutex.withLock { refreshOrLogin(session, force = true) }
                ?: throw error
            rawStream(method, path, byteCount, contentType, headers, session.accessToken, openBody)
        }
    }

    /**
     * Telemetry is intentionally unauthenticated and never invokes session
     * restoration. The payload has a random process id and bounded dimensions,
     * but no user/account/device/content identifiers.
     */
    suspend fun sendPerformanceBatch(batch: PerformanceBatch) {
        check(enabled) { "READTHAT_API_BASE_URL must be an HTTPS URL" }
        rawJson(
            method = "POST",
            path = "/v1/telemetry/performance",
            body = PerformanceWireFormat.encode(batch),
            accessToken = null,
        )
    }

    /**
     * Product analytics may use the current account only when it is the account
     * captured with the event. Guest/offline events are intentionally attributed
     * to the server-pseudonymized installation instead of a later signed-in user.
     */
    suspend fun sendProductAnalyticsBatch(
        batch: ProductAnalyticsBatch,
        expectedAccountId: String?,
    ) {
        check(enabled) { "READTHAT_API_BASE_URL must be an HTTPS URL" }
        val token = productAnalyticsAccessToken(expectedAccountId)
        rawJson(
            method = "POST",
            path = "/v1/telemetry/product",
            body = ProductAnalyticsWireFormat.encode(batch),
            accessToken = token,
        )
    }

    private suspend fun productAnalyticsAccessToken(expectedAccountId: String?): String? {
        if (expectedAccountId == null) return null
        val activeAccountId = withContext(Dispatchers.IO) { accountDao.active()?.id }
        if (activeAccountId != expectedAccountId) return null
        return authMutex.withLock {
            val current = readStoredSession() ?: return@withLock null
            val now = System.currentTimeMillis()
            if (current.accessExpiresAt > now + EXPIRY_SKEW_MS) return@withLock current.accessToken
            if (current.refreshExpiresAt <= now + EXPIRY_SKEW_MS) return@withLock null
            try {
                val response = rawJson(
                    "POST",
                    "/v1/auth/refresh",
                    buildJsonObject { put("refreshToken", current.refreshToken) },
                    accessToken = null,
                )
                val user = decodeUser(response)
                if (user.id != expectedAccountId) return@withLock null
                decodeSession(response).also { writeStoredSession(it) }.accessToken
            } catch (error: BackendHttpException) {
                if (error.status == 401) null else throw error
            }
        }
    }

    private suspend fun authenticate(path: String, body: JsonElement): UserProfile = authMutex.withLock {
        check(enabled) { "READTHAT_API_BASE_URL must be an HTTPS URL" }
        val response = rawJson("POST", path, body, accessToken = null)
        val session = decodeSession(response)
        writeStoredSession(session)
        decodeUser(response).also { user ->
            cacheActiveUser(user)
            mutableSessionState.value = SessionState.SignedIn(user)
        }
    }

    private fun decodeUser(response: JsonElement): UserProfile =
        json.decodeFromJsonElement(UserProfile.serializer(), response.jsonObject.getValue("user"))

    private suspend fun authenticatedSession(): StoredSession? = authMutex.withLock {
        val current = readStoredSession()
        if (current != null && current.accessExpiresAt > System.currentTimeMillis() + EXPIRY_SKEW_MS) {
            return@withLock current
        }
        refreshOrLogin(current, force = false)
    }

    private suspend fun refreshOrLogin(current: StoredSession?, force: Boolean): StoredSession? {
        if (!force && current != null && current.accessExpiresAt > System.currentTimeMillis() + EXPIRY_SKEW_MS) {
            return current
        }
        if (current != null && current.refreshExpiresAt > System.currentTimeMillis() + EXPIRY_SKEW_MS) {
            try {
                val response = rawJson(
                    "POST",
                    "/v1/auth/refresh",
                    buildJsonObject { put("refreshToken", current.refreshToken) },
                    accessToken = null,
                )
                return decodeSession(response).also { writeStoredSession(it) }
            } catch (error: BackendHttpException) {
                if (error.status != 401) throw error
                // A rejected refresh is definitive; transient failures are not.
            }
        }
        clearStoredSession()
        if (username.isBlank() || password.isBlank()) return null
        return runCatching {
            val response = rawJson(
                "POST",
                "/v1/auth/login",
                buildJsonObject {
                    put("username", username)
                    put("password", password)
                },
                accessToken = null,
            )
            decodeSession(response).also { writeStoredSession(it) }
        }.getOrNull()
    }

    private fun decodeSession(response: JsonElement): StoredSession {
        val session = response.jsonObject.getValue("session")
        return json.decodeFromJsonElement(StoredSession.serializer(), session)
    }

    private suspend fun rawJson(
        method: String,
        path: String,
        body: JsonElement?,
        accessToken: String?,
    ): JsonElement {
        val bytes = body?.toString()?.toByteArray(StandardCharsets.UTF_8)
        return executeJson(
            method = method,
            path = path,
            accessToken = accessToken,
            body = bytes?.let { RepeatableBody.Bytes(it, "application/json") },
            timeoutMillis = 20_000,
            extraHeaders = mapOf("X-Client-Schema" to "feed/1"),
            retrySafeRequest = method == "GET" || method == "HEAD",
        )
    }

    private suspend fun rawBytes(
        method: String,
        path: String,
        bytes: ByteArray,
        contentType: String,
        headers: Map<String, String>,
        accessToken: String,
    ): JsonElement = executeJson(
        method = method,
        path = path,
        accessToken = accessToken,
        body = RepeatableBody.Bytes(bytes, contentType),
        timeoutMillis = 60_000,
        extraHeaders = headers,
    )

    private suspend fun rawConditionalJson(
        path: String,
        validator: String?,
        accessToken: String,
    ): BackendConditionalResponse {
        val response = executeTransport(
            method = "GET",
            path = path,
            accessToken = accessToken,
            body = null,
            timeoutMillis = 20_000,
            extraHeaders = buildMap {
                put("X-Client-Schema", "community-drawer/1")
                validator?.let { put("If-None-Match", it) }
            },
            retrySafeRequest = true,
        )
        if (response.status == 304) {
            return BackendConditionalResponse.NotModified
        }
        return BackendConditionalResponse.Body(
            body = decodeResponse(response),
            validator = response.header("ETag"),
        )
    }

    private suspend fun rawStream(
        method: String,
        path: String,
        byteCount: Long,
        contentType: String,
        headers: Map<String, String>,
        accessToken: String,
        openBody: () -> InputStream,
    ): JsonElement = executeJson(
        method = method,
        path = path,
        accessToken = accessToken,
        body = RepeatableBody.Stream(byteCount, contentType, openBody),
        timeoutMillis = 120_000,
        extraHeaders = headers,
    )

    private suspend fun executeJson(
        method: String,
        path: String,
        accessToken: String?,
        body: RepeatableBody?,
        timeoutMillis: Long,
        extraHeaders: Map<String, String>,
        retrySafeRequest: Boolean = false,
    ): JsonElement = decodeResponse(executeTransport(
        method,
        path,
        accessToken,
        body,
        timeoutMillis,
        extraHeaders,
        retrySafeRequest,
    ))

    private suspend fun executeTransport(
        method: String,
        path: String,
        accessToken: String?,
        body: RepeatableBody?,
        timeoutMillis: Long,
        extraHeaders: Map<String, String>,
        retrySafeRequest: Boolean = false,
    ): TransportResponse {
        val headers = buildMap {
            put("Accept", "application/json")
            put("User-Agent", "ReadThat/${BuildConfig.VERSION_NAME}")
            accessToken?.let { put("Authorization", "Bearer $it") }
            sessionStore.bookmark()?.let { put("X-D1-Bookmark", it) }
            putAll(extraHeaders)
        }
        val request = TransportRequest(
            url = baseUrl + path,
            method = method,
            headers = headers,
            body = body,
            timeoutMillis = timeoutMillis,
        )
        val response = try {
            UnifiedTransport.execute(applicationContext, request)
        } catch (first: java.io.IOException) {
            if (!retrySafeRequest) throw first
            UnifiedTransport.execute(applicationContext, request)
        }
        response.header("X-D1-Bookmark")?.takeIf(String::isNotBlank)?.let(sessionStore::bookmark)
        return response
    }

    private fun decodeResponse(response: TransportResponse): JsonElement {
        val text = response.body.toString(StandardCharsets.UTF_8)
        if (response.status !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(text).jsonObject["error"]
                    ?.jsonObject?.get("message")?.jsonPrimitive?.content
            }.getOrNull() ?: "Backend request failed (${response.status})"
            throw BackendHttpException(response.status, message)
        }
        return if (text.isBlank()) buildJsonObject {} else json.parseToJsonElement(text)
    }

    private companion object { const val EXPIRY_SKEW_MS = 30_000L }

    private suspend fun readStoredSession(): StoredSession? =
        withContext(Dispatchers.IO) { sessionStore.read() }

    private suspend fun writeStoredSession(session: StoredSession) =
        withContext(Dispatchers.IO) { sessionStore.write(session) }

    private suspend fun clearStoredSession() =
        withContext(Dispatchers.IO) { sessionStore.clear() }

    private suspend fun cacheActiveUser(user: UserProfile) = withContext(Dispatchers.IO) {
        accountDao.activate(user.toEntity(lastAuthenticatedAt = System.currentTimeMillis()))
    }
}

private fun AccountEntity.toProfile() = UserProfile(
    id = id,
    username = username,
    displayName = displayName,
    bio = bio,
    avatarUrl = avatarUrl,
    karma = karma,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun UserProfile.toEntity(lastAuthenticatedAt: Long) = AccountEntity(
    id = id,
    username = username,
    displayName = displayName,
    bio = bio,
    avatarUrl = avatarUrl,
    karma = karma,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastAuthenticatedAt = lastAuthenticatedAt,
    isActive = true,
)

class BackendHttpException(val status: Int, message: String) : java.io.IOException(message)

sealed interface BackendConditionalResponse {
    data object NotModified : BackendConditionalResponse
    data class Body(val body: JsonElement, val validator: String?) : BackendConditionalResponse
}

@Serializable
private data class StoredSession(
    val sessionId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
)

/**
 * Access and refresh tokens are encrypted with a non-exportable Android
 * Keystore AES-GCM key. The D1 bookmark is consistency metadata, not a secret.
 */
private class SecureSessionStore(context: Context, private val json: Json) {
    private val preferences = context.getSharedPreferences("backend_session", Context.MODE_PRIVATE)

    fun read(): StoredSession? {
        val encoded = preferences.getString(SESSION, null) ?: return null
        return runCatching {
            json.decodeFromString<StoredSession>(decrypt(encoded))
        }.getOrElse {
            clear()
            null
        }
    }

    fun write(session: StoredSession) {
        preferences.edit().putString(SESSION, encrypt(json.encodeToString(session))).apply()
    }

    fun clear() { preferences.edit().remove(SESSION).apply() }
    fun bookmark(): String? = preferences.getString(BOOKMARK, null)
    fun bookmark(value: String) { preferences.edit().putString(BOOKMARK, value).apply() }

    private fun encrypt(cleartext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(cleartext.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        return String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val SESSION = "session"
        const val BOOKMARK = "d1_bookmark"
        const val KEY_ALIAS = "sdui_backend_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
