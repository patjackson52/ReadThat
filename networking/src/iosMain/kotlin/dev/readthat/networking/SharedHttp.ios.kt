package dev.readthat.networking

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLock
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.NSURLSessionTaskMetrics
import platform.Foundation.NSURLSessionTaskTransactionMetrics
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPShouldHandleCookies
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.valueForHTTPHeaderField
import platform.Security.tls_protocol_version_TLSv12
import platform.darwin.NSObject
import platform.posix.memcpy
import platform.posix.gettimeofday
import platform.posix.timeval
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One process-long URLSession for shared API, image, and preview requests.
 * NSURLSession owns TLS tickets, HTTP/2 pools, HTTP/3 sessions, Alt-Svc state,
 * default-path migration, while the shared stable-key cache exclusively owns
 * media-byte persistence. Signed delivery URLs must never become disk identities.
 */
class IosSharedHttpTransport(
    private val securityPolicy: TransportSecurityPolicy = TransportSecurityPolicy(),
) : HttpTransport {
    private val delegate = SecureSessionDelegate(securityPolicy)
    private val session: NSURLSession = createIosUrlSession(delegate)

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun execute(request: HttpRequest): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            securityPolicy.requirePermitted(request.url)
            val url = requireNotNull(NSURL.URLWithString(request.url)) { "Invalid URL" }
            val native = NSMutableURLRequest(url)
            native.setHTTPMethod(request.method)
            native.setTimeoutInterval(request.timeoutMillis / 1_000.0)
            native.setAllowsExpensiveNetworkAccess(request.allowsExpensiveAccess)
            native.setAllowsConstrainedNetworkAccess(request.allowsConstrainedAccess)
            native.setAssumesHTTP3Capable(request.knownHttp3Origin && securityPolicy.isHttps(request.url))
            // Room owns structured API caching and account isolation. Images and previews use
            // CachingHttpClient's stable content key, not an expiring signed URL. Bypassing
            // URLCache avoids an unbounded third copy when a CDN delivery URL rotates.
            native.setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
            native.setHTTPShouldHandleCookies(false)
            request.headers.forEach { (name, value) -> native.setValue(value, forHTTPHeaderField = name) }
            request.contentType?.takeIf { request.headers.keys.none { name -> name.equals("content-type", true) } }
                ?.let { native.setValue(it, forHTTPHeaderField = "Content-Type") }
            request.body?.let { native.setHTTPBody(it.toNSData()) }

            val sentAt = nowMillis()
            var task: NSURLSessionTask? = null
            task = session.dataTaskWithRequest(native) { data, response, error ->
                when {
                    error != null -> continuation.resumeWithException(
                        IllegalStateException(error.localizedDescription),
                    )
                    response !is NSHTTPURLResponse -> continuation.resumeWithException(
                        IllegalStateException("Non-HTTP response"),
                    )
                    else -> continuation.resume(
                        HttpResponse(
                            status = response.statusCode.toInt(),
                            headers = response.allHeaderFields.mapNotNull { (key, value) ->
                                key?.toString()?.lowercase()?.let { it to listOf(value.toString()) }
                            }.toMap(),
                            body = data?.toByteArray() ?: ByteArray(0),
                            protocol = task?.taskIdentifier?.let(delegate::takeProtocol) ?: "urlsession",
                            sentAtMillis = sentAt,
                            receivedAtMillis = nowMillis(),
                        ),
                    )
                }
            }
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
}

/** One process-long session owns DNS, TLS tickets, HTTP/2 pools and HTTP/3 state. */
@OptIn(ExperimentalForeignApi::class)
private fun createIosUrlSession(delegate: SecureSessionDelegate): NSURLSession {
    removeLegacyIosUrlCache()
    val configuration = NSURLSessionConfiguration.defaultSessionConfiguration
    configuration.setWaitsForConnectivity(true)
    configuration.setTimeoutIntervalForRequest(20.0)
    configuration.setTimeoutIntervalForResource(120.0)
    configuration.setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
    // The process-scoped CachingHttpClient provides the bounded 32 MiB memory + 512 MiB
    // stable-key file cache. Disabling URLCache prevents signed-URL aliases from creating an
    // extra 384 MiB persistent tier while retaining URLSession's connection and HTTP/3 state.
    configuration.setURLCache(null)
    configuration.setAllowsCellularAccess(true)
    configuration.setAllowsExpensiveNetworkAccess(true)
    configuration.setAllowsConstrainedNetworkAccess(true)
    configuration.setHTTPShouldSetCookies(false)
    configuration.setHTTPCookieStorage(null)
    configuration.setURLCredentialStorage(null)
    configuration.setTLSMinimumSupportedProtocolVersion(tls_protocol_version_TLSv12)
    return NSURLSession.sessionWithConfiguration(configuration, delegate, null)
}

/**
 * Versions before the stable-key-only cache contract could leave up to 384 MiB under this exact
 * URLCache disk path. It is disposable response data, not Room state or the offline media cache.
 */
@OptIn(ExperimentalForeignApi::class)
private fun removeLegacyIosUrlCache() {
    val cacheRoot = (NSSearchPathForDirectoriesInDomains(
        NSCachesDirectory,
        NSUserDomainMask,
        true,
    ).firstOrNull() as? String)?.takeIf(String::isNotBlank) ?: return
    val bundleId = NSBundle.mainBundle.bundleIdentifier?.takeIf(String::isNotBlank)
    val paths = buildList {
        bundleId?.let { add("$cacheRoot/$it/$LEGACY_URL_CACHE_DIRECTORY") }
        add("$cacheRoot/$LEGACY_URL_CACHE_DIRECTORY")
    }
    paths.distinct().forEach { path ->
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }
}

private class SecureSessionDelegate(
    private val securityPolicy: TransportSecurityPolicy,
) : NSObject(), NSURLSessionTaskDelegateProtocol {
    private val metricsLock = NSLock()
    private val protocols = mutableMapOf<ULong, String>()

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didFinishCollectingMetrics: NSURLSessionTaskMetrics,
    ) {
        val protocol = (didFinishCollectingMetrics.transactionMetrics
            .lastOrNull() as? NSURLSessionTaskTransactionMetrics)
            ?.networkProtocolName
            ?: return
        metricsLock.lock()
        try {
            if (protocols.size >= 256) {
                protocols.keys.firstOrNull()?.let(protocols::remove)
            }
            protocols[task.taskIdentifier] = protocol
        } finally {
            metricsLock.unlock()
        }
    }

    fun takeProtocol(taskIdentifier: ULong): String? {
        metricsLock.lock()
        return try {
            protocols.remove(taskIdentifier)
        } finally {
            metricsLock.unlock()
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        val destination = newRequest.URL?.absoluteString
        val sourceHost = task.currentRequest?.URL?.host ?: task.originalRequest?.URL?.host
        val destinationHost = newRequest.URL?.host
        val hasAuthorization = task.originalRequest?.valueForHTTPHeaderField("Authorization") != null
        val crossesHost = sourceHost?.lowercase() != destinationHost?.lowercase()
        val safe = destination != null && securityPolicy.permits(destination) &&
            !(hasAuthorization && crossesHost)
        completionHandler(newRequest.takeIf { safe })
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMillis(): Long = memScoped {
    val time = alloc<timeval>()
    gettimeofday(time.ptr, null)
    time.tv_sec * 1_000L + time.tv_usec / 1_000L
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).also { result ->
        result.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}

private const val LEGACY_URL_CACHE_DIRECTORY = "dev.readthat.http-cache"
