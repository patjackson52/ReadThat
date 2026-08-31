package dev.readthat.networking

import android.content.Context
import android.net.ConnectivityManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared-repository adapter over Android's one QUIC/HTTP-2 transport. */
class AndroidSharedHttpTransport(private val context: Context) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        requirePermittedNetwork(request)
        val response = UnifiedTransport.execute(
            context.applicationContext,
            TransportRequest(
                url = request.url,
                method = request.method,
                headers = request.headers,
                body = request.body?.let {
                    RepeatableBody.Bytes(it, request.contentType ?: "application/octet-stream")
                },
                timeoutMillis = request.timeoutMillis,
                recordTelemetry = false,
            ),
        )
        return HttpResponse(
            status = response.status,
            headers = response.headers,
            body = response.body,
            protocol = response.protocol,
            sentAtMillis = response.sentAtMillis,
            receivedAtMillis = response.receivedAtMillis,
        )
    }

    private fun requirePermittedNetwork(request: HttpRequest) {
        if (request.allowsExpensiveAccess && request.allowsConstrainedAccess) return
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (!request.allowsExpensiveAccess && connectivity.isActiveNetworkMetered) {
            throw IOException("Request is disabled on metered networks")
        }
        if (
            !request.allowsConstrainedAccess &&
            connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        ) {
            throw IOException("Request is disabled while Data Saver is active")
        }
    }
}

/** Bounded application-private L2 for immutable image and preview bytes. */
class AndroidFileByteCache(
    context: Context,
    name: String,
    private val maxBytes: Long,
) : ByteCacheStore {
    private val directory = File(context.applicationContext.cacheDir, name).apply { mkdirs() }

    override suspend fun read(key: String): CachedBytes? = withContext(Dispatchers.IO) {
        val file = file(key)
        if (!file.isFile) return@withContext null
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val storedAt = input.readLong()
                val bytes = input.readBytes()
                file.setLastModified(System.currentTimeMillis())
                CachedBytes(bytes, storedAt)
            }
        }.getOrNull()
    }

    override suspend fun write(key: String, value: CachedBytes) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val target = file(key)
        val temporary = File(directory, "${target.name}.tmp")
        DataOutputStream(temporary.outputStream().buffered()).use { output ->
            output.writeLong(value.storedAtMillis)
            output.write(value.bytes)
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        trimBlocking(maxBytes)
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) { file(key).delete(); Unit }

    override suspend fun trimToSize(maxBytes: Long) = withContext(Dispatchers.IO) {
        trimBlocking(maxBytes)
    }

    private fun file(key: String) = File(directory, stableCacheFileName(key))

    private fun trimBlocking(limit: Long) {
        val files = directory.listFiles()?.filter(File::isFile)?.sortedBy(File::lastModified).orEmpty()
        var total = files.sumOf(File::length)
        for (file in files) {
            if (total <= limit) break
            total -= file.length()
            file.delete()
        }
    }
}
