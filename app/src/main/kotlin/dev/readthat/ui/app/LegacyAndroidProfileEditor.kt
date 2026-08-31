package dev.readthat.ui.app

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import dev.readthat.data.backend.BackendGraph
import dev.readthat.data.backend.BackendRepository
import dev.readthat.shared.PostKind
import dev.readthat.shared.Validators
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compiled reference for the mature Android-only profile pipeline.
 *
 * Active Android and iOS profile screens use `SharedProfileViewModel`; keeping this class out of
 * the root graph preserves the old implementation for comparison without constructing its legacy
 * repository or duplicating profile state during normal application startup.
 */
@Suppress("unused")
internal class LegacyAndroidProfileEditor(
    private val app: Application,
    private val scope: CoroutineScope,
    private val backend: BackendRepository = BackendGraph.repository(app),
) {
    private val mutableSaving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = mutableSaving.asStateFlow()

    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    fun saveProfile(
        displayName: String,
        bio: String,
        selectedAvatar: Uri?,
        removeAvatar: Boolean,
        onSaved: () -> Unit,
    ) {
        val error = Validators.displayName(displayName) ?: Validators.bio(bio)
        if (error != null) {
            mutableMessages.tryEmit(error)
            return
        }
        mutableSaving.value = true
        scope.launch {
            runCatching {
                var preparedAvatar: PreparedAvatar? = null
                try {
                    val avatarMediaId = selectedAvatar?.let { uri ->
                        prepareAvatar(uri).also { preparedAvatar = it }.let { prepared ->
                            backend.uploadMedia(
                                kind = PostKind.Image,
                                contentType = prepared.contentType,
                                byteSize = prepared.file.length(),
                                openStream = prepared.file::inputStream,
                                width = prepared.width,
                                height = prepared.height,
                                altText = "${displayName.trim()} profile photo",
                            ).id
                        }
                    }
                    backend.updateProfile(
                        displayName = displayName,
                        bio = bio,
                        avatarMediaId = avatarMediaId,
                        updateAvatar = selectedAvatar != null || removeAvatar,
                    )
                } finally {
                    preparedAvatar?.file?.delete()
                }
            }
                .onSuccess { onSaved() }
                .onFailure { mutableMessages.emit(it.userMessage("Could not update profile")) }
            mutableSaving.value = false
        }
    }

    private suspend fun prepareAvatar(uri: Uri): PreparedAvatar = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        val contentType = resolver.getType(uri)?.lowercase()
            ?: error("Could not determine the image type")
        require(contentType in SUPPORTED_AVATAR_TYPES) {
            "Choose a JPEG, PNG, WebP, AVIF, or GIF image"
        }

        val directory = app.noBackupFilesDir.resolve("pending-avatars").apply { mkdirs() }
        val destination = directory.resolve(UUID.randomUUID().toString())
        try {
            resolver.openInputStream(uri)?.use { input ->
                destination.outputStream().buffered().use { output ->
                    input.copyToBounded(output, MAX_AVATAR_BYTES)
                }
            } ?: error("Could not read the selected image")
            require(destination.length() in 1..MAX_AVATAR_BYTES) {
                "Choose an image smaller than 10 MB"
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            destination.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "The selected image could not be decoded"
            }
            require(bounds.outWidth <= 20_000 && bounds.outHeight <= 20_000) {
                "Choose an image no larger than 20,000 pixels per side"
            }
            PreparedAvatar(destination, contentType, bounds.outWidth, bounds.outHeight)
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }
}

private data class PreparedAvatar(
    val file: File,
    val contentType: String,
    val width: Int,
    val height: Int,
)

private val SUPPORTED_AVATAR_TYPES = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/avif",
    "image/gif",
)
private const val MAX_AVATAR_BYTES = 10L * 1024 * 1024

private fun InputStream.copyToBounded(output: OutputStream, maxBytes: Long) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) return
        total += count
        require(total <= maxBytes) { "Choose an image smaller than 10 MB" }
        output.write(buffer, 0, count)
    }
}

private fun Throwable.userMessage(fallback: String): String =
    message?.takeIf(String::isNotBlank) ?: fallback
