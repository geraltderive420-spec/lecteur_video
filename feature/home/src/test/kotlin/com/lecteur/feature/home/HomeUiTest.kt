package com.lecteur.feature.home

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.data.library.ContinueItem
import com.lecteur.core.data.library.NextUpItem
import com.lecteur.core.model.HomeRow
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.WatchStatus
import org.junit.Test

class HomeUiTest {

    private fun item(id: Long) = LibraryItem(MediaKind.MOVIE, id, "T$id", 2000, null, null, WatchStatus.UNWATCHED, 0f, 1, 0, true, 0)

    @Test
    fun anEmptyHomeHasNoContent() {
        val ui = HomeUi()
        assertThat(HomeRow.entries.none(ui::hasContent)).isTrue()
    }

    @Test
    fun eachRowReadsItsOwnList() {
        val ui = HomeUi(
            recent = listOf(item(1)), movies = listOf(item(2)), series = listOf(item(3)), unwatched = listOf(item(4)), favorites = listOf(item(5))
        )
        assertThat(ui.itemsOf(HomeRow.RECENT).single().id).isEqualTo(1)
        assertThat(ui.itemsOf(HomeRow.MOVIES).single().id).isEqualTo(2)
        assertThat(ui.itemsOf(HomeRow.SERIES).single().id).isEqualTo(3)
        assertThat(ui.itemsOf(HomeRow.UNWATCHED).single().id).isEqualTo(4)
        assertThat(ui.itemsOf(HomeRow.FAVORITES).single().id).isEqualTo(5)
        assertThat(ui.itemsOf(HomeRow.CONTINUE)).isEmpty()
    }

    @Test
    fun continueAndNextUpHaveTheirOwnContent() {
        val resume = ContinueItem(1, MediaKind.MOVIE, 1, null, "Film", null, null, 0.5f, 30, true)
        val next = NextUpItem(2, 3, 4, "Show", "S01E02", null)
        val ui = HomeUi(continueItems = listOf(resume), nextUp = listOf(next))
        assertThat(ui.hasContent(HomeRow.CONTINUE)).isTrue()
        assertThat(ui.hasContent(HomeRow.NEXT_UP)).isTrue()
        assertThat(ui.hasContent(HomeRow.RECENT)).isFalse()
    }

    @Test
    fun anEmptyRowIsLeftOutEvenWhenVisible() {
        val ui = HomeUi(rows = HomeRow.entries, movies = listOf(item(1)))
        assertThat(ui.rows.filter(ui::hasContent)).containsExactly(HomeRow.MOVIES)
    }
}
