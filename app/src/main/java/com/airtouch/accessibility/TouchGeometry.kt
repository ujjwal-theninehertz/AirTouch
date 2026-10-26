package com.airtouch.accessibility

import kotlin.math.min

/** Pixel placement for injected fingers. Pure arithmetic, unit-tested; the screen size comes from [ScreenGeometry]. */
object TouchGeometry {

    /** A screen fraction (0..1) to a pixel, kept a pixel inside the screen: strokes with negative or off-screen points are refused. */
    fun toPixels(x: Float, y: Float, width: Float, height: Float): Pair<Float, Float> =
        (x * width).coerceIn(1f, width - 1f) to (y * height).coerceIn(1f, height - 1f)

    /**
     * Where a drag may start. A finger landing in the outer few per cent can start the system's Back gesture
     * (sides, on gesture navigation) or pull down the notification shade (top), so starts are nudged inside.
     * Moves after the start may go anywhere.
     */
    fun dragStart(x: Float, y: Float, width: Float, height: Float): Pair<Float, Float> =
        x.coerceIn(width * EDGE_X, width * (1 - EDGE_X)) to y.coerceIn(height * EDGE_TOP, height * (1 - EDGE_BOTTOM))

    /** The first gap between two zoom fingers: a quarter of the shorter side, about 270 px on the Note 8 Pro. */
    fun zoomStartGap(width: Float, height: Float): Float = min(width, height) * ZOOM_START_GAP

    /**
     * Two zoom fingers either side of ([cx], [cy]), [gap] apart, side by side. The gap is kept between a minimum
     * (fingers too close merge into one touch) and what fits on screen, and the pair is shifted inwards if the
     * centre is near an edge, so neither finger leaves the screen.
     */
    fun zoomFingers(cx: Float, cy: Float, gap: Float, width: Float, height: Float): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val margin = width * EDGE_X
        val clampedGap = gap.coerceIn(min(width, height) * ZOOM_MIN_GAP, width - 2 * margin)
        val half = clampedGap / 2
        val x = cx.coerceIn(margin + half, width - margin - half)
        val y = cy.coerceIn(height * EDGE_TOP, height * (1 - EDGE_BOTTOM))
        return (x - half to y) to (x + half to y)
    }

    private const val EDGE_X = 0.04f
    private const val EDGE_TOP = 0.03f
    private const val EDGE_BOTTOM = 0.04f
    private const val ZOOM_START_GAP = 0.25f
    private const val ZOOM_MIN_GAP = 0.08f
}
