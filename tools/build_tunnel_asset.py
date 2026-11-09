#!/usr/bin/env python3
"""Build the compact AutoTunnel tunnel asset (tunnels_kr.bin).

Input : an SQLite DB with the tunnel schema (table `tunnels` with
        osm_id, name, highway, tunnel, oneway, length_m, coordinates_json),
        typically produced from OpenStreetMap with tools/generate_tunnel_database.py.
Output: app/src/main/assets/tunnels_kr.bin  (format documented in TunnelStore.java)

Cleaning rules (the legacy DB fed every OSM `tunnel=*` way to the detector):
  * only tunnel=yes (drops avalanche_protector / covered / passage / parking ...)
  * drop service / living_street / non-motor ways unless named like a real tunnel
  * normalise direction: oneway=-1 is reversed, motorway(_link) implies oneway
  * merge ways that continue each other (OSM splits a bore at bridges/lanes)
  * keep merged tunnels >= MIN_LENGTH_M (runtime setting filters further)
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sqlite3
import struct
import time
from dataclasses import dataclass, field
from pathlib import Path

MAGIC = b"ATNL"
VERSION = 1
MIN_LENGTH_M = 0.0
MERGE_GAP_M = 25.0
MERGE_MAX_TURN_DEG = 40.0

HIGHWAY_CODES = {
    "motorway": 1, "motorway_link": 2, "trunk": 3, "trunk_link": 4,
    "primary": 5, "primary_link": 6, "secondary": 7, "secondary_link": 8,
    "tertiary": 9, "tertiary_link": 10, "unclassified": 11, "residential": 12,
    "service": 13, "living_street": 14,
}
EXCLUDED_UNLESS_NAMED = {"service", "living_street"}
TUNNEL_NAME_RE = re.compile(r"(터널|지하차도|지하도|굴|tunnel|underpass)", re.IGNORECASE)


def hav(lat1, lon1, lat2, lon2) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(h)))


def bearing(lat1, lon1, lat2, lon2) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def angle_diff(a, b) -> float:
    d = abs(a - b) % 360.0
    return min(d, 360.0 - d)


@dataclass
class Way:
    osm_id: int
    name: str
    highway: str
    oneway: bool
    pts: list  # [(lat, lon)]
    merged_ids: list = field(default_factory=list)

    def length(self) -> float:
        return sum(hav(a[0], a[1], b[0], b[1]) for a, b in zip(self.pts, self.pts[1:]))

    def start_bearing(self) -> float:
        return _edge_bearing(self.pts, from_start=True)

    def end_bearing(self) -> float:
        return _edge_bearing(self.pts, from_start=False)


def _edge_bearing(pts, from_start: bool, span_m: float = 40.0) -> float:
    seq = pts if from_start else list(reversed(pts))
    a = seq[0]
    acc = 0.0
    b = seq[1]
    for p, q in zip(seq, seq[1:]):
        acc += hav(p[0], p[1], q[0], q[1])
        b = q
        if acc >= span_m:
            break
    brg = bearing(a[0], a[1], b[0], b[1])
    return brg if from_start else (brg + 180.0) % 360.0


def load(db_path: Path, include_all_highways: bool = True) -> list[Way]:
    con = sqlite3.connect(db_path)
    ways = []
    stats = {"total": 0, "tag": 0, "class": 0, "geom": 0}
    for osm_id, name, highway, tunnel, oneway, coords in con.execute(
            "SELECT osm_id, name, highway, tunnel, oneway, coordinates_json FROM tunnels"):
        stats["total"] += 1
        if (tunnel or "").strip() != "yes":
            stats["tag"] += 1
            continue
        highway = (highway or "").strip()
        name = (name or "").strip()
        if name == "Unnamed tunnel":
            name = ""
        if highway not in HIGHWAY_CODES:
            stats["class"] += 1
            continue
        if not include_all_highways and highway in EXCLUDED_UNLESS_NAMED and not TUNNEL_NAME_RE.search(name):
            stats["class"] += 1
            continue
        try:
            raw = json.loads(coords)
            pts = [(float(p[1]), float(p[0])) for p in raw]
        except Exception:
            stats["geom"] += 1
            continue
        dedup = [pts[0]] if pts else []
        for p in pts[1:]:
            if hav(dedup[-1][0], dedup[-1][1], p[0], p[1]) > 0.5:
                dedup.append(p)
        if len(dedup) < 2:
            stats["geom"] += 1
            continue
        ow = (oneway or "").strip().lower()
        is_oneway = ow in ("yes", "1", "true", "-1") or (
            ow == "" and highway in ("motorway", "motorway_link"))
        if ow == "-1":
            dedup.reverse()
        try:
            oid = int(str(osm_id).split("/")[-1])
        except ValueError:
            oid = 0
        ways.append(Way(oid, name, highway, is_oneway, dedup, [oid]))
    con.close()
    print("input:", stats, "kept:", len(ways))
    return ways


def _key(p) -> tuple:
    return (round(p[0], 4), round(p[1], 4))


def merge(ways: list[Way]) -> list[Way]:
    """Greedy chain merge of consecutive tunnel ways (same directionality)."""
    alive = {id(w): w for w in ways}
    changed = True
    passes = 0
    while changed and passes < 10:
        changed = False
        passes += 1
        by_start: dict[tuple, list[Way]] = {}
        for w in alive.values():
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    k = _key(w.pts[0])
                    by_start.setdefault((round(k[0] + dx * 1e-4, 4), round(k[1] + dy * 1e-4, 4)), []).append(w)
        for w in list(alive.values()):
            if id(w) not in alive:
                continue
            end = w.pts[-1]
            best = None
            best_gap = MERGE_GAP_M
            for c in by_start.get(_key(end), []):
                if c is w or id(c) not in alive or c.oneway != w.oneway:
                    continue
                if w.name and c.name and w.name != c.name:
                    continue
                gap = hav(end[0], end[1], c.pts[0][0], c.pts[0][1])
                if gap > best_gap:
                    continue
                if angle_diff(w.end_bearing(), c.start_bearing()) > MERGE_MAX_TURN_DEG:
                    continue
                best, best_gap = c, gap
            if best is not None:
                tail = best.pts[1:] if best_gap < 1.0 else best.pts
                w.pts = w.pts + tail
                w.name = w.name or best.name
                w.merged_ids += best.merged_ids
                del alive[id(best)]
                changed = True
    print(f"merge passes={passes}: {len(ways)} -> {len(alive)}")
    return list(alive.values())


def write(ways: list[Way], out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    ways.sort(key=lambda w: (w.pts[0][0], w.pts[0][1]))
    with out.open("wb") as f:
        f.write(MAGIC)
        f.write(struct.pack(">iqi", VERSION, int(time.time() * 1000), len(ways)))
        for w in ways:
            name = w.name.encode("utf-8")[:600]
            f.write(struct.pack(">qH", w.osm_id, len(name)))
            f.write(name)
            f.write(struct.pack(">BBfi", HIGHWAY_CODES[w.highway], 1 if w.oneway else 0,
                                float(w.length()), len(w.pts)))
            for lat, lon in w.pts:
                f.write(struct.pack(">ii", int(round(lat * 1e6)), int(round(lon * 1e6))))
    print(f"wrote {out} ({out.stat().st_size / 1024:.0f} KiB, {len(ways)} tunnels)")


def main() -> None:
    repo_root = Path(__file__).resolve().parent.parent
    default_input = repo_root / "dist" / "korea_road_tunnels.sqlite"
    default_output = repo_root / "app" / "src" / "main" / "assets" / "tunnels_kr.bin"

    ap = argparse.ArgumentParser(description="Pack tunnel SQLite database into compact binary asset.")
    ap.add_argument("--input", type=Path, default=default_input,
                    help="Path to korea_road_tunnels.sqlite (default: dist/korea_road_tunnels.sqlite)")
    ap.add_argument("--output", type=Path, default=default_output,
                    help="Output path for tunnels_kr.bin (default: app/src/main/assets/tunnels_kr.bin)")
    ap.add_argument("--min-length", type=float, default=MIN_LENGTH_M,
                    help="Minimum tunnel length in meters (default: 0.0)")
    ap.add_argument("--filter-named-only", action="store_true", default=False,
                    help="Exclude unnamed service/living_street ways.")
    args = ap.parse_args()

    input_path = args.input
    if not input_path.exists():
        fallback_local = Path("korea_road_tunnels.sqlite")
        if fallback_local.exists():
            input_path = fallback_local
        else:
            raise FileNotFoundError(
                f"Input file not found at '{args.input}'. "
                "Please specify --input <path> or generate one using tools/generate_tunnel_database.py."
            )

    ways = merge(load(input_path, include_all_highways=not args.filter_named_only))
    kept = [w for w in ways if w.length() >= args.min_length]
    buckets = [(0, 30), (30, 60), (60, 100), (100, 200), (200, 500), (500, 1000), (1000, 3000), (3000, 1e9)]
    print("length buckets:", {f"{a}-{int(b) if b < 1e8 else 'inf'}": sum(1 for w in kept if a <= w.length() < b)
                              for a, b in buckets})
    print("oneway:", sum(w.oneway for w in kept), "/", len(kept))
    write(kept, args.output)


if __name__ == "__main__":
    main()
