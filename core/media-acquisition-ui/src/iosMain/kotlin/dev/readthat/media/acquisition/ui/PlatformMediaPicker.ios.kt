@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.readthat.media.acquisition.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.readthat.media.acquisition.MediaAcquisitionPolicies
import dev.readthat.media.acquisition.MediaAcquisitionPolicy
import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.PostKind
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUUID

/** Swift shell owns PHPicker; notifications keep UIKit/Photos details outside shared UI. */
@Composable
actual fun rememberPlatformAvatarPickerLauncher(
    onPicked: (List<LocalPostMedia>) -> Unit,
    onError: (String) -> Unit,
): () -> Unit = rememberIosMediaLauncher(MediaAcquisitionPolicies.avatar, onPicked, onError)

@Composable
actual fun rememberPlatformMediaPickerLauncher(
    kind: PostKind,
    onPicked: (List<LocalPostMedia>) -> Unit,
    onError: (String) -> Unit,
): () -> Unit = rememberIosMediaLauncher(
    MediaAcquisitionPolicies.forPostKind(kind),
    onPicked,
    onError,
)

@Composable
actual fun rememberPlatformCameraLauncher(
    onPicked: (List<LocalPostMedia>) -> Unit,
    onError: (String) -> Unit,
): (() -> Unit)? = rememberIosMediaLauncher(MediaAcquisitionPolicies.camera, onPicked, onError)

@Composable
private fun rememberIosMediaLauncher(
    policy: MediaAcquisitionPolicy,
    onPicked: (List<LocalPostMedia>) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val requestId = remember { NSUUID.UUID().UUIDString }
    val selection = remember(policy) { StagedMediaSelectionAccumulator(policy) }
    val currentOnPicked by rememberUpdatedState(onPicked)
    val currentOnError by rememberUpdatedState(onError)

    fun deleteStagedFiles(media: List<LocalPostMedia>) {
        media.forEach { item ->
            NSFileManager.defaultManager.removeItemAtPath(item.localPath, null)
        }
    }

    DisposableEffect(requestId, policy) {
        val center = NSNotificationCenter.defaultCenter
        val itemObserver = center.addObserverForName(
            name = MEDIA_PICKED,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { notification ->
            val values = notification?.userInfo.orEmpty()
            if (values["requestId"] as? String != requestId) return@addObserverForName
            val path = values["path"] as? String
            val name = values["name"] as? String
            val mime = values["mimeType"] as? String
            val size = (values["byteSize"] as? NSNumber)?.longLongValue
            if (path != null && name != null && mime != null && size != null) {
                val media = LocalPostMedia(
                    name, mime, path, size,
                    (values["width"] as? NSNumber)?.intValue,
                    (values["height"] as? NSNumber)?.intValue,
                    (values["durationSeconds"] as? NSNumber)?.intValue,
                )
                when (val offer = selection.offer(media)) {
                    StagedMediaOffer.Accepted -> Unit
                    is StagedMediaOffer.Rejected -> {
                        NSFileManager.defaultManager.removeItemAtPath(offer.media.localPath, null)
                    }
                }
            }
        }
        val finishObserver = center.addObserverForName(
            name = MEDIA_FINISHED,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { notification ->
            val values = notification?.userInfo.orEmpty()
            if (values["requestId"] as? String != requestId) return@addObserverForName
            when (val completion = selection.finish(values["error"] as? String)) {
                is StagedMediaCompletion.Deliver -> {
                    if (completion.media.isNotEmpty()) currentOnPicked(completion.media)
                }
                is StagedMediaCompletion.Reject -> {
                    deleteStagedFiles(completion.media)
                    currentOnError(completion.error)
                }
            }
        }
        onDispose {
            center.removeObserver(itemObserver)
            center.removeObserver(finishObserver)
            deleteStagedFiles(selection.reset())
        }
    }
    return remember(policy, requestId) {
        {
            deleteStagedFiles(selection.reset())
            NSNotificationCenter.defaultCenter.postNotificationName(
                PICK_MEDIA, null, mapOf("kind" to policy.identifier, "requestId" to requestId),
            )
        }
    }
}

private const val PICK_MEDIA = "ReadThatPickMedia"
private const val MEDIA_PICKED = "ReadThatMediaPicked"
private const val MEDIA_FINISHED = "ReadThatMediaPickerFinished"
