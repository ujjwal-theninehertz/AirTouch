package com.airtouch.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTargetsTest {

    private val button = Box(100, 1_000, 200, 1_100)

    @Test
    fun aTapOnAButtonStaysWhereItIs() {
        assertEquals(150f to 1_050f, ScreenTargets.snap(150f, 1_050f, listOf(button), radius = 130f))
        assertEquals(101f to 1_001f, ScreenTargets.snap(101f, 1_001f, listOf(button), radius = 130f))
    }

    @Test
    fun aTapJustOffAButtonSnapsToItsCentre() {
        assertEquals(150f to 1_050f, ScreenTargets.snap(260f, 1_050f, listOf(button), radius = 130f))
    }

    @Test
    fun theNearestButtonWins() {
        val far = Box(400, 1_000, 500, 1_100)
        assertEquals(150f to 1_050f, ScreenTargets.snap(290f, 1_050f, listOf(far, button), radius = 130f))
        assertEquals(450f to 1_050f, ScreenTargets.snap(310f, 1_050f, listOf(button, far), radius = 130f))
    }

    @Test
    fun nothingNearMeansNoSnap() {
        assertEquals(700f to 300f, ScreenTargets.snap(700f, 300f, listOf(button), radius = 130f))
        assertEquals(700f to 300f, ScreenTargets.snap(700f, 300f, emptyList(), radius = 130f))
    }

    @Test
    fun insideALargeClickableRowANearbySmallButtonDoesNotPullTheTap() {
        val row = Box(0, 900, 1_080, 1_200)
        val small = Box(980, 1_000, 1_060, 1_080)
        assertEquals(800f to 1_050f, ScreenTargets.snap(800f, 1_050f, listOf(row, small), radius = 200f))
    }

    // YouTube

    private val width = 1_080
    private val height = 2_340

    @Test
    fun aFullScreenReelIsShorts() {
        val nodes = listOf(
            NodeSummary("com.google.android.youtube:id/reel_player_page_container", "android.widget.FrameLayout", Box(0, 0, width, height)),
            NodeSummary(null, "android.view.SurfaceView", Box(0, 0, width, height)),
        )
        assertEquals(VideoSurface.Shorts, ScreenTargets.youtubeSurface(nodes, width, height))
    }

    @Test
    fun aPortraitFullScreenVideoIsTakenForAShortWhateverItsIds() {
        val nodes = listOf(NodeSummary("com.google.android.youtube:id/player_view", "android.view.SurfaceView", Box(0, 0, width, height)))
        assertEquals(VideoSurface.Shorts, ScreenTargets.youtubeSurface(nodes, width, height))
    }

    @Test
    fun thePortraitPlayerIsFoundAtTheTop() {
        val player = Box(0, 80, width, 688)
        val nodes = listOf(
            NodeSummary("com.google.android.youtube:id/watch_player", "android.widget.FrameLayout", player),
            NodeSummary(null, "android.view.SurfaceView", Box(0, 80, width, 688)),
            NodeSummary("com.google.android.youtube:id/reel_shelf_item", "android.view.ViewGroup", Box(0, 1_500, 360, 2_000)),
        )
        assertEquals(VideoSurface.Player(player), ScreenTargets.youtubeSurface(nodes, width, height))
    }

    @Test
    fun aLandscapePlayerFillsTheScreen() {
        val nodes = listOf(NodeSummary(null, "android.view.TextureView", Box(0, 0, height, width)))
        assertEquals(VideoSurface.Player(Box(0, 0, height, width)), ScreenTargets.youtubeSurface(nodes, height, width))
    }

    @Test
    fun theMiniplayerAndTheFeedAreNoVideo() {
        val nodes = listOf(
            NodeSummary("com.google.android.youtube:id/player_view", "android.widget.FrameLayout", Box(20, 2_050, 380, 2_250)),
            NodeSummary("com.google.android.youtube:id/results", "androidx.recyclerview.widget.RecyclerView", Box(0, 200, width, 2_050)),
        )
        assertEquals(VideoSurface.NoVideo, ScreenTargets.youtubeSurface(nodes, width, height))
        assertEquals(VideoSurface.NoVideo, ScreenTargets.youtubeSurface(emptyList(), width, height))
    }

    @Test
    fun skipsTapTheMatchingSideOfThePlayer() {
        val player = Box(0, 80, 1_000, 680)
        assertEquals(800f to 380f, ScreenTargets.seekPoint(player, forward = true))
        assertEquals(200f to 380f, ScreenTargets.seekPoint(player, forward = false))
    }

    // Pages

    @Test
    fun theMostVisiblePageIsTheOneOnScreen() {
        val viewport = Box(0, 0, 1_080, 2_340)
        // Mid-scroll from Reel 4 to Reel 5: 5 shows more of itself.
        val pages = listOf(4 to Box(0, -1_400, 1_080, 940), 5 to Box(0, 940, 1_080, 3_280))
        assertEquals(5, ScreenTargets.mostVisible(pages, viewport))
        assertEquals(4, ScreenTargets.mostVisible(listOf(4 to Box(0, 0, 1_080, 2_340), 5 to Box(0, 2_340, 1_080, 4_680)), viewport))
        assertEquals(null, ScreenTargets.mostVisible(listOf(9 to Box(0, 2_400, 1_080, 4_000)), viewport))
        assertEquals(null, ScreenTargets.mostVisible(emptyList(), viewport))
    }

    // Finger placement

    @Test
    fun pointsStayInsideTheScreen() {
        assertEquals(1f to 1f, TouchGeometry.toPixels(0f, 0f, 1_080f, 2_340f))
        assertEquals(1_079f to 2_339f, TouchGeometry.toPixels(1f, 1f, 1_080f, 2_340f))
        assertEquals(540f to 1_170f, TouchGeometry.toPixels(0.5f, 0.5f, 1_080f, 2_340f))
    }

    @Test
    fun dragsDoNotStartOnTheSystemEdges() {
        val (x, y) = TouchGeometry.dragStart(2f, 3f, 1_080f, 2_340f)
        assertTrue("x $x", x >= 1_080f * 0.04f)
        assertTrue("y $y", y >= 2_340f * 0.03f)
        assertEquals(540f to 1_170f, TouchGeometry.dragStart(540f, 1_170f, 1_080f, 2_340f))
    }

    @Test
    fun zoomFingersStaySideBySideOnScreen() {
        val (a, b) = TouchGeometry.zoomFingers(540f, 1_170f, 270f, 1_080f, 2_340f)
        assertEquals(405f to 1_170f, a)
        assertEquals(675f to 1_170f, b)
        // Near the right edge the pair shifts left; a huge gap is capped to what fits.
        val (c, d) = TouchGeometry.zoomFingers(1_070f, 1_170f, 5_000f, 1_080f, 2_340f)
        assertTrue(c.first >= 0f && d.first <= 1_080f)
        assertEquals(d.first - c.first, 1_080f - 2 * 1_080f * 0.04f, 0.5f)
        // Too small a gap would merge the fingers into one touch.
        val (e, f) = TouchGeometry.zoomFingers(540f, 1_170f, 5f, 1_080f, 2_340f)
        assertTrue(f.first - e.first >= 1_080f * 0.08f - 0.5f)
    }
}
