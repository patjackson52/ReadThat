package dev.readthat.media.acquisition.ui

import dev.readthat.media.acquisition.MediaAcquisitionPolicies
import dev.readthat.shared.LocalPostMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StagedMediaSelectionAccumulatorTest {
    @Test
    fun validSelectionTransfersOwnershipOnlyAtSuccessfulFinish() {
        val accumulator = StagedMediaSelectionAccumulator(MediaAcquisitionPolicies.avatar)
        val avatar = image("avatar.jpg")

        assertEquals(StagedMediaOffer.Accepted, accumulator.offer(avatar))
        assertEquals(
            StagedMediaCompletion.Deliver(listOf(avatar)),
            accumulator.finish(nativeError = null),
        )
        assertEquals(StagedMediaCompletion.Deliver(emptyList()), accumulator.finish(null))
    }

    @Test
    fun overflowRejectsNewFileAndMakesAcceptedSiblingsAllOrNothing() {
        val accumulator = StagedMediaSelectionAccumulator(MediaAcquisitionPolicies.avatar)
        val first = image("first.jpg")
        val overflow = image("overflow.jpg")

        assertEquals(StagedMediaOffer.Accepted, accumulator.offer(first))
        assertEquals(
            StagedMediaOffer.Rejected(overflow, "Choose one profile photo"),
            accumulator.offer(overflow),
        )
        assertEquals(
            StagedMediaCompletion.Reject(listOf(first), "Choose one profile photo"),
            accumulator.finish(null),
        )
    }

    @Test
    fun validationFailureRejectsItsFileAndNativeFailureWinsCompletionMessage() {
        val accumulator = StagedMediaSelectionAccumulator(MediaAcquisitionPolicies.avatar)
        val invalid = image("avatar.pdf", mime = "application/pdf")
        val offer = assertIs<StagedMediaOffer.Rejected>(accumulator.offer(invalid))
        assertEquals(invalid, offer.media)

        assertEquals(
            StagedMediaCompletion.Reject(emptyList(), "Photos access was denied"),
            accumulator.finish("Photos access was denied"),
        )
    }

    @Test
    fun resetReturnsOwnedFilesAndClearsPriorError() {
        val accumulator = StagedMediaSelectionAccumulator(MediaAcquisitionPolicies.avatar)
        val first = image("first.jpg")
        val overflow = image("overflow.jpg")
        accumulator.offer(first)
        accumulator.offer(overflow)

        assertEquals(listOf(first), accumulator.reset())
        assertEquals(StagedMediaCompletion.Deliver(emptyList()), accumulator.finish(null))
    }

    private fun image(name: String, mime: String = "image/jpeg") = LocalPostMedia(
        name = name,
        mimeType = mime,
        localPath = "/staged/$name",
        byteSize = 1_024,
        width = 100,
        height = 100,
    )
}
