package com.lecteur.core.data.library

import com.lecteur.core.model.ListChoice
import com.lecteur.core.model.ListPickerState
import com.lecteur.core.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The "add to a list" sheet's logic, shared by every screen that offers it (details, library, home) so each view model only
 * holds one of these. Created with the view model's scope; [state] is null while the sheet is closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListPickerModel(private val lists: UserListsRepository, private val scope: CoroutineScope) {

    private data class Target(val kind: MediaKind, val id: Long, val title: String)

    private val target = MutableStateFlow<Target?>(null)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<ListPickerState?> = combine(
        target.flatMapLatest { t -> if (t == null) emptyFlow() else lists.choicesFor(t.kind, t.id).map { t to it } },
        error
    ) { (t, choices), error -> ListPickerState(t.title, choices, error) }
        .combine(target) { state, current -> state.takeIf { current != null } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    fun open(kind: MediaKind, id: Long, title: String) {
        error.value = null
        target.value = Target(kind, id, title)
    }

    fun close() {
        target.value = null
        error.value = null
    }

    fun toggle(choice: ListChoice) {
        val t = target.value ?: return
        scope.launch { lists.setMember(t.kind, t.id, choice.id, !choice.isMember) }
    }

    /** Creates a list holding the title. A refused name keeps the sheet open with the reason. */
    fun create(rawName: String) {
        val t = target.value ?: return
        scope.launch {
            error.value = when (val result = lists.createAndAdd(t.kind, t.id, rawName)) {
                is ListResult.Done -> null
                is ListResult.Refused -> result.problem.message
            }
        }
    }
}
