package dev.readthat.media.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosPlaybackProgressPolicyTest {
    @Test
    fun activeForegroundIntentKeepsOneProcessPublisherRunning() {
        assertTrue(shouldPublishIosPlaybackProgress(
            hasOwner = true,
            appForeground = true,
            playbackRequested = true,
            state = PlatformPlaybackState.Playing,
        ))
        // AVPlayer can briefly remain paused after play() while the item becomes ready. The
        // publisher must survive that edge so it can observe the next time-control transition.
        assertTrue(shouldPublishIosPlaybackProgress(
            hasOwner = true,
            appForeground = true,
            playbackRequested = true,
            state = PlatformPlaybackState.Paused,
        ))
        assertTrue(shouldPublishIosPlaybackProgress(
            hasOwner = true,
            appForeground = true,
            playbackRequested = true,
            state = PlatformPlaybackState.Buffering,
        ))
    }

    @Test
    fun idleEdgesDoNotRetainThePublisher() {
        assertFalse(shouldPublishIosPlaybackProgress(
            hasOwner = false,
            appForeground = true,
            playbackRequested = true,
            state = PlatformPlaybackState.Playing,
        ))
        assertFalse(shouldPublishIosPlaybackProgress(
            hasOwner = true,
            appForeground = false,
            playbackRequested = true,
            state = PlatformPlaybackState.Playing,
        ))
        assertFalse(shouldPublishIosPlaybackProgress(
            hasOwner = true,
            appForeground = true,
            playbackRequested = false,
            state = PlatformPlaybackState.Paused,
        ))
        assertFalse(shouldPublishIosPlaybackProgress(
            hasOwner = true,
            appForeground = true,
            playbackRequested = true,
            state = PlatformPlaybackState.Ended,
        ))
        assertFalse(shouldPublishIosPlaybackProgress(
            hasOwner = true,
            appForeground = true,
            playbackRequested = true,
            state = PlatformPlaybackState.Error,
        ))
    }
}
