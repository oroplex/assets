"""Reads a walk-through recording made by the FloorPlan Scan app (docs/WALKTHROUGH-FORMAT.md, version 1).

Needs numpy; Pillow only for images. Works on the zip the app shares or on an unpacked folder.
"""

from __future__ import annotations

import bisect
import io
import json
import os
import zipfile

import numpy as np

FORMAT = "floorplan-walkthrough"
VERSION = 1


def rotation(q) -> np.ndarray:
    """3x3 matrix of the quaternion (qx, qy, qz, qw): camera coordinates to world coordinates."""
    x, y, z, w = (float(v) for v in q)
    n = x * x + y * y + z * z + w * w
    if n < 1e-12:
        return np.eye(3)
    s = 2.0 / n
    return np.array(
        [
            [1 - s * (y * y + z * z), s * (x * y - z * w), s * (x * z + y * w)],
            [s * (x * y + z * w), 1 - s * (x * x + z * z), s * (y * z - x * w)],
            [s * (x * z - y * w), s * (y * z + x * w), 1 - s * (x * x + y * y)],
        ]
    )


class Recording:
    def __init__(self, path: str):
        self.path = path
        self._zip = None if os.path.isdir(path) else zipfile.ZipFile(path)
        self.names = set(self._list())
        self.meta = json.loads(self.read("meta.json")) if "meta.json" in self.names else None
        if self.meta is not None and self.meta.get("format") != FORMAT:
            raise ValueError(f"not a walk-through recording: format {self.meta.get('format')!r}")
        if self.meta is not None and int(self.meta.get("version", 0)) > VERSION:
            raise ValueError(f"recording version {self.meta.get('version')} is newer than this reader ({VERSION})")
        self.frames = []
        self.skipped_lines = 0
        if "frames.jsonl" in self.names:
            for line in self.read("frames.jsonl").decode("utf-8", "replace").splitlines():
                if not line.strip():
                    continue
                try:
                    self.frames.append(json.loads(line))
                except ValueError:
                    self.skipped_lines += 1  # a recording that was cut off ends in half a line
        self.planes = json.loads(self.read("planes.json")) if "planes.json" in self.names else []
        self.trajectory = self._trajectory()
        self._timestamps = [int(t) for t in self.trajectory[:, 0]] if len(self.trajectory) else []

    # ---- files -------------------------------------------------------------------------------

    def _list(self):
        if self._zip is not None:
            return [n for n in self._zip.namelist() if not n.endswith("/")]
        out = []
        for root, _, files in os.walk(self.path):
            for f in files:
                out.append(os.path.relpath(os.path.join(root, f), self.path).replace(os.sep, "/"))
        return out

    def read(self, name: str) -> bytes:
        if self._zip is not None:
            return self._zip.read(name)
        with open(os.path.join(self.path, name), "rb") as f:
            return f.read()

    def image(self, frame):
        """The saved camera image of a frame record, as a Pillow image (landscape, as the sensor saw it)."""
        from PIL import Image

        return Image.open(io.BytesIO(self.read(frame["image"])))

    # ---- poses -------------------------------------------------------------------------------

    def _trajectory(self) -> np.ndarray:
        if "trajectory.csv" not in self.names:
            return np.zeros((0, 10))
        rows = []
        for line in self.read("trajectory.csv").decode("utf-8", "replace").splitlines()[1:]:
            parts = line.split(",")
            if len(parts) != 10:
                continue
            try:
                rows.append([int(parts[0]), int(parts[1]), int(parts[2])] + [float(p) for p in parts[3:]])
            except ValueError:
                continue
        # float64 holds nanosecond timestamps of a session exactly (they are far below 2**53).
        return np.array(rows, dtype=np.float64).reshape(-1, 10)

    def pose_at(self, timestamp_ns: int, tolerance_ns: int = 0):
        """The camera pose (7 numbers) of the camera frame with this timestamp while tracking, else None."""
        ts = self._timestamps
        i = bisect.bisect_left(ts, int(timestamp_ns))
        best = None
        for k in (i - 1, i):
            if 0 <= k < len(ts) and abs(ts[k] - int(timestamp_ns)) <= tolerance_ns:
                if best is None or abs(ts[k] - int(timestamp_ns)) < abs(ts[best] - int(timestamp_ns)):
                    best = k
        if best is None or self.trajectory[best, 2] != 1:
            return None
        return self.trajectory[best, 3:10]

    # ---- depth -------------------------------------------------------------------------------

    def depth(self, frame):
        """(depth in metres HxW float32, confidence HxW uint8) of a frame record, or None when it has none."""
        if not frame.get("depth"):
            return None
        w, h = int(frame["depthWidth"]), int(frame["depthHeight"])
        d = np.frombuffer(self.read(frame["depth"]), dtype="<u2")
        if d.size != w * h:
            return None
        depth = d.reshape(h, w).astype(np.float32) / 1000.0
        conf = None
        if frame.get("confidence"):
            c = np.frombuffer(self.read(frame["confidence"]), dtype=np.uint8)
            conf = c.reshape(h, w) if c.size == w * h else None
        if conf is None:
            conf = np.full((h, w), 255, dtype=np.uint8)
        return depth, conf

    def depth_points(self, frame, min_confidence: int = 128, max_depth: float = 8.0, stride: int = 1):
        """World points (N x 3) of a frame's raw depth, projected with the pose the depth was computed for."""
        loaded = self.depth(frame)
        if loaded is None:
            return np.zeros((0, 3))
        depth, conf = loaded
        pose = self.pose_at(frame.get("depthTimestampNs", frame["timestampNs"]))
        if pose is None:
            return np.zeros((0, 3))
        k = frame.get("textureIntrinsics") or frame["intrinsics"]
        h, w = depth.shape
        sx, sy = w / float(k["width"]), h / float(k["height"])
        fx, fy, cx, cy = k["fx"] * sx, k["fy"] * sy, k["cx"] * sx, k["cy"] * sy
        v, u = np.mgrid[0:h:stride, 0:w:stride]
        d = depth[::stride, ::stride]
        keep = (d > 0) & (d <= max_depth) & (conf[::stride, ::stride] >= min_confidence)
        d, u, v = d[keep], u[keep].astype(np.float64), v[keep].astype(np.float64)
        cam = np.stack([(u - cx) * d / fx, -(v - cy) * d / fy, -d], axis=1)
        return cam @ rotation(pose[3:7]).T + pose[0:3]

    # ---- summary -----------------------------------------------------------------------------

    def path_length(self) -> float:
        """Metres walked, from the saved frames' positions (per segment)."""
        total = 0.0
        previous = None
        for f in self.frames:
            p = np.array(f["pose"][:3])
            if previous is not None and previous[0] == f.get("segment", 0):
                total += float(np.linalg.norm(p - previous[1]))
            previous = (f.get("segment", 0), p)
        return total

    def floor_height(self):
        """World Y of the largest upward-facing plane ARCore tracked, or None."""
        best = None
        for p in self.planes:
            if p.get("type") != "HORIZONTAL_UPWARD_FACING":
                continue
            area = float(p.get("extentX", 0)) * float(p.get("extentZ", 0))
            if best is None or area > best[0]:
                best = (area, float(p["pose"][1]))
        return None if best is None else best[1]
