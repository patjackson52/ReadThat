package dev.readthat.media.acquisition.ui

import dev.readthat.media.acquisition.MediaAcquisitionPolicy
import dev.readthat.shared.LocalPostMedia

/**
 * Owns staged files only until a native picker request is delivered to shared feature state.
 *
 * Native code performs the deletion because file APIs differ, while this state machine makes
 * validation, overflow, all-or-nothing completion, replacement and disposal behavior testable
 * once for every target.
 */
internal class StagedMediaSelectionAccumulator(
    private val policy: MediaAcquisitionPolicy,
) {
    private val staged = mutableListOf<LocalPostMedia>()
    private var selectionError: String? = null

    fun offer(media: LocalPostMedia): StagedMediaOffer = try {
        val validated = policy.validate(media)
        if (staged.size >= policy.maximumItems) {
            selectionError = selectionError ?: policy.tooManyItemsMessage
            StagedMediaOffer.Rejected(media, policy.tooManyItemsMessage)
        } else {
            staged += validated
            StagedMediaOffer.Accepted
        }
    } catch (error: IllegalArgumentException) {
        val message = error.message ?: "The selected media is invalid"
        selectionError = selectionError ?: message
        StagedMediaOffer.Rejected(media, message)
    }

    fun finish(nativeError: String?): StagedMediaCompletion {
        val items = takeStaged()
        val error = nativeError ?: selectionError
        selectionError = null
        return if (error == null) {
            StagedMediaCompletion.Deliver(items)
        } else {
            StagedMediaCompletion.Reject(items, error)
        }
    }

    /** Returns still-owned files so the platform can remove them before reuse or disposal. */
    fun reset(): List<LocalPostMedia> {
        val items = takeStaged()
        selectionError = null
        return items
    }

    private fun takeStaged(): List<LocalPostMedia> = staged.toList().also { staged.clear() }
}

internal sealed interface StagedMediaOffer {
    data object Accepted : StagedMediaOffer
    data class Rejected(val media: LocalPostMedia, val error: String) : StagedMediaOffer
}

internal sealed interface StagedMediaCompletion {
    data class Deliver(val media: List<LocalPostMedia>) : StagedMediaCompletion
    data class Reject(val media: List<LocalPostMedia>, val error: String) : StagedMediaCompletion
}
