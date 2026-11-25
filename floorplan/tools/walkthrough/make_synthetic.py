"""Writes a made-up walk-through recording of empty rooms, exactly as docs/WALKTHROUGH-FORMAT.md describes
one. It exists to test the reader and whatever is built on it against rooms whose sizes are known.

    python make_synthetic.py out_folder [--scene room|two-rooms] [--noise 0.01]

The "phone" is carried through the rooms at 1.4 m, upright, turning as it goes, so the images are sideways
like real ones. Depth is exact unless --noise gives it an error (a fraction of the distance, per pixel); the
pictures are plain grey.
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


class Scene:
    """Vertical walls (segments in the X/Z plane, full height) between a floor at Y = 0 and a ceiling."""

    def __init__(self, walls, height, path, rooms):
        self.walls = np.array(walls, dtype=np.float64).reshape(-1, 4)  # x0, z0, x1, z1
        self.height = float(height)
        self.path = np.array(path, dtype=np.float64).reshape(-1, 2)  # way-points x, z of the walk
        self.rooms = rooms  # the true interior outlines, lists of (x, z)

    def turned(self, degrees: float) -> "Scene":
        """The same rooms turned about the vertical axis, as a building rarely lines up with the recording's axes."""
        a = math.radians(degrees)
        c, s = math.cos(a), math.sin(a)

        def turn(x, z):
            return (x * c - z * s, x * s + z * c)

        walls = [turn(x0, z0) + turn(x1, z1) for x0, z0, x1, z1 in self.walls]
        return Scene(walls, self.height, [turn(x, z) for x, z in self.path], [[turn(x, z) for x, z in room] for room in self.rooms])

    def depth(self, origin, rot, k, w, h):
        """Exact z-depth image (metres) seen from a camera at [origin] with rotation [rot]."""
        v, u = np.mgrid[0:h, 0:w].astype(np.float64)
        dirs = (np.stack([(u - k["cx"]) / k["fx"], -(v - k["cy"]) / k["fy"], -np.ones_like(u)], axis=-1) @ rot.T).reshape(-1, 3)
        with np.errstate(divide="ignore", invalid="ignore"):
            t = np.where(dirs[:, 1] < 0, -origin[1] / dirs[:, 1], np.where(dirs[:, 1] > 0, (self.height - origin[1]) / dirs[:, 1], np.inf))
            for x0, z0, x1, z1 in self.walls:
                ex, ez = x1 - x0, z1 - z0
                den = dirs[:, 0] * ez - dirs[:, 2] * ex
                tw = ((x0 - origin[0]) * ez - (z0 - origin[2]) * ex) / den
                sw = ((x0 - origin[0]) * dirs[:, 2] - (z0 - origin[2]) * dirs[:, 0]) / den
                hit = (tw > 1e-6) & (sw >= 0) & (sw <= 1)
                t = np.where(hit & (tw < t), tw, t)
        return t.reshape(h, w)


def box(x0, z0, x1, z1):
    return [(x0, z0, x1, z0), (x1, z0, x1, z1), (x1, z1, x0, z1), (x0, z1, x0, z0)]


def scene_room(width=4.0, depth=3.0, height=2.4):
    path = [(width / 2 + 0.25 * width * math.cos(a), -(depth / 2 + 0.25 * depth * math.sin(a))) for a in np.linspace(0, 2 * math.pi, 25)]
    return Scene(box(0, 0, width, -depth), height, path, [[(0, 0), (width, 0), (width, -depth), (0, -depth)]])


def scene_two_rooms(height=2.4):
    """A 4 x 3 m room and a 3 x 3 m room side by side, a 12 cm wall between them with a 0.9 m doorway."""
    t = 0.12
    walls = [
        (0, 0, 4, 0), (0, -3, 4, -3), (0, 0, 0, -3),  # room A, three outer walls
        (4, 0, 4, -1.1), (4, -2.0, 4, -3),  # A's face of the shared wall, either side of the doorway
        (4 + t, 0, 4 + t, -1.1), (4 + t, -2.0, 4 + t, -3),  # B's face
        (4, -1.1, 4 + t, -1.1), (4, -2.0, 4 + t, -2.0),  # the doorway's jambs
        (4 + t, 0, 7 + t, 0), (4 + t, -3, 7 + t, -3), (7 + t, 0, 7 + t, -3),  # room B
    ]
    path = [
        (1.0, -1.5), (2.0, -0.9), (3.0, -1.5), (2.0, -2.2), (1.2, -1.5), (3.2, -1.55),
        (4.06, -1.55), (5.0, -1.5), (5.6, -0.9), (6.3, -1.5), (5.6, -2.2), (4.9, -1.6),
        (4.06, -1.55), (3.0, -1.5), (1.0, -1.5),
    ]
    rooms = [[(0, 0), (4, 0), (4, -3), (0, -3)], [(4 + t, 0), (7 + t, 0), (7 + t, -3), (4 + t, -3)]]
    return Scene(walls, height, path, rooms)


SCENES = {"room": scene_room, "two-rooms": scene_two_rooms}


def write(out: str, scene: Scene | None = None, frames: int = 120, noise: float = 0.0, seed: int = 1, **room):
    from PIL import Image

    scene = scene or scene_room(**room)
    rng = np.random.default_rng(seed)
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
    # The walk: along the way-points at constant speed, looking around all the time.
    legs = np.linalg.norm(np.diff(scene.path, axis=0), axis=1)
    along = np.concatenate([[0.0], np.cumsum(legs)])
    turns = max(2.0, along[-1] / 2.0)
    poses = {}
    for i in range(frames * sub):
        f = i / (frames * sub)
        d = f * along[-1]
        x = float(np.interp(d, along, scene.path[:, 0]))
        z = float(np.interp(d, along, scene.path[:, 1]))
        centre = np.array([x, 1.4, z])
        sensor, display = camera_rotation(0.3 + 2 * math.pi * turns * f, pitch=0.15 * math.sin(2 * math.pi * 3 * f))
        ts = start + i * 33_333_333
        poses[ts] = (centre, sensor, display)
        lines.append(",".join([str(ts), "0", "1"] + [repr(float(x)) for x in list(centre) + quaternion(sensor)]))
    stamps = sorted(poses)
    for n in range(frames):
        ts = stamps[n * sub + 2]
        depth_ts = stamps[n * sub]  # raw depth lags the camera image
        centre, sensor, display = poses[ts]
        d_centre, d_sensor, _ = poses[depth_ts]
        z = scene.depth(d_centre, d_sensor, kd, dw, dh)
        if noise > 0:
            z = z * (1.0 + noise * rng.standard_normal(z.shape))
        np.round(np.clip(z, 0, 65.0) * 1000).astype("<u2").tofile(os.path.join(out, f"depth/{n:06d}.raw16"))
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
    xs, zs = scene.walls[:, [0, 2]], scene.walls[:, [1, 3]]
    cx, cz = (xs.min() + xs.max()) / 2, (zs.min() + zs.max()) / 2
    ex, ez = xs.max() - xs.min(), zs.max() - zs.min()
    floor = {"type": "HORIZONTAL_UPWARD_FACING", "pose": [cx, 0.0, cz, 0, 0, 0, 1], "extentX": ex, "extentZ": ez,
             "polygon": [-ex / 2, -ez / 2, ex / 2, -ez / 2, ex / 2, ez / 2, -ex / 2, ez / 2]}
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
    return scene


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("out")
    parser.add_argument("--scene", choices=sorted(SCENES), default="room")
    parser.add_argument("--frames", type=int, default=120)
    parser.add_argument("--noise", type=float, default=0.0, help="depth error per pixel as a fraction of the distance")
    parser.add_argument("--turn", type=float, default=0.0, help="turn the building by this many degrees")
    args = parser.parse_args()
    write(args.out, SCENES[args.scene]().turned(args.turn), frames=args.frames, noise=args.noise)
    print(f"wrote {args.out}")
