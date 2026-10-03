package com.lecteur.feature.cast

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.common.cast.protocol.RemoteTrack
import com.lecteur.feature.cast.ui.clock
import com.lecteur.feature.cast.ui.trackLabel
import org.junit.Test

class RemoteLabelsTest {
    @Test fun `clock formats minutes and hours`() {
        assertThat(clock(0)).isEqualTo("0:00")
        assertThat(clock(65_000)).isEqualTo("1:05")
        assertThat(clock(3_725_000)).isEqualTo("1:02:05")
        assertThat(clock(-5)).isEqualTo("0:00")
    }

    @Test fun `track label combines what is known`() {
        assertThat(trackLabel(RemoteTrack(0, "fr", "VFF", "ac3"))).isEqualTo("VFF · FR · AC3")
        assertThat(trackLabel(RemoteTrack(1, "en", null, null))).isEqualTo("EN")
        assertThat(trackLabel(RemoteTrack(2))).isEqualTo("Piste 3")
    }
}
