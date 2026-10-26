package com.airtouch.spike

import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

internal const val TAG = "AirTouchSpike"
internal const val MODEL_ASSET = "hand_landmarker.task"

// Commands for the accessibility service. Exported so they can also come from `adb shell am broadcast`.
internal const val ACTION_SWIPE = "com.airtouch.spike.SWIPE"
internal const val ACTION_CAMERA_START = "com.airtouch.spike.CAMERA_START"
internal const val ACTION_CAMERA_STOP = "com.airtouch.spike.CAMERA_STOP"
internal const val EXTRA_DIRECTION = "dir"
internal const val EXTRA_FOREGROUND = "fg"
internal const val EXTRA_LANDMARKS = "landmarks"
internal const val EXTRA_DELEGATE = "delegate"

internal const val WINDOW_A_MS = 5_000L
internal const val WINDOW_C_MS = 10_000L

/** Shared by the activity and the service, which run in the same process. */
internal object SpikeState {
    @Volatile
    var cameraInService = false
}

/** 640×480 RGBA frames, dropping stale ones, as the real pipeline will use. */
internal fun analysisUseCase(): ImageAnalysis = ImageAnalysis.Builder()
    .setResolutionSelector(
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build(),
    )
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .build()
