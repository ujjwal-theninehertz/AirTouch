package com.airtouch.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads the screen through the accessibility tree, and only when an action needs it: the buttons around a cursor
 * tap (the magnet) and YouTube's player (to skip 10 s, and to stay out of Shorts). Only view boxes, IDs and class
 * names are read; no text. Nothing is kept after the call. Main thread; each call visits at most [MAX_NODES] views.
 */
class ScreenInspector(private val service: AccessibilityService) {

    /** Clickable views in the topmost window under ([x], [y]), in pixels. */
    fun clickablesAt(x: Float, y: Float): List<Box> {
        val root = rootAt(x, y) ?: return emptyList()
        val boxes = mutableListOf<Box>()
        walk(root) { node ->
            if (node.isVisibleToUser && node.isEnabled && (node.isClickable || node.isLongClickable)) boxes += node.box()
        }
        return boxes
    }

    /** What YouTube shows, from the app window's views. */
    fun youtube(width: Int, height: Int): VideoSurface {
        val root = service.rootInActiveWindow ?: return VideoSurface.NoVideo
        val nodes = mutableListOf<NodeSummary>()
        walk(root) { node ->
            if (node.isVisibleToUser) nodes += NodeSummary(node.viewIdResourceName, node.className?.toString(), node.box())
        }
        return ScreenTargets.youtubeSurface(nodes, width, height)
    }

    /**
     * Presses the button whose label or view ID contains [word] (case ignored), in any window, such as the call
     * screen's Speaker button. Through the button's own click action, not a tap, so it works over the lock screen.
     */
    fun clickLabelled(word: String): Boolean {
        for (window in service.windows.sortedByDescending { it.layer }) {
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val root = window.root ?: continue
            var target: AccessibilityNodeInfo? = null
            walk(root) { node ->
                val label = listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName).joinToString(" ")
                if (target == null && node.isVisibleToUser && label.contains(word, ignoreCase = true)) target = node
            }
            var node = target ?: continue
            // The label is often on a text under the button: the click belongs to the nearest clickable parent.
            while (!node.isClickable) node = node.parent ?: break
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    /** A list or pager's position: which item is on screen, of how many. */
    data class Page(val index: Int, val count: Int)

    /**
     * The item on screen in the largest scrolling list of the app in front: the Reel or Short showing, read from
     * the collection info Android's lists expose. Null if the app's list does not expose it.
     */
    fun visiblePage(): Page? {
        val root = service.rootInActiveWindow ?: return null
        var pager: AccessibilityNodeInfo? = null
        var largest = 0L
        walk(root) { node ->
            if (node.isScrollable && node.collectionInfo != null) {
                val area = node.box().area
                if (area > largest) {
                    largest = area
                    pager = node
                }
            }
        }
        val list = pager ?: return null
        val info = list.collectionInfo ?: return null
        val items = (0 until list.childCount).mapNotNull { i ->
            val child = list.getChild(i) ?: return@mapNotNull null
            val item = child.collectionItemInfo ?: return@mapNotNull null
            // One of the two is 0 in a one-row or one-column list.
            (item.rowIndex + item.columnIndex) to child.box()
        }
        val index = ScreenTargets.mostVisible(items, list.box()) ?: return null
        return Page(index, maxOf(info.rowCount, info.columnCount))
    }

    /** The topmost app or system window containing the point; never AirTouch's own overlays. */
    private fun rootAt(x: Float, y: Float): AccessibilityNodeInfo? {
        val bounds = Rect()
        val window = service.windows
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .sortedByDescending { it.layer }
            .firstOrNull { window ->
                window.getBoundsInScreen(bounds)
                bounds.contains(x.toInt(), y.toInt())
            }
        return window?.root ?: service.rootInActiveWindow
    }

    private inline fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            visit(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(stack::addLast)
        }
    }

    private fun AccessibilityNodeInfo.box(): Box {
        val rect = Rect()
        getBoundsInScreen(rect)
        return Box(rect.left, rect.top, rect.right, rect.bottom)
    }

    private companion object {
        /** Enough for a busy feed screen (Instagram's is about 300 views) while bounding the cost to a few ms. */
        const val MAX_NODES = 1_500
    }
}
