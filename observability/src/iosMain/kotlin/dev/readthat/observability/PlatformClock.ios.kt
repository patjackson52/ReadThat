package dev.readthat.observability

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval

@OptIn(ExperimentalForeignApi::class)
internal actual fun epochMilliseconds(): Long = memScoped {
    val value = alloc<timeval>()
    gettimeofday(value.ptr, null)
    value.tv_sec * 1_000L + value.tv_usec / 1_000L
}
