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
that draws a touch-transparent overlay above every window, tracking the
hinge. There's also a plain live wallpaper mode if you'd rather not enable an
accessibility service.

Two ways to draw the whole-screen fold (**Tune → How it's drawn**):

- **Snapshot** (default): one screenshot per fold phase, bent through the
  shader. Smooth frost gradient, darkening and the perspective bend at the far
  edge — but the picture is frozen while it plays, and a panel that has just
  switched on can't be captured for ~0.4 s (see the next section).
- **Live blur**: no screenshot at all. The system's own window blur
  (SurfaceFlinger cross-window blur, the thing behind the notification shade)
  is applied to the live screen through a handful of strip windows, each
  blurred by the radius the shader would use at that distance from the
  crease, with the darkening painted on top. Starts the instant a phase
  begins, content keeps moving underneath, no capture rate limit. The frost
  is stepped rather than smooth and there's no perspective bend. Needs window
  blur enabled on the device (`WindowManager.isCrossWindowBlurEnabled`); the
  chip is greyed out otherwise, and battery saver can switch blur off
  system-wide.

## One screen at a time vs. both screens on

This matters more than anything else for how the effect looks, so it's worth
understanding before you judge it.

**Phones that only ever light one panel (OnePlus Open, and most book-style
foldables).** The cover screen switches *off* and the inner screen switches
*on* at ~10–30° into an unfold — there is never a moment when both are lit,
and no app can draw on a panel that is off. On top of that the freshly-lit
panel shows the system's own black-to-reveal for ~0.4 s before there is
anything to screenshot, and Android allows one screenshot per ~333 ms. So
the effect is necessarily two phases with a gap at the swap:

1. cover screen frosts in as you start opening → *swap: black, then the
   system reveal* → 2. inner screen picks up frosted and clears.

On a normal-speed open you see both; on a fast flick the second phase may
not have time to appear before you're at 180°, and it is skipped rather than
popping in late. Closing is the same in reverse. This is a platform limit
(mostly software: the OS turns the other panel off), not something the app
can work around without root.

**Phones that keep both panels on.** The service runs one engine per lit
built-in display, so if your phone leaves the cover screen on while the
inner screen is active, the cover and inner effects run side by side and the
handover has no gap: the cover frosts in from closed, the inner screen lights
up and clears, and a cover that stays lit clears again on its own as you
reach flat (it peaks mid-fold, since there is no swap to hand over to).

I don't own such a device, so this path is verified only on an emulator with
a simulated second display (Android's *Developer options → Simulate
secondary displays*, plus `adb shell settings put global duoopen_test_displays 1`
so the service accepts it): both panels get an engine and play together.
Two things to know on real hardware: Android's screenshot rate limit is
global, so in **Snapshot** mode the second panel's capture lands ~0.35 s after
the first — **Live blur** has no such limit and is the better choice there;
and I'd like to hear how it behaves — please open an issue with
`adb logcat -s DuoOverlay` from one open/close; the log shows which displays
got an engine and when each captured.

## Install

Two editions on the [Releases](../../releases) page:

- **`DuoOpen-<version>.apk`** — full: system-wide fold (accessibility
  service) plus the live wallpaper. Needs the restricted-settings dance below.
- **`DuoOpen-<version>-lite.apk`** — wallpaper only. No accessibility
  service is declared or compiled in, so Play Protect and restricted settings
  never get involved; the fold plays on the home/lock-screen wallpaper and
  icons stay sharp. Separate app id (`com.duoopen.lite`), so it installs
  alongside the full edition.

Full edition:

1. Download `DuoOpen-<version>.apk` and install it.
2. Open **Duo Open** → **Tune** → **Turn on in Accessibility** → enable
   *Duo Open full-screen fold*.
   - Android 13+ blocks accessibility for sideloaded apps until you allow
     it. If the toggle is greyed out: tap it once so Android refuses you,
     then go to *Settings → Apps → Duo Open → ⋮ (top right) → Allow
     restricted settings* and try again. **Tune → Toggle greyed out?** walks
     through it with deep links.
   - Samsung: turn off *Settings → Security and privacy → Auto Blocker*
     first if the option is missing.
   - Or skip the whole check by installing over USB — apps installed with
     `adb install` aren't restricted:
     `adb uninstall com.duoopen; adb install DuoOpen-<version>.apk`
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

- On phones that light one panel at a time there is an unavoidable gap at
  the panel swap, and on a fast flick the second phase may not appear at all
  — see *One screen at a time vs. both screens on* above.
- If you stop partway (tent mode) the overlay fades out after ~0.7 s so the
  live screen isn't hidden.
- Reinstalling the app turns the accessibility service off again.
- **Stops-only hinge sensors.** Some foldables only let apps read three
  hinge positions — the Galaxy Z Fold 7 and earlier report 0 / 90 / 180
  (`resolution 90°`); Samsung's continuous `folding_angle` sensor needs a
  system permission. On those the fold can't follow your hand: each stop
  change plays a ~0.5 s ease instead (frost in on leaving rest, frost out
  once the other panel lights up). **Tune** says so when it detects one,
  and **Copy sensor report** dumps every hinge-related sensor for a bug
  report. Measured on a Galaxy Z Fold 8 (Android 17): its public
  `hinge_angle` is also stops-only (0 / 90 / 180), so this applies to Fold 8
  too. The Pixel Folds expose a continuous angle.

## Build

```
./gradlew assembleFullDebug     # debug-signed, full edition
./gradlew assembleLiteRelease   # wallpaper-only edition
./gradlew assembleRelease       # both editions, signed with keystore.properties if present
```
The `full`/`lite` product flavors differ only by `src/full` (accessibility
service, overlay, setup guide) and `src/lite` (stubs); everything else is
shared.
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
fold/HingeAngleSource.kt                  hinge sensor picker (vendor fallback, coarse detection, report)
fold/TiltFollower.kt                      per-vsync ease that hides the sensor's 1° steps
fold/Panels.kt                            inner vs cover panel from the display mode
overlay/OverlayFeature.kt                 per-flavor facade (full: the service; lite: stubs)
src/full/…/overlay/FoldOverlayService.kt  accessibility service: one engine per lit display
src/full/…/overlay/PanelEngine.kt         per-display fold: screenshot + overlay + hinge tracking
src/full/…/overlay/FoldSurface.kt         snapshot surface, or live-blur strip windows
src/full/…/overlay/FoldOverlayView.kt     draws the snapshot through the shader (half-res layer)
wallpaper/DuoWallpaperService.kt          live wallpaper engine
wallpaper/WallpaperImage.kt               picked image / generated default
ui/                                       Compose app: preview, Tune sheet, restricted-settings guide
settings/DuoSettings.kt                   shared tuning (SharedPreferences + StateFlow)
```

## Galaxy Z Fold 8 notes

- Measured 2026-09-26 (SM-F971B, Android 17). Two separate logical displays:
  0 is the cover (1248×1972); 1 is the inner panel (2448×1848), flagged
  `FLAG_PRESENTATION` and **off** whenever the phone is closed. On a normal
  open the OS turns the cover off and puts the inner panel under display 0,
  exactly like the OnePlus — the phone does *not* keep both screens on while
  you fold it. Its `CONCURRENT_INNER/OUTER_DEFAULT` device states exist but
  are only entered when an app asks for dual-screen (camera cover preview,
  Interpreter); the service accepts the second built-in display when that
  happens (it matches the default display's name, "Built-in Screen").
- Public `hinge_angle` reports only 0 / 90 / 180, so the fold plays as timed
  eases per stop: cover frosts in at the first 90° event, the inner panel
  lights ~0.5 s later and its capture takes ~0.2–0.3 s, then it clears.
- One UI has SurfaceFlinger window blur disabled, so **Live blur** is greyed
  out; Snapshot only.

## OnePlus Open notes

- Inner panel 2268×2440, fold splits the short side; display 0 swaps between
  the cover (1116×2484) and inner panels at ~10–30° depending on speed.
- The hinge sensor is wake-up only and sends nothing on registration, goes
  quiet at ~30° during a close, and idles anywhere from 0–5° when shut. The
  service compensates for all three.

## Related projects

Other takes on the same idea, useful for comparing approaches:

- [ServerReset/duo-open](https://github.com/ServerReset/duo-open) and
  [nihal711/Z-Fold-Duo-TEST](https://github.com/nihal711/Z-Fold-Duo-TEST)
  — forks of this app for the Galaxy Z Fold 7/8. The sensor picker and the
  coarse-sensor handling here follow what they found on Samsung hardware;
  Z-Fold-Duo also streams live frames through an embedded ADB shell process.
- [iamkeeler/FoldFX](https://github.com/iamkeeler/FoldFX) — no screenshots:
  a transparent overlay with compositor blur behind it, plus scrim and light
  sweep. Live content and no accessibility service, but no perspective and
  nothing over the lock screen.
- [keepYaoung/android-also-could-fold](https://github.com/keepYaoung/android-also-could-fold)
  — Samsung compositor snapshots via a local ADB client.
- [mossan819/DuoFoldWallpaper](https://github.com/mossan819/DuoFoldWallpaper),
  [StepFPV/foldwall](https://github.com/StepFPV/foldwall),
  [Ant-lib/hingewave](https://github.com/Ant-lib/hingewave) — live-wallpaper
  only variants.

## License

MIT — see [LICENSE](LICENSE). The shader is adapted from
[Atomicx7/Duo-animation](https://github.com/Atomicx7/Duo-animation).
