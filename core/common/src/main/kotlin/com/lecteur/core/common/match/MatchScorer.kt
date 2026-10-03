package com.lecteur.core.common.match

import com.lecteur.core.model.MatchState
import kotlin.math.abs

/** What the file name and the file itself say about the title. */
data class MatchQuery(
    val title: String,
    val year: Int? = null,
    /** Real duration of the file (movies only: an episode's length says little about its series). */
    val runtimeMinutes: Int? = null
)

data class MatchCandidate(
    val id: Long,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val runtimeMinutes: Int? = null,
    val popularity: Double = 0.0
)

data class MatchScore(
    val candidate: MatchCandidate,
    val confidence: Double,
    val titleScore: Double,
    val yearScore: Double?,
    val runtimeScore: Double?
)

data class MatchDecision(val best: MatchScore?, val state: MatchState)

/**
 * Confidence of a candidate: title similarity, release year and duration, each weighted, with the missing
 * components dropped (a file without a year is not penalised, it just has less evidence).
 */
object MatchScorer {

    const val IDENTIFIED_THRESHOLD = 0.80
    const val TO_VERIFY_THRESHOLD = 0.50

    private const val TITLE_WEIGHT = 0.60
    private const val YEAR_WEIGHT = 0.30
    private const val RUNTIME_WEIGHT = 0.10

    /** Two different candidates this close in confidence, without a year to arbitrate, are a coin flip. */
    private const val AMBIGUITY_MARGIN = 0.05

    fun score(query: MatchQuery, candidate: MatchCandidate): MatchScore {
        val titleScore = listOfNotNull(candidate.title, candidate.originalTitle)
            .maxOf { TitleSimilarity.similarity(query.title, it) }
        val yearScore = yearScore(query.year, candidate.year)
        val runtimeScore = runtimeScore(query.runtimeMinutes, candidate.runtimeMinutes)

        var weighted = titleScore * TITLE_WEIGHT
        var weights = TITLE_WEIGHT
        if (yearScore != null) { weighted += yearScore * YEAR_WEIGHT; weights += YEAR_WEIGHT }
        if (runtimeScore != null) { weighted += runtimeScore * RUNTIME_WEIGHT; weights += RUNTIME_WEIGHT }

        return MatchScore(candidate, weighted / weights, titleScore, yearScore, runtimeScore)
    }

    /** Candidates best first; popularity breaks ties. */
    fun rank(query: MatchQuery, candidates: List<MatchCandidate>): List<MatchScore> =
        candidates.map { score(query, it) }
            .sortedWith(compareByDescending<MatchScore> { it.confidence }.thenByDescending { it.candidate.popularity })

    fun decide(query: MatchQuery, candidates: List<MatchCandidate>): MatchDecision {
        val ranked = rank(query, candidates)
        val best = ranked.firstOrNull() ?: return MatchDecision(null, MatchState.UNIDENTIFIED)

        val runnerUp = ranked.drop(1).firstOrNull { it.candidate.id != best.candidate.id }
        val ambiguous = query.year == null && runnerUp != null && best.confidence - runnerUp.confidence < AMBIGUITY_MARGIN

        val state = when {
            best.confidence < TO_VERIFY_THRESHOLD -> MatchState.UNIDENTIFIED
            best.confidence >= IDENTIFIED_THRESHOLD && !ambiguous -> MatchState.IDENTIFIED
            else -> MatchState.TO_VERIFY
        }
        // Below the floor nothing is proposed; above it, even a "to verify" guess is kept for the review screen
        return MatchDecision(best.takeIf { state != MatchState.UNIDENTIFIED }, state)
    }

    /** Release year and production year often differ by one. */
    private fun yearScore(wanted: Int?, actual: Int?): Double? {
        if (wanted == null || actual == null) return null
        return when (abs(wanted - actual)) {
            0 -> 1.0
            1 -> 0.7
            2 -> 0.3
            else -> 0.0
        }
    }

    private fun runtimeScore(wanted: Int?, actual: Int?): Double? {
        if (wanted == null || actual == null || wanted <= 0 || actual <= 0) return null
        val gap = abs(wanted - actual).toDouble() / actual
        return when {
            gap <= 0.08 -> 1.0
            gap <= 0.15 -> 0.7
            gap <= 0.30 -> 0.3
            else -> 0.0
        }
    }
}
