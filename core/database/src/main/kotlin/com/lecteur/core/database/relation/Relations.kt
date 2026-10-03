package com.lecteur.core.database.relation

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.lecteur.core.database.entity.AudioTrackInfoEntity
import com.lecteur.core.database.entity.CastMemberEntity
import com.lecteur.core.database.entity.EpisodeEntity
import com.lecteur.core.database.entity.GenreEntity
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.entity.MovieGenreCrossRef
import com.lecteur.core.database.entity.SeasonEntity
import com.lecteur.core.database.entity.SeriesEntity
import com.lecteur.core.database.entity.SeriesGenreCrossRef
import com.lecteur.core.database.entity.SubtitleTrackInfoEntity
import com.lecteur.core.database.entity.WatchStateEntity

data class MovieWithFiles(
    @Embedded val movie: MovieEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "movieId"
    )
    val files: List<MediaFileEntity>
)

data class MovieDetailRelation(
    @Embedded val movie: MovieEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "movieId"
    )
    val files: List<MediaFileEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "movieId"
    )
    val cast: List<CastMemberEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = MovieGenreCrossRef::class,
            parentColumn = "movieId",
            entityColumn = "genreId"
        )
    )
    val genres: List<GenreEntity>
)

data class SeriesDetailRelation(
    @Embedded val series: SeriesEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "seriesId"
    )
    val seasons: List<SeasonEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "seriesId"
    )
    val cast: List<CastMemberEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = SeriesGenreCrossRef::class,
            parentColumn = "seriesId",
            entityColumn = "genreId"
        )
    )
    val genres: List<GenreEntity>
)

data class MediaFileWithTracks(
    @Embedded val mediaFile: MediaFileEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "mediaFileId"
    )
    val audioTracks: List<AudioTrackInfoEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "mediaFileId"
    )
    val subtitleTracks: List<SubtitleTrackInfoEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "mediaFileId"
    )
    val watchState: WatchStateEntity?
)

data class SeasonWithEpisodes(
    @Embedded val season: SeasonEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "seasonId"
    )
    val episodes: List<EpisodeEntity>
)

data class EpisodeWithFiles(
    @Embedded val episode: EpisodeEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "episodeId"
    )
    val files: List<MediaFileEntity>
)
