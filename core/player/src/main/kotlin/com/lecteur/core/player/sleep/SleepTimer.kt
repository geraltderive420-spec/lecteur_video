package com.lecteur.core.player.sleep

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SleepTimerState {
    data object Off : SleepTimerState
    data class Counting(val remainingMs: Long) : SleepTimerState
    data object UntilEndOfMedia : SleepTimerState
}

/** Sleep timer: pauses playback after a delay, or at the end of the current media. [onFire] performs the pause. */
class SleepTimer(
    private val scope: CoroutineScope,
    private val onFire: () -> Unit
) {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var job: Job? = null

    fun startAfter(durationMs: Long) {
        require(durationMs > 0) { "durationMs must be positive" }
        cancel()
        _state.value = SleepTimerState.Counting(durationMs)
        job = scope.launch {
            var remaining = durationMs
            while (remaining > 0) {
                val step = minOf(TICK_MS, remaining)
                delay(step)
                remaining -= step
                _state.value = SleepTimerState.Counting(remaining)
            }
            fire()
        }
    }

    fun startUntilEndOfMedia() {
        cancel()
        _state.value = SleepTimerState.UntilEndOfMedia
    }

    /** The engine reports the end of the current media. */
    fun onMediaEnded() {
        if (_state.value == SleepTimerState.UntilEndOfMedia) fire()
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = SleepTimerState.Off
    }

    private fun fire() {
        _state.value = SleepTimerState.Off
        job = null
        onFire()
    }

    companion object {
        const val TICK_MS = 1_000L
    }
}
