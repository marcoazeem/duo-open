# Fold 7 build

Local fork of [marcoazeem/duo-open](https://github.com/marcoazeem/duo-open).
Installs as **Duo Open Fold 7** (`com.duoopen.fold7`) alongside the original.
Enable just the Fold 7 accessibility service to avoid two competing effects.

Changes:
- Independent overlay sessions for Samsung inner and cover displays, including display-off cleanup.
- In concurrent mode, the cover uses a center-cropped copy of the current inner snapshot; both effects share that capture. There is no live app task on Samsung's concurrent cover.
- Hinge smoothing reduced from 45 ms to 20 ms, shorter fades, demo starts when capture completes, and the inner effect remains eligible up to 170 degrees.
- Shader bindings and stable uniforms are reused. Cancelled frame callbacks cannot restart old animations.
- Capture generations reject old callbacks; fading overlays and pending demo callbacks are removed on panel changes.

The experimental [device-local display bridge](tools/fold-bridge.md) requests Samsung concurrent mode while folding. Leave it stopped for normal phone use: physical testing showed delayed panel handoffs and flashing. It requires an ADB-started shell process; the APK cannot obtain the privileged display permission itself. It releases the override at rest, on inactivity/screen-off, when the service is disabled, and on process death. Restart the bridge after a phone reboot. The app works with the normal panel handoff when the bridge is absent.

Build with JDK 21 (bytecode targets Java 17) and Android SDK 35:

```sh
ANDROID_HOME=/path/to/android-sdk bash gradlew assembleDebug lintDebug
python3 tools/tests/run_tilt_follower_test.py
ANDROID_HOME=/path/to/android-sdk bash tools/build-fold-bridge.sh
```

Device verification on SM-F966U1 / Android 16, 2026-09-12: both physical displays ON, overlays attached to display IDs 0 and 1 in a simultaneous demo, both overlays removed after completion, state restored to physical OPENED. Shared demo capture: inner 13 ms, cover 23 ms total; measured updated cold launch 455 ms. These are individual observations, not a before/after benchmark. Subsequent physical testing reported delayed handoffs and flashing. The always-awake experiment was reverted after it froze the inner screen. This build restores the pre-experiment app; seamless dual-screen folding is unresolved.

Original upstream documentation follows.

---

# Duo Open

The iPhone "Duo" frosted-glass fold, playing system-wide on a book-style
foldable as you open and close it. Driven by the real hinge angle — no root.
Built and tested on the OnePlus Open; should work on other Android 13+
foldables with a hinge sensor (Pixel Fold, Galaxy Z Fold, OPPO Find N…) but
those are untested — reports welcome.

Based on the AGSL shader from
[Atomicx7/Duo-animation](https://github.com/Atomicx7/Duo-animation).

## What it does

Each half of the screen acts as a pane of frosted glass hinged at the crease.
While the phone is partly folded the moving half is blurred and darkened by
how far it is from flat; as the hinge reaches 180° the picture settles into
focus. Both panels take part: the cover screen frosts in over the first ~20°
of an open, then the inner screen picks up frosted and clears. Closing plays
it in reverse.

It works over *everything* — your own wallpaper, icons, widgets, the lock
screen, whatever app is open — because it runs as an accessibility service
that takes one screenshot per fold phase and draws it through the shader in a
touch-transparent overlay tracking the hinge. There's also a plain live
wallpaper mode if you'd rather not enable an accessibility service.

## Install

1. Download `DuoOpen-<version>.apk` from
   [Releases](../../releases) and install it.
2. Open **Duo Open** → **Tune** → **Turn on in Accessibility** → enable
   *Duo Open full-screen fold*.
   - Android 13+ blocks accessibility for sideloaded apps until you allow
     it: if the toggle is greyed out, go to *Settings → Apps → Duo Open → ⋮
     (top right) → Allow restricted settings*, then try again.
3. Fold the phone partway and open it. **Tune → Test it now** replays the
   effect without folding.

The **Tune** sheet has strength, frost, darkening, eye distance, which half
moves (left/right/both), which edge the cover-screen frost comes from, and
a hinge simulator.

Wallpaper-only mode: **Set live wallpaper** in the app (home + lock screen).
Only the wallpaper folds in that mode; icons stay sharp.

## Privacy

The accessibility service takes a screenshot of the display each time a fold
phase starts and keeps it in memory only while the overlay is on screen.
Nothing is stored, logged or sent anywhere; the app has no network
permission. Screens the system marks secure (banking apps, DRM video) can't
be captured and the effect simply doesn't play there.

## Known limits

- Android allows one screenshot every ~333 ms, and a freshly-lit panel shows
  the system's own black-to-reveal for ~0.4 s first. On a fast flick the
  second phase (inner screen on open) may not have time to appear; you'll get
  the cover-screen phase only. Normal-speed folds get both.
- If you stop partway (tent mode) the overlay fades out after ~0.7 s so the
  live screen isn't hidden.
- Reinstalling the app turns the accessibility service off again.

## Build

```
./gradlew assembleDebug        # debug-signed
./gradlew assembleRelease      # signed with keystore.properties if present
```
Release signing reads `keystore.properties` in the project root
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it the
release build uses the debug key.

Handy adb bits: enable the service with
`adb shell settings put secure enabled_accessibility_services com.duoopen/com.duoopen.overlay.FoldOverlayService`,
replay the effect with `adb shell am broadcast -a com.duoopen.DEMO`,
watch it with `adb logcat -s DuoOverlay`.

## Layout

```
app/src/main/res/raw/duo_unfold.agsl      fold shader (hinge line, moving side, eye)
fold/DuoShader.kt                         uniforms, hinge→tilt mapping, fold placement
fold/HingeAngleSource.kt                  TYPE_HINGE_ANGLE (wake-up fallback, vendor fallback)
fold/TiltFollower.kt                      per-vsync ease that hides the sensor's 1° steps
fold/Panels.kt                            inner vs cover panel from the display mode
overlay/FoldOverlayService.kt             accessibility service: screenshot + overlay
overlay/FoldOverlayView.kt                draws the snapshot through the shader (half-res layer)
wallpaper/DuoWallpaperService.kt          live wallpaper engine
wallpaper/WallpaperImage.kt               picked image / generated default
ui/                                       Compose app: preview, Tune sheet
settings/DuoSettings.kt                   shared tuning (SharedPreferences + StateFlow)
```

## OnePlus Open notes

- Inner panel 2268×2440, fold splits the short side; display 0 swaps between
  the cover (1116×2484) and inner panels at ~10–30° depending on speed.
- The hinge sensor is wake-up only and sends nothing on registration, goes
  quiet at ~30° during a close, and idles anywhere from 0–5° when shut. The
  service compensates for all three.

## License

MIT — see [LICENSE](LICENSE). The shader is adapted from
[Atomicx7/Duo-animation](https://github.com/Atomicx7/Duo-animation).
