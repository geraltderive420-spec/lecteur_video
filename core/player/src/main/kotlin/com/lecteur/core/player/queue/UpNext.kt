package com.lecteur.core.player.queue

/**
 * The "next episode" card shown near the end of a file. [secondsLeft] counts down to the automatic switch; it is null in
 * the credits, where the card only offers the button (the switch then happens at the very end).
 */
data class UpNextPrompt(val secondsLeft: Int?)

object UpNext {

    /**
     * @param creditsStartMs where the end credits begin when the file has a chapter for them, null otherwise
     * @return the card to show now, or null when it is not time yet
     */
    fun prompt(positionMs: Long, durationMs: Long, speed: Float, countdownSeconds: Int, creditsStartMs: Long?): UpNextPrompt? {
        if (durationMs <= 0 || positionMs < 0) return null
        val remainingMs = ((durationMs - positionMs) / speed.coerceAtLeast(0.25f)).toLong()
        if (remainingMs <= 0) return UpNextPrompt(0)
        if (countdownSeconds > 0 && remainingMs <= countdownSeconds * 1_000L) {
            return UpNextPrompt(((remainingMs + 999) / 1_000).toInt())
        }
        if (creditsStartMs != null && positionMs >= creditsStartMs) return UpNextPrompt(null)
        return null
    }
}
