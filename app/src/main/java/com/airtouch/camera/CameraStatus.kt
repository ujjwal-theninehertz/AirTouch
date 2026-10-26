package com.airtouch.camera

sealed interface CameraStatus {
    data object Idle : CameraStatus
    data object Opening : CameraStatus
    data object Streaming : CameraStatus
    data class Problem(val reason: CameraProblem, val recoverable: Boolean) : CameraStatus
}

enum class CameraProblem(val message: String) {
    NoFrontCamera("This phone has no front camera."),
    InUseByAnotherApp("Another app is using the camera. AirTouch resumes when it is free."),
    TooManyCamerasOpen("Too many cameras are open. Close another camera app."),
    DisabledByPolicy("The camera is disabled on this phone, by a device policy or the camera access toggle."),
    DoNotDisturb("The camera is blocked while Do Not Disturb is on."),
    StreamConfiguration("The camera could not start with these settings."),
    BindFailed("The camera could not be started."),
    Fatal("The camera stopped working. Restart the phone if this keeps happening."),
    Unknown("The camera reported an unknown problem."),
}
