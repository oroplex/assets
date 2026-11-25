# First test on the phone

The app has been checked on a computer in every way that does not need a camera. This first session answers
the two questions only a phone can: **does scanning work on the Galaxy S26 Ultra, and how accurate is it?**
Plan on about 30 minutes. A tape measure (or a laser measure) is required.

## Before you start
1. Install the latest build: GitHub → **Actions → FloorPlan Scan (Android)** → newest green run on the branch →
   download **FloorPlanScan-apk**, unzip, open `app-debug.apk` on the phone.
2. Open the app once. It asks for the camera and, if needed, installs **Google Play Services for AR** from the
   Play Store. If the Play Store says the phone is not supported, stop here and say so: nothing else can work.
3. Open **Open sample house** from the first screen and look around the editor, the 3D view and Export. This
   part needs no camera; anything odd here is worth a note or a screenshot.

## The room
Pick one ordinary rectangular room: lights on, at least two walls with something on them (pictures, shelves,
a window), nothing fragile underfoot. Measure all four walls with the tape **at floor level, wall to wall**
and write them down first. Measure the ceiling height too if it is easy.

| Wall | Tape | Scan 1 | Scan 2 | Scan 3 | After calibration |
| --- | --- | --- | --- | --- | --- |
| 1 |  |  |  |  |  |
| 2 |  |  |  |  |  |
| 3 |  |  |  |  |  |
| 4 |  |  |  |  |  |
| Ceiling |  |  |  |  |  |

## The scans
Create a project, tap **Scan room**, and scan the same room three times, saving each as its own room
(**Save & scan next room** twice, then **Save & finish**). For each scan:

1. Point at open floor and move slowly until the floor locks.
2. Aim straight up and tap **Measure ceiling** (try **Skip** on one of the three scans).
3. Go around the room in one direction. For each wall, stand 1.5–3 m (5–10 ft) back, aim the crosshair at the
   middle of the wall above the furniture, hold still and tap **Capture wall**.
4. **Close room**, mark one door (left edge, then right edge at the floor), **Done**, then save.

Afterwards, in the editor, tap each room and each wall and copy the lengths into the table. Then pick the
longest wall of scan 1, tap it, choose **Calibrate floor**, type its tape length, and fill the last column.

## What to send back
* The filled table (a photo of it is fine).
* **Export → JSON backup** of the project (it contains the raw measurements behind every wall).
* The scan log, if the app offers **Share scan log** in Settings → About.
* Anything that went wrong, in a sentence each: a wall that would not capture and what it looked like
  (plain, glossy, window, dark), a wrong floor or ceiling, a message that made no sense, a freeze or crash,
  a screen that looked wrong (a screenshot helps), anything that took more than two tries.
* How it felt compared with CubiCasa: slower or faster, clearer or more confusing, and where.

## If the phone is plugged into a computer
With USB debugging on and the computer attached to the session, the scan log can be read live
(`adb logcat -s FloorPlanScan`) and new builds installed directly, which makes fixing things much faster.
It is optional for this first test.

## What "good" looks like
* Every wall captures within two tries; the room closes into the right shape.
* Before calibration: each wall within about 1–3 % of the tape (2–8 cm on a 3–4 m wall).
* After calibration: clearly better than before, and the three scans agree with each other within 1–2 cm.

If the numbers are worse than that, the session has still done its job: the JSON backup and the log show
whether the error comes from the phone's depth data, its motion tracking or the app's own fitting, and that
decides what to change.
