# Phase 0 spike report

Device: Redmi Note 8 Pro (begoniain), Android 11 (API 30), MIUI 12.5, Helio G90T, arm64-v8a. Tested 23 Oct 2026.
Build: AGP 9.4.1, Kotlin 2.4.21, CameraX 1.6.2, MediaPipe Tasks Vision 1.1.1, compileSdk 37, targetSdk 36, minSdk 26.

| Spike | Question | Result | Verdict |
|---|---|---|---|
| A | Is hand tracking fast enough? | CPU (XNNPACK): 8–19 results/s, latency p50 53–146 ms, p95 101–235 ms. **GPU (OpenCL, Mali-G76): 19–25 results/s, p50 36–38 ms, p95 39–51 ms**, no dropped frames. Thermal status 0. | **Pass on GPU.** CPU fails. |
| B | Does a dispatched swipe move Instagram Reels? | 10 of 10 swipes advanced exactly one item (including an ad and a Threads card). Each gesture completed in about 255 ms. | **Pass** |
| C | Does the camera keep running in the AccessibilityService while Instagram is in front? | Started from the background (Instagram in front). With `startForeground(camera)`: accepted, camera open for 600 s, about 29.6 fps in every recorded window. Without a foreground service: 300 s fully recorded, 29.3–29.7 fps, no gaps. Thermal status 0, CPU 47 °C. | **Pass** |

## Decisions

- **AD-1 confirmed on this device:** the live pipeline runs inside the AccessibilityService. Keep `startForeground(FOREGROUND_SERVICE_TYPE_CAMERA)`: it gives the status notification with controls, the documented camera path on Android 14+, and higher process priority against MIUI's background killer. Still to confirm on an Android 14+ phone (P8).
- **GPU delegate by default**, CPU fallback if a device cannot initialise it.
- **Frame-rate governor is required (P1).** Feeding all 30 camera frames per second while Instagram plays video and no hand is in view dropped the GPU landmarker to 12–15 results/s, with p50 latency around 100 ms. Target: about 20 fps with a hand in view, 8 fps while searching, 4 fps in standby.

## Findings for later phases

- MediaPipe Tasks 1.1.1 bundles Google's datatransport library, which uploads usage statistics (session starts, frame counts, latency, device model) to Clearcut. Both apps strip `INTERNET`, `ACCESS_NETWORK_STATE` and its upload components in the manifest (AD-7), and the build fails if `INTERNET` reappears.
- MIUI's own windows (`miui.systemui.plugin`, `com.android.systemui`) show up as window-state events. The foreground-app tracker (P4) must ignore system UI packages when choosing a per-app profile.
- Spike C's run-1 log has a gap: the phone's log buffer holds about 2 minutes, so the continuous record covers 510–600 s, plus spot checks at 10 s, 70–80 s and 530–550 s. Long tests must stream logcat to a file from the start.
- Spike-only bugs not to repeat: remove `CameraState` observers when stopping, and reset frame counters when a session starts.
