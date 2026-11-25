# FloorPlan Scan — design brief

Native Android app (Kotlin, Jetpack Compose, ARCore) that measures rooms with the phone's AR tracking
and produces a dimensioned 2D floor plan (PDF / PNG / SVG / DXF / JSON). Target device: Samsung Galaxy
S26 Ultra (ARCore-certified, Depth API via depth-from-motion; no hardware ToF sensor is assumed, but a
ToF sensor is used automatically if a device has one).

## Accuracy model (why the app is built this way)
* ARCore world frame: metric, gravity-aligned (+Y up). Floor planes are detected very reliably;
  vertical (wall) planes are less reliable on blank walls; Depth API points are noisy (several cm at 3 m).
* Therefore walls are modeled as **vertical planes = 2D lines in the plan**, fitted robustly (RANSAC +
  total least squares) to MANY depth samples accumulated over ~0.5 s. Corners are **intersections of
  adjacent wall lines**, so furniture hiding a corner does not matter and the outline always closes.
* Fallbacks: (a) a detected ARCore vertical plane under the reticle, (b) "baseline" mode — tap two
  points where the wall meets the floor (ray ∩ floor plane, very accurate), (c) corner-tap mode,
  (d) manual drawing / typed dimensions in the editor.
* Optional squaring snaps walls within ±5° of the room's dominant axis to exact 90° (rotating each wall
  about its observed midpoint, so positions are preserved).
* Rooms scanned in one AR session share a coordinate frame, so a whole floor assembles itself. Each
  room gets an ARCore anchor; on finish, rooms are re-positioned by their anchor's refined pose.
* Tape calibration: the user can enter one tape-measured wall length; the whole floor is scaled by
  true/measured. This removes systematic AR scale error (typically 1–3 %).
* Expect roughly ±1–3 % on room dimensions with careful scanning; worse on blank/glossy walls, dim
  light, or long multi-room sessions. The app surfaces quality warnings rather than hiding them.

## Coordinate conventions (MUST follow)
* World: ARCore meters, +Y up. `Vec3`.
* Plan: `plan.x = world.X`, `plan.y = -world.Z` (`Vec3.toPlan()`), meters, y up, CCW angles positive.
* Room outlines: interior faces of walls, **counter-clockwise**, no repeated closing vertex.
  Edge i = corners[i] → corners[(i+1) % n]. Openings reference edges by that index.
* Screen: pixels, origin top-left, y down. `ViewTransform` maps plan ↔ screen.
* Matrices: 4×4 column-major FloatArray (android.opengl.Matrix / ARCore layout).
* DrawList: geometry in plan meters; line widths / text sizes / text offsets in points (1/72").

## Modules
* `:core` — pure Kotlin/JVM, no Android. All math, solving, measurement, formatting, rendering to
  DrawList, SVG/DXF/JSON export. Fully unit-tested (`./gradlew :core:test`).
* `:app` — Android. AR session + GL camera rendering + measurement sampling (`ar/`), Compose UI
  (`ui/`), Canvas painter (`render/PlanPainter`), PDF/PNG export (`export/`), storage (`data/`).
  Keep Android code thin: anything testable without a device belongs in `:core`.

## Shared contracts (do not change signatures; additive changes only, and report them)
core: `geometry/*` (implemented), `model/Model.kt` (implemented), `render/DrawList.kt`,
`render/ViewTransform.kt` (implemented), stubs: `fit/*`, `projection/Projection.kt`, `solve/*`,
`measure/*`, `render/PlanRenderer.kt`, `render/SvgExporter.kt`, `render/DxfExporter.kt`, `io/*`.
app: `data/ProjectRepository.kt`, `data/SettingsRepository.kt` (interface), `data/AppContainer.kt`,
`ar/ArCaptureController.kt`, `ar/ArCaptureHost.kt` (signature), `render/PlanPainter.kt` (signature),
screen composable signatures in `ui/*/…Screen.kt`, `MainActivity.kt`.

## Capture flow (CaptureScreen + CaptureViewModel)
Steps per room: FIND_FLOOR → CEILING (optional, skippable) → WALLS or CORNERS → OPENINGS (optional) →
REVIEW (name, type, stats, warnings) → save → "Scan next room" (same AR session) or "Finish".
* FIND_FLOOR: guidance "Point at the floor and slowly move the phone"; auto-lock when floorArea ≥ 1 m²
  stays stable ~1 s, or a "Lock floor" button (MeasureRequest.LockFloor). After the first room in a
  session, the floor stays locked for following rooms (same floor).
* CEILING: aim up, "Measure ceiling" (CeilingHeight) or "Skip" (uses settings default, marked estimated).
* WALLS: "Aim at wall 1 above furniture, hold steady, tap Capture". Progress spinner while sampling.
  On failure show WallFitFailure.message + offer "Tap the wall base instead" (baseline: 2 FloorPoints →
  WallEstimator.fromBaseline). Undo last. After ≥3 walls show the live solved outline (AR overlay on the
  floor + a top-down mini-map with wall lengths). "Close room" solves via RoomSolver.solveWalls.
* CORNERS: "Aim at a floor corner, tap Add corner" (FloorPoint). ≥3 → "Close room" (solveCorners).
* OPENINGS: pick Door / Window / Opening, mark left edge then right edge (doors/openings: FloorPoint;
  windows: SurfacePoint), RoomOps.placeOpening with default heights from settings; list with delete.
* Overlay: reticle (color by surface kind; red when tracking problem), guidance banner, tracking problem
  banner, distance warning when reticle > 4 m, captured walls drawn as lines on the floor, corners as
  dots, preview edges with length labels (Units.formatLength). Draw with Compose Canvas using
  Projection.projectSegment / worldToScreen with ArFrameState.viewProjection.
* Back = confirm discard of the room in progress. Finish = apply anchorCorrections then save.

## Editor (PlanEditorScreen)
Pan/zoom canvas of the current floor (pinch, drag, double-tap = fit). Tap a room = select (highlight +
corner handles + bottom panel: name, type, L×W, area, ceiling height, openings, actions). Drag a corner
handle to move it. Tap a wall-length label = wall panel: set length (Units.parseLength, stretch
semantics), add door/window on this wall, calibrate floor from this wall (tape), insert corner.
Room actions: move (drag), rotate ±90° / ±1°, re-solve (squaring on/off), delete. Floor tabs (add /
rename). FAB "Scan room" (→ capture) and "Draw room" (manual rectangle with typed width × length).
Undo/redo. Top bar: project name, Export, overflow (project settings: units, precision, wall thickness,
squaring, labels).

## Export (ExportScreen)
PDF (Letter/A4): page per floor — title block (project, address, date, floor), plan fitted with
margins, scale bar, total net area, disclaimer; then a room schedule page (room, L×W, area, ceiling).
PNG (≈3000 px wide), SVG (core), DXF (core), JSON backup. Share via FileProvider (cacheDir/exports) and
"Save to device" via SAF CreateDocument.

## Look
CubiCasa-like: white background, near-black thick wall bands, white room interiors, thin door leaf +
swing arc, blue window symbols, bold room names, secondary text for dimensions and area.

## Engineering rules
* No new third-party dependencies beyond the version catalog without strong reason.
* Kotlin, no `!!` on data that can legitimately be absent; never crash on bad AR data — degrade with a message.
* Core code: deterministic (seeded RNG), unit tests for every public function incl. edge cases.
* Build: `./gradlew :core:test` and `./gradlew :app:assembleDebug` must pass (needs `local.properties`
  with `sdk.dir=/opt/android-sdk`, which is gitignored).

## Round 2 additions (contracts)
* Model: `Room.ceilingEstimated`, `Calibration.origin`, `PlanSettings.defaultDoorWidth/defaultWindowWidth/showOverallDimensions`.
* Core: `WallEstimator.fromPoints(..., aimPoint)` (fit the surface the user aimed at), `SolverOptions.axisHint`
  (share one squaring axis across rooms of a session), `FloorOps.straighten/rotate/snapRoom/applyCalibration`,
  `FloorChecks` (pre-export checks, overlaps), `RoomQuality` (per-room trust grade), `RenderOptions.showOverallDimensions`
  / `highlightRoomIds`, `Dollhouse` (3D model faces).
* App: `AppContainer.branding` (`BrandingRepository`: company name/contact/logo, one-time flags),
  `ui/view3d/DollhouseScreen`, `PlanEditorScreen(onOpen3d)`; route `view3d/{projectId}/{floorId}`.

## Round 3 additions (contracts)
Driven by `docs/REVIEW-2.md` (finding ids SEAM-n, ARX-n, MATH-n, CAPX-n, APPX-n, PREM-n).
* Model: `PlanSettings.exteriorWallThickness` (default 6.5"; overall dimensions measure to its outside), `AreaCategory` + `RoomType.defaultArea`
  + `Room.areaCategory` / `Room.effectiveAreaCategory`, new room types (den, pantry, bonus room, sunroom,
  mudroom, workshop, basement, porch, deck, balcony, patio), `Opening.typed`, `Room.ceilingTyped`,
  `Room.handSetWalls` (`HandSetWall(segment, typedLength)`), `Room.frameId` / `Room.placementFrame` (single-room
  moves leave the session frame; group moves keep it), `RoomNames.unique/isDefaultName` (one numbering
  rule for capture, editor and type changes).
* Provenance rule for calibration (tape scale correction): only AR-measured values scale. Typed values
  (`ceilingTyped`, `Opening.typed` widths, `HandSetWall.typedLength`) and estimated ceilings keep their
  values; drawn (MANUAL) rooms keep their size but move with the scaled plan.
* Core: `RoomQuality.assess(room, numbering = OUTLINE|CAPTURE, unitSystem)` (capture review must use CAPTURE
  numbers, which match the wall chips), `FloorIssue.action` (`STRAIGHTEN_FLOOR`, `SNAP_ROOM`),
  `Measurements.areaSummary` (living / non-living / outdoor; never call it GLA), `DxfExporter.export(...,
  showAreas, showOverallDimensions)`, `RenderOptions.pointsPerMeter`, `RoomSolver.sharedAxis(outlines)`
  (robust shared squaring axis; never use the plain `dominantAngle` mean as a hint), `FloorOps.snapGroup` /
  `transformRooms` (move a whole scan session as one block).
* AR: `ArFrameState.localFloorY` (floor under the user; another level seen through a doorway never wins),
  `floorAutoLockable`, `cameraForward`; `ArCaptureController.setReferenceFloor(y)` (default no-op).
* App: `PlanEditorScreen(focusRoomId, onFocusRoomConsumed)`, `ExportScreen(onOpenRoom)`.
