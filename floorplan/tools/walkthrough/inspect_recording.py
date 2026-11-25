"""Says what a walk-through recording holds and draws it from above.

    python inspect_recording.py Walkthrough-20261125-1432.zip [--out overview.png] [--min-confidence 128]

The picture shows every raw depth point (grey, darker where more of them agree) and the path walked (blue,
green dot = start, red dot = end), looking straight down, 1 px = 2 cm. Walls show as dark lines. It uses only
what the phone measured: no model, no clean-up. It is the first check that a recording is usable.
"""

from __future__ import annotations

import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from recording import Recording  # noqa: E402


def summary(rec: Recording) -> list[str]:
    lines = []
    m = rec.meta
    if m is None:
        lines.append("no meta.json: the recording was cut off (frames up to that point are still usable)")
    else:
        lines.append(f"{m.get('name')} recorded on {m.get('device')}, Android {m.get('android')}, {m.get('app')}")
        lines.append(
            f"{m.get('durationSeconds', 0):.0f} s, images {m.get('imageWidth')} x {m.get('imageHeight')}, "
            f"depth {'on' if m.get('depthEnabled') else 'OFF'}"
            f"{' (hardware sensor)' if m.get('hardwareDepth') else ' (from motion)' if m.get('depthEnabled') else ''}"
        )
        if m.get("interrupted"):
            lines.append("the app ended this recording itself (storage, time limit, or the screen was left)")
    frames = rec.frames
    with_depth = [f for f in frames if f.get("depth")]
    lines.append(f"{len(frames)} frames, {len(with_depth)} with raw depth, {rec.path_length():.1f} m walked")
    if rec.skipped_lines:
        lines.append(f"{rec.skipped_lines} unreadable line(s) in frames.jsonl were skipped")
    if len(rec.trajectory):
        lost = int((rec.trajectory[:, 2] == 0).sum())
        seconds = (rec.trajectory[-1, 0] - rec.trajectory[0, 0]) / 1e9
        lines.append(
            f"{len(rec.trajectory)} camera frames in {seconds:.0f} s, tracking lost for {lost} "
            f"({100.0 * lost / len(rec.trajectory):.1f} %)"
        )
    segments = sorted({int(f.get("segment", 0)) for f in frames})
    if len(segments) > 1:
        lines.append(f"{len(segments)} segments: the camera session was interrupted {len(segments) - 1} time(s)")
    if m is not None and m.get("droppedFrames"):
        lines.append(f"{m['droppedFrames']} frames were dropped because the phone could not save them in time")
    fast = sum(1 for f in frames if f.get("turnRate", 0) > 60)
    if frames:
        lines.append(f"{fast} frames ({100.0 * fast / len(frames):.0f} %) were taken while turning faster than 60 deg/s")
    missing = [f["image"] for f in frames if f["image"] not in rec.names]
    if missing:
        lines.append(f"{len(missing)} image file(s) are missing, first {missing[0]}")
    floor = rec.floor_height()
    walls = sum(1 for p in rec.planes if p.get("type") == "VERTICAL")
    lines.append(
        f"ARCore planes: {len(rec.planes)} ({walls} vertical)"
        + (f", floor {floor:.2f} m" if floor is not None else ", no floor plane")
        + (f", camera about {np.median([f['pose'][1] for f in frames]) - floor:.2f} m above it" if floor is not None and frames else "")
    )
    return lines


def overview(rec: Recording, out: str, min_confidence: int, px_per_m: float = 50.0, margin_m: float = 0.5):
    from PIL import Image, ImageDraw

    clouds = [rec.depth_points(f, min_confidence=min_confidence) for f in rec.frames if f.get("depth")]
    points = np.concatenate(clouds) if clouds else np.zeros((0, 3))
    path = np.array([f["pose"][:3] for f in rec.frames]) if rec.frames else np.zeros((0, 3))
    floor = rec.floor_height()
    if len(points) and floor is not None:
        # Walls only: drop the floor and what is near the ceiling or above it.
        points = points[(points[:, 1] > floor + 0.3) & (points[:, 1] < floor + 2.2)]
    everything = np.concatenate([points[:, [0, 2]], path[:, [0, 2]]]) if len(points) or len(path) else np.zeros((1, 2))
    lo = everything.min(axis=0) - margin_m
    hi = everything.max(axis=0) + margin_m
    size = np.maximum(((hi - lo) * px_per_m).astype(int) + 1, 64)
    size = np.minimum(size, 6000)

    def to_px(xz):
        # Plan view as in the app: x to the right, −z up (so z grows down the picture).
        return np.stack([(xz[:, 0] - lo[0]) * px_per_m, (xz[:, 1] - lo[1]) * px_per_m], axis=1)

    density = np.zeros((size[1], size[0]), dtype=np.float64)
    if len(points):
        p = to_px(points[:, [0, 2]]).astype(int)
        ok = (p[:, 0] >= 0) & (p[:, 0] < size[0]) & (p[:, 1] >= 0) & (p[:, 1] < size[1])
        np.add.at(density, (p[ok, 1], p[ok, 0]), 1.0)
    shade = 255 - (np.clip(np.log1p(density) / np.log1p(max(density.max(), 1.0)), 0, 1) * 255).astype(np.uint8)
    image = Image.fromarray(shade, mode="L").convert("RGB")
    draw = ImageDraw.Draw(image)
    if len(path) > 1:
        pp = [tuple(v) for v in to_px(path[:, [0, 2]])]
        draw.line(pp, fill=(30, 110, 230), width=2)
        r = 5
        draw.ellipse([pp[0][0] - r, pp[0][1] - r, pp[0][0] + r, pp[0][1] + r], fill=(40, 170, 70))
        draw.ellipse([pp[-1][0] - r, pp[-1][1] - r, pp[-1][0] + r, pp[-1][1] + r], fill=(220, 50, 50))
    # 1 m scale bar.
    draw.line([(10, size[1] - 12), (10 + px_per_m, size[1] - 12)], fill=(0, 0, 0), width=3)
    draw.text((14 + px_per_m, size[1] - 20), "1 m", fill=(0, 0, 0))
    image.save(out)
    return len(points), (hi - lo - 2 * margin_m)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("recording", help="the zip the app shared, or an unpacked folder")
    parser.add_argument("--out", help="where to write the picture (default: next to the recording)")
    parser.add_argument("--min-confidence", type=int, default=128, help="ignore depth less certain than this (0-255)")
    args = parser.parse_args(argv)
    rec = Recording(args.recording)
    for line in summary(rec):
        print(line)
    out = args.out or os.path.splitext(args.recording.rstrip("/\\"))[0] + "-overview.png"
    try:
        count, extent = overview(rec, out, args.min_confidence)
        print(f"{count} depth points over {extent[0]:.1f} m x {extent[1]:.1f} m -> {out}")
    except ImportError:
        print("Pillow is not installed, so no picture was written (pip install pillow)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
