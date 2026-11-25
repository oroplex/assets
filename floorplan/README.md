# FloorPlan Scan

Walk a home or job site with your Android phone and get a dimensioned floor plan: room outlines,
wall lengths, doors and windows, room areas, and ceiling heights. Export a professional PDF, a PNG
image, an SVG, a CAD-ready DXF, or a JSON backup.

Built for the Samsung Galaxy S26 Ultra; meant for any ARCore-supported Android phone (Android 8+).
Units follow your region on first launch (feet & inches in the US, metric elsewhere) and can be changed
in Settings.

---

## Install

1. On GitHub, open **Actions → FloorPlan Scan (Android)**, choose the latest successful run on this
   branch, and download the **FloorPlanScan-apk** artifact (a zip containing `app-debug.apk`).
2. Copy `app-debug.apk` to the phone and open it. Allow "Install unknown apps" for the app you opened
   it with when Android asks.
3. On first launch the app asks for camera access and, if needed, installs **Google Play Services for AR**
   from the Play Store.

Every build is signed with the same key, so new versions install over old ones **without losing your
projects**. Use *Export → JSON backup* now and then anyway.

Developers: `adb install -r app/build/outputs/apk/debug/app-debug.apk` after building (see below).

## Scanning a room — the short version

1. **Find the floor.** Point at the floor and sweep slowly until the app locks it.
2. **Ceiling (optional).** Aim straight up and tap *Measure ceiling*, or *Skip* (the default height is
   used and marked *est.* everywhere).
3. **Walls, in order.** Go around the room in one direction. For each wall, aim the crosshair at **bare wall
   above furniture** (beside doors, windows, pictures and TVs, not on them), hold still, and tap *Capture*. Corners hidden behind furniture
   don't matter — corners are computed where neighbouring walls meet.
4. **Close the room**, add doors and windows (mark the left and right edge of each), review, name it.
5. **Save & scan next room** without leaving the scanner. Rooms scanned in one session line up automatically.
   Back asks before it ends a scan; *Discard room, keep scanning* throws away only the room in progress.

If a wall won't capture (plain white wall, mirror, glass): use *Tap the wall base* — tap two points
where the wall meets the floor — or switch to *Corners* mode.

## Getting the best accuracy

* Good lighting; slow, steady movement; stay roughly 1–4 m (3–13 ft) from the wall you're capturing.
* Aim at the wall itself. A door, a picture or a TV under the crosshair gets measured instead of the wall
  (the app refuses the capture or warns that the wall came out off square). Detail nearby helps; avoid
  mirrors, glass and glossy surfaces.
* Scan a whole floor in **one** session so rooms align. If you must stop, select a room of the later scan
  in the editor, keep *Whole scan* on, and use *Square to floor* and *Snap to neighbour* to dock all its
  rooms against the existing ones in one go.
* **Calibrate once per floor:** measure one long wall with a tape, tap that wall in the editor and
  choose *Calibrate floor*. This removes the phone's overall scale error (typically 1–3 %), including for
  rooms you add afterwards. Lengths, ceilings and door sizes you typed yourself are never rescaled.
* Check the room quality badge and the *Floor check* before exporting.

### What accuracy to expect

Phone AR on the S26 Ultra estimates depth from camera motion (it has no LiDAR / time-of-flight sensor, as
far as we know). Expect roughly **±1–3 % before tape calibration** and better after it. The app's own
simulation is tighter than that, but it checks the maths, not the phone: it has not been measured on a
device yet (see `docs/FIELD-TEST.md`). Blank, glossy or dark walls and long multi-room sessions are the
weak spots — the app grades every room and tells you when to recapture. Always verify measurements
that matter (e.g. for ordering materials) with a tape.

## Editing

Tap a room to select it: drag corners, set an exact wall length, add doors/windows, change the name,
type, ceiling height or what its area counts as (living area, garage & unfinished, outdoor), rotate, move,
or snap it to a neighbour. At "fit the whole floor" zoom the plan shows only the labels that fit; zoom in
for wall lengths. *Floor options* has the room list, *Floor check*, *Straighten floor* and *Rotate floor*.
*Draw room* adds a rectangle by typed dimensions when scanning isn't possible. Undo/redo cover every edit.

## Exports

| Format | Use |
| --- | --- |
| PDF | Client-ready plan at a standard architectural scale (e.g. 1/8" = 1'-0" or 1:100) with title block, your company branding, scale bar, overall dimensions, room schedule with living-area totals |
| PNG | High-resolution image for messages and listings |
| SVG | Vector image for web or design tools |
| DXF | CAD (AutoCAD, etc.) — inches (imperial) or millimeters (metric), layered |
| JSON | Full backup; re-import on any device |

## Building from source

Requirements: JDK 17+, Android SDK (platform 35, build-tools 35).

```bash
cd floorplan
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :core:test              # geometry, solver, exporters + scan accuracy simulation
./gradlew :app:testDebugUnitTest  # app logic + a headless walkthrough of every screen
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
```

The walkthrough runs the real app on the JVM and saves a screenshot of every screen to
`app/build/ui-walkthrough/` (CI uploads them as `ui-walkthrough-screenshots`).

Project layout: `core/` is pure Kotlin (math, solving, rendering, exports — fully unit-tested);
`app/` is the Android app (ARCore capture, Compose UI, PDF/PNG export). Design notes:
`docs/DESIGN.md`; current status: `docs/HANDOFF.md`; first test on a phone: `docs/FIELD-TEST.md`.
