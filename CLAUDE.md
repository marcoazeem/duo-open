# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Duo Open: an Android app (Kotlin, Jetpack Compose, single `:app` module) that plays a frosted-glass "fold" effect system-wide on book-style foldables, driven by the hinge angle sensor, no root. The effect is a shader applied to a screenshot of the display, drawn in a touch-transparent accessibility overlay. The shader is AGSL (`app/src/main/res/raw/duo_unfold.agsl`) on API 33+ and a GLSL ES 3.0 port (`duo_unfold_gles.frag`) on API 30–32. A live-wallpaper mode does the same effect on the wallpaper only.

`README.md` is the user-facing spec. It covers device behaviour (OnePlus Open, Galaxy Z Fold 8, Surface Duo), known platform limits, and the Shizuku mode. Read it before changing fold timing or panel logic. `SURFACE_DUO_PORT.md` (in Russian) holds the Surface Duo 1 (API 32) port notes: device measurements and what was verified on the device.

## Build / run

```
./gradlew assembleFullDebug     # full edition (accessibility overlay + wallpaper + Shizuku)
./gradlew assembleLiteRelease   # lite: wallpaper only, app id com.duoopen.lite
./gradlew assembleRelease       # both; signed with keystore.properties if present, else debug key
./gradlew installFullDebug
./gradlew lintFullDebug
```

There are no unit or instrumented tests. You verify changes on a device:

```
adb shell settings put secure enabled_accessibility_services com.duoopen/com.duoopen.overlay.FoldOverlayService
adb shell am broadcast -a com.duoopen.DEMO     # replay the effect without folding
adb logcat -s DuoOverlay
adb shell settings put global duoopen_test_displays 1   # accept emulator "simulated secondary display" as a panel
adb shell settings put global duoopen_test_dual_screen 1   # treat the device as a Surface Duo (DeviceProfile)
adb logcat -s DuoOverlay DuoGl   # DuoGl = the OpenGL ES renderer on API 30-32
```

The service also needs `adb shell settings put secure accessibility_enabled 1`. Force-stopping the app clears both accessibility settings, so set them again afterwards.

The toolchain is JDK 17, AGP 8.9, Kotlin 2.2, compileSdk/targetSdk 35, and minSdk 30 (`AccessibilityService.takeScreenshot` and `TYPE_HINGE_ANGLE`). Anything above API 30 must sit behind a `Build.VERSION.SDK_INT` check; `lintFullDebug` and `lintLiteDebug` report no NewApi issues today, so keep it that way.

## Architecture

**Flavors (`edition` dimension).** `src/main` is shared. `src/full` adds the accessibility service, overlay engines, Shizuku shell helper, and setup guide. `src/lite` contains stubs only. Shared code reaches flavor-specific features only through `com.duoopen.overlay.OverlayFeature`, which is defined in both `src/full` and `src/lite` with the same API (`AVAILABLE`, `playDemo()`, Shizuku status…). If you add a capability that shared UI needs, add it to both `OverlayFeature` objects. Full-only dependencies use `"fullImplementation"`.

**Fold core (`src/main/.../fold/`)** is shared by three renderers: the in-app preview (`ui/FoldEffect.kt` on 33+, `ui/GlFoldPreview.kt` below, both via `ui/HomePreview.kt`), the live wallpaper (`wallpaper/DuoWallpaperService.kt`), and the overlay (`src/full/.../overlay/FoldOverlayView.kt`).
- `DuoShader`: backend-free fold maths. It maps hinge angle to pane tilt, places the fold line (`FoldLine`), and computes every shader uniform as one `FoldUniforms` value.
- Two shader backends consume `FoldUniforms`. `AgslFold` (`@RequiresApi(33)`) owns `RuntimeShader`; keep every caller behind an SDK check. `fold/gl/GlFoldRenderer` runs `duo_unfold_gles.frag` on its own GL thread into a `Surface` or `SurfaceTexture`, with an optional half-resolution fold pass (`foldScale`). The `.agsl` and `_gles.frag` files must stay in sync: same uniforms, same maths.
- `DeviceProfile`: detects dual-screen devices (Surface Duo: one spanned display that never swaps panels). `Display.isInnerPanel(context)` in `Panels.kt` treats that display as the inner panel.
- `HingeAngleSource`: picks the hinge sensor (vendor fallbacks), detects "stops-only" coarse sensors (Samsung 0/90/180), and produces the "Copy sensor report".
- `TiltFollower`: a per-vsync ease that smooths over 1° sensor steps. On coarse sensors it plays timed eases instead.
- `Panels.kt`: decides inner vs cover panel from the display mode, and which displays count as live panels.

**Settings.** `settings/DuoSettings.kt` stores one `DuoConfig` in SharedPreferences, exposed as a `StateFlow`. The Tune sheet (`ui/ControlSheet.kt`), the wallpaper, and the overlay all read it live. A new tuning knob goes there and gets documented in the `DuoConfig` KDoc.

**Overlay (full).** `FoldOverlayService` (AccessibilityService) watches `DisplayManager` and runs one `PanelEngine` per lit built-in display. `PanelEngine` is the largest file and holds the per-display state machine: hinge tracking, fold phases (cover frosts in, then panel swap, then inner clears), capture timing around the ~0.4 s panel-reveal blackout and the ~333 ms screenshot rate limit, and the tent-mode fade-out. On a dual-screen device it arms on motion instead: the pose the display lights up in is rest (`baselineTilt`), and it re-arms wherever the hinge stopped once a dismiss fade is over. `FoldSurface` is either a snapshot surface drawn through the shader (`FoldOverlayView`: an AGSL view on 33+, a GL `TextureView` below, both at half resolution) or the "Live blur" strip windows that use SurfaceFlinger cross-window blur (API 31+). `OverlayState.running` tells the wallpaper and the preview to skip their own pass while the overlay is live, so the effect isn't drawn twice.

**Shizuku mode (full, optional).** `ShizukuBridge` (app side) binds `DuoShellService`, a Shizuku user service that runs with ADB shell privileges in a separate process. They talk over a hand-rolled Binder protocol: transaction codes in `ShellProtocol`, Bundles in and out. The shell service provides fast display capture that excludes our own overlay layer (the layer id is read via hidden API, hence `HiddenApiBypass` in `OverlayFeature.initProcess`), and it tails logcat for Samsung's fold wallpaper angle lines. `WallpaperAngleFeed` pings that wallpaper about 30×/s to make it log. Everything must fall back cleanly to the accessibility screenshot and the public sensor when Shizuku is absent.

**`DualScreen.kt`** uses the Jetpack Window Area API to request concurrent (both-panels-on) mode, so the both-screens engine path can be exercised.

## Conventions

- Logging tag for the overlay is `DuoOverlay`. Bug reports in the README depend on that tag, so keep using it.
- The app has no network permission, and the README's Privacy section promises that captures stay in memory only. Don't break either.
- Release signing reads an untracked `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).
- Techniques borrowed from other projects are credited in the README "Credits" section and in `THIRD_PARTY_NOTICES.md`. Keep those in sync when you add a dependency or a borrowed technique.
