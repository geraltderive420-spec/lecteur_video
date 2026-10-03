package com.lecteur.feature.scanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lecteur.core.data.identify.MetadataRepository
import com.lecteur.core.data.library.ReviewItem
import com.lecteur.core.data.library.ReviewRepository
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.MetadataException
import com.lecteur.core.model.MetadataSearchHit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The correction dialog of one item: manual search, direct TMDB id entry, results. */
data class CorrectionState(
    val item: ReviewItem,
    val query: String,
    val year: String = "",
    val tmdbId: String = "",
    val results: List<MetadataSearchHit> = emptyList(),
    val searched: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val reviews: ReviewRepository,
    private val metadata: MetadataRepository
) : ViewModel() {

    val items: StateFlow<List<ReviewItem>?> = reviews.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _correction = MutableStateFlow<CorrectionState?>(null)
    val correction: StateFlow<CorrectionState?> = _correction

    fun open(item: ReviewItem) {
        _correction.value = CorrectionState(item, query = item.title, year = item.year?.toString().orEmpty())
        search()
    }

    /** Opens the dialog for a title picked anywhere in the app (a detail page); closes at once when it no longer exists. */
    fun openById(kind: MediaKind, id: Long) {
        viewModelScope.launch {
            val item = reviews.find(kind, id)
            if (item != null) open(item) else _correction.value = null
        }
    }

    fun close() { _correction.value = null }

    fun onQuery(text: String) = _correction.update { it?.copy(query = text) }

    fun onYear(text: String) = _correction.update { it?.copy(year = text.filter(Char::isDigit).take(4)) }

    fun onTmdbId(text: String) = _correction.update { it?.copy(tmdbId = text.filter(Char::isDigit).take(10), error = null) }

    fun search() {
        val current = _correction.value ?: return
        if (current.query.isBlank()) return
        _correction.update { it?.copy(busy = true, error = null) }
        viewModelScope.launch {
            val outcome = runCatching { metadata.search(current.item.kind, current.query, current.year.toIntOrNull()) }
            _correction.update { state ->
                state?.copy(
                    busy = false, searched = true,
                    results = outcome.getOrDefault(emptyList()),
                    error = outcome.exceptionOrNull()?.let(::describe)
                )
            }
        }
    }

    fun choose(hit: MetadataSearchHit) = apply(hit.tmdbId)

    fun applyTypedId() {
        val id = _correction.value?.tmdbId?.toLongOrNull() ?: return
        apply(id)
    }

    /** "Yes, that is the right film": the proposal becomes the user's choice and is locked. */
    fun confirm() {
        val item = _correction.value?.item ?: return
        viewModelScope.launch {
            metadata.confirm(item.kind, item.id)
            close()
        }
    }

    /** "Search again by yourself": removes the lock and the retry delay. */
    fun retryAutomatically() {
        val item = _correction.value?.item ?: return
        viewModelScope.launch {
            metadata.unlock(item.kind, item.id)
            close()
        }
    }

    private fun apply(tmdbId: Long) {
        val item = _correction.value?.item ?: return
        _correction.update { it?.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { metadata.applyManual(item.kind, item.id, tmdbId) }
                .onSuccess { close() }
                .onFailure { error -> _correction.update { it?.copy(busy = false, error = describe(error)) } }
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is MetadataException.Offline -> "Pas de connexion : la recherche en ligne est impossible pour le moment."
        is MetadataException.NotFound -> "Aucun titre ne porte l'identifiant ${error.tmdbId} sur TMDB."
        is MetadataException.NotConfigured -> "Clé API TMDB absente : ajoutez tmdb.apiKey dans local.properties."
        is MetadataException.Http -> "TMDB a répondu par une erreur (${error.code}). Réessayez dans un moment."
        else -> "Erreur inattendue : ${error.message ?: error::class.simpleName}"
    }
}

fun MediaKind.label(): String = if (this == MediaKind.MOVIE) "Film" else "Série"
