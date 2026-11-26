"""Build the bundled offline GeoNames coastal town index (CC BY 4.0).

Inputs: cities1000.zip and admin1CodesASCII.txt from
https://download.geonames.org/export/dump/ and the existing model_geometry.npz.
No query or user location is sent anywhere by the Android search.
"""
from pathlib import Path
import gzip
import hashlib
import json
import zipfile
import numpy as np
from scipy.spatial import cKDTree

ROOT = Path(__file__).resolve().parents[1]


def xyz(lat, lon):
    lat, lon = np.deg2rad(lat), np.deg2rad(lon)
    return np.column_stack((np.cos(lat) * np.cos(lon), np.cos(lat) * np.sin(lon), np.sin(lat)))


def main():
    source = ROOT / "data/geonames"
    regions = {}
    for line in (source / "admin1CodesASCII.txt").read_text().splitlines():
        fields = line.split("\t")
        regions[fields[0]] = fields[1]
    with zipfile.ZipFile(source / "cities1000.zip") as archive:
        towns = [line.split("\t") for line in archive.read("cities1000.txt").decode().splitlines()]
    geometry = np.load(ROOT / "data/model_geometry.npz")
    y, x = np.where(geometry["band1"] & ((geometry["lat"] >= -60) & (geometry["lat"] <= 72))[:, None])
    tree = cKDTree(xyz(geometry["lat"][y], geometry["lon"][x]))
    distances, _ = tree.query(xyz([float(t[4]) for t in towns], [float(t[5]) for t in towns]))
    km = 2 * 6371.0088 * np.arcsin(np.clip(distances / 2, 0, 1))
    retained = [(t, d) for t, d in zip(towns, km) if d <= 30 and -60 <= float(t[4]) <= 72]
    retained.sort(key=lambda item: (-int(item[0][14] or 0), int(item[0][0])))
    rows = []
    for t, _ in retained:
        aliases = list(dict.fromkeys([t[2], *t[3].split(",")]))
        aliases = [a for a in aliases if a and a != t[1] and len(a) <= 80]
        rows.append("\t".join([t[0], t[1], t[8], regions.get(t[8] + "." + t[10], t[10]),
                               t[4], t[5], ",".join(aliases)]))
    output = ROOT / "app/src/main/assets/coastal-places.idx"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(gzip.compress(("\n".join(rows) + "\n").encode(), compresslevel=9, mtime=0))
    report = {"source": "GeoNames cities1000 + admin1CodesASCII", "source_url": "https://download.geonames.org/export/dump/",
              "license": "CC BY 4.0", "changes": "Towns within 30 km of a retained tide cell; population order, selected fields, gzip",
              "towns": len(rows), "bytes": output.stat().st_size,
              "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
              "source_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in source.iterdir() if p.is_file()},
              "note": "Town centres are approximate. Proximity alone does not guarantee reachable marine support; the app checks coverage on selection."}
    (ROOT / "results/place-index.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: v for k, v in report.items() if k != "source_sha256"}, indent=2))


if __name__ == "__main__":
    main()
