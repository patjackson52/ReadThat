package dev.readthat.media.acquisition

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.PostKind
import java.io.File
import java.util.UUID

/**
 * Android's narrow native media-acquisition seam. Both Android hosts use this implementation so
 * private staging, MIME checks, byte limits, and metadata extraction cannot drift during migration.
 * Call from an IO dispatcher.
 */
fun stageAndroidMedia(context: Context, uri: Uri, kind: PostKind): LocalPostMedia {
    return stageAndroidMedia(context, uri, MediaAcquisitionPolicies.forPostKind(kind))
}

/** Stages one picker result against an explicit shared policy such as profile-avatar limits. */
fun stageAndroidMedia(
    context: Context,
    uri: Uri,
    policy: MediaAcquisitionPolicy,
): LocalPostMedia {
    val resolver = context.contentResolver
    var name = if (policy.postKind == PostKind.Image) "photo" else "video"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
    }
    val mimeType = resolver.getType(uri) ?: policy.defaultMimeType
    require(policy.acceptsMimeType(mimeType)) {
        if (policy.postKind == PostKind.Image) "The selected file is not a supported image"
        else "The selected file is not a supported video"
    }
    val destination = privateStagingFile(context)
    try {
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Selected media is unavailable" }
            destination.outputStream().buffered().use { output ->
                input.copyBoundedTo(output, policy)
            }
        }
        val metadata = if (policy.postKind == PostKind.Image) {
            imageMetadata(destination)
        } else {
            videoMetadata(destination)
        }
        return policy.validate(
            LocalPostMedia(
                name = name,
                mimeType = mimeType,
                localPath = destination.absolutePath,
                byteSize = destination.length(),
                width = metadata.width,
                height = metadata.height,
                durationSeconds = metadata.durationSeconds,
            ),
        )
    } catch (error: Throwable) {
        destination.delete()
        throw error
    }
}

/** Stages one atomic system-picker result and removes partial files if any item is invalid. */
fun stageAndroidMediaSelection(
    context: Context,
    uris: List<Uri>,
    kind: PostKind,
): List<LocalPostMedia> {
    return stageAndroidMediaSelection(context, uris, MediaAcquisitionPolicies.forPostKind(kind))
}

/** Stages an atomic picker selection against an explicit cross-platform policy. */
fun stageAndroidMediaSelection(
    context: Context,
    uris: List<Uri>,
    policy: MediaAcquisitionPolicy,
): List<LocalPostMedia> {
    require(uris.isNotEmpty()) { "No media was selected" }
    require(uris.size <= policy.maximumItems) { policy.tooManyItemsMessage }
    val staged = mutableListOf<LocalPostMedia>()
    try {
        uris.forEach { uri -> staged += stageAndroidMedia(context, uri, policy) }
        return staged
    } catch (error: Throwable) {
        staged.forEach { media -> File(media.localPath).delete() }
        throw error
    }
}

/** Opaque handle saved by the host while the system camera owns the foreground. */
class AndroidCameraCapture internal constructor(
    val token: String,
    val outputUri: Uri,
)

/**
 * Creates a FileProvider-backed cache target for ActivityResultContracts.TakePicture.
 *
 * The token, rather than a path, is safe to retain with rememberSaveable across host recreation.
 */
fun prepareAndroidCameraCapture(context: Context): AndroidCameraCapture {
    pruneStaleCameraCaptures(context)
    val token = UUID.randomUUID().toString()
    val output = cameraCaptureFile(context, token).apply {
        require(createNewFile()) { "Could not prepare a private camera file" }
    }
    return try {
        AndroidCameraCapture(
            token = token,
            outputUri = FileProvider.getUriForFile(context, cameraProviderAuthority(context), output),
        )
    } catch (error: Throwable) {
        output.delete()
        throw IllegalStateException("The private camera provider is unavailable", error)
    }
}

/**
 * Finalizes a full-resolution camera result into durable no-backup outbox storage.
 * Cancellation and every validation failure remove the temporary camera file.
 */
fun finishAndroidCameraCapture(
    context: Context,
    token: String,
    succeeded: Boolean,
): LocalPostMedia? {
    val policy = MediaAcquisitionPolicies.camera
    val source = cameraCaptureFile(context, token)
    if (!succeeded) {
        source.delete()
        return null
    }
    val destination = privateStagingFile(context, policy.defaultFileExtension)
    try {
        require(source.isFile && source.length() > 0L) { "The captured photo is unavailable" }
        require(source.length() <= policy.maximumBytesPerItem) { policy.tooLargeMessage }
        val metadata = imageMetadata(source)
        source.inputStream().buffered().use { input ->
            destination.outputStream().buffered().use { output ->
                input.copyBoundedTo(output, policy)
            }
        }
        return policy.validate(
            LocalPostMedia(
                name = "Captured photo.jpg",
                mimeType = policy.defaultMimeType,
                localPath = destination.absolutePath,
                byteSize = destination.length(),
                width = metadata.width,
                height = metadata.height,
            ),
        )
    } catch (error: Throwable) {
        destination.delete()
        throw error
    } finally {
        source.delete()
    }
}

private fun privateStagingFile(context: Context, extension: String? = null): File =
    File(context.noBackupFilesDir, "pending-uploads").apply { mkdirs() }
        .resolve(
            buildString {
                append(UUID.randomUUID())
                extension?.takeIf(String::isNotBlank)?.let { append('.').append(it) }
            },
        )

internal fun cameraProviderAuthority(context: Context): String =
    "${context.packageName}$CAMERA_PROVIDER_AUTHORITY_SUFFIX"

internal fun cameraCaptureFile(context: Context, token: String): File {
    val canonicalToken = runCatching { UUID.fromString(token).toString() }.getOrNull()
    require(canonicalToken == token) { "The camera capture token is invalid" }
    return cameraCaptureDirectory(context).resolve("$canonicalToken.jpg")
}

private fun cameraCaptureDirectory(context: Context): File =
    File(context.cacheDir, CAMERA_CAPTURE_DIRECTORY).apply {
        require(isDirectory || mkdirs()) { "Could not prepare private camera storage" }
    }

private fun pruneStaleCameraCaptures(context: Context, nowMillis: Long = System.currentTimeMillis()) {
    cameraCaptureDirectory(context).listFiles().orEmpty()
        .filter { file -> nowMillis - file.lastModified() >= CAMERA_CAPTURE_MAX_AGE_MILLIS }
        .forEach(File::delete)
}

private fun java.io.InputStream.copyBoundedTo(
    output: java.io.OutputStream,
    policy: MediaAcquisitionPolicy,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= policy.maximumBytesPerItem) { policy.tooLargeMessage }
        output.write(buffer, 0, count)
    }
}

private data class AndroidMediaMetadata(
    val width: Int?,
    val height: Int?,
    val durationSeconds: Int? = null,
)

private fun imageMetadata(file: File): AndroidMediaMetadata {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    require(options.outWidth > 0 && options.outHeight > 0) { "The selected image could not be decoded" }
    return AndroidMediaMetadata(options.outWidth, options.outHeight)
}

private fun videoMetadata(file: File): AndroidMediaMetadata = MediaMetadataRetriever().use { metadata ->
    metadata.setDataSource(file.absolutePath)
    AndroidMediaMetadata(
        width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
        height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
        durationSeconds = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.let { (it / 1_000L).toInt() },
    )
}

private const val CAMERA_PROVIDER_AUTHORITY_SUFFIX = ".readthat-media"
private const val CAMERA_CAPTURE_DIRECTORY = "readthat-camera-captures"
private const val CAMERA_CAPTURE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
