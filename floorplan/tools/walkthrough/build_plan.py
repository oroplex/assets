"""Turns a walk-through recording into a floor plan the FloorPlan Scan app can open.

    python build_plan.py Walkthrough-20261125-1432.zip [--out plan.json] [--picture plan.png]

Open the result on the phone with "Import backup" on the project list.

This is the baseline: it uses only the depth the phone recorded, no model and no GPU. A depth model that
looks at the pictures can replace `points_from_recording` without touching the rest.

How it works
1. Every depth pixel becomes a point in the room (the pose and intrinsics come with the recording). Points
   are thinned to one per 3 cm cube, so a wall looked at for a long time counts no more than one glanced at.
2. The floor and the ceiling are the two heights where most points with a level surface are.
3. Walls are looked for in a band between them, from above. Buildings are mostly square, so the direction is
   found in which the points line up best, and walls are the lines in that direction and across it where
   points pile up (a "Manhattan" plan; walls at other angles are not found yet).
4. Every ray from the camera to a depth point passed through empty air. A cell between wall lines that was
   mostly seen through is floor you can stand on; cells are joined into one room unless a wall runs along
   most of the edge between them, so a doorway does not join two rooms and a wide opening does.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import uuid

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from recording import Recording, rotation  # noqa: E402

VOXEL = 0.03  # m: points are thinned to one per cube of this size
GRID = 0.05  # m: cell of the free-space and wall-evidence grids
MIN_WALL_LENGTH = 0.5  # m of wall evidence a line needs
WALL_REACH = 0.07  # m: evidence this close to a line belongs to it
MIN_ROOM_AREA = 1.0  # m2
WALL_BAND_LOW = 0.9  # m above the floor: over most furniture
WALL_BAND_HIGH = 2.3  # m above the floor, or just under the ceiling
EDGE_IS_WALL = 0.5  # share of an edge between two cells that must be wall to separate them
DOORWAY = 1.25  # m: a gap in a wall up to this wide is a doorway and still separates two rooms
MIN_FACE_POINTS = 40  # points seen from one side of a wall line before that side's face is trusted
CELL_IS_FREE = 0.5  # share of a cell that must have been seen through


# ---------------------------------------------------------------------------------------------------
# 1. Points


def points_from_recording(rec: Recording, min_confidence: int = 128, max_depth: float = 4.0, max_ray: float = 6.0, ray_stride: int = 4):
    """Thinned world points with the camera position each was seen from (N x 6: point x, y, z, camera x, y, z)
    and a thinner set of rays (M x 6: camera x, y, z, point x, y, z).

    Depth gets worse quickly with distance, so walls are only believed up to [max_depth] metres; that a ray
    reached further ([max_ray]) still shows the way to it was clear.
    """
    seen = {}
    rays = []
    for frame in rec.frames:
        if not frame.get("depth"):
            continue
        pose = rec.pose_at(frame.get("depthTimestampNs", frame["timestampNs"]))
        if pose is None:
            continue
        p = rec.depth_points(frame, min_confidence=min_confidence, max_depth=max_depth)
        if not len(p):
            continue
        keys = np.floor(p / VOXEL).astype(np.int64)
        packed = (keys[:, 0] + 2**20) << 42 | (keys[:, 1] + 2**20) << 21 | (keys[:, 2] + 2**20)
        _, first = np.unique(packed, return_index=True)
        for k, i in zip(packed[first].tolist(), first.tolist()):
            if k not in seen:
                seen[k] = np.concatenate([p[i], pose[0:3]])
        sparse = rec.depth_points(frame, min_confidence=min_confidence, max_depth=max_ray, stride=ray_stride)
        if len(sparse):
            rays.append(np.concatenate([np.broadcast_to(pose[0:3], sparse.shape), sparse], axis=1))
    points = np.array(list(seen.values())) if seen else np.zeros((0, 6))
    return points, (np.concatenate(rays) if rays else np.zeros((0, 6)))


# ---------------------------------------------------------------------------------------------------
# 2. Floor and ceiling


def levels(points: np.ndarray, camera_y: float, floor_hint):
    """(floor Y, ceiling Y or None): where the points below and above the camera pile up."""
    y = points[:, 1]
    bins = np.arange(y.min() - 0.05, y.max() + 0.07, 0.02)
    hist, edges = np.histogram(y, bins=bins)
    centres = (edges[:-1] + edges[1:]) / 2
    smooth = np.convolve(hist, [0.25, 0.5, 0.25], mode="same")

    def peak(mask):
        if not mask.any() or smooth[mask].max() <= 0:
            return None
        return float(centres[mask][np.argmax(smooth[mask])])

    floor = floor_hint if floor_hint is not None else peak(centres < camera_y - 0.8)
    if floor is None:
        floor = float(y.min())
    ceiling = peak(centres > max(camera_y + 0.4, floor + 2.0))
    # A ceiling is a surface: it must hold a fair share of what the floor holds.
    if ceiling is not None:
        strength = smooth[np.argmin(np.abs(centres - ceiling))]
        floor_strength = smooth[np.argmin(np.abs(centres - floor))]
        if strength < 0.15 * max(floor_strength, 1.0):
            ceiling = None
    return floor, ceiling


# ---------------------------------------------------------------------------------------------------
# 3. Walls


def main_direction(xz: np.ndarray) -> float:
    """Angle (radians, −45..45 deg) of the direction along which the wall points line up best."""
    best, best_score = 0.0, -1.0
    sample = xz if len(xz) <= 60_000 else xz[np.random.default_rng(0).choice(len(xz), 60_000, replace=False)]

    def score(theta):
        c, s = math.cos(theta), math.sin(theta)
        total = 0.0
        for v in (sample[:, 0] * c + sample[:, 1] * s, -sample[:, 0] * s + sample[:, 1] * c):
            h, _ = np.histogram(v, bins=np.arange(v.min(), v.max() + 0.04, 0.02))
            total += float((h.astype(np.float64) ** 2).sum())
        return total

    for deg in np.arange(0.0, 90.0, 1.0):
        sc = score(math.radians(deg))
        if sc > best_score:
            best, best_score = deg, sc
    for step in (0.1, 0.02):
        centre = best
        for deg in np.arange(centre - 10 * step, centre + 10.0001 * step, step):
            sc = score(math.radians(deg))
            if sc > best_score:
                best, best_score = deg, sc
    # The same walls are found at this angle and a quarter turn on: report the one nearest to no turn at all.
    best = (best + 45.0) % 90.0 - 45.0
    return math.radians(best)


def noise_scale(values: np.ndarray) -> float:
    """How thick the walls look (m, one standard deviation), from the strongest pile-ups along one direction."""
    lo = values.min()
    hist, _ = np.histogram(values, bins=np.arange(lo, values.max() + 0.02, 0.01))
    kernel = np.exp(-0.5 * (np.arange(-9, 10) / 3.0) ** 2)
    smooth = np.convolve(hist, kernel / kernel.sum(), mode="same")
    widths = []
    taken = np.zeros(len(smooth), dtype=bool)
    for i in np.argsort(-smooth)[:400]:
        if taken[max(0, i - 30): i + 31].any():
            continue
        taken[max(0, i - 30): i + 31] = True
        centre = lo + (i + 0.5) * 0.01
        near = values[np.abs(values - centre) <= 0.15]
        if len(near) < 50:
            continue
        median = np.median(near)
        widths.append(1.4826 * float(np.median(np.abs(near - median))))
        if len(widths) == 3:
            break
    return float(np.median(widths)) if widths else 0.01


class Line:
    """A wall line: where it is, where along it the wall was seen, and its two faces."""

    def __init__(self, offset, occupied, separating, low, high):
        self.offset = offset
        self.occupied = occupied  # over GRID-sized steps along the line: the wall was seen here
        self.separating = separating  # ... and here it keeps two rooms apart (see doorways_closed)
        self.low = low  # the face seen from the side of smaller coordinates, or None when never seen from there
        self.high = high


def wall_lines(along: np.ndarray, across: np.ndarray, sigma: float, viewer: np.ndarray):
    """The walls running in the `across` direction: their offsets in `along`, with their evidence.

    [sigma] is how thick walls look in this recording: pile-ups closer together than a few of those are one
    wall, not several. [viewer] is where (in `along`) each point was seen from: a wall between two rooms is
    seen from both, each room sees its own face, and the faces are a wall's thickness apart even when the
    noise blurs them into one pile-up. Returns (lines, across_lo).
    """
    lo = along.min()
    hist, _ = np.histogram(along, bins=np.arange(lo, along.max() + 0.02, 0.01))
    width = max(1.5, 100.0 * sigma)  # in 1 cm bins
    half = int(math.ceil(3 * width))
    kernel = np.exp(-0.5 * (np.arange(-half, half + 1) / width) ** 2)
    smooth = np.convolve(hist, kernel / kernel.sum(), mode="same")
    reach = max(WALL_REACH, 2.5 * sigma)
    # Two pile-ups closer than this cannot be told apart at this noise: they are one wall (often its two
    # faces, seen from the rooms either side), and its line is put between them.
    apart = int(round(100.0 * max(0.06, 4.0 * sigma)))
    face_reach = max(0.15, reach + apart / 100.0)
    across_lo = across.min()
    steps = int(math.ceil((across.max() - across_lo) / GRID)) + 1
    lines = []
    taken = np.zeros(len(smooth), dtype=bool)
    floor_level = max(3.0, 0.05 * smooth.max())
    for i in np.argsort(-smooth):
        if smooth[i] < floor_level:
            break
        if taken[max(0, i - apart): i + apart + 1].any():
            continue
        taken[max(0, i - apart): i + apart + 1] = True
        centre = lo + (i + 0.5) * 0.01
        near = np.abs(along - centre) <= reach
        if not near.any():
            continue
        offset = float(np.median(along[near]))
        near = np.abs(along - offset) <= reach
        occupied = np.zeros(steps, dtype=bool)
        occupied[np.clip(((across[near] - across_lo) / GRID).astype(int), 0, steps - 1)] = True
        # A wall is seen along a stretch. The walls running the other way cross every line and leave a blob
        # as wide as they look thick: that is not a wall along this line.
        stretches = runs(close_gaps(occupied, int(round(0.10 / GRID))))
        longest = max((b - a for a, b in stretches), default=0) * GRID
        if longest < max(MIN_WALL_LENGTH, 6.0 * sigma + 0.15):
            continue
        wide = np.abs(along - offset) <= face_reach
        faces = []
        for side in (wide & (viewer < offset - 0.10), wide & (viewer > offset + 0.10)):
            faces.append(float(np.median(along[side])) if side.sum() >= MIN_FACE_POINTS else None)
        lines.append(Line(offset, occupied, doorways_closed(occupied), faces[0], faces[1]))
    lines.sort(key=lambda line: line.offset)
    return lines, across_lo


def refine(lines, along: np.ndarray, viewer: np.ndarray, usable: np.ndarray, sigma: float):
    """Places each line and its two faces again from the points in [usable] only: those not on a wall running
    the other way. Near a corner the crossing wall's points all lie on the room's side of a line and would
    pull it into the room, the more so the noisier the depth."""
    reach = max(WALL_REACH, 2.5 * sigma)
    face_reach = max(0.15, reach + max(0.06, 4.0 * sigma))
    for line in lines:
        near = usable & (np.abs(along - line.offset) <= reach)
        if near.sum() >= MIN_FACE_POINTS:
            line.offset = float(np.median(along[near]))
        wide = usable & (np.abs(along - line.offset) <= face_reach)
        low = wide & (viewer < line.offset - 0.10)
        high = wide & (viewer > line.offset + 0.10)
        line.low = float(np.median(along[low])) if low.sum() >= MIN_FACE_POINTS else None
        line.high = float(np.median(along[high])) if high.sum() >= MIN_FACE_POINTS else None


def on_lines(values: np.ndarray, lines, reach: float) -> np.ndarray:
    """True for the points within [reach] of any of [lines] (in the coordinate the lines are offsets of)."""
    mask = np.zeros(len(values), dtype=bool)
    for line in lines:
        mask |= np.abs(values - line.offset) <= reach
    return mask


def runs(mask: np.ndarray):
    """[start, end) index pairs of the True stretches of a boolean array."""
    padded = np.concatenate([[False], mask, [False]])
    change = np.flatnonzero(padded[1:] != padded[:-1])
    return list(zip(change[0::2].tolist(), change[1::2].tolist()))


def close_gaps(mask: np.ndarray, max_gap: int, min_side: int = 1) -> np.ndarray:
    """[mask] with the False stretches of at most [max_gap] between True stretches of at least [min_side] filled."""
    out = mask.copy()
    stretches = runs(mask)
    for (a0, a1), (b0, b1) in zip(stretches, stretches[1:]):
        if b0 - a1 <= max_gap and a1 - a0 >= min_side and b1 - b0 >= min_side:
            out[a1:b0] = True
    return out


def doorways_closed(occupied: np.ndarray) -> np.ndarray:
    """Where a wall line separates two rooms: where it was seen, and across doorway-sized gaps in it."""
    solid = close_gaps(occupied, int(round(0.10 / GRID)))
    return close_gaps(solid, int(round(DOORWAY / GRID)), min_side=int(round(0.25 / GRID)))


# ---------------------------------------------------------------------------------------------------
# 4. Rooms


def free_space(rays_uw: np.ndarray, origin: np.ndarray, shape, margin: float = 0.10):
    """Grid (shape) of how many rays passed through each GRID cell; rays_uw is M x 4: camera u, w, point u, w."""
    free = np.zeros(shape, dtype=np.int32)
    if not len(rays_uw):
        return free
    a = rays_uw[:, 0:2]
    d = rays_uw[:, 2:4] - a
    length = np.linalg.norm(d, axis=1)
    keep = length > 0.15
    a, d, length = a[keep], d[keep], length[keep]
    # Stop short of the surface: the last stretch is the wall's own noise, not air.
    stop = np.clip((length - margin) / length, 0.0, 1.0)
    for f in np.linspace(0.0, 1.0, 48):
        p = a + d * (f * stop)[:, None]
        i = ((p[:, 0] - origin[0]) / GRID).astype(int)
        j = ((p[:, 1] - origin[1]) / GRID).astype(int)
        ok = (i >= 0) & (i < shape[0]) & (j >= 0) & (j < shape[1])
        np.add.at(free, (i[ok], j[ok]), 1)
    return free


def rooms_from_lines(u_lines, w_lines, u_lo, w_lo, free, origin):
    """Rectilinear room outlines in (u, w), from the wall lines and the free-space grid: (outline, cells) pairs."""
    us = [line.offset for line in u_lines]
    ws = [line.offset for line in w_lines]
    if len(us) < 2 or len(ws) < 2:
        return []
    nu, nw = len(us) - 1, len(ws) - 1

    def free_share(i, j):
        i0, i1 = int((us[i] - origin[0]) / GRID) + 1, int((us[i + 1] - origin[0]) / GRID)
        j0, j1 = int((ws[j] - origin[1]) / GRID) + 1, int((ws[j + 1] - origin[1]) / GRID)
        if i1 <= i0 or j1 <= j0:
            return 0.0
        return float((free[i0:i1, j0:j1] >= 2).mean())

    inside = np.array([[free_share(i, j) >= CELL_IS_FREE for j in range(nw)] for i in range(nu)])

    def coverage(mask, lo, a, b):
        k0, k1 = int((a - lo) / GRID), int(math.ceil((b - lo) / GRID))
        k0, k1 = max(k0, 0), min(k1, len(mask))
        return float(mask[k0:k1].mean()) if k1 > k0 else 1.0

    # Is the edge between two neighbouring cells a wall?
    def wall_between_u(i, j):  # between cell (i, j) and (i + 1, j): the line us[i + 1] over ws[j]..ws[j + 1]
        return coverage(u_lines[i + 1].separating, w_lo, ws[j], ws[j + 1]) >= EDGE_IS_WALL

    def wall_between_w(i, j):  # between cell (i, j) and (i, j + 1)
        return coverage(w_lines[j + 1].separating, u_lo, us[i], us[i + 1]) >= EDGE_IS_WALL

    label = -np.ones((nu, nw), dtype=int)
    rooms = []
    for si in range(nu):
        for sj in range(nw):
            if not inside[si, sj] or label[si, sj] >= 0:
                continue
            index = len(rooms)
            cells = []
            stack = [(si, sj)]
            label[si, sj] = index
            while stack:
                i, j = stack.pop()
                cells.append((i, j))
                for ni, nj, wall in (
                    (i + 1, j, lambda: wall_between_u(i, j)),
                    (i - 1, j, lambda: wall_between_u(i - 1, j)),
                    (i, j + 1, lambda: wall_between_w(i, j)),
                    (i, j - 1, lambda: wall_between_w(i, j - 1)),
                ):
                    if 0 <= ni < nu and 0 <= nj < nw and inside[ni, nj] and label[ni, nj] < 0 and not wall():
                        label[ni, nj] = index
                        stack.append((ni, nj))
            rooms.append(cells)

    outlines = []
    for cells in rooms:
        area = sum((us[i + 1] - us[i]) * (ws[j + 1] - ws[j]) for i, j in cells)
        if area < MIN_ROOM_AREA:
            continue
        loop = trace(set(cells))
        if loop is not None:
            outlines.append((outline_points(loop, u_lines, w_lines), cells))
    return outlines


def trace(cells: set):
    """Outer boundary of a set of grid cells: grid corners (i, j) in counter-clockwise order (u right, w up),
    one per turn of the outline. None when the cells have no simple outer boundary."""
    edges = {}
    for i, j in cells:
        corners = [(i, j), (i + 1, j), (i + 1, j + 1), (i, j + 1)]
        neighbours = [(i, j - 1), (i + 1, j), (i, j + 1), (i - 1, j)]
        for k in range(4):
            if neighbours[k] not in cells:
                edges.setdefault(corners[k], []).append(corners[(k + 1) % 4])
    loops = []
    while edges:
        start = next(iter(edges))
        loop = [start]
        current = start
        while True:
            following = edges[current].pop()
            if not edges[current]:
                del edges[current]
            if following == start:
                break
            loop.append(following)
            current = following
            if current not in edges:
                loop = None
                break
        if loop:
            loops.append(loop)
    if not loops:
        return None

    def extent(loop):
        return (max(i for i, _ in loop) - min(i for i, _ in loop)) * (max(j for _, j in loop) - min(j for _, j in loop))

    outer = max(loops, key=extent)  # holes lie inside the outer boundary's bounding box
    n = len(outer)
    turns = [outer[k] for k in range(n) if not (outer[k - 1][0] == outer[k][0] == outer[(k + 1) % n][0] or outer[k - 1][1] == outer[k][1] == outer[(k + 1) % n][1])]
    return turns if len(turns) >= 4 else None


def outline_points(loop, u_lines, w_lines):
    """The outline's corners in (u, w). Each edge lies on a wall line and takes the face of that wall the room
    sees: walking counter-clockwise the room is on the left, so an edge going up has the room at smaller u."""
    n = len(loop)
    us = [None] * n  # u of the vertical edge at each corner
    ws = [None] * n
    for k in range(n):
        (i0, j0), (i1, j1) = loop[k], loop[(k + 1) % n]
        if i0 == i1:  # an edge along w, on u-line i0
            line = u_lines[i0]
            value = (line.low if j1 > j0 else line.high)
            value = line.offset if value is None else value
            us[k] = us[(k + 1) % n] = value
        else:  # an edge along u, on w-line j0
            line = w_lines[j0]
            value = (line.high if i1 > i0 else line.low)
            value = line.offset if value is None else value
            ws[k] = ws[(k + 1) % n] = value
    return [(us[k], ws[k]) for k in range(n)]


# ---------------------------------------------------------------------------------------------------
# The whole thing


class Plan:
    def __init__(self):
        self.rooms = []  # each a list of (x, y) in plan metres, counter-clockwise (plan x = world X, y = −Z)
        self.floor_y = None
        self.ceiling_height = None
        self.angle = 0.0
        self.wall_noise = None  # how thick walls look in the depth, m (one standard deviation)
        self.notes = []
        self.debug = {}


def build(rec: Recording, min_confidence: int = 128) -> Plan:
    plan = Plan()
    points, rays = points_from_recording(rec, min_confidence=min_confidence)
    if len(points) < 500 or not rec.frames:
        plan.notes.append("The recording holds too little depth to find walls in.")
        return plan
    camera = np.array([f["pose"][:3] for f in rec.frames])
    floor, ceiling = levels(points[:, 0:3], float(np.median(camera[:, 1])), rec.floor_height())
    plan.floor_y = floor
    plan.ceiling_height = None if ceiling is None else ceiling - floor
    top = floor + WALL_BAND_HIGH if ceiling is None else min(ceiling - 0.2, floor + WALL_BAND_HIGH)
    band = points[(points[:, 1] >= floor + WALL_BAND_LOW) & (points[:, 1] <= top)]
    if len(band) < 300:
        plan.notes.append("Too few points at wall height: was the camera pointed at the walls?")
        return plan

    theta = main_direction(band[:, [0, 2]])
    plan.angle = theta
    c, s = math.cos(theta), math.sin(theta)

    def to_uw(xz):
        return np.stack([xz[:, 0] * c + xz[:, 1] * s, -xz[:, 0] * s + xz[:, 1] * c], axis=1)

    uw = to_uw(band[:, [0, 2]])
    seen_from = to_uw(band[:, [3, 5]])
    sigma = min(0.08, max(0.005, 0.5 * (noise_scale(uw[:, 0]) + noise_scale(uw[:, 1]))))
    plan.wall_noise = sigma
    u_lines, w_lo = wall_lines(uw[:, 0], uw[:, 1], sigma, seen_from[:, 0])
    w_lines, u_lo = wall_lines(uw[:, 1], uw[:, 0], sigma, seen_from[:, 1])
    reach = max(WALL_REACH, 2.5 * sigma)
    on_u, on_w = on_lines(uw[:, 0], u_lines, reach), on_lines(uw[:, 1], w_lines, reach)
    refine(u_lines, uw[:, 0], seen_from[:, 0], ~on_w, sigma)
    refine(w_lines, uw[:, 1], seen_from[:, 1], ~on_u, sigma)
    if len(u_lines) < 2 or len(w_lines) < 2:
        plan.notes.append("Fewer than two walls were found in each direction, so no room could be closed.")
        return plan

    origin = np.array([u_lo, w_lo]) - 0.5
    shape = (int((uw[:, 0].max() - origin[0] + 0.5) / GRID) + 1, int((uw[:, 1].max() - origin[1] + 0.5) / GRID) + 1)
    rays_uw = np.concatenate([to_uw(rays[:, [0, 2]]), to_uw(rays[:, [3, 5]])], axis=1) if len(rays) else np.zeros((0, 4))
    free = free_space(rays_uw, origin, shape, margin=max(0.10, 3.0 * sigma))
    outlines = rooms_from_lines(u_lines, w_lines, u_lo, w_lo, free, origin)

    # Rooms in the order they were walked into.
    path_uw = to_uw(camera[:, [0, 2]])

    def first_visit(cells, us=[line.offset for line in u_lines], ws=[line.offset for line in w_lines]):
        for n, (pu, pw) in enumerate(path_uw):
            for i, j in cells:
                if us[i] <= pu <= us[i + 1] and ws[j] <= pw <= ws[j + 1]:
                    return n
        return len(path_uw)

    for outline, _cells in sorted(outlines, key=lambda item: first_visit(item[1])):
        world = [(u * c - w * s, u * s + w * c) for u, w in outline]  # back to world x, z
        room = [(x, -z) for x, z in world]
        area = 0.5 * sum(room[k][0] * room[(k + 1) % len(room)][1] - room[(k + 1) % len(room)][0] * room[k][1] for k in range(len(room)))
        plan.rooms.append(room if area > 0 else room[::-1])
    if not plan.rooms:
        plan.notes.append("Walls were found, but no closed room: walk into each room and look at all its walls.")
    plan.debug = {"band": band, "camera": camera, "u_lines": u_lines, "w_lines": w_lines, "theta": theta}
    return plan


def area(room) -> float:
    return abs(0.5 * sum(room[k][0] * room[(k + 1) % len(room)][1] - room[(k + 1) % len(room)][0] * room[k][1] for k in range(len(room))))


def project_json(plan: Plan, name: str, now_ms: int) -> dict:
    """The plan as a FloorPlan Scan project (the format of the app's JSON backup)."""
    frame = "walkthrough-" + uuid.uuid4().hex[:12]
    rooms = []
    for n, corners in enumerate(plan.rooms, start=1):
        rooms.append(
            {
                "id": uuid.uuid4().hex,
                "name": f"Room {n}",
                "corners": [{"x": round(x, 4), "y": round(y, 4)} for x, y in corners],
                "ceilingHeight": None if plan.ceiling_height is None else round(plan.ceiling_height, 3),
                "ceilingEstimated": plan.ceiling_height is None,
                "captureMethod": "MANUAL",
                "frameId": frame,
                "warnings": ["Made from a walk-through recording: check the dimensions"],
            }
        )
    return {
        "id": uuid.uuid4().hex,
        "name": name,
        "notes": "Made from a walk-through recording. " + " ".join(plan.notes),
        "createdAt": now_ms,
        "updatedAt": now_ms,
        "floors": [{"id": uuid.uuid4().hex, "name": "Floor 1", "rooms": rooms}],
        "schemaVersion": 1,
    }


def picture(plan: Plan, out: str, px_per_m: float = 60.0):
    from PIL import Image, ImageDraw

    band, camera = plan.debug["band"], plan.debug["camera"]
    xy = np.stack([band[:, 0], -band[:, 2]], axis=1)
    path = np.stack([camera[:, 0], -camera[:, 2]], axis=1)
    everything = np.concatenate([xy, path] + [np.array(r) for r in plan.rooms])
    lo, hi = everything.min(axis=0) - 0.5, everything.max(axis=0) + 0.5
    size = (int((hi[0] - lo[0]) * px_per_m) + 1, int((hi[1] - lo[1]) * px_per_m) + 1)

    def px(p):
        return (float((p[0] - lo[0]) * px_per_m), float((hi[1] - p[1]) * px_per_m))

    image = Image.new("RGB", size, (255, 255, 255))
    draw = ImageDraw.Draw(image)
    for p in xy[:: max(1, len(xy) // 150_000)]:
        x, y = px(p)
        draw.point((x, y), fill=(150, 150, 150))
    draw.line([px(p) for p in path], fill=(120, 170, 240), width=1)
    for n, room in enumerate(plan.rooms, start=1):
        pts = [px(p) for p in room]
        draw.line(pts + [pts[0]], fill=(200, 30, 30), width=3)
        cx, cy = np.mean([p[0] for p in pts]), np.mean([p[1] for p in pts])
        draw.text((cx - 30, cy - 6), f"Room {n}: {area(room):.1f} m2", fill=(0, 0, 0))
    draw.line([(10, size[1] - 12), (10 + px_per_m, size[1] - 12)], fill=(0, 0, 0), width=3)
    draw.text((14 + px_per_m, size[1] - 20), "1 m", fill=(0, 0, 0))
    image.save(out)


def describe(plan: Plan) -> list[str]:
    lines = []
    if plan.floor_y is not None:
        lines.append(
            f"floor at {plan.floor_y:.2f} m, "
            + (f"ceiling {plan.ceiling_height:.2f} m above it" if plan.ceiling_height else "no ceiling seen")
            + f", walls turned {math.degrees(plan.angle):.1f} deg from the recording's axes"
            + (f", depth scatter on walls {plan.wall_noise * 100:.1f} cm" if plan.wall_noise is not None else "")
        )
    for n, room in enumerate(plan.rooms, start=1):
        xs, ys = [p[0] for p in room], [p[1] for p in room]
        sides = [math.dist(room[k], room[(k + 1) % len(room)]) for k in range(len(room))]
        lines.append(f"Room {n}: {area(room):.2f} m2, {len(room)} corners, walls " + ", ".join(f"{s:.2f}" for s in sides) + " m")
    lines.extend(plan.notes)
    return lines


def main(argv=None) -> int:
    import time

    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("recording", help="the zip the app shared, or an unpacked folder")
    parser.add_argument("--out", help="the project file to write (default: <recording>-plan.json)")
    parser.add_argument("--picture", help="a picture of the result (default: <recording>-plan.png)")
    parser.add_argument("--min-confidence", type=int, default=128, help="ignore depth less certain than this (0-255)")
    args = parser.parse_args(argv)
    rec = Recording(args.recording)
    plan = build(rec, min_confidence=args.min_confidence)
    for line in describe(plan):
        print(line)
    base = os.path.splitext(args.recording.rstrip("/\\"))[0]
    out = args.out or base + "-plan.json"
    name = (rec.meta or {}).get("name") or os.path.basename(base)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(project_json(plan, name, int(time.time() * 1000)), f)
    print(f"{len(plan.rooms)} room(s) -> {out}")
    if plan.debug:
        try:
            pic = args.picture or base + "-plan.png"
            picture(plan, pic)
            print(f"picture -> {pic}")
        except ImportError:
            print("Pillow is not installed, so no picture was written (pip install pillow)")
    return 0 if plan.rooms else 1


if __name__ == "__main__":
    raise SystemExit(main())
