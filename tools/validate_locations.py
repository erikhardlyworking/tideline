"""Benchmark the actual Kotlin coastal selector against the frozen official sources."""
import json
import os
import subprocess

from common import ROOT, RESULTS, write_json

import numpy as np


def main():
    from coastal import ocean_geometry
    from shapely.geometry import Point, LineString
    refs = json.loads((RESULTS / "references.json").read_text())["stations"]
    requests = []
    for station in refs:
        dates = np.concatenate([np.array(w["times"], dtype="datetime64[s]") for w in station.get("windows", [])]) if station.get("windows") else np.array(["2026-01-01T00:00"], dtype="datetime64[s]")
        requests.append({"id": station["provider"] + "/" + station["id"], "lat": station["lat"], "lon": station["lon"], "epoch_seconds": dates.astype(int).tolist()})
    request_path, output_path = RESULTS / "kotlin_location_requests.json", RESULTS / "kotlin_location_predictions.json"
    request_path.write_text(json.dumps(requests))
    environment = {**os.environ, "JAVA_HOME": str(ROOT / ".build-tools/jdk")}
    subprocess.run([str(ROOT / "tide-cli/build/install/tide-cli/bin/tide-cli"), str(ROOT / "data/packs/tideline-global.tpk"), str(request_path), str(output_path)], check=True, env=environment)
    actual = json.loads(output_path.read_text())
    ocean = ocean_geometry()
    rows, path_failures = [], []
    for prediction, station in zip(actual, refs):
        row = {"provider": station["provider"], "id": station["id"], "name": station["name"], "status": prediction["status"]}
        if prediction["status"] == "available":
            sea = (prediction["sea_lon"], prediction["sea_lat"])
            for cell in prediction["support"]:
                lon = ((cell["col"] * .125 + 180) % 360) - 180
                lat = -90 + cell["row"] * .125
                # Independent geometry audit of the actual app's selected path.
                land_km_upper_bound = LineString([sea, (lon, lat)]).difference(ocean).length * 111
                if land_km_upper_bound > .005:
                    path_failures.append({"station": station["name"], "land_km_upper_bound": land_km_upper_bound})
            row.update(method=prediction["method"], support=prediction["support"], sea_lat=prediction["sea_lat"], sea_lon=prediction["sea_lon"], shore_snap_km=prediction["shore_snap_km"])
            if station["type"] == "continuous":
                reference = np.concatenate([w["heights_m"] for w in station["windows"]])
                heights = np.asarray(prediction["heights"])
                rmse = float(np.sqrt(np.mean((heights - reference) ** 2)))
                span = float(np.mean([np.ptp(w["heights_m"]) for w in station["windows"]]))
                row.update(rmse_m=rmse, percent=100 * rmse / span if span >= .1 else None)
        else:
            row["reason"] = prediction["reason"]
        rows.append(row)
    scored = [r for r in rows if "rmse_m" in r]
    result = {"requested_locations": len(rows), "available": sum(r["status"] == "available" for r in rows), "continuous_scored": len(scored),
              "median_rmse_m": float(np.median([r["rmse_m"] for r in scored])), "median_rmse_percent": float(np.median([r["percent"] for r in scored if r["percent"] is not None])),
              "within_10_percent": sum(r["percent"] <= 10 for r in scored if r["percent"] is not None), "independent_path_audit_failures": path_failures,
              "selector": "Original double-precision shoreline polygons tiled for the JVM; geodesic local shore snapping <=1 km; complex bilinear where marine, otherwise nearest reachable wet node <=20 km. No unverified diagnostics used as widget coverage.",
              "locations": rows}
    write_json(RESULTS / "kotlin_location_validation.json", result)
    print(json.dumps({key: value for key, value in result.items() if key != "locations"}, indent=2))
    if path_failures:
        raise AssertionError("Kotlin selector crossed a mapped land barrier")


if __name__ == "__main__":
    main()
