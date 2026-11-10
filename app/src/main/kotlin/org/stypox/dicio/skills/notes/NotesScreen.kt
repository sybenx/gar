package org.stypox.dicio.skills.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.stypox.dicio.R

/**
 * GAR's screen of what it keeps (the lists, notes and facts skills' settings): every list, note
 * and remembered fact, with Share all (plain text to any app, for export) and Delete all (a
 * second tap confirms).
 */
@Composable
fun NotesScreen() {
    val context = LocalContext.current
    val notes = remember { GarNotes.get(context) }
    var data by remember { mutableStateOf(notes.current()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val empty = stringResource(R.string.gar_notes_screen_empty)

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = stringResource(R.string.gar_notes_screen_title), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.gar_notes_screen_description), style = MaterialTheme.typography.bodySmall)

        Section(stringResource(R.string.gar_notes_screen_lists))
        if (data.lists.isEmpty()) Line(empty)
        for (list in data.lists) {
            Line("${NotesSay.sentence(list.name)}: ${list.items.joinToString(", ").ifEmpty { "-" }}")
        }

        Section(stringResource(R.string.gar_notes_screen_notes))
        if (data.notes.isEmpty()) Line(empty)
        for (note in data.notes.reversed()) {
            Line(note.text)
        }

        Section(stringResource(R.string.gar_notes_screen_facts))
        if (data.facts.isEmpty()) Line(empty)
        for (fact in data.facts.reversed()) {
            Line(FactWords.secondPerson(fact.text))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { NotesApps.share(context, exportText(data)) }) {
                Text(text = stringResource(R.string.gar_notes_screen_share_all))
            }
            OutlinedButton(onClick = {
                if (confirmDelete) {
                    data = notes.update { GarNotesData() }
                    confirmDelete = false
                } else {
                    confirmDelete = true
                }
            }) {
                Text(
                    text = stringResource(
                        if (confirmDelete) R.string.gar_notes_screen_delete_confirm else R.string.gar_notes_screen_delete_all
                    )
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Line(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

/** Everything as plain text, for "Share all". */
fun exportText(data: GarNotesData): String = buildString {
    for (list in data.lists) {
        append(NotesSay.sentence(list.name)).append(" list\n")
        list.items.forEach { append("- ").append(it).append('\n') }
        append('\n')
    }
    if (data.notes.isNotEmpty()) {
        append("Notes\n")
        data.notes.reversed().forEach { append("- ").append(it.text).append('\n') }
        append('\n')
    }
    if (data.facts.isNotEmpty()) {
        append("Remembered\n")
        data.facts.reversed().forEach { append("- ").append(FactWords.secondPerson(it.text)).append('\n') }
    }
}.trim()
