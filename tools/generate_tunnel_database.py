#!/usr/bin/env python3
"""Build the tunnel SQLite database from Geofabrik's South Korea OSM extract.

Required external tools: osmium-tool, Node.js, and osmtogeojson.
The resulting database is a derived database under ODbL 1.0; distribute it with
the accompanying TUNNEL_DATA_LICENSE.md file.
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
import sqlite3
import subprocess
import tempfile
import urllib.request
from pathlib import Path
from typing import Any, Iterable


SOURCE_URL = "https://download.geofabrik.de/asia/south-korea-latest.osm.pbf"
ATTRIBUTION = "© OpenStreetMap contributors, ODbL 1.0"

SCHEMA = """
CREATE TABLE tunnels (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    osm_id TEXT NOT NULL,
    name TEXT,
    highway TEXT,
    tunnel TEXT,
    layer TEXT,
    oneway TEXT,
    maxspeed TEXT,
    lanes TEXT,
    start_lat REAL NOT NULL,
    start_lng REAL NOT NULL,
    end_lat REAL NOT NULL,
    end_lng REAL NOT NULL,
    min_lat REAL NOT NULL,
    min_lng REAL NOT NULL,
    max_lat REAL NOT NULL,
    max_lng REAL NOT NULL,
    length_m REAL NOT NULL,
    point_count INTEGER NOT NULL,
    coordinates_json TEXT NOT NULL
);
CREATE INDEX idx_tunnels_osm_id ON tunnels(osm_id);
CREATE INDEX idx_tunnels_name ON tunnels(name);
CREATE INDEX idx_tunnels_highway ON tunnels(highway);
CREATE INDEX idx_tunnels_length ON tunnels(length_m);
"""


def run(command: list[str], cwd: Path) -> None:
    print("+", " ".join(command))
    subprocess.run(command, cwd=cwd, check=True)


def require(command: str) -> None:
    if shutil.which(command) is None:
        raise SystemExit(f"Required command not found: {command}")


def flatten_lines(geometry: dict[str, Any]) -> list[list[float]]:
    geometry_type = geometry.get("type")
    coordinates = geometry.get("coordinates", [])
    if geometry_type == "LineString":
        return coordinates
    if geometry_type != "MultiLineString":
        return []

    points: list[list[float]] = []
    for line in coordinates:
        for point in line:
            if not points or point != points[-1]:
                points.append(point)
    return points


def distance_meters(a: list[float], b: list[float]) -> float:
    radius = 6_371_000.0
    lon_a, lat_a = map(math.radians, a[:2])
    lon_b, lat_b = map(math.radians, b[:2])
    delta_lat = lat_b - lat_a
    delta_lon = lon_b - lon_a
    h = math.sin(delta_lat / 2) ** 2 + math.cos(lat_a) * math.cos(lat_b) * math.sin(delta_lon / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(h))


def feature_rows(features: Iterable[dict[str, Any]]) -> Iterable[tuple[Any, ...]]:
    for feature in features:
        points = flatten_lines(feature.get("geometry", {}))
        if len(points) < 2:
            continue

        props = feature.get("properties", {})
        lats = [point[1] for point in points]
        lngs = [point[0] for point in points]
        length = sum(distance_meters(a, b) for a, b in zip(points, points[1:]))
        yield (
            str(feature.get("id", "")),
            props.get("name", "Unnamed tunnel"),
            props.get("highway"),
            props.get("tunnel"),
            props.get("layer"),
            props.get("oneway"),
            props.get("maxspeed"),
            props.get("lanes"),
            points[0][1], points[0][0], points[-1][1], points[-1][0],
            min(lats), min(lngs), max(lats), max(lngs),
            length, len(points), json.dumps(points, ensure_ascii=False, separators=(",", ":")),
        )


def write_database(geojson_file: Path, output_file: Path) -> int:
    with geojson_file.open(encoding="utf-8") as source:
        features = json.load(source).get("features", [])

    output_file.parent.mkdir(parents=True, exist_ok=True)
    if output_file.exists():
        output_file.unlink()
    connection = sqlite3.connect(output_file)
    try:
        connection.executescript(SCHEMA)
        connection.executemany(
            """INSERT INTO tunnels (
                osm_id, name, highway, tunnel, layer, oneway, maxspeed, lanes,
                start_lat, start_lng, end_lat, end_lng,
                min_lat, min_lng, max_lat, max_lng, length_m, point_count, coordinates_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            feature_rows(features),
        )
        count = connection.execute("SELECT COUNT(*) FROM tunnels").fetchone()[0]
        connection.commit()
        return count
    finally:
        connection.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True,
                        help="Output path for the generated SQLite database.")
    parser.add_argument("--source-url", default=SOURCE_URL,
                        help="URL of the OSM extract (.osm.pbf).")
    args = parser.parse_args()

    for command in ("osmium", "npx"):
        require(command)

    with tempfile.TemporaryDirectory(prefix="autotunnel-tunnels-") as directory:
        workdir = Path(directory)
        source_pbf = workdir / "south-korea-latest.osm.pbf"
        tunnel_pbf = workdir / "korea-tunnels.osm.pbf"
        tunnel_osm = workdir / "korea-tunnels.osm"
        geojson = workdir / "korea-tunnels.geojson"

        print(f"Downloading {args.source_url}...")
        urllib.request.urlretrieve(args.source_url, source_pbf)
        run(["osmium", "tags-filter", str(source_pbf), "wr/tunnel=yes", "-o", str(tunnel_pbf), "--overwrite"], workdir)
        run(["osmium", "cat", str(tunnel_pbf), "-o", str(tunnel_osm), "--overwrite"], workdir)
        with geojson.open("w", encoding="utf-8") as destination:
            subprocess.run(["npx", "--yes", "osmtogeojson", str(tunnel_osm)], cwd=workdir, stdout=destination, check=True)

        count = write_database(geojson, args.output)
        metadata = args.output.with_suffix(".metadata.json")
        metadata.write_text(json.dumps({
            "source_url": args.source_url,
            "filter": "wr/tunnel=yes",
            "attribution": ATTRIBUTION,
            "license": "ODbL-1.0",
            "tunnel_count": count,
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Wrote {args.output} with {count} tunnel records")


if __name__ == "__main__":
    main()
