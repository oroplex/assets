# Handoff — state of the project and next steps

_Last updated 2026-11-25. Branch: `claude/floorplan-generation-app-hlws61`._

## Where things stand
* Rounds 1, 2 and 3 are merged. Round 3 fixed the 55 confirmed findings of `REVIEW-2.md` in six packages
  (capture flow, editor, core ops/render, export/shell, core accuracy, AR layer); the joins between them
  were fixed afterwards (aim-checked wall results, estimator hints shown to the user, AR diagnostics in the
  shareable scan log). Build: 403 core + 605 app tests, 0 failed, APK builds.
* **The app has never run on a phone.** A headless walkthrough launches the real app on the JVM (see below);
  the camera / ARCore part can only be checked on a device.
* **Feature freeze.** Fixes only until the first on-device test (`docs/FIELD-TEST.md`).
* A narrow regression check of round 3 (major problems only, each finding verified by a second agent) found
  three bugs in the first four packages, all fixed (confirming a wall at its shown length did nothing; the
  "Scan interrupted" dialog could discard a restorable room; calibrating from a hand-set wall stored a
  meaningless factor). The check of the AR package found one more, also fixed: the wall sampling grid was a
  quarter of the screen's short side, about ±4.5° of the camera on a tall phone, while the estimator's
  gates and the whole simulation assume ±12°. At ±4.5° walls nearer than about 0.6 m were always refused and
  a sensor 1.5× noisier than the model failed most captures. The grid is now sized by angle from the
  camera's projection (`WallSampleGrid.HALF_ANGLE_DEG` = 12), and each wall's log line states the angle used.
  The check of the accuracy package found the last one, rated critical and confirmed by a second reviewer:
  a door, picture or TV under the crosshair that is a few centimetres proud of or behind the wall was
  fitted as one diagonal line through wall and object, 5–9° off, and the room was saved skewed without a
  warning (a recessed door from 4 m: 44 of 60 simulated captures). Fixed in the estimator (the shallow-step
  rule, see "Known issues") with a solver warning behind it; `ShallowStepTest` keeps it fixed and writes
  `core/build/step-report.txt`.
* **Every wall capture keeps its raw samples on the phone** (`diagnostics/WallCaptureLog`, newest 60, in the
  app's private storage). "Share scan log" appends them to the shared text file. On a computer,
  `WallCaptureRecord.decodeAll(text)` reads them out of the whole file and `record.replay(config)` runs the
  estimator on them again, so thresholds can be tuned on real captures without a re-scan. Timed-out and
  tracking-lost captures are kept too.

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
  4. The plan was unreadable at phone size in the editor: wall lengths, room sizes and names overlapped,
     because label sizes are in points and the layout assumed a ~560 pt wide plan (a phone screen is about
     185 pt wide) — fixed: at a known scale (`RenderOptions.pointsPerMeter`) only labels that fit are drawn
     (`core/render/LabelFit.kt`: name, then size, area, wall lengths). The onboarding illustration and the PDF use it too.
* Known runtime quirk: a dialog containing a text field never reports "idle" on Robolectric, so those
  steps use `RawUi` (manual clock, direct semantics access). It is not an app bug.

## Known issues and open questions
* **Short walls are refused from across the room, by design.** A wall the estimator cannot verify (a 0.3-0.6 m
  closet return or chimney side seen at an angle) is refused with the hint "Stand square to this wall ... or
  tap its base" instead of being fitted to a wrong surface. Square-on it is measured within about 2-3 times
  its length. With noisier depth than assumed, 0.3 m returns are refused about half the time even close up;
  "Tap wall base" is the way out. Watch the refusal rate in the first device session.
* **Doors, pictures and TVs under the crosshair.** The estimator now looks for a shallow step among a
  wall's sampling columns (two parallel faces a few centimetres apart) and keeps only the crosshair's side.
  From close up the object's own face is then measured, square but offset by the object's depth (a door
  8 cm back puts that wall 8 cm out); from further away the capture is refused with "A door, picture or TV
  is in the way here. Aim at bare wall beside it". In simulation 8 of 1 860 such captures still come out
  more than 5° off; every room they skew carries the new "Wall N is 7° off square with the room" warning.
  The cost: about 1 mm in the smooth-distortion scenario, and on a real phone possibly false refusals if
  its depth map has step-like artefacts on plain walls (unknown; the thresholds are `STEP_MIN_SIGMAS` 0.8,
  `STEP_Z` 3.5 in `WallEstimator`). The field test has one deliberate capture of a door to measure this.
  Guidance in the app now says "bare wall, beside doors and pictures", not "the middle of the wall".
* **An ARCore plane can stand in for a refused fit** when the crosshair's depth lies on it; it is marked
  low-confidence (amber chip, "weak capture" in the review). Whether such planes are good enough is a
  device question: the scan log prints the plane next to the depth fit for every wall.
* **Floor auto-lock is stricter than before** (a plane within 1 m of the phone; under the phone when it is
  less than 1.2 m below). Manual "Lock floor" always works. The log says why auto-lock did not fire.
* **Neighbouring rooms may be about 1° apart.** The shared squaring axis is now only taken when a room's own
  axis agrees within max(0.5°, 2 standard errors); it never changes a dimension.
* Decision for the owner after the first test: PDFs print at a standard architectural scale (the sample house
  at 1/8" = 1'-0" fills about half a Letter page). Fit-to-page fills the sheet but is not to scale. A switch
  is a small change in `export/PlanScale.kt`.
* Deferred for the feature freeze: extra room-schedule columns (wall area, volume, openings) in the PDF.
* Wall sampling still uses hit tests (9x9 grid over ±12°, shrinking to 7x7 / 5x5 when frames are slow) instead
  of reading the depth image and its confidence map. Evaluate after the first device data.
* Every threshold in the AR layer and the estimator is calibrated on simulation only.

## Ideas from similar projects (after the first phone test; nothing here is being built now)
A GitHub survey (November 2026, READMEs only) found no open Android project that produces a multi-room
dimensioned plan: the field is iPhone LiDAR apps on Apple RoomPlan, CubiCasa's capture-only SDK sample (the
plan is made on their servers), a few early-alpha ARCore depth tools and offline research pipelines. Worth
taking, in order:
1. Read ARCore's raw depth image and its confidence map instead of hit tests (`depthfold`, `denseframe`,
   Depth Lab). Note from `depthfold`: the depth image can belong to an earlier frame, so pair it with the
   pose of its own timestamp. Decide from the recorded captures.
2. Fixture and furniture symbols placed by hand (toilet, tub, sink, counters, appliances): the biggest
   visible difference from a CubiCasa plan.
3. Job-site outputs: a materials take-off (flooring with waste, paint, drywall, baseboard) and CSV from the
   areas `Measurements` already computes; photos pinned to the plan with their direction.
4. A ± on every dimension, only once the phone data shows the estimator's precision figures are honest.
5. Small: mark tape-checked dimensions on the exported plan (today only the editor shows them); a low-light
   warning; aligning rooms from separate scans by a shared doorway; GLB / OBJ export of the 3D model.
Not planned: cloud drafting, accounts and sync, AI furniture layout, damage detection, voice room labels,
a compass north arrow.

## Accuracy: what is and is not known
`core/build/sim-report.txt` (written by `:core:test`) is the full report. Summary, wall-length error in mm:

| Scenario | worst | 95th pct | wrong surface |
| --- | --- | --- | --- |
| Sample house, the estimator's own noise model | 11 | 4 | 0 of 80 scans |
| With furniture in front of walls | 17 | 6-7 | 0 of 120 |
| Noise x1.5 + per-frame error + plane hits | 18 | 9 | 0 of 80 |
| One smooth 0.3 % depth error per capture | 40 | 17 | 0 of 80 |
| Rooms with closet returns, chimney breast, column | 8 | 5 | 0 of 50 |
| Short faces from across the room (920 captures) | — | — | 0 (874 refused, 46 measured) |
| Door, panel, TV or picture under the crosshair (1 860 captures) | — | — | 8 more than 5° off (was over 250); 3 skewed rooms, all warned |

* "Wrong surface" was the critical round-2 finding (a short wall fitted to a fake diagonal); the estimator
  now refuses what it cannot verify. Zero is an empirical result on right-angled rooms, not a proof.
* The simulation is **not** a prediction for the phone. Real depth-from-motion error on a plain wall is
  smooth (a whole patch is off together); the "smooth field" row shows that even 0.3 % of that triples the
  error. Tracking drift is not simulated at all.
* Plan on ±1–3 % per room before tape calibration. Whether depth fits or ARCore's detected wall planes are
  more accurate on the S26 Ultra is an open question; the scan log records both for every wall.

## Gate for "ready to test on the phone"
1. Round 3 fully merged; `:core:test :app:testDebugUnitTest :app:assembleDebug` green; simulation re-read.
2. Walkthrough green and its screenshots reviewed by eye (including the label fix).
3. One narrow regression check of the round-3 diff (major problems only; no new features): done, six
   findings confirmed (three in the app's flows, the sampling grid, the shallow step, calibration from a
   hand-set wall), all fixed.

## First on-device session (a measurement study, not a demo)
* Follow `docs/FIELD-TEST.md`. Diagnostics come back either way: **Settings → About → Share scan log** on
  the phone alone, or `adb logcat -s FloorPlanScan` with the phone on USB. One line per measurement: samples
  by kind, noise against expected, the depth fit and the ARCore plane side by side, the result or the hint.
  The shared file also carries the raw samples of every wall capture (see above).
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
