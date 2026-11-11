package org.stypox.dicio.gar.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * GAR's Tide icon as the launcher shows it, for the About screen (in place of Dicio's squircle
 * logo): the central 72 of the adaptive icon's 108 grid, masked to a squircle, with the glyph of
 * `res/drawable/ic_launcher_foreground.xml` (same paths, same 0.9 scale) on the Tide background.
 */
val GarTideIcon: ImageVector by lazy {
    val mint = SolidColor(Color(0xFFA7F3D0))
    ImageVector.Builder(
        name = "GarTideIcon",
        defaultWidth = 72.dp,
        defaultHeight = 72.dp,
        viewportWidth = 72f,
        viewportHeight = 72f,
    ).addPath(
        pathData = addPathNodes("M0 36 C0 10.8 10.8 0 36 0 s36 10.8 36 36 -10.8 36 -36 36 S0 61.2 0 36"),
        fill = SolidColor(Color(0xFF0E4F4A)),
    ).addGroup(
        translationX = -18f,
        translationY = -18f,
    ).addGroup(
        pivotX = 54f,
        pivotY = 54f,
        scaleX = 0.9f,
        scaleY = 0.9f,
        translationY = 0.5f,
    ).addPath(
        pathData = addPathNodes(
            "M32 34 h44 a8 8 0 0 1 8 8 v22 a8 8 0 0 1 -8 8 h-26 l-12 10 v-10 h-6 " +
                "a8 8 0 0 1 -8 -8 v-22 a8 8 0 0 1 8 -8 z"
        ),
        stroke = mint,
        strokeLineWidth = 5f,
        strokeLineJoin = StrokeJoin.Round,
    ).addPath(
        pathData = addPathNodes("M42 50 v6 M50 45 v16 M58 42 v22 M66 48 v10"),
        stroke = mint,
        strokeLineWidth = 5f,
        strokeLineCap = StrokeCap.Round,
    ).clearGroup().clearGroup().build()
}

@Preview
@Composable
private fun GarTideIconPreview() {
    Image(imageVector = GarTideIcon, contentDescription = null, modifier = Modifier.size(100.dp))
}
