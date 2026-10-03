package com.lecteur.core.common.cast.http

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Decides when the streaming service should shut itself down: nothing is connected and nothing has been requested
 * for [idleTimeoutMs]. The foreground notification must not outlive the cast session.
 */
class AutoStopPolicy(
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val active = AtomicInteger(0)
    private val lastActivityAt = AtomicLong(clock())

    val activeConnections: Int get() = active.get()

    fun onRequestStart() {
        active.incrementAndGet()
        lastActivityAt.set(clock())
    }

    fun onRequestEnd() {
        active.updateAndGet { (it - 1).coerceAtLeast(0) }
        lastActivityAt.set(clock())
    }

    /** Any sign of life that is not a request (a token handed out, the receiver asking for the stream). */
    fun touch() = lastActivityAt.set(clock())

    fun shouldStop(): Boolean = active.get() == 0 && clock() - lastActivityAt.get() >= idleTimeoutMs

    companion object {
        /** A paused movie keeps its connection open; a closed one is not coming back after this long. */
        const val DEFAULT_IDLE_TIMEOUT_MS = 10L * 60 * 1000
    }
}
