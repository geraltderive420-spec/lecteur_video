package com.lecteur.feature.details

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.core.data.shared.CopyOption
import com.lecteur.core.data.shared.CopyTarget
import com.lecteur.core.data.shared.PhoneCopies
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Copier sur le téléphone" of a PC's title: when the PC can make smaller versions, the screen first asks which one
 * ([qualityRequest]); the files go to the library folder of their kind; when the app may not write in one yet, the screen
 * asks the user to pick it ([folderRequest]) and the copy starts once it is chosen.
 */
class CopyRequests(private val copies: PhoneCopies, private val scope: CoroutineScope, private val say: suspend (String) -> Unit) {

    /** The folder picker to show: for which kind, opened on the library folder of that kind when there is one. */
    data class FolderRequest(val kind: MediaKind, val initialUri: String?)

    /** The choice between the original files and smaller versions made by the PC. */
    data class QualityRequest(val what: String, val options: List<CopyOption>)

    private data class Pending(val serverId: String, val kind: MediaKind, val fileIds: List<Long>, val what: String, val height: Int? = null)

    private var pending: Pending? = null
    private val _folderRequest = MutableStateFlow<FolderRequest?>(null)
    val folderRequest: StateFlow<FolderRequest?> = _folderRequest.asStateFlow()

    private val _qualityRequest = MutableStateFlow<QualityRequest?>(null)
    val qualityRequest: StateFlow<QualityRequest?> = _qualityRequest.asStateFlow()

    /** [what] names the copy in the confirmation: "« Dune (2021) »", "la saison 2"... */
    fun copy(serverId: String, kind: MediaKind, fileIds: List<Long>, what: String) {
        scope.launch {
            if (fileIds.isEmpty()) return@launch say("Rien à copier : aucun fichier n'est disponible sur le PC.")
            val request = Pending(serverId, kind, fileIds.distinct(), what)
            val options = copies.options(serverId, request.fileIds)
            if (options.size > 1) {
                pending = request
                _qualityRequest.value = QualityRequest(what, options)
                return@launch
            }
            toFolder(request)
        }
    }

    /** The quality dialog's answer; null when the user backed out. */
    fun onQualityPicked(option: CopyOption?) {
        val request = pending
        _qualityRequest.value = null
        if (option == null || request == null) {
            pending = null
            return
        }
        scope.launch { toFolder(request.copy(height = option.height)) }
    }

    private suspend fun toFolder(request: Pending) {
        val target = copies.target(request.kind)
        if (target != null) return start(request, target)
        pending = request
        _folderRequest.value = FolderRequest(request.kind, copies.pickHint(request.kind))
    }

    /** The picker's answer; null when the user backed out. */
    fun onFolderPicked(treeUri: String?) {
        val request = pending
        pending = null
        _folderRequest.value = null
        if (treeUri == null || request == null) return
        scope.launch {
            copies.adopt(treeUri, request.kind).fold(
                onSuccess = { start(request, it) },
                onFailure = { say(it.message ?: "Ce dossier ne peut pas recevoir de copies.") }
            )
        }
    }

    private suspend fun start(request: Pending, target: CopyTarget) {
        copies.enqueue(request.serverId, request.fileIds, target, request.height)
        val count = if (request.fileIds.size > 1) " (${request.fileIds.size} fichiers)" else ""
        val quality = request.height?.let { " en ${it}p (le PC convertit d'abord)" }.orEmpty()
        say("Copie de ${request.what}$count$quality vers « ${target.label} » : suivez-la dans les notifications.")
    }
}

/** Original or smaller: shown before a copy when the PC can make smaller versions. */
@Composable
private fun CopyQualityPicker(requests: CopyRequests) {
    val request by requests.qualityRequest.collectAsStateWithLifecycle()
    val current = request ?: return
    AlertDialog(
        onDismissRequest = { requests.onQualityPicked(null) },
        title = { Text("Quelle qualité ?") },
        text = {
            Column {
                Text(
                    "Une version réduite prend moins de place ; le PC la prépare avant de l'envoyer, ce qui peut prendre plusieurs minutes par film.",
                    style = MaterialTheme.typography.bodyMedium
                )
                current.options.forEach { option ->
                    TextButton(onClick = { requests.onQualityPicked(option) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (option.height == null) "Originale · ${Formatters.size(option.bytes)}" else "${option.height}p · environ ${Formatters.size(option.bytes)}",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { requests.onQualityPicked(null) }) { Text("Annuler") } }
    )
}

/** Explains where the copies go, then opens the system folder picker. */
@Composable
fun CopyFolderPicker(requests: CopyRequests) {
    CopyQualityPicker(requests)
    val request by requests.folderRequest.collectAsStateWithLifecycle()
    var picking by remember(request) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> requests.onFolderPicked(uri?.toString()) }
    val current = request ?: return
    if (picking) return
    val movies = current.kind == MediaKind.MOVIE
    AlertDialog(
        onDismissRequest = { requests.onFolderPicked(null) },
        title = { Text(if (movies) "Où ranger les films ?" else "Où ranger les séries ?") },
        text = {
            Text(
                if (movies) "Choisissez le dossier Films du téléphone. Chaque film y est copié dans son propre dossier « Titre (année) », puis apparaît dans la bibliothèque du téléphone."
                else "Choisissez le dossier Séries du téléphone. Les épisodes y sont rangés par série puis par saison (« Série/Saison 01 »), puis apparaissent dans la bibliothèque du téléphone."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                picking = true
                launcher.launch(current.initialUri?.let(::documentOf))
            }) { Text("Choisir le dossier") }
        },
        dismissButton = { TextButton(onClick = { requests.onFolderPicked(null) }) { Text("Annuler") } }
    )
}

/** The picker opens on a document, not on a tree: the tree's root document. */
private fun documentOf(treeUri: String): Uri? = runCatching {
    val tree = Uri.parse(treeUri)
    DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
}.getOrNull()
