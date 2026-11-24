# FloorPlan Scan

Walk a home or job site with your Android phone and get a dimensioned floor plan: room outlines,
wall lengths, doors and windows, room areas, and ceiling heights. Export a professional PDF, a PNG
image, an SVG, a CAD-ready DXF, or a JSON backup.

Built for the Samsung Galaxy S26 Ultra; works on any ARCore-supported Android phone (Android 8+).

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
3. **Walls, in order.** Go around the room in one direction. For each wall, aim the crosshair at a clear
   area of the wall **above furniture**, hold still, and tap *Capture*. Corners hidden behind furniture
   don't matter — corners are computed where neighbouring walls meet.
4. **Close the room**, add doors and windows (mark the left and right edge of each), review, name it.
5. **Scan the next room without leaving the scanner.** Rooms scanned in one session line up automatically.

If a wall won't capture (plain white wall, mirror, glass): use *Tap the wall base* — tap two points
where the wall meets the floor — or switch to *Corners* mode.

## Getting the best accuracy

* Good lighting; slow, steady movement; stay roughly 1–4 m (3–13 ft) from the wall you're capturing.
* Aim at textured or decorated parts of walls; avoid mirrors, glass and glossy surfaces.
* Scan a whole floor in **one** session so rooms align. If you must stop, use *Snap to neighbour* in
  the editor to dock later rooms against existing ones.
* **Calibrate once per floor:** measure one long wall with a tape, open that wall in the editor and
  choose *Calibrate floor from this wall*. This removes the phone's overall scale error (typically
  1–3 %), including for rooms you add afterwards.
* Check the room quality badge and the *Floor check* before exporting.

### What accuracy to expect

Phone AR on the S26 Ultra estimates depth from camera motion (it has no LiDAR / time-of-flight sensor).
In simulation with realistic depth noise and furniture, wall lengths come out within about 1 cm typically
and a few cm in the worst case for a large room; on real devices, expect roughly **±1–3 % before tape
calibration** and better after it. Blank, glossy or dark walls and long multi-room sessions are the
weak spots — the app grades every room and tells you when to recapture. Always verify measurements
that matter (e.g. for ordering materials) with a tape.

## Editing

Tap a room to select it: drag corners, set an exact wall length, add doors/windows, change the name,
type or ceiling height, rotate, move, or snap it to a neighbour. *Straighten floor* rotates the whole
plan square to the page. *Draw room* adds a rectangle by typed dimensions when scanning isn't possible.
Undo/redo cover every edit.

## Exports

| Format | Use |
| --- | --- |
| PDF | Client-ready plan with title block, your company branding, scale bar, overall dimensions, room schedule |
| PNG | High-resolution image for messages and listings |
| SVG | Vector image for web or design tools |
| DXF | CAD (AutoCAD, etc.) — inches (imperial) or millimeters (metric), layered |
| JSON | Full backup; re-import on any device |

## Building from source

Requirements: JDK 17+, Android SDK (platform 35, build-tools 35).

```bash
cd floorplan
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :core:test            # geometry, solver, exporters + scan accuracy simulation
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

Project layout: `core/` is pure Kotlin (math, solving, rendering, exports — fully unit-tested);
`app/` is the Android app (ARCore capture, Compose UI, PDF/PNG export). Design notes:
`docs/DESIGN.md`; current status: `docs/HANDOFF.md`.
