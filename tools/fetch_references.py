"""Freeze an a-priori geographic sample and cache official astronomical predictions."""
import csv
import io
import json
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from urllib.parse import quote
from zoneinfo import ZoneInfo

import numpy as np

from common import RAW, ROOT, RESULTS, fetch, utc64, write_json

WINDOWS = [(f"2026-{month:02d}-01", f"2026-{month:02d}-03") for month in (1, 4, 7, 10)]
NOAA_META = "https://api.tidesandcurrents.noaa.gov/mdapi/prod/webapi/"
NOAA_DATA = "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter"
KARTVERKET = "https://vannstand.kartverket.no/tideapi.php"
LINZ_PORTS = ["Auckland", "Wellington", "Lyttelton", "Nelson", "Bluff", "Port Taranaki", "Tauranga", "Napier"]


def select_stations():
    catalog = json.loads(fetch(NOAA_META + "stations.json", params={"type": "tidepredictions", "units": "metric"}).read_text())
    candidates = sorted([s for s in catalog["stations"] if s["type"] == "R"], key=lambda s: s["id"])
    # IDs are resolved and checked against the downloaded official catalog.
    seeds = ["9414290", "9447130", "9410170", "1612340", "8443970", "8724580", "8771450"]
    chosen = []
    for sid in seeds:
        matches = [i for i, s in enumerate(candidates) if s["id"] == sid]
        if len(matches) != 1:
            raise ValueError(f"Ambiguous/unavailable NOAA seed: {sid}, {matches}")
        chosen.append(matches[0])
    lat = np.radians([s["lat"] for s in candidates])
    lon = np.radians([s["lng"] for s in candidates])
    xyz = np.column_stack((np.cos(lat) * np.cos(lon), np.cos(lat) * np.sin(lon), np.sin(lat)))
    while len(chosen) < 67:
        minimum_chord = np.min(np.linalg.norm(xyz[:, None, :] - xyz[chosen][None, :, :], axis=2), axis=1)
        minimum_chord[chosen] = -1
        chosen.append(int(np.argmax(minimum_chord)))
    stations = [{"provider": "NOAA", "id": candidates[i]["id"], "name": candidates[i]["name"],
                 "lat": candidates[i]["lat"], "lon": candidates[i]["lng"],
                 "state": candidates[i]["state"], "type": "continuous"} for i in chosen]
    root = ET.parse(fetch(KARTVERKET, params={"tide_request": "stationlist"})).getroot()
    for s in root.findall(".//location"):
        if s.get("type") == "PERM":
            stations.append({"provider": "Kartverket", "id": s.get("code"), "name": s.get("name"),
                             "lat": float(s.get("latitude")), "lon": float(s.get("longitude")), "type": "continuous"})
    return stations


def noaa_station(station):
    sid = station["id"]
    datum_file = fetch(NOAA_META + f"stations/{sid}/datums.json", params={"units": "metric"})
    datums = json.loads(datum_file.read_text())
    data = []
    for start, end in WINDOWS:
        end_inclusive = str(np.datetime64(end) - np.timedelta64(1, "D"))
        params = {"product": "predictions", "application": "coastal_tide_feasibility", "begin_date": start.replace("-", ""),
                  "end_date": end_inclusive.replace("-", ""), "datum": "MSL", "station": sid,
                  "time_zone": "gmt", "units": "metric", "interval": "6", "format": "json"}
        path = fetch(NOAA_DATA, params=params)
        obj = json.loads(path.read_text())
        if "predictions" not in obj:
            raise ValueError(f"NOAA {sid}: {obj}")
        times = [np.datetime64(p["t"].replace(" ", "T"), "s") for p in obj["predictions"]]
        data.append({"start": start, "end": end, "times": [str(t) for t in times],
                     "heights_m": [float(p["v"]) for p in obj["predictions"]], "raw_file": str(path.relative_to(RAW.parent.parent))})
    correction = json.loads((ROOT / "tools/source_corrections.json").read_text()).get("NOAA:" + sid)
    if correction:
        fetch(correction["source_url"])
        station = {**station, "catalog_coordinates": {"lat": station["lat"], "lon": station["lon"]},
                   "lat": correction["latitude"], "lon": correction["longitude"], "source_coordinate_correction": correction}
    return {**station, "datum": "MSL (local NOAA tidal epoch; not a demonstrated common physical zero with EOT20)",
            "datum_metadata": datums, "reference_resolution_m": 0.001, "interval_minutes": 6, "windows": data}


def norway_all(stations):
    by_id = {s["id"]: {**s, "datum": "MSL (Kartverket local epoch; not a demonstrated common physical zero with EOT20)",
                         "reference_resolution_m": 0.001, "interval_minutes": 10, "windows": []} for s in stations}
    for start, end in WINDOWS:
        params = {"tide_request": "stationdata", "stationcode": ",".join(by_id), "refcode": "msl", "datatype": "pre",
                  "fromtime": start + "T00:00", "totime": end + "T00:00", "interval": "10", "tzone": "0", "dst": "0", "lang": "en"}
        path = fetch(KARTVERKET, params=params)
        root = ET.parse(path).getroot()
        seen = set()
        for loc in root.findall(".//stationdata/location"):
            sid = loc.get("code")
            if sid not in by_id:
                continue
            data = loc.find("data[@type='prediction']")
            if data is None or data.get("unit") != "cm" or data.get("reflevelcode") != "MSL":
                raise ValueError(f"Unexpected Kartverket units/type/datum at {sid}")
            points = [(utc64(p.get("time")), float(p.get("value")) / 100) for p in data.findall("waterlevel") if p.get("flag") == "pre"]
            points = [(t, v) for t, v in points if np.datetime64(start) <= t < np.datetime64(end)]
            by_id[sid]["windows"].append({"start": start, "end": end, "times": [str(t) for t, _ in points],
                                         "heights_m": [v for _, v in points], "raw_file": str(path.relative_to(RAW.parent.parent))})
            seen.add(sid)
        for sid in by_id.keys() - seen:
            by_id[sid].setdefault("source_errors", []).append(f"No official predictions in {start}")
    return list(by_id.values())


def parse_coord(value):
    import re
    match = re.search(r"(\d+)°(\d+(?:\.\d+)?)['′]([NSEW])", value)
    if not match:
        raise ValueError(value)
    return (float(match[1]) + float(match[2]) / 60) * (-1 if match[3] in "SW" else 1)


def linz_station(port):
    url = "https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/" + quote(port + " 2026.csv")
    path = fetch(url)
    rows = list(csv.reader(io.StringIO(path.read_text(encoding="utf-8-sig"))))
    lat, lon = parse_coord(rows[0][2]), parse_coord(rows[0][3].strip())
    if "Local Std or Daylight Time" not in rows[2][0] or "metres" not in rows[2][1]:
        raise ValueError(f"Unrecognised LINZ convention: {rows[:3]}")
    events = []
    for row in rows[3:]:
        if not row or not row[0].isdigit():
            continue
        for i in range(4, len(row) - 1, 2):
            if row[i].strip():
                dt = datetime(int(row[3]), int(row[2]), int(row[0]), *map(int, row[i].split(":")), tzinfo=ZoneInfo("Pacific/Auckland"))
                events.append({"time": dt.astimezone(timezone.utc).replace(tzinfo=None).isoformat(),
                               "local_time": dt.isoformat(), "height_m": float(row[i + 1])})
    events.sort(key=lambda e: e["time"])
    for i, event in enumerate(events):
        neighbours = [events[j]["height_m"] for j in (i - 1, i + 1) if 0 <= j < len(events)]
        event["kind"] = "H" if event["height_m"] > max(neighbours) else "L" if event["height_m"] < min(neighbours) else "ambiguous"
    return {"provider": "LINZ", "id": rows[0][0], "name": rows[0][1], "lat": lat, "lon": lon,
            "type": "events", "datum": "LINZ tide-table datum (no absolute EOT20 datum alignment claimed)",
            "reference_resolution_m": 0.1, "reference_resolution_minutes": 1,
            "constituent_reference_date": rows[1], "time_conversion": "Pacific/Auckland IANA timezone; local standard/daylight time",
            "events": events, "raw_file": str(path.relative_to(RAW.parent.parent))}


def main():
    stations = select_stations()
    write_json(RESULTS / "selected_stations.json", {"selection": "67 NOAA reference stations: seven named seeds then spherical farthest-point sampling; all 33 Norwegian permanent gauges. Selected before viewing EOT20 support/errors.",
                                                   "windows_utc": WINDOWS, "stations": stations, "extra_linz_ports": LINZ_PORTS})
    print(f"Selected {len(stations)} core locations before examining model data", flush=True)
    references, failures = [], []
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = {pool.submit(noaa_station, s): s for s in stations if s["provider"] == "NOAA"}
        futures[pool.submit(norway_all, [s for s in stations if s["provider"] == "Kartverket"])] = {"provider": "Kartverket", "name": "all"}
        for port in LINZ_PORTS:
            futures[pool.submit(linz_station, port)] = {"provider": "LINZ", "name": port}
        for future in as_completed(futures):
            station = futures[future]
            try:
                result = future.result()
                references.extend(result if isinstance(result, list) else [result])
                print(f"Fetched {station['provider']} {station['name']}", flush=True)
            except Exception as exc:
                failures.append({**station, "error": str(exc)})
                print(f"FAILED {station['provider']} {station['name']}: {exc}", flush=True)
    references.sort(key=lambda s: (s["provider"], s["id"]))
    write_json(RESULTS / "references.json", {"windows_utc": WINDOWS, "stations": references, "failures": failures})
    print(f"Saved {len(references)} locations; {len(failures)} source failures", flush=True)


if __name__ == "__main__":
    main()
