package com.lecteur.feature.shared

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.common.library.Catalog
import com.lecteur.core.common.library.EpisodeDto
import com.lecteur.core.common.library.FileDto
import com.lecteur.core.common.library.MovieDto
import com.lecteur.core.common.library.SeriesDto
import com.lecteur.core.common.library.WatchDto
import org.junit.Test

class SharedShelvesTest {
    private fun file(id: Long, watch: WatchDto? = null, available: Boolean = true, height: Int = 1080) =
        FileDto(id, "f$id.mkv", 1_000, durationMs = 100_000, height = height, available = available, watch = watch)

    private fun started(at: Long, position: Long = 50_000) = WatchDto(positionMs = position, durationMs = 100_000, lastWatchedAt = at)
    private fun done(at: Long) = WatchDto(positionMs = 100_000, durationMs = 100_000, completed = true, lastWatchedAt = at)
    private fun episode(id: Long, number: Int, vararg files: FileDto) = EpisodeDto(id, 1, number, "Ep $number", files = files.toList())

    private val series = SeriesDto(
        1, "Show",
        episodes = listOf(
            episode(10, 1, file(100, done(1_000))),
            episode(11, 2, file(101, done(2_000))),
            episode(12, 3, file(102)),
            episode(13, 4, file(103, available = false))
        )
    )

    @Test fun `next up is the episode after the last one finished`() {
        val next = SharedShelves.nextUp(Catalog("s", "PC", 0, series = listOf(series)))
        assertThat(next.single().episodeId).isEqualTo(12)
        assertThat(SharedShelves.seriesTarget(series)?.id).isEqualTo(12)
    }

    @Test fun `an episode in progress wins over the next one`() {
        val inProgress = series.copy(episodes = series.episodes.map { if (it.id == 12L) it.copy(files = listOf(file(102, started(3_000)))) else it })
        assertThat(SharedShelves.seriesTarget(inProgress)?.id).isEqualTo(12)
        val catalog = Catalog("s", "PC", 0, series = listOf(inProgress))
        assertThat(SharedShelves.nextUp(catalog)).isEmpty()
        assertThat(SharedShelves.continueWatching(catalog).single().episodeId).isEqualTo(12)
    }

    @Test fun `continue watching is sorted by last watched`() {
        val catalog = Catalog(
            "s", "PC", 0,
            movies = listOf(
                MovieDto(1, "Old", files = listOf(file(1, started(10)))),
                MovieDto(2, "Recent", files = listOf(file(2, started(20)))),
                MovieDto(3, "Finished", files = listOf(file(3, done(30))))
            )
        )
        assertThat(SharedShelves.continueWatching(catalog).map { it.movieId }).containsExactly(2L, 1L).inOrder()
    }

    @Test fun `queue skips unavailable episodes`() {
        val queue = SharedShelves.queueFrom(series, series.episodes[1])
        assertThat(queue.map { it.first.id }).containsExactly(11L, 12L).inOrder()
    }

    @Test fun `movie plays its sharpest available file`() {
        val movie = MovieDto(1, "M", files = listOf(file(1, height = 2160, available = false), file(2, height = 1080), file(3, height = 720)))
        assertThat(SharedShelves.movieFile(movie)?.id).isEqualTo(2)
    }
}
