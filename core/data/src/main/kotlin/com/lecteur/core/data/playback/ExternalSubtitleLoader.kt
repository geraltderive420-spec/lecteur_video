package com.lecteur.core.data.playback

import com.lecteur.core.player.engine.ExternalSubtitleSource
import com.lecteur.core.player.subtitles.ExternalSubtitleFinder
import com.lecteur.core.player.tracks.LanguageCodes
import java.io.File
import javax.inject.Inject

/**
 * Finds subtitle files sitting next to a video. Only possible when the video has a real path and the folder
 * is readable (all-files access); otherwise the user loads subtitles by hand from the player.
 */
class ExternalSubtitleLoader @Inject constructor() {

    fun find(videoUri: String): List<ExternalSubtitleSource> {
        val path = StoragePaths.fromUri(videoUri) ?: return emptyList()
        val parent = StoragePaths.parentOf(path) ?: return emptyList()
        val names = runCatching { File(parent).list()?.toList() }.getOrNull() ?: return emptyList()

        return ExternalSubtitleFinder.find(StoragePaths.nameOf(path), names)
            .filter { it.format.isRenderable }
            .map { sub ->
                val language = LanguageCodes.displayName(sub.language)
                ExternalSubtitleSource(
                    uri = File(parent, sub.fileName).toURI().toString(),
                    mimeType = sub.format.mimeType!!,
                    language = sub.language,
                    label = buildString {
                        append(language ?: sub.fileName)
                        if (sub.isForced) append(" (forcés)")
                    },
                    isForced = sub.isForced
                )
            }
    }
}
