"""Checks the reader and the plan builder against made-up rooms of known size, and the reader against a
sample the app's own code wrote.

    python test_recording.py [folder written by the app's unit tests]

No test framework needed: it stops at the first thing that is wrong.
"""

from __future__ import annotations

import os
import shutil
import sys
import tempfile
import zipfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_plan  # noqa: E402
import inspect_recording  # noqa: E402
import make_synthetic  # noqa: E402
from recording import Recording, rotation  # noqa: E402


def check_synthetic(tmp: str):
    folder = os.path.join(tmp, "Walkthrough-synthetic")
    make_synthetic.write(folder, make_synthetic.scene_room(4.0, 3.0, 2.4), frames=60)
    rec = Recording(folder)
    assert len(rec.frames) == 60 and rec.meta["frames"] == 60
    assert len(rec.trajectory) == 360 and rec.skipped_lines == 0
    assert abs(rec.floor_height() - 0.0) < 1e-9

    # Every depth point lies on the room's surfaces: the conventions of pose, intrinsics and depth agree.
    worst = 0.0
    total = 0
    for frame in rec.frames:
        p = rec.depth_points(frame)
        assert len(p) == 160 * 90, len(p)
        off = np.minimum.reduce([np.abs(p[:, 0]), np.abs(p[:, 0] - 4.0), np.abs(p[:, 1]), np.abs(p[:, 1] - 2.4), np.abs(p[:, 2]), np.abs(p[:, 2] + 3.0)])
        worst = max(worst, float(off.max()))
        total += len(p)
        assert p[:, 0].min() > -0.01 and p[:, 0].max() < 4.01 and p[:, 2].min() > -3.01 and p[:, 2].max() < 0.01
    assert worst < 0.005, f"depth points are up to {worst * 1000:.1f} mm off the room's surfaces"

    # The depth belongs to an OLDER camera frame: with the frame's own pose the walls smear.
    frame = rec.frames[10]
    assert frame["depthTimestampNs"] < frame["timestampNs"]
    wrong = dict(frame, depthTimestampNs=frame["timestampNs"])
    p = rec.depth_points(wrong)
    off = np.minimum.reduce([np.abs(p[:, 0]), np.abs(p[:, 0] - 4.0), np.abs(p[:, 1]), np.abs(p[:, 1] - 2.4), np.abs(p[:, 2]), np.abs(p[:, 2] + 3.0)])
    assert off.max() > 0.02, "the lagging depth pose should matter in this recording"
    assert rec.pose_at(frame["timestampNs"] + 1) is None and rec.pose_at(frame["timestampNs"] + 1, tolerance_ns=5) is not None

    # displayPose is the same camera turned a quarter about its viewing direction.
    r = rotation(frame["pose"][3:]).T @ rotation(frame["displayPose"][3:])
    assert abs(r[2, 2] - 1.0) < 1e-9 and abs(abs(r[0, 1]) - 1.0) < 1e-9, r

    assert 4.0 < rec.path_length() < 6.5, rec.path_length()

    # The same recording as the zip the app shares, through the command line tool.
    archive = os.path.join(tmp, "Walkthrough-synthetic.zip")
    with zipfile.ZipFile(archive, "w") as z:
        for root, _, files in os.walk(folder):
            for f in files:
                full = os.path.join(root, f)
                z.write(full, os.path.relpath(full, folder).replace(os.sep, "/"))
    zipped = Recording(archive)
    assert len(zipped.frames) == 60 and len(zipped.depth_points(zipped.frames[0])) == 160 * 90
    text = "\n".join(inspect_recording.summary(zipped))
    assert "60 frames, 60 with raw depth" in text and "camera about 1.40 m above it" in text, text
    picture = os.path.join(tmp, "overview.png")
    count, extent = inspect_recording.overview(zipped, picture, min_confidence=128)
    assert os.path.getsize(picture) > 1000 and count > 100_000
    # Seen from above, the points span the room: 4 m by 3 m.
    assert abs(extent[0] - 4.0) < 0.05 and abs(extent[1] - 3.0) < 0.05, extent

    # A recording that was cut off: no summary, half a last line.
    os.remove(os.path.join(folder, "meta.json"))
    with open(os.path.join(folder, "frames.jsonl"), "a") as f:
        f.write('{"index":60,"timestampNs":')
    cut = Recording(folder)
    assert cut.meta is None and len(cut.frames) == 60 and cut.skipped_lines == 1
    assert "cut off" in inspect_recording.summary(cut)[0]
    print(f"synthetic room: {total} depth points, worst {worst * 1000:.2f} mm off the walls: ok")


def sides(room):
    return sorted(float(np.hypot(room[k][0] - room[(k + 1) % len(room)][0], room[k][1] - room[(k + 1) % len(room)][1])) for k in range(len(room)))


def signed_area(room):
    return 0.5 * sum(room[k][0] * room[(k + 1) % len(room)][1] - room[(k + 1) % len(room)][0] * room[k][1] for k in range(len(room)))


def check_plans(tmp: str):
    """The plan builder on rooms of known size: exact depth, a turned building, and noisy depth."""
    # One room, exact depth: 4 x 3 m to the millimetre.
    folder = os.path.join(tmp, "one")
    make_synthetic.write(folder, make_synthetic.scene_room(4.0, 3.0, 2.4), frames=90)
    plan = build_plan.build(Recording(folder))
    assert len(plan.rooms) == 1, build_plan.describe(plan)
    assert np.allclose(sides(plan.rooms[0]), [3.0, 3.0, 4.0, 4.0], atol=0.005), sides(plan.rooms[0])
    assert abs(plan.ceiling_height - 2.4) < 0.02 and abs(plan.floor_y) < 0.01
    assert signed_area(plan.rooms[0]) > 0, "rooms must be counter-clockwise in plan coordinates"

    # Two rooms with a 12 cm wall and a doorway between them, turned 27 degrees: the doorway does not join
    # them, and each room ends at its own face of the wall.
    cases = [(0.0, 27.0, 0.01), (0.01, 33.0, 0.03), (0.02, -5.0, 0.04), (0.03, -14.0, 0.06)]
    for noise, turn, tolerance in cases:
        folder = os.path.join(tmp, f"two-{noise}")
        scene = make_synthetic.write(folder, make_synthetic.scene_two_rooms().turned(turn), frames=180, noise=noise)
        plan = build_plan.build(Recording(folder))
        label = f"noise {noise}, turned {turn}: " + " | ".join(build_plan.describe(plan))
        assert len(plan.rooms) == 2, label
        assert abs(np.degrees(plan.angle) - turn) < 0.3 or abs(abs(np.degrees(plan.angle) - turn) - 90) < 0.3, label
        first, second = plan.rooms  # in the order they were walked into: the 4 x 3 m room first
        assert np.allclose(sides(first), [3.0, 3.0, 4.0, 4.0], atol=tolerance), label
        assert np.allclose(sides(second), [3.0, 3.0, 3.0, 3.0], atol=tolerance), label
        assert signed_area(first) > 0 and signed_area(second) > 0, label
        # The rooms are where the true ones are, not just the right size: compare centres.
        for room, truth in zip(plan.rooms, scene.rooms):
            centre = np.mean(np.array(room), axis=0)
            true_centre = np.mean(np.array([(x, -z) for x, z in truth]), axis=0)
            assert np.linalg.norm(centre - true_centre) < tolerance, label

    # The project file the app imports.
    project = build_plan.project_json(plan, "Walkthrough-test", 1_795_617_120_000)
    assert set(project) == {"id", "name", "notes", "createdAt", "updatedAt", "floors", "schemaVersion"}
    rooms = project["floors"][0]["rooms"]
    assert [r["name"] for r in rooms] == ["Room 1", "Room 2"] and len({r["frameId"] for r in rooms}) == 1
    assert all(set(c) == {"x", "y"} for r in rooms for c in r["corners"]) and all(r["captureMethod"] == "MANUAL" for r in rooms)
    assert abs(rooms[0]["ceilingHeight"] - 2.4) < 0.03

    # Through the command line, from the zip, with the picture.
    archive = os.path.join(tmp, "two.zip")
    with zipfile.ZipFile(archive, "w") as z:
        for root, _, files in os.walk(folder):
            for f in files:
                full = os.path.join(root, f)
                z.write(full, os.path.relpath(full, folder).replace(os.sep, "/"))
    assert build_plan.main([archive]) == 0
    with open(os.path.join(tmp, "two-plan.json"), encoding="utf-8") as f:
        import json

        assert len(json.load(f)["floors"][0]["rooms"]) == 2
    assert os.path.getsize(os.path.join(tmp, "two-plan.png")) > 1000

    # A recording with nothing in it gives no rooms and says why, instead of failing.
    empty = os.path.join(tmp, "empty")
    make_synthetic.write(empty, make_synthetic.scene_room(), frames=4)
    for name in os.listdir(os.path.join(empty, "depth")):
        os.remove(os.path.join(empty, "depth", name))
    nothing = build_plan.build(Recording(empty))
    assert nothing.rooms == [] and nothing.notes, nothing.notes
    print("plan builder: one room exact, two rooms with a doorway up to 3 % depth noise: ok")


def check_app_sample(folder: str):
    """A tiny recording written by the app's Kotlin code (RecordingFormatTest): do the two sides agree?"""
    rec = Recording(folder)
    assert rec.meta["format"] == "floorplan-walkthrough" and rec.meta["frames"] == 2, rec.meta
    assert [f["index"] for f in rec.frames] == [0, 1]
    first, second = rec.frames
    assert first["image"] == "rgb/000000.jpg" and first["image"] in rec.names
    assert first["intrinsics"]["width"] == 1280 and first["textureIntrinsics"]["width"] == 1920
    assert "depth" not in second or second["depth"] is None
    depth, conf = rec.depth(first)
    assert depth.shape == (2, 3) and abs(float(depth[1, 2]) - 2.5) < 1e-6 and int(conf[0, 0]) == 200, (depth, conf)
    assert rec.depth(second) is None
    assert len(rec.trajectory) == 3 and rec.trajectory[1, 2] == 0
    pose = rec.pose_at(first["depthTimestampNs"])
    assert pose is not None and abs(pose[0] - 1.5) < 1e-6
    assert rec.pose_at(int(rec.trajectory[1, 0])) is None  # tracking was lost on that line
    # The pixel (2, 1) at 2.5 m, through the app's pose (no rotation, camera at 1.5, 1.4, -2.25).
    p = rec.depth_points(first, min_confidence=0)
    k = first["textureIntrinsics"]
    fx, cx, fy, cy = k["fx"] * 3 / 1920, k["cx"] * 3 / 1920, k["fy"] * 2 / 1080, k["cy"] * 2 / 1080
    expected = np.array([1.5 + (2 - cx) * 2.5 / fx, 1.4 - (1 - cy) * 2.5 / fy, -2.25 - 2.5])
    assert np.abs(p - expected).sum(axis=1).min() < 1e-4, (p, expected)
    assert rec.planes[0]["type"] == "VERTICAL" and len(rec.planes[0]["polygon"]) == 8
    print("sample written by the app: ok")


if __name__ == "__main__":
    tmp = tempfile.mkdtemp(prefix="walkthrough-test-")
    try:
        check_synthetic(tmp)
        check_plans(tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    if len(sys.argv) > 1:
        check_app_sample(sys.argv[1])
    print("all checks passed")
