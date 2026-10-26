package com.airtouch.gesture.model

/**
 * The 21 landmarks of one detected hand in one camera frame, normalised to the upright image as the
 * user sees it. [isRightHand] is the user's actual hand; [score] is MediaPipe's handedness confidence.
 */
data class HandFrame(
    val landmarks: List<Landmark>,
    val isRightHand: Boolean,
    val score: Float,
    val timestampMs: Long,
) {
    init {
        require(landmarks.size == LANDMARK_COUNT) { "A hand has $LANDMARK_COUNT landmarks, got ${landmarks.size}" }
    }

    val wrist: Landmark get() = landmarks[WRIST]

    companion object {
        const val LANDMARK_COUNT = 21
        const val WRIST = 0
        const val THUMB_TIP = 4
        const val INDEX_TIP = 8
        const val MIDDLE_MCP = 9

        /** Bones of the MediaPipe hand model, as pairs of landmark indices. */
        val CONNECTIONS: List<Pair<Int, Int>> = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20,
        )
    }
}
