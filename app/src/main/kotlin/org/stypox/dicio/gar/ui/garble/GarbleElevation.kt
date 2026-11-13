package org.stypox.dicio.gar.ui.garble

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSimple
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Garble's corner radii. */
object GarbleShapes {
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val Card = RoundedCornerShape(24.dp)

    /** Home's "Try saying" cards. */
    val StarterCard = RoundedCornerShape(20.dp)
    val Chip = RoundedCornerShape(8.dp)

    /** Buttons are fully round. */
    val Pill = CircleShape
}

/**
 * The depth rules: a tonal step (dim < surface < card < raised) plus one tight soft shadow, drawn
 * with the fill in one pass. No inset highlights, no glows, no gradients.
 *
 * Each helper fills the shape (default color from [LocalGarbleColors], or [color]), draws its
 * shadow outside the bounds and clips the content to [shape]. Leave room around it (about 8dp
 * below a card) and don't clip the parent, or the shadow is cut off. Apply it before
 * `clickable` so the ripple is clipped too: `Modifier.garbleRaised().clickable { }`.
 */
fun Modifier.garbleCard(shape: Shape = GarbleShapes.Card, color: Color = Color.Unspecified): Modifier =
    this.then(GarbleSurfaceElement(GarbleSurfaceKind.CARD, shape, color)).clip(shape)

/** Secondary buttons (borderless raised pills), the mic button, icon circles; a 0 1 2 shadow. */
fun Modifier.garbleRaised(shape: Shape = GarbleShapes.Pill, color: Color = Color.Unspecified): Modifier =
    this.then(GarbleSurfaceElement(GarbleSurfaceKind.RAISED, shape, color)).clip(shape)

/** The sheet: surface fill, a 1dp `line` hairline along the top edge, a soft upward shadow. */
fun Modifier.garbleSheet(shape: Shape = GarbleShapes.Sheet, color: Color = Color.Unspecified): Modifier =
    this.then(GarbleSurfaceElement(GarbleSurfaceKind.SHEET, shape, color)).clip(shape)

enum class GarbleSurfaceKind { CARD, RAISED, SHEET }

/** One CSS-like box shadow: offset y, blur, and the black alpha in dark and in light. */
internal data class GarbleShadow(val dy: Dp, val blur: Dp, val darkAlpha: Float, val lightAlpha: Float)

internal object GarbleDepth {
    val Card = listOf(
        GarbleShadow(dy = 1.dp, blur = 2.dp, darkAlpha = 0.30f, lightAlpha = 0.10f),
        GarbleShadow(dy = 2.dp, blur = 8.dp, darkAlpha = 0.22f, lightAlpha = 0.06f),
    )
    val Raised = listOf(
        GarbleShadow(dy = 1.dp, blur = 2.dp, darkAlpha = 0.30f, lightAlpha = 0.10f),
    )
    val Sheet = listOf(
        GarbleShadow(dy = (-4).dp, blur = 16.dp, darkAlpha = 0.30f, lightAlpha = 0.08f),
    )

    fun of(kind: GarbleSurfaceKind) = when (kind) {
        GarbleSurfaceKind.CARD -> Card
        GarbleSurfaceKind.RAISED -> Raised
        GarbleSurfaceKind.SHEET -> Sheet
    }

    /** CSS blur is 2 sigma; Paint.setShadowLayer's radius r has sigma = 0.57735 r + 0.5. */
    fun shadowLayerRadius(blurPx: Float): Float =
        ((blurPx / 2f - 0.5f) / 0.57735f).coerceAtLeast(0.5f)
}

private data class GarbleSurfaceElement(
    val kind: GarbleSurfaceKind,
    val shape: Shape,
    val color: Color,
) : ModifierNodeElement<GarbleSurfaceNode>() {
    override fun create() = GarbleSurfaceNode(kind, shape, color)

    override fun update(node: GarbleSurfaceNode) {
        node.kind = kind
        node.shape = shape
        node.color = color
        node.outlineSize = Size.Unspecified
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "garble${kind.name.lowercase().replaceFirstChar { it.uppercase() }}"
        properties["shape"] = shape
        properties["color"] = color
    }
}

private class GarbleSurfaceNode(
    var kind: GarbleSurfaceKind,
    var shape: Shape,
    var color: Color,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    // a framework Paint for setShadowLayer (Compose's asFrameworkPaint is deprecated)
    private val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var outline: Outline? = null

    // reused while the size animates, so a resize doesn't allocate a path every frame
    private val reusablePath = android.graphics.Path()
    private val pathRect = android.graphics.RectF()
    private val pathRadii = FloatArray(8)
    private var outlinePath: android.graphics.Path? = null

    // a rounded rect with one radius draws with drawRoundRect (Skia's rrect blur fast path)
    private var simpleRadiusX = -1f
    private var simpleRadiusY = -1f
    var outlineSize: Size = Size.Unspecified
    private var outlineDirection: LayoutDirection? = null

    override fun ContentDrawScope.draw() {
        val colors = currentValueOf(LocalGarbleColors)
        val fill = if (color.isSpecified) {
            color
        } else {
            when (kind) {
                GarbleSurfaceKind.CARD -> colors.card
                GarbleSurfaceKind.RAISED -> colors.raised
                GarbleSurfaceKind.SHEET -> colors.surface
            }
        }
        if (outlineSize != size || outlineDirection != layoutDirection || outline == null) {
            val o = shape.createOutline(size, layoutDirection, this)
            outline = o
            simpleRadiusX = -1f
            simpleRadiusY = -1f
            outlinePath = when (o) {
                is Outline.Generic -> o.path.asAndroidPath()
                is Outline.Rounded -> {
                    val r = o.roundRect
                    if (r.isSimple) {
                        simpleRadiusX = r.topLeftCornerRadius.x
                        simpleRadiusY = r.topLeftCornerRadius.y
                        null
                    } else {
                        pathRect.set(r.left, r.top, r.right, r.bottom)
                        pathRadii[0] = r.topLeftCornerRadius.x
                        pathRadii[1] = r.topLeftCornerRadius.y
                        pathRadii[2] = r.topRightCornerRadius.x
                        pathRadii[3] = r.topRightCornerRadius.y
                        pathRadii[4] = r.bottomRightCornerRadius.x
                        pathRadii[5] = r.bottomRightCornerRadius.y
                        pathRadii[6] = r.bottomLeftCornerRadius.x
                        pathRadii[7] = r.bottomLeftCornerRadius.y
                        reusablePath.rewind()
                        reusablePath.addRoundRect(pathRect, pathRadii, android.graphics.Path.Direction.CW)
                        reusablePath
                    }
                }
                is Outline.Rectangle -> null
            }
            outlineSize = size
            outlineDirection = layoutDirection
        }
        val o = outline!!

        drawIntoCanvas { canvas ->
            val paint = shadowPaint
            paint.isAntiAlias = true
            paint.color = fill.toArgb()
            for (shadow in GarbleDepth.of(kind)) {
                val alpha = if (colors.isLight) shadow.lightAlpha else shadow.darkAlpha
                paint.setShadowLayer(
                    GarbleDepth.shadowLayerRadius(shadow.blur.toPx()),
                    0f,
                    shadow.dy.toPx(),
                    Color.Black.copy(alpha = alpha).toArgb(),
                )
                val path = outlinePath
                if (path != null) {
                    canvas.nativeCanvas.drawPath(path, paint)
                } else if (simpleRadiusX >= 0f) {
                    canvas.nativeCanvas.drawRoundRect(
                        0f, 0f, size.width, size.height, simpleRadiusX, simpleRadiusY, paint,
                    )
                } else {
                    canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
                }
            }
            paint.clearShadowLayer()
        }
        drawOutline(o, fill)

        if (kind == GarbleSurfaceKind.SHEET) {
            // the hairline follows the top corners and stops where they end
            val top = ((o as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.y ?: 0f) +
                1.dp.toPx()
            clipRect(bottom = top) {
                drawOutline(o, colors.line, style = Stroke(width = 1.dp.toPx()))
            }
        }
        drawContent()
    }
}
