package com.lecteur.core.player.tracks

/** Picks the initial audio and subtitle tracks from the user's preferences (series overrides already merged in). */
object TrackSelectionPolicy {

    fun choose(
        audio: List<AudioOption>,
        subtitles: List<SubtitleOption>,
        prefs: LanguagePreferences
    ): TrackChoice {
        val playableAudio = audio.filter { it.isSupported }.ifEmpty { audio }
        val chosenAudio = chooseAudio(playableAudio, prefs)
        val chosenSubtitle = chooseSubtitle(subtitles.filter { it.isSupported }, chosenAudio, prefs)
        return TrackChoice(chosenAudio?.index, chosenSubtitle?.index)
    }

    private fun chooseAudio(audio: List<AudioOption>, prefs: LanguagePreferences): AudioOption? {
        for (lang in prefs.audio) {
            audio.firstOrNull { LanguageCodes.matches(it.language, lang) }?.let { return it }
        }
        return audio.firstOrNull { it.isDefault } ?: audio.firstOrNull()
    }

    private fun chooseSubtitle(
        subtitles: List<SubtitleOption>,
        audio: AudioOption?,
        prefs: LanguagePreferences
    ): SubtitleOption? {
        if (subtitles.isEmpty()) return null

        fun preferredFull(): SubtitleOption? {
            for (lang in prefs.subtitles) {
                subtitles.firstOrNull { !it.isForced && LanguageCodes.matches(it.language, lang) }?.let { return it }
            }
            return null
        }

        fun forcedFor(language: String?): SubtitleOption? =
            subtitles.firstOrNull { it.isForced && LanguageCodes.matches(it.language, language) }
                ?: subtitles.firstOrNull { it.isForced && LanguageCodes.normalize(it.language) == null }

        val audioUnderstood = audio != null &&
            (prefs.subtitles + prefs.audio).any { LanguageCodes.matches(audio.language, it) }

        return when (prefs.subtitleMode) {
            SubtitleMode.OFF -> forcedFor(audio?.language)
            SubtitleMode.ALWAYS -> preferredFull() ?: forcedFor(prefs.subtitles.firstOrNull())
            SubtitleMode.AUTO ->
                if (audioUnderstood) {
                    forcedFor(audio?.language)
                } else {
                    preferredFull() ?: forcedFor(prefs.subtitles.firstOrNull())
                }
        }
    }
}
