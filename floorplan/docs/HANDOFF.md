# Handoff — state of the project and next steps

_Last updated 2026-11-24. Branch: `claude/floorplan-generation-app-hlws61`._

## Where things stand
* Complete first implementation: AR capture (ARCore, depth-based wall fitting, baseline / corner fallbacks),
  room solver, plan editor, projects, PDF / PNG / SVG / DXF / JSON export, settings, GitHub Actions APK build.
* Build: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` passes (293 tests + simulation suite).
* **Not yet field-ready.** Review round 1 found real defects (see `REVIEW-1.md`). Nothing has run on a phone yet.

## Accuracy simulation (core pipeline, synthetic data — see `core/build/sim-report.txt` after `:core:test`)
* With realistic depth-from-motion noise (σ = 5 mm + 1.2 % of range), 10 % outliers and furniture:
  wall-length error p95 ≈ 8–11 mm, worst ≈ 24 mm (large L-shaped living room); area error < 1 %;
  squared corners exact. A +2 % AR scale error is removed by one tape calibration.
* Problems it exposed (fix in round 2):
  1. Fixed noise gates (`maxRms` 3 cm, inlier band 2.5 cm) reject every capture beyond ~2 m (TOO_NOISY).
     A range-adaptive band — inlierThreshold = max(2.5 cm, 2σ(d)), maxRms = max(3 cm, 1.5σ(d)) — removed all
     first-try failures in simulation and also fixed the hallway "wrong wall" case. Adopt it (and re-check the
     large-room worst case, which grew to ~5 cm at 2.5 m+ — consider sampling more frames at long range).
  2. Grazing-incidence captures (aiming down a hallway at the end wall) can fit a side wall.
  3. The re-ordering heuristic breaks the L-shaped room when two walls are swapped or a wall is re-captured
     non-consecutively (8 corners instead of 6).
  4. "Corner beyond what was measured" warning fires on most normal rooms (false alarm).

## Round 2 plan (in priority order)
1. Fix all confirmed findings in `REVIEW-1.md`; re-verify the unverified Product/UX and simulation findings first.
   Highest impact: floor locking onto beds/tables; adaptive wall-fit gates; skipped ceiling saved as measured;
   calibration not applied to later rooms; setWallLength skew; squaring dragged by an angled wall;
   prefer-farther picking a wall through a doorway; activity recreation ending the AR session mid-scan.
2. Multi-room workflow: snap a room to a neighbour by a shared wall; straighten the whole floor (rotate to the
   dominant axis); keep calibration for rooms added later; per-room quality badges; pre-export check.
3. Premium polish: first-run tutorial + scanning coach; exterior overall dimension lines; company name/logo on
   PDFs; cleaner schedule; optional 3D "dollhouse" preview.
4. Verification round 2 (independent reviewers + adversarial verify + simulation must stay green; un-ignore the
   `@Ignore("FINDING …")` tests in `core/src/test/.../sim/ScanSimulationTest.kt` as they are fixed).
5. On-device testing on the Galaxy S26 Ultra (install via USB/adb, read `adb logcat`).

## Building on a new machine
* JDK 17+ (Android Studio's bundled JBR is fine), Android SDK with platform 35 + build-tools 35.
* Create `floorplan/local.properties` with `sdk.dir=<path to Android SDK>` (Windows: escape backslashes,
  e.g. `sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk`).
* `cd floorplan && ./gradlew :app:assembleDebug` (Windows: `gradlew.bat`). APK: `app/build/outputs/apk/debug/app-debug.apk`.
* Install on the phone: enable Developer options → USB debugging, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
  All builds share `keystore/debug.keystore`, so updates install over each other without losing saved projects.
