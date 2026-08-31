package dev.readthat.feed.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedFeedInteractionTelemetryTest {
    @Test
    fun interactionDimensionIsStableBoundedAndUnique() {
        val values = SharedFeedInteraction.entries.map(SharedFeedInteraction::telemetryValue)
        assertEquals(values.size, values.toSet().size)
        assertEquals(
            setOf(
                "vote",
                "open_detail",
                "open_media",
                "open_community",
                "open_profile",
                "open_ad_profile",
                "open_ad",
                "open_ad_related",
                "share",
                "reshare",
            ),
            values.toSet(),
        )
        SharedFeedInteraction.entries.forEach { interaction ->
            assertEquals(
                mapOf("interaction_type" to interaction.telemetryValue),
                interaction.telemetryAttributes(),
            )
        }
    }
}
