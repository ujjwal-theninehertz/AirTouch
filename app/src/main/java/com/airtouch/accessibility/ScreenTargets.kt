package com.airtouch.accessibility

import kotlin.math.hypot
import kotlin.math.max

/** A box on screen, in pixels. Plain data, so the choices below are unit-tested without Android. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    /** 0 inside the box, otherwise the distance to its nearest edge. */
    fun distanceTo(x: Float, y: Float): Float = hypot(max(max(left - x, 0f), x - right), max(max(top - y, 0f), y - bottom))
}

/** What the accessibility tree says about one view: just enough to find buttons and video players. */
data class NodeSummary(val viewId: String?, val className: String?, val box: Box)

/** YouTube's screen as far as skipping 10 s is concerned. */
sealed interface VideoSurface {
    /** Shorts: a double-tap likes there, so skipping must do nothing. */
    data object Shorts : VideoSurface

    data class Player(val box: Box) : VideoSurface

    data object NoVideo : VideoSurface
}

object ScreenTargets {

    /**
     * The magnet: where a tap at ([x], [y]) should land. Inside a clickable view it stays put (the smallest one
     * under it is what a finger would hit); otherwise it moves to the centre of the nearest clickable view within
     * [radius], so a slightly-off cursor still hits a small button. With nothing near, it stays put.
     */
    fun snap(x: Float, y: Float, clickables: List<Box>, radius: Float): Pair<Float, Float> {
        if (clickables.any { it.contains(x, y) }) return x to y
        val nearest = clickables
            .map { it to it.distanceTo(x, y) }
            .filter { (_, distance) -> distance <= radius }
            .minByOrNull { (_, distance) -> distance }
            ?.first
            ?: return x to y
        return nearest.centerX to nearest.centerY
    }

    /**
     * Finds the player in YouTube's view tree. Shorts views carry "reel" in their IDs (YouTube's internal name for
     * them); a full-screen one means a Short is playing. Otherwise the player is the largest video surface or
     * "player" view at least most of the screen wide, which leaves out the miniplayer docked at the bottom. A
     * player filling most of a portrait screen is taken for a Short whatever its IDs say: a double-tap there would
     * like it (seen by others), which is worse than not skipping.
     */
    fun youtubeSurface(nodes: List<NodeSummary>, screenWidth: Int, screenHeight: Int): VideoSurface {
        val shorts = nodes.any { node ->
            node.viewId?.contains("reel", ignoreCase = true) == true && node.box.height >= screenHeight * SHORTS_MIN_HEIGHT
        }
        if (shorts) return VideoSurface.Shorts
        val player = nodes
            .filter { node ->
                val id = node.viewId?.lowercase()
                val looksLikePlayer = (id != null && "player" in id && "reel" !in id) || node.className in VIDEO_SURFACES
                looksLikePlayer && node.box.width >= screenWidth * PLAYER_MIN_WIDTH && node.box.height >= screenHeight * PLAYER_MIN_HEIGHT
            }
            .maxByOrNull { it.box.area }
            ?: return VideoSurface.NoVideo
        val portrait = screenHeight > screenWidth
        if (portrait && player.box.height >= screenHeight * SHORTS_MIN_HEIGHT) return VideoSurface.Shorts
        return VideoSurface.Player(player.box)
    }

    /**
     * Which item of a list or pager is on screen: the one showing the most of itself inside [viewport]. Null when
     * none is visible. Used after each scroll to tell a single Reel advance from a double one.
     */
    fun mostVisible(items: List<Pair<Int, Box>>, viewport: Box): Int? =
        items.map { (index, box) -> index to overlap(box, viewport) }
            .filter { (_, area) -> area > 0 }
            .maxByOrNull { (_, area) -> area }
            ?.first

    private fun overlap(a: Box, b: Box): Long {
        val width = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (width <= 0 || height <= 0) 0 else width.toLong() * height
    }

    /** Where a double-tap skips: a fifth of the way in from that side of the player, halfway down it. */
    fun seekPoint(player: Box, forward: Boolean): Pair<Float, Float> {
        val inset = player.width * SEEK_INSET
        return (if (forward) player.right - inset else player.left + inset) to player.centerY
    }

    private val VIDEO_SURFACES = setOf("android.view.SurfaceView", "android.view.TextureView")
    private const val SHORTS_MIN_HEIGHT = 0.5f
    private const val PLAYER_MIN_WIDTH = 0.6f
    private const val PLAYER_MIN_HEIGHT = 0.15f
    private const val SEEK_INSET = 0.2f
}
