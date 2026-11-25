"""Checks the reader against a made-up room of known size, and against a sample the app's own code wrote.

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
import inspect_recording  # noqa: E402
import make_synthetic  # noqa: E402
from recording import Recording, rotation  # noqa: E402


def check_synthetic(tmp: str):
    folder = os.path.join(tmp, "Walkthrough-synthetic")
    make_synthetic.write(folder, width=4.0, depth=3.0, height=2.4, frames=60)
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
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    if len(sys.argv) > 1:
        check_app_sample(sys.argv[1])
    print("all checks passed")
