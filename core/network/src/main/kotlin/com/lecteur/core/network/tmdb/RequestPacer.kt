package com.lecteur.core.network.tmdb

/**
 * Hands out request slots at least [minIntervalMs] apart (TMDB tolerates roughly 50 requests per second;
 * the default spacing keeps well under that). Pure and clock-driven so it can be tested without sleeping.
 */
class RequestPacer(
    private val minIntervalMs: Long = 40,
    private val now: () -> Long = System::currentTimeMillis
) {
    private var nextSlot = 0L

    /** Reserves the next slot and returns how long the caller must wait before using it. */
    @Synchronized
    fun reserve(): Long {
        val current = now()
        val slot = maxOf(current, nextSlot)
        nextSlot = slot + minIntervalMs
        return slot - current
    }
}
