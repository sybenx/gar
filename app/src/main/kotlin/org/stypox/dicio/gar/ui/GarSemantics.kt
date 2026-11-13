package org.stypox.dicio.gar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics

/**
 * Accessibility cost control (gar/DESIGN.md, "Frame rate"). With any accessibility service on
 * (an autofill or launcher service is enough, TalkBack need not be), Compose rebuilds its
 * semantics map and sorts it by geometry after every layout change, e.g. every scroll frame. The
 * sort is roughly quadratic in the size of each traversal group, and without groups the whole
 * screen is one group. Marking each list item and card as its own group splits that sort into
 * many small ones. The reading order stays the same: the items are stacked and don't overlap.
 */
fun Modifier.garTraversalGroup(): Modifier = semantics { isTraversalGroup = true }

/**
 * A list item's content as one traversal group. The content is laid out top to bottom, as
 * LazyColumn lays out an item's several children, so an item looks the same with or without it.
 */
@Composable
fun GarTraversalGroup(content: @Composable () -> Unit) {
    Column(modifier = Modifier.garTraversalGroup()) {
        content()
    }
}

object GarSemantics {
    /**
     * What a skill row reads as, the way merging its two texts read: "name, example". One
     * description instead of the row's merged texts keeps the unmerged tree (which the
     * accessibility pass walks every frame) to one node per row.
     */
    fun skillRowDescription(name: String, example: String): String = when {
        name.isBlank() -> example.trim()
        example.isBlank() -> name.trim()
        else -> "${name.trim()}, ${example.trim()}"
    }
}
