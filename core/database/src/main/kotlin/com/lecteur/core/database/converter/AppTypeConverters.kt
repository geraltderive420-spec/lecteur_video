package com.lecteur.core.database.converter

import androidx.room.TypeConverter
import com.lecteur.core.model.AudioCodec
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.VideoCodec

class AppTypeConverters {

    @TypeConverter
    fun fromMediaCategory(category: MediaCategory?): String = category?.name ?: MediaCategory.GENERIC.name

    @TypeConverter
    fun toMediaCategory(value: String?): MediaCategory = value?.let { MediaCategory.fromString(it) } ?: MediaCategory.GENERIC

    @TypeConverter
    fun fromMatchState(state: MatchState?): String = state?.name ?: MatchState.UNIDENTIFIED.name

    @TypeConverter
    fun toMatchState(value: String?): MatchState = value?.let {
        runCatching { MatchState.valueOf(it) }.getOrDefault(MatchState.UNIDENTIFIED)
    } ?: MatchState.UNIDENTIFIED

    @TypeConverter
    fun fromVideoCodec(codec: VideoCodec?): String = codec?.name ?: VideoCodec.UNKNOWN.name

    @TypeConverter
    fun toVideoCodec(value: String?): VideoCodec = value?.let {
        runCatching { VideoCodec.valueOf(it) }.getOrDefault(VideoCodec.UNKNOWN)
    } ?: VideoCodec.UNKNOWN

    @TypeConverter
    fun fromAudioCodec(codec: AudioCodec?): String = codec?.name ?: AudioCodec.UNKNOWN.name

    @TypeConverter
    fun toAudioCodec(value: String?): AudioCodec = value?.let {
        runCatching { AudioCodec.valueOf(it) }.getOrDefault(AudioCodec.UNKNOWN)
    } ?: AudioCodec.UNKNOWN

    @TypeConverter
    fun fromHdrType(type: HdrType?): String = type?.name ?: HdrType.NONE.name

    @TypeConverter
    fun toHdrType(value: String?): HdrType = value?.let {
        runCatching { HdrType.valueOf(it) }.getOrDefault(HdrType.NONE)
    } ?: HdrType.NONE
}
