"""Writes a made-up walk-through recording of an empty rectangular room, exactly as docs/WALKTHROUGH-FORMAT.md
describes one. It exists to test the reader and whatever is built on it against a room whose size is known.

    python make_synthetic.py out_folder [--width 4.0 --depth 3.0 --height 2.4]

The "phone" is carried once around the middle of the room at 1.4 m, upright, turning as it goes, so the
images are sideways like real ones. Depth is exact; the pictures are plain grey.
"""

from __future__ import annotations

import argparse
import io
import json
import math
import os

import numpy as np


def quaternion(r: np.ndarray):
    """(qx, qy, qz, qw) of a rotation matrix."""
    t = np.trace(r)
    if t > 0:
        s = math.sqrt(t + 1.0) * 2
        return [(r[2, 1] - r[1, 2]) / s, (r[0, 2] - r[2, 0]) / s, (r[1, 0] - r[0, 1]) / s, 0.25 * s]
    i = int(np.argmax(np.diag(r)))
    j, k = (i + 1) % 3, (i + 2) % 3
    s = math.sqrt(1.0 + r[i, i] - r[j, j] - r[k, k]) * 2
    q = [0.0, 0.0, 0.0, 0.0]
    q[i] = 0.25 * s
    q[j] = (r[j, i] + r[i, j]) / s
    q[k] = (r[k, i] + r[i, k]) / s
    q[3] = (r[k, j] - r[j, k]) / s
    return q


def camera_rotation(yaw: float, pitch: float = 0.0):
    """Sensor (image) frame and display frame of an upright phone looking along [yaw] (radians, about +Y)."""
    forward = np.array([math.sin(yaw) * math.cos(pitch), math.sin(pitch), -math.cos(yaw) * math.cos(pitch)])
    up0 = np.array([0.0, 1.0, 0.0])
    right = np.cross(forward, up0)
    right /= np.linalg.norm(right)
    up = np.cross(right, forward)
    display = np.stack([right, up, -forward], axis=1)  # columns: +X right, +Y up, +Z backwards
    sensor = np.stack([-up, right, -forward], axis=1)  # the image is the display turned a quarter
    return sensor, display


def room_depth(origin, rot, k, w, h, size):
    """Exact z-depth image (metres) of the inside of the box 0..X, 0..Y, -Z..0 seen from a camera."""
    v, u = np.mgrid[0:h, 0:w].astype(np.float64)
    dirs = np.stack([(u - k["cx"]) / k["fx"], -(v - k["cy"]) / k["fy"], -np.ones_like(u)], axis=-1) @ rot.T
    lo = np.array([0.0, 0.0, -size[2]])
    hi = np.array([size[0], size[1], 0.0])
    with np.errstate(divide="ignore", invalid="ignore"):
        t = np.where(dirs > 0, (hi - origin) / dirs, np.where(dirs < 0, (lo - origin) / dirs, np.inf))
    return t.min(axis=-1)


def write(out: str, width: float, depth: float, height: float, frames: int = 120):
    from PIL import Image

    for d in ("rgb", "depth", "conf"):
        os.makedirs(os.path.join(out, d), exist_ok=True)
    image = {"fx": 900.0, "fy": 900.0, "cx": 640.0, "cy": 360.0, "width": 1280, "height": 720}
    texture = {"fx": 1350.0, "fy": 1350.0, "cx": 960.0, "cy": 540.0, "width": 1920, "height": 1080}
    dw, dh = 160, 90
    kd = {"fx": texture["fx"] * dw / 1920, "fy": texture["fy"] * dh / 1080, "cx": texture["cx"] * dw / 1920, "cy": texture["cy"] * dh / 1080}
    grey = io.BytesIO()
    Image.new("L", (1280, 720), 128).save(grey, "JPEG", quality=50)
    lines = ["timestamp_ns,segment,tracking,tx,ty,tz,qx,qy,qz,qw"]
    records = []
    start = 1_000_000_000_000
    sub = 6  # camera frames per saved frame
    poses = {}
    for i in range(frames * sub):
        a = 2 * math.pi * i / (frames * sub)
        centre = np.array([width / 2 + 0.25 * width * math.cos(a), 1.4, -(depth / 2 + 0.25 * depth * math.sin(a))])
        yaw = 2 * a + 0.3  # looks around twice while walking once round
        sensor, display = camera_rotation(yaw, pitch=0.15 * math.sin(3 * a))
        ts = start + i * 33_333_333
        poses[ts] = (centre, sensor, display)
        lines.append(",".join([str(ts), "0", "1"] + [repr(float(x)) for x in list(centre) + quaternion(sensor)]))
    stamps = sorted(poses)
    for n in range(frames):
        ts = stamps[n * sub + 2]
        depth_ts = stamps[n * sub]  # raw depth lags the camera image
        centre, sensor, display = poses[ts]
        d_centre, d_sensor, _ = poses[depth_ts]
        z = room_depth(d_centre, d_sensor, kd, dw, dh, (width, height, depth))
        np.round(z * 1000).astype("<u2").tofile(os.path.join(out, f"depth/{n:06d}.raw16"))
        np.full((dh, dw), 255, dtype=np.uint8).tofile(os.path.join(out, f"conf/{n:06d}.raw8"))
        with open(os.path.join(out, f"rgb/{n:06d}.jpg"), "wb") as f:
            f.write(grey.getvalue())
        records.append(
            {
                "index": n, "timestampNs": ts, "segment": 0, "image": f"rgb/{n:06d}.jpg",
                "pose": [float(x) for x in list(centre) + quaternion(sensor)],
                "displayPose": [float(x) for x in list(centre) + quaternion(display)],
                "intrinsics": image, "textureIntrinsics": texture, "turnRate": 20.0, "speed": 0.5,
                "depth": f"depth/{n:06d}.raw16", "confidence": f"conf/{n:06d}.raw8",
                "depthWidth": dw, "depthHeight": dh, "depthTimestampNs": depth_ts,
            }
        )
    with open(os.path.join(out, "trajectory.csv"), "w") as f:
        f.write("\n".join(lines) + "\n")
    with open(os.path.join(out, "frames.jsonl"), "w") as f:
        for r in records:
            f.write(json.dumps(r, separators=(",", ":")) + "\n")
    floor = {"type": "HORIZONTAL_UPWARD_FACING", "pose": [width / 2, 0.0, -depth / 2, 0, 0, 0, 1], "extentX": width, "extentZ": depth,
             "polygon": [-width / 2, -depth / 2, width / 2, -depth / 2, width / 2, depth / 2, -width / 2, depth / 2]}
    with open(os.path.join(out, "planes.json"), "w") as f:
        json.dump([floor], f)
    meta = {
        "format": "floorplan-walkthrough", "version": 1, "name": os.path.basename(out.rstrip("/\\")), "startedAtMillis": 0,
        "durationSeconds": frames * sub / 30.0, "device": "synthetic", "android": "-", "app": "make_synthetic.py",
        "imageWidth": 1280, "imageHeight": 720, "jpegQuality": 50, "depthEnabled": True, "hardwareDepth": False,
        "frames": frames, "depthFrames": frames, "droppedFrames": 0, "trackingLostFrames": 0, "segments": 1, "interrupted": False,
    }
    with open(os.path.join(out, "meta.json"), "w") as f:
        json.dump(meta, f, indent=2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("out")
    parser.add_argument("--width", type=float, default=4.0)
    parser.add_argument("--depth", type=float, default=3.0)
    parser.add_argument("--height", type=float, default=2.4)
    args = parser.parse_args()
    write(args.out, args.width, args.depth, args.height)
    print(f"wrote {args.out}")
