package dev.readthat.observability

import kotlin.js.Date

internal actual fun epochMilliseconds(): Long = Date.now().toLong()
