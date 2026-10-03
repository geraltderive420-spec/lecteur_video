package com.lecteur.core.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import com.lecteur.core.model.ListPickerState

/**
 * "Add to a list": every list with a checkbox, and a line to create a new one that already holds the title.
 * Stateless apart from the text being typed; the caller owns the lists (see ListPickerModel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListPickerSheet(
    state: ListPickerState,
    onToggle: (com.lecteur.core.model.ListChoice) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    // A refused name stays in the field so it can be fixed; once the list exists (one more line) the field empties.
    LaunchedEffect(state.choices.size) { name = "" }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Ajouter à une liste", style = MaterialTheme.typography.titleMedium)
            Text(state.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)

            state.choices.forEach { choice ->
                Row(
                    Modifier.fillMaxWidth().toggleable(value = choice.isMember, role = Role.Checkbox, onValueChange = { onToggle(choice) }).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // The row carries the toggle and the semantics: the checkbox is only its picture.
                    Checkbox(checked = choice.isMember, onCheckedChange = null)
                    Text(choice.name, Modifier.padding(start = 16.dp))
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nouvelle liste") },
                    singleLine = true,
                    isError = state.error != null,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Créer") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
