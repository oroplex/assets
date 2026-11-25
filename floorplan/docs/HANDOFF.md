# Handoff — state of the project and next steps

_Last updated 2026-11-25. Branch: `claude/floorplan-generation-app-hlws61`._

## Where things stand
* Rounds 1 and 2 are merged. Round 3 (fixes for `REVIEW-2.md`, 55 confirmed findings) is being merged
  package by package: capture flow and editor are in; core accuracy, core ops/render, export/shell and
  the AR layer follow.
* **The app has never run on a phone.** Until 2026-11-25 it had never run at all: every test was a logic
  test. A headless walkthrough now launches the real app on the JVM (see below). The camera / ARCore part
  can only be checked on a device.
* **Feature freeze after round 3.** Fixes only until the first on-device test.

## Headless UI walkthrough (the only check that the UI starts and draws)
* `./gradlew :app:testDebugUnitTest` runs `walkthrough/AppWalkthroughTest` and `CaptureWalkthroughTest` on
  Robolectric: real `MainActivity`, navigation and repositories; onboarding, projects, editor (every
  dialog, sheet and menu), 3D view, export, settings, dark theme, and the scanning screens driven by a
  simulated AR feed. Any exception fails the build.
* Screenshots land in `app/build/ui-walkthrough/` (CI uploads them as the `ui-walkthrough-screenshots`
  artifact). **Look at them after every UI change.**
* Found on its first runs (two review rounds of code reading had missed all of these):
  1. "New project" and every rename dialog crashed (`FocusRequester is not initialized`) — fixed.
  2. Draw room / Scan room / New project buttons had no accessibility label — fixed.
  3. Capture review: both Save buttons were below the bottom of the screen — fixed (pinned).
  4. **Open:** the plan is unreadable at phone size in the editor (and in the onboarding illustration):
     wall lengths, room sizes and names overlap. Cause: label sizes are in points and the renderer's fit
     logic assumes a ~560 pt wide plan, but a phone screen is only ~185 pt wide. Fix in `PlanRenderer`
     using `RenderOptions.pointsPerMeter` (level of detail: name > area > size > wall lengths).
* Known runtime quirk: a dialog containing a text field never reports "idle" on Robolectric, so those
  steps use `RawUi` (manual clock, direct semantics access). It is not an app bug.

## Known issues not yet fixed
* `ArSessionFactory` sets `FocusMode.AUTO`. ARCore's docs say the default (fixed focus) tracks best; a
  measuring app should use `FIXED`. Change after the AR package (round 3) is merged.
* Plan label level of detail (item 4 above).
* Wall sampling uses 81 hit tests per frame instead of reading the depth image and its confidence map.
  Evaluate after the first device data.

## Accuracy: what is and is not known
* `core/build/sim-report.txt` (written by `:core:test`) shows the geometry is sound. It is **not** a
  prediction for the phone: until round 3 it used the estimator's own noise model, and real
  depth-from-motion error on plain walls is spatially correlated (a whole wall patch can be off by
  centimetres together), which averaging many samples does not remove.
* Plan on ±1–3 % per room before tape calibration. Whether depth fits or ARCore's detected wall planes are
  more accurate on the S26 Ultra is an open question for the first device session.

## Gate for "ready to test on the phone"
1. Round 3 fully merged; `:core:test :app:testDebugUnitTest :app:assembleDebug` green; simulation re-read.
2. Walkthrough green and its screenshots reviewed by eye (including the label fix).
3. One narrow regression check of the round-3 diff (major problems only; no new features).

## First on-device session (a measurement study, not a demo)
* Phone on USB with debugging on, so logs can be read: `adb logcat -s FloorPlanScan` (the AR package adds
  one line per measurement: samples by kind, noise vs expected, result).
* One ordinary room. Tape-measure its four walls first. Scan it three times, in one session.
* Compare each wall with the tape. Then calibrate from one wall and compare again.
* Note any wall that would not capture, and what it looked like (plain, glossy, window).
* Check first that the phone supports ARCore: "Google Play Services for AR" must install from the Play
  Store (expected for the S26 Ultra; not verified from here).

## Building on a new machine
* JDK 17+ (Android Studio's bundled JBR is fine), Android SDK with platform 35 + build-tools 35.
* Create `floorplan/local.properties` with `sdk.dir=<path to Android SDK>` (Windows: escape backslashes,
  e.g. `sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk`).
* `cd floorplan && ./gradlew :app:assembleDebug` (Windows: `gradlew.bat`). APK: `app/build/outputs/apk/debug/app-debug.apk`.
* Install on the phone: enable Developer options → USB debugging, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
  All builds share `keystore/debug.keystore`, so updates install over each other without losing saved projects.
* Dependencies resolve through Google's Maven Central mirror first (Central's own hosts rate-limit some
  networks); see `settings.gradle.kts`.
