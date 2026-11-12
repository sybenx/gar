package org.stypox.dicio.gar.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.stypox.dicio.gar.ui.garble.GarbleMarkGeometry
import org.stypox.dicio.gar.ui.garble.GarblePresets
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Garble's launcher icon as the launcher shows it, for the About screen (in place of Dicio's
 * squircle logo): the central 72 of the adaptive icon's 108 grid, masked to a squircle, with the
 * mark in its logo pose in the default dark mark colors on #121212. Same fit as
 * `res/drawable/ic_launcher_foreground.xml`: the pose's bounding box centered, its farthest bar end
 * [GLYPH_RADIUS] from the center.
 */
val GarbleIcon: ImageVector by lazy {
    val builder = ImageVector.Builder(
        name = "GarbleIcon",
        defaultWidth = 72.dp,
        defaultHeight = 72.dp,
        viewportWidth = 72f,
        viewportHeight = 72f,
    ).addPath(
        pathData = addPathNodes("M0 36 C0 10.8 10.8 0 36 0 s36 10.8 36 36 -10.8 36 -36 36 S0 61.2 0 36"),
        fill = SolidColor(Color(0xFF121212)),
    )
    val ends = garbleLogoEnds()
    val half = GarbleMarkGeometry.BAR_WIDTH / 2f
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (i in 0 until ends.size / 2) {
        val x = ends[i * 2]
        val y = ends[i * 2 + 1]
        minX = minOf(minX, x - half)
        maxX = maxOf(maxX, x + half)
        minY = minOf(minY, y - half)
        maxY = maxOf(maxY, y + half)
    }
    val ox = (minX + maxX) / 2f
    val oy = (minY + maxY) / 2f
    var extent = 0f
    for (i in 0 until ends.size / 2) {
        extent = maxOf(extent, hypot(ends[i * 2] - ox, ends[i * 2 + 1] - oy) + half)
    }
    val k = GLYPH_RADIUS / extent
    fun x(v: Float) = 36f + (v - ox) * k
    fun y(v: Float) = 36f + (v - oy) * k
    for (bar in 0 until GarbleMarkGeometry.BARS) {
        val p = bar * 4
        builder.addPath(
            pathData = addPathNodes(
                "M${x(ends[p])} ${y(ends[p + 1])} L${x(ends[p + 2])} ${y(ends[p + 3])}"
            ),
            stroke = SolidColor(GarblePresets.DarkMark[bar]),
            strokeLineWidth = GarbleMarkGeometry.BAR_WIDTH * k,
            strokeLineCap = StrokeCap.Round,
        )
    }
    builder.build()
}

/** In the 72 unit viewport, as the launcher icon's 28 of 108 (the safe circle is 33). */
private const val GLYPH_RADIUS = 28f

/**
 * The centers of each logo bar's two round caps, in mark units around the mark's center:
 * `[x0a, y0a, x0b, y0b, x1a, ...]`. A bar of height h is a stroke of width w between two points
 * h - w apart, rotated about the bar's center (positive degrees clockwise, y down).
 */
private fun garbleLogoEnds(): FloatArray {
    val pose = GarbleMarkGeometry.LOGO
    val out = FloatArray(GarbleMarkGeometry.BARS * 4)
    for (bar in 0 until GarbleMarkGeometry.BARS) {
        val h = pose[bar * 3]
        val rad = Math.toRadians(pose[bar * 3 + 1].toDouble())
        val cx = GarbleMarkGeometry.barCenterX(bar)
        val cy = pose[bar * 3 + 2]
        val l = (h - GarbleMarkGeometry.BAR_WIDTH) / 2f
        for ((end, sign) in listOf(-1f, 1f).withIndex()) {
            val dy = sign * l
            out[bar * 4 + end * 2] = cx - dy * sin(rad).toFloat()
            out[bar * 4 + end * 2 + 1] = cy + dy * cos(rad).toFloat()
        }
    }
    return out
}

@Preview
@Composable
private fun GarbleIconPreview() {
    Image(imageVector = GarbleIcon, contentDescription = null, modifier = Modifier.size(100.dp))
}
