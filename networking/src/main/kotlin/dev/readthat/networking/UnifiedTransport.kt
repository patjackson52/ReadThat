package dev.readthat.networking

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.ConnectionMigrationOptions
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.UploadDataProvider
import android.net.http.UploadDataSink
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpEngineDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.readthat.observability.PerformanceEvent
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceOutcome
import dev.readthat.observability.PerformanceTelemetry
import coil3.network.NetworkClient
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.source

data class TransportRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: RepeatableBody? = null,
    val timeoutMillis: Long = 20_000,
)

sealed interface RepeatableBody {
    val byteCount: Long
    val contentType: String

    data class Bytes(
        val bytes: ByteArray,
        override val contentType: String,
    ) : RepeatableBody {
        override val byteCount: Long = bytes.size.toLong()
    }

    /** A fresh stream is opened if the engine needs to retry or rewind. */
    data class Stream(
        override val byteCount: Long,
        override val contentType: String,
        val open: () -> InputStream,
    ) : RepeatableBody
}

data class TransportResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val protocol: String,
    val sentAtMillis: Long,
    val receivedAtMillis: Long,
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]?.lastOrNull()
}

data class TransportDebugSnapshot(
    val implementation: String,
    val engineIdentity: Int,
    val completedRequests: Int,
    val lastProtocol: String,
)

/**
 * Process-wide transport. A single HttpEngine owns QUIC sessions, TLS tickets,
 * DNS state, and multiplexed connections for API, images, and video on API 34+.
 * Android 8-13 share a single modern-TLS OkHttp HTTP/2 pool.
 */
object UnifiedTransport {
    private val lock = Any()
    private var applicationContext: Context? = null
    private var quicOrigins: Set<String> = emptySet()
    private var engine: HttpEngine? = null
    private var engineExecutor: ExecutorService? = null
    private var fallback: OkHttpClient? = null
    private val requestCount = AtomicInteger()
    private val lastProtocol = AtomicReference("none")

    fun initialize(context: Context, quicOrigins: Set<String> = emptySet()) {
        synchronized(lock) {
            if (applicationContext == null) applicationContext = context.applicationContext
            this.quicOrigins = this.quicOrigins + quicOrigins.mapNotNull(::httpsHost)
        }
    }

    suspend fun execute(context: Context, request: TransportRequest): TransportResponse {
        require(request.url.startsWith("https://")) { "Cleartext HTTP is disabled" }
        initialize(context)
        val startedAt = System.currentTimeMillis()
        val route = metricRoute(request.url)
        return try {
            val response = withTimeout(request.timeoutMillis) {
                if (Build.VERSION.SDK_INT >= 34) {
                    HttpEngineClient(engine(context), executor()).execute(request)
                } else {
                    OkHttpClientAdapter(okHttp()).execute(request)
                }
            }
            requestCount.incrementAndGet()
            lastProtocol.set(response.protocol)
            Log.d(LOG_TAG, "${request.method} ${URI(request.url).host} ${response.status} ${response.protocol}")
            if (route != TELEMETRY_ROUTE) recordNetwork(
                request = request,
                route = route,
                durationMs = (response.receivedAtMillis - response.sentAtMillis).toDouble(),
                outcome = if (response.status in 200..399) PerformanceOutcome.SUCCESS else PerformanceOutcome.FAILURE,
                protocol = response.protocol,
                status = response.status,
                bytesIn = response.body.size.toDouble(),
                edgeMs = response.header("server-timing")?.edgeDuration(),
            )
            response
        } catch (cancellation: CancellationException) {
            if (route != TELEMETRY_ROUTE) recordNetwork(
                request, route, (System.currentTimeMillis() - startedAt).toDouble(),
                PerformanceOutcome.CANCELLED,
            )
            throw cancellation
        } catch (error: Throwable) {
            if (route != TELEMETRY_ROUTE) recordNetwork(
                request, route, (System.currentTimeMillis() - startedAt).toDouble(),
                PerformanceOutcome.FAILURE,
            )
            throw error
        }
    }

    @UnstableApi
    fun mediaDataSourceFactory(context: Context): DataSource.Factory {
        initialize(context)
        return if (Build.VERSION.SDK_INT >= 34) {
            httpEngineMediaDataSourceFactory(context)
        } else {
            OkHttpDataSource.Factory(okHttp())
                .setUserAgent(USER_AGENT)
        }
    }

    @RequiresApi(34)
    @UnstableApi
    @SuppressLint("NewApi") // API 34 includes the S-extension APIs Media3 annotates as v7.
    private fun httpEngineMediaDataSourceFactory(context: Context): DataSource.Factory =
        HttpEngineDataSource.Factory(engine(context), executor())
            .setUserAgent(USER_AGENT)

    fun debugSnapshot(): TransportDebugSnapshot {
        val active = if (Build.VERSION.SDK_INT >= 34) engine else fallback
        return TransportDebugSnapshot(
            implementation = if (Build.VERSION.SDK_INT >= 34) "HttpEngine" else "OkHttp",
            engineIdentity = active?.let(System::identityHashCode) ?: 0,
            completedRequests = requestCount.get(),
            lastProtocol = lastProtocol.get(),
        )
    }

    @RequiresApi(34)
    private fun engine(context: Context): HttpEngine = engine ?: synchronized(lock) {
        engine ?: HttpEngine.Builder(applicationContext ?: context.applicationContext)
            .setUserAgent(USER_AGENT)
            .setEnableHttp2(true)
            .setEnableQuic(true)
            .setEnableBrotli(true)
            // Coil and Media3 own bounded disk caches. Disabling the transport
            // cache avoids duplicating large image/video objects on disk.
            .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISABLED, 0)
            .setConnectionMigrationOptions(
                ConnectionMigrationOptions.Builder()
                    .setDefaultNetworkMigration(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
                    .setPathDegradationMigration(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
                    // Never keep racing an old, potentially metered network.
                    .setAllowNonDefaultNetworkUsage(ConnectionMigrationOptions.MIGRATION_OPTION_DISABLED)
                    .build(),
            )
            .apply {
                quicOrigins.forEach { host -> addQuicHint(host, 443, 443) }
            }
            .build()
            .also { engine = it }
    }

    private fun executor(): ExecutorService = engineExecutor ?: synchronized(lock) {
        engineExecutor ?: Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(4, 8),
        ) { runnable ->
            Thread(runnable, "sdui-http").apply { isDaemon = true }
        }.also { engineExecutor = it }
    }

    private fun okHttp(): OkHttpClient = fallback ?: synchronized(lock) {
        fallback ?: OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 8
            })
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
            .also { fallback = it }
    }

    private fun httpsHost(value: String): String? = runCatching {
        URI(value).takeIf { it.scheme.equals("https", true) }?.host
    }.getOrNull()

    private fun recordNetwork(
        request: TransportRequest,
        route: String,
        durationMs: Double,
        outcome: PerformanceOutcome,
        protocol: String = "unknown",
        status: Int? = null,
        bytesIn: Double = 0.0,
        edgeMs: Double? = null,
    ) {
        PerformanceTelemetry.record(PerformanceEvent(
            name = PerformanceMetric.NETWORK_REQUEST,
            value = durationMs.coerceAtLeast(0.0),
            surface = PerformanceTelemetry.currentSurface,
            outcome = outcome,
            attributes = mapOf(
                "route" to route,
                "protocol" to protocol.lowercase(Locale.US).take(32),
                "status_class" to (status?.let { "${it / 100}xx" } ?: "transport"),
            ),
            measurements = buildMap {
                put("bytes_in", bytesIn)
                put("bytes_out", request.body?.byteCount?.toDouble() ?: 0.0)
                edgeMs?.let { put("edge_ms", it) }
            },
        ))
    }

    /** Route templates only: never emit signed URLs, content ids, or query values. */
    private fun metricRoute(value: String): String = runCatching {
        val uri = URI(value)
        val host = uri.host.orEmpty().lowercase(Locale.US)
        when {
            host == "imagedelivery.net" -> "cloudflare_images"
            host.endsWith("videodelivery.net") || uri.path.endsWith(".m3u8", true) -> "cloudflare_stream"
            else -> uri.path
                .replace(Regex("/[0-9a-f]{8}-[0-9a-f-]{27,}", RegexOption.IGNORE_CASE), "/{id}")
                .replace(Regex("/(posts|comments|media|uploads|users|subreddits|members)/[^/]+"), "/$1/{id}")
                .replace(Regex("/parts/\\d+"), "/parts/{part}")
                .take(80)
        }
    }.getOrDefault("unknown")

    private fun String.edgeDuration(): Double? =
        Regex("(?:^|,)\\s*edge;dur=([0-9]+(?:\\.[0-9]+)?)").find(this)
            ?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    private const val LOG_TAG = "UnifiedTransport"
    private const val USER_AGENT = "ReadThat/1.0"
    private const val TELEMETRY_ROUTE = "/v1/telemetry/performance"
}

@RequiresApi(34)
private class HttpEngineClient(
    private val engine: HttpEngine,
    private val executor: ExecutorService,
) {
    suspend fun execute(request: TransportRequest): TransportResponse = suspendCancellableCoroutine { continuation ->
        val sentAt = System.currentTimeMillis()
        val output = ByteArrayOutputStream()
        var activeRequest: UrlRequest? = null
        val callback = object : UrlRequest.Callback {
            override fun onRedirectReceived(
                requestHandle: UrlRequest,
                info: UrlResponseInfo,
                newLocationUrl: String,
            ) {
                val from = URI(info.url)
                val to = URI(newLocationUrl)
                val hasCredentials = request.headers.keys.any { it.equals("authorization", true) }
                if (!to.scheme.equals("https", true) || (hasCredentials && from.host != to.host)) {
                    requestHandle.cancel()
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("Unsafe HTTP redirect refused"))
                    }
                } else {
                    requestHandle.followRedirect()
                }
            }

            override fun onResponseStarted(requestHandle: UrlRequest, info: UrlResponseInfo) {
                requestHandle.read(ByteBuffer.allocateDirect(READ_BUFFER_BYTES))
            }

            override fun onReadCompleted(
                requestHandle: UrlRequest,
                info: UrlResponseInfo,
                byteBuffer: ByteBuffer,
            ) {
                byteBuffer.flip()
                val bytes = ByteArray(byteBuffer.remaining())
                byteBuffer.get(bytes)
                output.write(bytes)
                byteBuffer.clear()
                requestHandle.read(byteBuffer)
            }

            override fun onSucceeded(requestHandle: UrlRequest, info: UrlResponseInfo) {
                if (continuation.isActive) {
                    continuation.resume(
                        TransportResponse(
                            status = info.httpStatusCode,
                            headers = info.headers.asMap.mapKeys { it.key.lowercase(Locale.US) },
                            body = output.toByteArray(),
                            protocol = info.negotiatedProtocol,
                            sentAtMillis = sentAt,
                            receivedAtMillis = System.currentTimeMillis(),
                        ),
                    )
                }
            }

            override fun onFailed(requestHandle: UrlRequest, info: UrlResponseInfo?, error: HttpException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onCanceled(requestHandle: UrlRequest, info: UrlResponseInfo?) {
                if (continuation.isActive) continuation.resumeWithException(CancellationException("HTTP request canceled"))
            }
        }

        try {
            val builder = engine.newUrlRequestBuilder(request.url, executor, callback)
                .setHttpMethod(request.method)
                .setPriority(UrlRequest.REQUEST_PRIORITY_MEDIUM)
            request.headers.forEach(builder::addHeader)
            request.body?.let { body ->
                if (request.headers.keys.none { it.equals("content-type", true) }) {
                    builder.addHeader("Content-Type", body.contentType)
                }
                builder.setUploadDataProvider(StreamUploadProvider(body), executor)
            }
            activeRequest = builder.build().also(UrlRequest::start)
            continuation.invokeOnCancellation { activeRequest.cancel() }
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private companion object { const val READ_BUFFER_BYTES = 64 * 1024 }
}

@RequiresApi(34)
private class StreamUploadProvider(private val body: RepeatableBody) : UploadDataProvider() {
    private var stream: InputStream? = null
    override fun getLength(): Long = body.byteCount

    override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
        try {
            val input = stream ?: open().also { stream = it }
            val buffer = ByteArray(minOf(byteBuffer.remaining(), UPLOAD_BUFFER_BYTES))
            val count = input.read(buffer)
            if (count < 0) {
                uploadDataSink.onReadSucceeded(true)
            } else {
                byteBuffer.put(buffer, 0, count)
                uploadDataSink.onReadSucceeded(false)
            }
        } catch (error: Exception) {
            uploadDataSink.onReadError(error)
        }
    }

    override fun rewind(uploadDataSink: UploadDataSink) {
        try {
            stream?.close()
            stream = open()
            uploadDataSink.onRewindSucceeded()
        } catch (error: Exception) {
            uploadDataSink.onRewindError(error)
        }
    }

    override fun close() { stream?.close() }

    private fun open(): InputStream = when (body) {
        is RepeatableBody.Bytes -> body.bytes.inputStream()
        is RepeatableBody.Stream -> body.open()
    }

    private companion object { const val UPLOAD_BUFFER_BYTES = 64 * 1024 }
}

private class OkHttpClientAdapter(private val client: OkHttpClient) {
    suspend fun execute(request: TransportRequest): TransportResponse = suspendCancellableCoroutine { continuation ->
        val sentAt = System.currentTimeMillis()
        val okhttpRequest = Request.Builder()
            .url(request.url)
            .method(request.method, request.body?.toOkHttpBody())
            .apply { request.headers.forEach(::addHeader) }
            .build()
        val call = client.newCall(okhttpRequest)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val result = TransportResponse(
                            status = it.code,
                            headers = it.headers.toMultimap().mapKeys { header ->
                                header.key.lowercase(Locale.US)
                            },
                            body = it.body?.bytes() ?: ByteArray(0),
                            protocol = it.protocol.toString(),
                            sentAtMillis = sentAt,
                            receivedAtMillis = System.currentTimeMillis(),
                        )
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun RepeatableBody.toOkHttpBody(): RequestBody = when (this) {
        is RepeatableBody.Bytes -> bytes.toRequestBody(contentType.toMediaTypeOrNull())
        is RepeatableBody.Stream -> object : RequestBody() {
            override fun contentType() = this@toOkHttpBody.contentType.toMediaTypeOrNull()
            override fun contentLength() = byteCount
            override fun writeTo(sink: BufferedSink) {
                open().use { input -> sink.writeAll(input.source()) }
            }
        }
    }
}

/** Coil adapter backed by the same process-wide engine as API and video. */
class UnifiedCoilNetworkClient(private val context: Context) : NetworkClient {
    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (response: NetworkResponse) -> T,
    ): T {
        val requestBody = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            RepeatableBody.Bytes(buffer.readByteArray(), request.headers["content-type"] ?: "application/octet-stream")
        }
        val response = UnifiedTransport.execute(
            context,
            TransportRequest(
                url = request.url,
                method = request.method,
                headers = request.headers.asMap().mapValues { it.value.joinToString(",") },
                body = requestBody,
                timeoutMillis = 30_000,
            ),
        )
        val headers = NetworkHeaders.Builder().apply {
            response.headers.forEach { (name, values) -> this[name] = values }
        }.build()
        return block(
            NetworkResponse(
                code = response.status,
                requestMillis = response.sentAtMillis,
                responseMillis = response.receivedAtMillis,
                headers = headers,
                body = NetworkResponseBody(Buffer().write(response.body)),
                delegate = response,
            ),
        )
    }
}
