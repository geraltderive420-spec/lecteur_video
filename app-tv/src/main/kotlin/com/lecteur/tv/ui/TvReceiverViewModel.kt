package com.lecteur.tv.ui

import androidx.lifecycle.ViewModel
import androidx.media3.common.util.UnstableApi
import com.lecteur.core.player.engine.Media3PlayerEngine
import com.lecteur.feature.cast.receiver.ReceiverHost
import com.lecteur.feature.cast.receiver.ReceiverUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** The receiver host as seen by the TV screens. */
@UnstableApi
@HiltViewModel
class TvReceiverViewModel @Inject constructor(private val host: ReceiverHost) : ViewModel() {
    val ui: StateFlow<ReceiverUi> = host.ui
    val engine: StateFlow<Media3PlayerEngine?> = host.engine

    fun closePlayback() = host.closePlayback()
}
