# P4b live test plan: extra actions, cursor, drag, zoom, volume

Hand 30–60 cm from the front camera, palm towards the phone. Start hands-free mode in AirTouch first. Each row says
what to do, what should happen, and (for the worst cases) what must **not** happen. The phone log records every
action (`adb logcat -s AirTouch`), so a failed row can be traced afterwards.

Automated coverage before this plan: 99 engine tests (synthetic hands, plus all 33 recorded takes replayed with no
wrong gesture, no stray tap, drag or zoom) and 23 app tests (magnet, YouTube player detection, finger placement).

## 1. Cursor (☝ point)

| # | Do | Expect |
|---|---|---|
| 1.1 | Point ☝ and hold still about a second | Ring appears in the middle of the screen |
| 1.2 | Move the pointing hand right, left, up, down | Ring follows the same way (your view), smoothly |
| 1.3 | Hold the pointing hand still 3 s | Ring stays still, no visible shaking |
| 1.4 | Move far right and keep going | Ring stops at the edge; moving back brings it straight back |
| 1.5 | Open the hand and wait 2 s | Ring fades |
| W1 | Flick down a few times | **No** ring appears, flicks still work |
| W2 | Point briefly (half a second) | **No** ring |
| W3 | 👎 to pause, then point | **No** ring until resumed |
| W4 | Open the AirTouch app, the phone app or the camera and point | **No** ring there |

## 2. Pinch at the cursor (tap, double-tap, long-press, drag)

| # | Do | Expect |
|---|---|---|
| 2.1 | Home screen: point at an app icon, quick pinch (thumb to index, release) | App opens (about 0.3 s after release) |
| 2.2 | Point slightly off a small button (Settings back arrow), quick pinch | Button still pressed (magnet) |
| 2.3 | Instagram Reel: point at the video, two quick pinches | ❤ Reel liked |
| 2.4 | Home screen: point at an icon, pinch and hold until the ring is full (1.5 s) | Icon's long-press menu opens |
| 2.4b | Point at a button, pinch, hold about 1 s, release before the ring is full | A normal tap |
| 2.5 | Settings: point at the list, pinch, move hand up, release | List scrolls by the distance moved (on this phone when you let go) |
| 2.6 | Home screen: long-press an icon (2.4), then move the pinched hand | Not supported yet: the long-press is a fixed 0.7 s press on this phone |
| W5 | During a drag, take the hand out of view | Finger lifts within half a second, nothing else happens |
| W6 | During a drag, 👎 (other hand) or stop hands-free from the notification | Finger lifts |
| W7 | Pinch after opening the hand (not straight from pointing) | **No** tap at the ring |
| W8 | Relaxed open hand with thumb near the index for a few seconds | **No** tap or drag |

## 3. Zoom (pinch, then pull closer / push away)

| # | Do | Expect |
|---|---|---|
| 3.1 | Gallery photo: point at it, pinch, move the hand 10 cm towards the phone, release | Photo zooms in |
| 3.2 | Same, moving the hand away | Photo zooms out |
| 3.3 | Google Maps: same | Map zooms where the ring is |
| W9 | Pinch and hold still (no pull) | A long-press, **not** a zoom |
| W10 | Zoom in an app that does not zoom (Settings) | Nothing harmful happens |

## 4. Open-hand pinch without the cursor

| # | Do | Expect |
|---|---|---|
| 4.1 | Reel: OK-sign pinch (other fingers up), two quick pinches | ❤ like (double-tap in the centre) |
| 4.2 | Article in Chrome: OK-sign pinch, hold 0.3 s, move down slowly, release | Page drags with the hand |
| 4.3 | OK-sign pinch held still 1 s | Tap in the centre (as before) |
| W11 | Flick up several times quickly | Only flicks: **no** like, tap or drag |

## 5. Volume knob (✊ fist)

| # | Do | Expect |
|---|---|---|
| 5.1 | Make a fist, hold ½ s, raise it slowly by a hand's length | Volume panel, 2–3 steps up, pill shows "🔊 Volume n/15" |
| 5.2 | Same, lowering it | Steps down |
| 5.3 | Fist held still 1 s in YouTube | Play / pause, **no** volume change |
| W12 | Fist moved sideways | Nothing (no volume, no play/pause) |
| W13 | Volume already at maximum, raise again | Stays at maximum, no error |
| W14 | Flick down repeatedly | **No** volume steps |

## 6. Media apps

| # | Do | Expect |
|---|---|---|
| 6.1 | YouTube, normal video playing (portrait): flick right / left | Forward / back 10 s, pill "⏩ Forward 10 s" |
| 6.2 | YouTube full-screen landscape video: flick right / left | Forward / back 10 s |
| 6.3 | Spotify or YouTube Music: flick left / right | Next / previous song |
| W15 | YouTube **Shorts**: flick left / right | Pill "Skipping is off in Shorts", **no** like |
| W16 | YouTube home feed (no video): flick left | Pill "No video playing" |
| W17 | Music app with nothing playing: flick left | Nothing breaks (log says no media playing) |

## 7. 🤘 shortcut menu

| # | Do | Expect |
|---|---|---|
| 7.1 | 🤘 (index and little finger up) held 1 s | Menu with four choices appears |
| 7.2 | Then flick ⬆ | Notifications open |
| 7.3 | 🤘, then flick ⬇ | Quick settings open |
| 7.4 | 🤘, then flick ⬅ | Screenshot, **without** AirTouch's pill, ring or menu in it |
| 7.5 | 🤘, then flick ➡ | Phone locks (unlock by hand; AirTouch cannot) |
| W18 | 🤘, then wait 5 s | Menu closes, "Shortcuts closed" |
| W19 | 🤘, then ✌ | Menu closes, **not** Home |
| W20 | 🤘, then 👎 | Menu closes and AirTouch pauses |
| W21 | Screenshot in a banking app (screenshots blocked) | Phone's own "can't take screenshot" message, nothing else |

## 8. 🤙 open your app

| # | Do | Expect |
|---|---|---|
| 8.1 | AirTouch → 🤙 Shortcut app → choose WhatsApp; then 🤙 held 1 s anywhere | WhatsApp opens |
| W22 | No app chosen, 🤙 | Pill "Choose an app in AirTouch first" |
| W23 | Chosen app uninstalled, 🤙 | Pill "That app is no longer installed" |
| W24 | MIUI blocks background starts | Pill explains to allow "Display pop-up windows while running in the background" for AirTouch (Settings → Apps → AirTouch → Other permissions) |

## Exit

P4b passes when every numbered row works at least 4 times out of 5, and no "W" row ever does what it must not.
Rows that fail are tuned from the log, or from a practice recording with the matching label (point, pinch_drag,
pinch_zoom, double_pinch, fist_volume, rock, call).
