package dev.readthat.navigation

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Narrow event bridge for native iOS edge-back presentation.
 *
 * Shared Compose remains authoritative for whether Back is currently legal and what it means.
 * UIKit supplies only the platform gesture recognizer and offers a request after the gesture has
 * crossed its completion threshold. A bounded, non-suspending flow prevents native callbacks
 * from blocking the main thread or replaying an old gesture into a newly created screen.
 */
class PlatformBackGestureBridge {
    private val mutableRequests = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val requests: SharedFlow<Unit> = mutableRequests.asSharedFlow()

    private var enabled = false

    val isEnabled: Boolean get() = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** Called by the native edge-pan adapter. Returns false at a shared root destination. */
    fun request(): Boolean = enabled && mutableRequests.tryEmit(Unit)
}
