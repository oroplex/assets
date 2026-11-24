# Handoff — state of the project and next steps

_Last updated 2026-11-24. Branch: `claude/floorplan-generation-app-hlws61`._

## Where things stand
* Round 1 (first implementation) and round 2 (fixes for review round 1 + premium features) are merged.
  Round 2 added: aim-point wall fitting with a range-adaptive noise model, robust squaring/order handling,
  calibration provenance (only AR-measured values scale; later rooms get the floor's calibration), floor
  straighten/rotate, snap-to-neighbour (incl. auto-snap), overlap detection + floor checks, per-room quality
  grades, overall dimension lines, estimated-ceiling tracking, first-scan coaching, per-wall delete/recapture,
  company branding on exports, onboarding, dark theme, and a 3D dollhouse view.
* Build: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` passes — 329 core + 365 app tests, 0 skipped.
* **Review round 2 is in progress / next** (independent reviewers + adversarial verification). Then fix round 3,
  then on-device testing on the Galaxy S26 Ultra. Not yet field-tested.

## Accuracy simulation (core pipeline, synthetic data — `core/build/sim-report.txt` after `:core:test`)
Realistic depth-from-motion noise (σ = 5 mm + 1.2 % of range), 10 % outliers, the app's aim point, production defaults:
* 0 first-try capture failures out to 4.5 m; 0 wrong-wall fits; correct room topology in every scenario
  (clean, furniture, stress furniture, swapped walls, repeated captures, narrow rooms).
* Wall-length error: p95 ≈ 6 mm, worst ≈ 22 mm (8 m L-shaped room); ≤ 7 mm worst in rooms up to 5 m.
* +2 % AR scale error → one tape calibration → worst ≈ 10 mm (8 m room), ≤ 7 mm elsewhere.
* Caveat: the noise model is an assumption; real-device accuracy must be confirmed on the S26 Ultra.

## Next steps
1. Fix confirmed findings from review round 2 (record them in `REVIEW-2.md`).
2. On-device testing on the Galaxy S26 Ultra (install via USB/adb, read `adb logcat`); tune coaching/floor/ceiling
   thresholds and the depth noise model from real captures.
3. Nice-to-haves: per-side exterior dimension chains, depth-image sampling instead of 81 hit-tests per frame.

## Building on a new machine
* JDK 17+ (Android Studio's bundled JBR is fine), Android SDK with platform 35 + build-tools 35.
* Create `floorplan/local.properties` with `sdk.dir=<path to Android SDK>` (Windows: escape backslashes,
  e.g. `sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk`).
* `cd floorplan && ./gradlew :app:assembleDebug` (Windows: `gradlew.bat`). APK: `app/build/outputs/apk/debug/app-debug.apk`.
* Install on the phone: enable Developer options → USB debugging, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
  All builds share `keystore/debug.keystore`, so updates install over each other without losing saved projects.
