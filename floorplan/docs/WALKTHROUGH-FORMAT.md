# Walk-through recording format (version 1)

A walk-through recording is what the phone hands to the computer that makes the floor plan: camera frames,
the phone's own motion tracking for every frame, and ARCore's depth where it has any. The app writes it in
**Record walk-through** (the camera icon on the project list) and packs it into one zip.

The Kotlin side is `app/src/main/java/com/oroplex/floorplan/recording/WalkthroughFormat.kt`; the reader is
`tools/walkthrough/recording.py`. Keep all three in step.

## Files

```
Walkthrough-20261125-1432.zip
  meta.json            what, when, which phone, how many frames
  frames.jsonl         one JSON object per saved camera frame, in order
  trajectory.csv       the camera pose of EVERY camera frame (about 30 a second), saved or not
  planes.json          the flat surfaces ARCore itself was tracking when the recording ended
  rgb/000000.jpg       the saved frames
  depth/000000.raw16   ARCore raw depth for some frames
  conf/000000.raw8     its confidence
```

Frames are saved when the camera has moved 4 cm or turned 3° since the last one, at most about five a
second, one every two seconds while it stands still (`KeyframePolicy`). Nothing is saved while tracking is
lost. Expect 300–900 frames and 60–250 MB for a few minutes.

## Coordinates

**World.** Metres. +Y is up (against gravity). Right-handed. The origin and the heading are wherever the
camera session started; they mean nothing.

**Pose.** Seven numbers `tx, ty, tz, qx, qy, qz, qw`. The quaternion rotates camera coordinates into world
coordinates and `t` is the camera's position: `p_world = R(q) · p_camera + t`.

**Camera frame.** The camera frame belongs to the image **as saved**. Images are saved the way the sensor
delivers them, which is landscape; the app is held upright, so they look turned on their side. In that image:
+X points right, +Y points up, and the camera looks along **−Z** (OpenGL convention).

**Pixels.** Origin top-left, u to the right, v down. With intrinsics `fx, fy, cx, cy` of the same image, a
pixel `(u, v)` whose depth along the optical axis is `d` metres is the camera point

```
x =  (u − cx) · d / fx
y = −(v − cy) · d / fy
z = −d
```

## frames.jsonl

| Field | Meaning |
| --- | --- |
| `index` | 0, 1, 2 … also the number in the file names |
| `timestampNs` | ARCore's timestamp of the camera image; the key into `trajectory.csv` |
| `segment` | Counts up each time the camera session was interrupted (the app went to the background). Poses of different segments may not share a world frame: treat each segment on its own unless you can register them |
| `image` | `rgb/000012.jpg` |
| `pose` | Camera pose for this image, as above |
| `displayPose` | The same position with the orientation ARCore reports for the phone's screen (portrait). `pose⁻¹ · displayPose` is the fixed rotation between the sideways image and an upright view: use it to turn images upright before feeding them to a model that expects that |
| `intrinsics` | `fx, fy, cx, cy, width, height` of the saved image |
| `textureIntrinsics` | Intrinsics of the picture ARCore works on internally. **Depth is aligned to this**, not to the saved image |
| `turnRate`, `speed` | Degrees per second and metres per second at this frame; frames with a high turn rate are blurred |
| `depth`, `confidence` | File names, present only when ARCore had a NEW raw depth image at this frame |
| `depthWidth`, `depthHeight` | Size of both, e.g. 160 × 90 |
| `depthTimestampNs` | Timestamp of the camera image the depth was computed for |

## Depth

`depth/…raw16` is `depthWidth × depthHeight` little-endian unsigned 16-bit values, row by row from the top:
millimetres along the optical axis, 0 = no value. `conf/…raw8` has one byte per pixel, 0 (no idea) to 255
(certain); ARCore's own advice is to ignore low values.

Two things are easy to get wrong:

1. **Intrinsics.** Scale `textureIntrinsics` to the depth image:
   `fx_d = fx · depthWidth / width`, `cx_d = cx · depthWidth / width`, and the same for y.
2. **Pose.** Raw depth is computed a little behind the camera. `depthTimestampNs` is usually older than the
   frame's `timestampNs`. Look up the pose with that timestamp in `trajectory.csv` (it is always one of the
   camera timestamps listed there) and use it; using the frame's own pose smears walls while the phone turns.

On a phone without a depth sensor this depth comes from motion and is coarse and noisy on plain walls. It is
recorded as a metric reference for the depth a model estimates from the images, not as the final answer.

## trajectory.csv

Header `timestamp_ns,segment,tracking,tx,ty,tz,qx,qy,qz,qw`, one line per camera frame. `tracking` is 0 while
ARCore had lost tracking; the pose on such a line is the last known one and must not be used.

## planes.json

A list of `{type, pose, extentX, extentZ, polygon}`. `type` is `HORIZONTAL_UPWARD_FACING` (floors, tables),
`HORIZONTAL_DOWNWARD_FACING` (ceilings) or `VERTICAL` (walls). The plane is the local XZ plane of `pose` and
its +Y axis is the normal; `polygon` is its boundary as `x0, z0, x1, z1, …` in that local frame. These are
ARCore's own guesses, useful as a cross-check (the floor height, the main wall directions).

## meta.json

`format` is `floorplan-walkthrough` and `version` 1. The rest describes the recording: `name`,
`startedAtMillis`, `durationSeconds`, `device`, `android`, `app`, `imageWidth`, `imageHeight`,
`jpegQuality`, `depthEnabled`, `hardwareDepth`, `frames`, `depthFrames`, `droppedFrames` (frames the phone
could not save in time), `trackingLostFrames`, `segments`, and `interrupted` (the app ended the recording
itself: storage, the 30 minute limit, or the screen was left).

A recording that was cut off (the app was killed) has no `meta.json` and possibly a last line of
`frames.jsonl` that is incomplete; the app's **Recover** packs it anyway, and readers should skip a line
that does not parse.

## Getting it to the computer

After **Stop and save** the app offers **Share** (Quick Share to a Windows PC, Google Drive, …) and
**Downloads**, which copies the zip to `Download/FloorPlanScan/` on the phone, where a computer sees it
over a USB cable. Then:

```
python tools/walkthrough/inspect_recording.py Walkthrough-20261125-1432.zip
```

prints what is in it and writes `…-overview.png`: the path walked and every depth point seen from above.

```
python tools/walkthrough/build_plan.py Walkthrough-20261125-1432.zip
```

makes the floor plan: `…-plan.json`, which the app opens with **Import backup**, and `…-plan.png`.
