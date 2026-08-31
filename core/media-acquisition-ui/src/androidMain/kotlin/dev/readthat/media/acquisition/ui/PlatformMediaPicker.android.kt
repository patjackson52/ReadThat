package dev.readthat.media.acquisition.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.readthat.media.acquisition.MediaAcquisitionPolicies
import dev.readthat.media.acquisition.MediaAcquisitionPolicy
import dev.readthat.media.acquisition.finishAndroidCameraCapture
import dev.readthat.media.acquisition.prepareAndroidCameraCapture
import dev.readthat.media.acquisition.stageAndroidMediaSelection
import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.PostKind
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@Composable
actual fun rememberPlatformAvatarPickerLauncher(
    onPicked: (List<LocalPostMedia>) -> Unit,
    onError: (String) -> Unit,
): () -> Unit = rememberAndroidSinglePickerLauncher(
        policy = MediaAcquisitionPolicies.avatar,
        request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        onPicked = onPicked,
        onError = onError,
        fallbackError = "Unable to read the selected photo",
    )

@Composable
actual fun rememberPlatformMediaPickerLauncher(
    kind: PostKind,
    onPicked: (List<LocalPostMedia>) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun stage(uris: List<Uri>, policy: MediaAcquisitionPolicy) {
        if (uris.isEmpty()) return
        scope.launch {
            var staged = emptyList<LocalPostMedia>()
            try {
                staged = withContext(Dispatchers.IO) {
                    stageAndroidMediaSelection(context, uris, policy)
                }
                coroutineContext.ensureActive()
                onPicked(staged)
            } catch (cancelled: CancellationException) {
                staged.deleteStagedFiles()
                throw cancelled
            } catch (error: Throwable) {
                staged.deleteStagedFiles()
                onError(error.message ?: "Unable to read selected media")
            }
        }
    }
    val imagePolicy = MediaAcquisitionPolicies.image
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(imagePolicy.maximumItems),
    ) { uris ->
        stage(uris, imagePolicy)
    }
    val videoPolicy = MediaAcquisitionPolicies.video
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { stage(listOf(it), videoPolicy) }
    }
    return when (kind) {
        PostKind.Image -> ({
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        PostKind.Video -> ({
            videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        })
        else -> ({ onError("Choose an image or video post type first") })
    }
}

@Composable
actual fun rememberPlatformCameraLauncher(
    onPicked: (List<LocalPostMedia>) -> Unit,
    onError: (String) -> Unit,
): (() -> Unit)? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCaptureToken by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { succeeded ->
        val token = pendingCaptureToken ?: return@rememberLauncherForActivityResult
        pendingCaptureToken = null
        scope.launch {
            var staged: LocalPostMedia? = null
            try {
                staged = withContext(Dispatchers.IO) {
                    finishAndroidCameraCapture(context, token, succeeded)
                }
                coroutineContext.ensureActive()
                staged?.let { onPicked(listOf(it)) }
            } catch (cancelled: CancellationException) {
                staged?.let { File(it.localPath).delete() }
                throw cancelled
            } catch (error: Throwable) {
                staged?.let { File(it.localPath).delete() }
                onError(error.message ?: "Unable to save the captured photo")
            }
        }
    }
    return {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { prepareAndroidCameraCapture(context) } }
                .onSuccess { capture ->
                    pendingCaptureToken = capture.token
                    runCatching { camera.launch(capture.outputUri) }
                        .onFailure { error ->
                            pendingCaptureToken = null
                            scope.launch(Dispatchers.IO) {
                                finishAndroidCameraCapture(context, capture.token, succeeded = false)
                            }
                            onError(error.message ?: "Unable to open the camera")
                        }
                }
                .onFailure { onError(it.message ?: "Unable to prepare the camera") }
        }
    }
}

@Composable
private fun rememberAndroidSinglePickerLauncher(
    policy: MediaAcquisitionPolicy,
    request: PickVisualMediaRequest,
    onPicked: (List<LocalPostMedia>) -> Unit,
    onError: (String) -> Unit,
    fallbackError: String,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            var staged = emptyList<LocalPostMedia>()
            try {
                staged = withContext(Dispatchers.IO) {
                    stageAndroidMediaSelection(context, listOf(uri), policy)
                }
                coroutineContext.ensureActive()
                onPicked(staged)
            } catch (cancelled: CancellationException) {
                staged.deleteStagedFiles()
                throw cancelled
            } catch (error: Throwable) {
                staged.deleteStagedFiles()
                onError(error.message ?: fallbackError)
            }
        }
    }
    return { picker.launch(request) }
}

private fun List<LocalPostMedia>.deleteStagedFiles() {
    forEach { media -> File(media.localPath).delete() }
}
