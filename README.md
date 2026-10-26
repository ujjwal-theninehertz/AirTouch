# AirTouch

Control an Android phone with hand gestures in front of the front camera. Everything runs on the phone: CameraX frames, MediaPipe Hand Landmarker, Kotlin gesture rules, and an AccessibilityService that performs the swipe, tap or Back.

The full plan is in `AirTouch Project Blueprint.html`: architecture, phases P0–P9, tests and risks.

## Project layout

| Module | What it is |
|---|---|
| `app/` | The AirTouch app. In Phase 0: manifest, permissions, declared accessibility service, bundled model, settings store, setup-check screen. |
| `gesture-engine/` | Pure Kotlin, no Android imports (AD-2). Landmark model types now; classifiers and guards from P3. |
| `spike/` | Throwaway Phase 0 feasibility tests A, B and C. Delete once the spike report is signed off. |

## Phase 0 setup

### 1. Tools

- Android Studio 2026.1 or newer. The build uses its bundled JDK.
- Android SDK platform 37. Missing build tools download automatically on the first build.
- Open the project with **File → Open → this folder**. Android Studio uses the Gradle wrapper (Gradle 9.8.2).

### 2. Phone (Redmi Note 8 Pro, MIUI 12.5)

1. **Settings → About phone →** tap **MIUI version** 7 times.
2. **Settings → Additional settings → Developer options**, turn on:
   - **USB debugging**
   - **Install via USB** (needs a Mi account)
   - **USB debugging (Security settings)**, which lets `adb` grant permissions and enable the accessibility service
3. Replug the cable and allow the computer when the phone asks.
4. Check: `adb devices -l` lists the phone as `device`.

### 3. Build and install

```sh
./gradlew :gesture-engine:test :app:assembleDebug :spike:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r spike/build/outputs/apk/debug/spike-debug.apk
```

The app opens a setup check: camera permission, accessibility service, bundled model and **practice mode**.

Practice mode (Phase 1) shows the front-camera preview, frame statistics and a thumbnail of the exact frame passed to the detector, turned upright and mirrored. It logs one line every 2 s: `adb logcat -s AirTouch | grep "P1 practice"`.

## Phase status

| Phase | Status | Evidence |
|---|---|---|
| P0 Setup | Done 23 Oct 2026 | `docs/p0-spike-report.md` |
| P1 Capture | Done 23 Oct 2026 | On the Note 8 Pro: 29.7 → 20 fps through the governor, conversion 0.6 ms (p95 1.1 ms), first frame 558–907 ms, 0 unclosed frames, 1 buffer allocation, correct rotation in all four orientations, camera released on Home, all permission paths. 11 unit tests. |
| P2 Perception | Done 24 Oct 2026, one check open | GPU Hand Landmarker with CPU fallback, one frame in flight, primary-hand selection, 20/8 fps adaptive rate, skeleton overlay aligned in practice mode, trace recorder (JSON Lines, `files/traces`). Right hand: 15.5–19.3 results/s, p50 41–47 ms, p95 48–52 ms, found in 100 % of frames. Open: confirm left/right labels and the busy-skip fix with one more hand test. |
| P3 Decision | Done 24 Oct 2026 | `gesture-engine`: features, poses (open, fist, pinch with hysteresis), swipes (150 ms velocity, split on reversal, 1–1.5 s limits), arming state machine, cooldown, return-stroke block, pause. 19 recorded traces replay with 0 wrong gestures; slow 3–4 s drifts refused. Practice mode shows recognised gestures (dry run) and coaching for refused swipes. 38 unit tests. |
| P4 Action | Done 24 Oct 2026 | Hands-free mode in the accessibility service; per-app profiles (Reels, YouTube, music, photos, home screen); held shapes for Home, Back, Recents, play/pause and pause; finger flicks in four directions, told apart by their fast stroke; air cursor (point) with pinch to tap, double-tap, long-press, drag and zoom, and a magnet onto buttons; fist volume knob; 🤘 shortcut menu (notifications, quick settings, screenshot, lock); 🤙 chosen app; YouTube skip 10 s; next/previous song. Live-tested on the Note 8 Pro (user sign-off 24 Oct 2026). 33 recorded traces replay with 0 wrong gestures (one known limitation); 102 engine and 28 app tests. Open: the formal 50-try Reels count and the ±8 dp tap grid (moved to P8). |

## Gestures

Palm towards the phone, about 30–60 cm from the front camera. Rest the hand for a moment before the first gesture.

| Gesture | How | Default action |
|---|---|---|
| Flick up | Curl the fingers in, then **snap them open, fast** | Next (scroll down) |
| Flick down | **Snap** the fingers down into the palm, then open them **slowly** | Previous |
| Flick left | **Swing** the fingers to the left from the knuckles, then bring them back **slowly** | Next page · YouTube: back 10 s · music apps: next song |
| Flick right | **Swing** the fingers to the right, then bring them back **slowly** | Back; previous page on home screens · YouTube: forward 10 s · music apps: previous song |
| Swipe up / down / left / right | Move the whole open hand about a hand's length in under a second | As the flick in the same direction |
| ☝ Point | Index finger up, others folded, held still about a second | Cursor appears and follows the hand |
| Pinch at the cursor | From pointing, bring the index down to the thumb | Released: tap · twice quickly: double-tap · held until the ring fills (1.5 s): long-press · moved: drag · pulled closer / pushed away: zoom in / out |
| Open-hand pinch (no cursor) | OK sign: thumb to index, other fingers up | Held 1 s: tap the centre · twice quickly: double-tap the centre (❤ like a Reel) · held, then moved: drag · pulled / pushed: zoom |
| ✊ Fist | Held still 1 s | Play / pause (taps the Reel in Instagram) |
| ✊ Fist raised / lowered | Fist held ½ s, then moved up or down | Volume up / down, a step per third of a hand's length |
| ✌ / 👍 / three fingers | Held 1 s | Home / Back / Recent apps |
| 🤘 Index and little finger | Held 1 s, then flick ⬆ ⬇ ⬅ ➡ | Shortcut menu: notifications, quick settings, screenshot, lock screen |
| 🤙 Thumb and little finger | Held 1 s | Opens the app chosen on AirTouch's main screen |
| 👎 | Held 1 s | Pause or resume AirTouch |
| **While a call rings** | Hand mode: ✊ fist held 1 s · ✋ open hand held still 1.5 s (opened after the ringing began). Face mode: left eyebrow twice · right eyebrow twice | Reject · answer on speaker. Answer on speaker · reject (needs the Phone permission; nothing else acts while a call rings) |

### Face gestures (P4c)

Hand gestures and face gestures take turns: turning on **🙂 Face gestures** on the main screen turns hand gestures off, and turning on **✋ Hand gestures** turns face gestures off. Only the chosen mode's model runs (in hands-free mode and in practice mode), so it gets every camera frame. Hands are the default.

| Face gesture | How | Default action |
|---|---|---|
| Small nod up / down, turn left / right | Quick, about 15°, and back | As the flick in the same direction |
| Eyes closed | Both, for 1 s (blinks never count) | Home |
| Left eyebrow | Raise and lower | Back |
| Right eyebrow | Raise and lower | Play / pause (taps the Reel in Instagram) |
| Left / right eyebrow held | Hold up | Volume down / up, a step every ½ s |
| Both eyebrows twice | Raise, lower, raise | Screenshot (both once does nothing: surprise looks like it) |
| Kiss | Lips puckered, held ½ s, mouth closed | Double-tap the centre (❤ like) |

A single eyebrow raise acts about 0.6 s after it ends, once a second raise can no longer start. A raise is a quick movement from the eyebrow's own resting level (each eyebrow's level is learned separately, and one that rests high needs less); a slow drift, a blink or a nod is no raise. The face tracker is MediaPipe Face Landmarker, bundled. The status pill shows what AirTouch sees in the current mode: "✋ ready", "✋ hold still" or "show your hand" by hand; "🙂 ready", "🙂 look at the phone" or "show your face" by face. In face mode, pause and resume from the notification (👎 is a hand gesture). Quick Settings has an **AirTouch** tile to start and stop hands-free mode (add it with the shade's edit button); the notification has Pause / Resume and Stop.

In a flick the fast stroke is the gesture and the slow half is ignored, so only the speed of the stroke matters, not how far the fingers go. On phones that refuse a held-down virtual finger (the Note 8 Pro on MIUI 12.5 does), drags and zooms play back as one gesture when the hand lets go. In YouTube Shorts skipping is off, since a double-tap there would like the Short. The cursor's taps snap to a button within a fingertip's width (the magnet), which reads where views are on screen through the accessibility service; text is never read and nothing leaves the phone. Live test plan: `docs/p4b-test-plan.md`.

The build fails if any dependency adds `android.permission.INTERNET` to the app's merged manifest (task `verifyDebugOfflineManifest`). AirTouch stays offline by design (AD-7).

## Feasibility spikes (Phase 0 exit test)

All spike output goes to logcat: `adb logcat -s AirTouchSpike`.

| Spike | Question | How to run | Pass |
|---|---|---|---|
| **A** | Is hand tracking fast enough on this phone? | Open **AirTouch Spike**, allow the camera, hold one hand 30–80 cm from the screen. Use the CPU/GPU button to compare. | ≥ 15 fps with a hand in view |
| **B** | Does a dispatched swipe move Instagram Reels? | Turn on **AirTouch Spike** in Accessibility settings, open Instagram Reels, then send 10 swipes: `adb shell am broadcast -a com.airtouch.spike.SWIPE --es dir up -p com.airtouch.spike` | 9 of 10 advance exactly one Reel |
| **C** | Does the camera keep running inside the accessibility service while Instagram is in front? | In AirTouch Spike tap **C: start in service, foreground**, open Instagram Reels and leave it for 10 minutes. Repeat with **no foreground**. | Heartbeats every 10 s with fps > 0 and `front=com.instagram.android` |

The spikes can also be driven entirely from `adb`:

```sh
adb shell am broadcast -a com.airtouch.spike.CAMERA_START --ez fg true -p com.airtouch.spike
adb shell am broadcast -a com.airtouch.spike.CAMERA_STOP -p com.airtouch.spike
```

## Model

`app/src/main/assets/hand_landmarker.task`: MediaPipe Hand Landmarker, float16, 7.8 MB, stored uncompressed in the APK.
SHA-256 `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1`.
Source: `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task`
