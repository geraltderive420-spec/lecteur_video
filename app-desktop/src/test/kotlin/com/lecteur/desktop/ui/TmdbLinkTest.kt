package com.lecteur.desktop.ui

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.model.MediaKind
import org.junit.Test

class TmdbLinkTest {

    @Test
    fun `page addresses give the kind and the id`() {
        assertThat(TmdbLink.parse("https://www.themoviedb.org/movie/27205-inception")).isEqualTo(TmdbLink(MediaKind.MOVIE, 27205))
        assertThat(TmdbLink.parse("https://www.themoviedb.org/tv/1396-breaking-bad?language=fr-FR")).isEqualTo(TmdbLink(MediaKind.SERIES, 1396))
        assertThat(TmdbLink.parse("themoviedb.org/fr/tv/42951")).isEqualTo(TmdbLink(MediaKind.SERIES, 42951))
        assertThat(TmdbLink.parse("https://www.themoviedb.org/tv/1396/season/2")).isEqualTo(TmdbLink(MediaKind.SERIES, 1396))
    }

    @Test
    fun `a bare id is accepted, anything else is not`() {
        assertThat(TmdbLink.parse(" 157336 ")).isEqualTo(TmdbLink(null, 157336))
        assertThat(TmdbLink.parse("Inception")).isNull()
        assertThat(TmdbLink.parse("https://www.imdb.com/title/tt1375666/")).isNull()
        assertThat(TmdbLink.parse("")).isNull()
    }
}
