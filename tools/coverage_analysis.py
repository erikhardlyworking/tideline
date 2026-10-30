"""Filter the interpolation experiment by the actual stored coastal coverage mask."""
import csv
import json
from collections import Counter

import numpy as np

from common import ROOT, RESULTS, sha256, write_json
from report import summary


def summarize(records):
    dense = [r for r in records if "raw" in r["baseline"] and "raw" in r["weighted"]]
    changes = [100 * (r["weighted"]["raw"]["rmse_m"] - r["baseline"]["raw"]["rmse_m"]) for r in dense]
    return {"attempted_sites": len(records), "comparable_continuous_sites": len(dense),
            "comparable_event_sites": sum("events" in r["weighted"] and "raw" not in r["weighted"] for r in records),
            "unavailable_sites": sum("events" not in r["weighted"] and "raw" not in r["weighted"] for r in records),
            "baseline": summary([r["baseline"] for r in dense]), "weighted": summary([r["weighted"] for r in dense]),
            "median_paired_rmse_change_cm": float(np.median(changes)) if changes else None,
            "improved_over_1mm": sum(delta < -.1 for delta in changes), "worsened_over_1mm": sum(delta > .1 for delta in changes),
            "one_cell_continuous_estimates": sum(len(r["weighted"]["support"]["cells"]) == 1 for r in dense)}


def main():
    geometry = np.load(ROOT / "data/model_geometry.npz")
    lat, lon = geometry["lat"], geometry["lon"]
    comparison_path = RESULTS / "interpolation_comparison.json"
    comparisons = json.loads(comparison_path.read_text())
    references = {(s["provider"], s["id"]): s for s in json.loads((RESULTS / "references.json").read_text())["stations"]}
    classified, counts = [], {band: Counter() for band in (1, 2, 4)}
    for record in comparisons["locations"]:
        station = references[(record["provider"], record["id"])]
        r = int(np.clip(np.floor((station["lat"] - lat[0]) / .125 + .5), 0, len(lat) - 1))
        c = int(np.floor(((station["lon"] - lon[0]) % 360) / .125 + .5)) % len(lon)
        classes = {}
        for band in (1, 2, 4):
            classification = "inside_retained_footprint" if geometry[f"band{band}"][r, c] else "valid_wet_cell_omitted_by_band" if geometry["wet"][r, c] else "masked_in_full_model"
            classes[str(band)] = classification
            counts[band][classification] += 1
        classified.append({**record, "requested_coordinates": {"lat": station["lat"], "lon": station["lon"]},
                           "closest_regular_grid_node": {"row": r, "col": c, "lat": float(lat[r]), "lon": float(((lon[c] + 180) % 360) - 180)},
                           "coverage_class_by_band": classes})
    outside = [r for r in classified if r["coverage_class_by_band"]["1"] != "inside_retained_footprint"]
    inside = [r for r in classified if r["coverage_class_by_band"]["1"] == "inside_retained_footprint"]
    result = {"definition": "A retained grid node represents its 0.125-degree regular-grid footprint (half a grid step each side). A requested location is outside when the closest regular-grid node is absent from the coastal mask. This tests the requested coordinate, not the already-snapped support cells.",
              "band_comparison": {str(b): dict(counts[b]) for b in (1, 2, 4)},
              "interpretation": "All outside cases in this station sample are already masked in the full EOT20 grid. There are no valid wet requested cells omitted solely by coastal subsetting, so this subset does not test extrapolation farther offshore beyond the coastal band.",
              "outside_all": summarize(outside), "outside_accepted": summarize([r for r in outside if r["cohort"] == "accepted"]),
              "outside_diagnostic": summarize([r for r in outside if r["cohort"] == "diagnostic_only"]), "inside_all": summarize(inside),
              "input_sha256": {"interpolation_comparison.json": sha256(comparison_path.read_bytes())},
              "locations": classified}
    write_json(RESULTS / "coverage_interpolation.json", result)
    fields = ["provider", "id", "name", "cohort", "requested_lat", "requested_lon", "band1", "band2", "band4", "baseline_rmse_cm", "weighted_rmse_cm", "baseline_percent", "weighted_percent", "weighted_cells"]
    with (RESULTS / "coverage_interpolation.csv").open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        for record in classified:
            row = {key: record[key] for key in ("provider", "id", "name", "cohort")}
            row.update({"requested_lat": record["requested_coordinates"]["lat"], "requested_lon": record["requested_coordinates"]["lon"],
                        **{f"band{b}": record["coverage_class_by_band"][str(b)] for b in (1, 2, 4)},
                        "weighted_cells": len(record["weighted"]["support"].get("cells", []))})
            if "raw" in record["weighted"]:
                row.update({"baseline_rmse_cm": 100 * record["baseline"]["raw"]["rmse_m"], "weighted_rmse_cm": 100 * record["weighted"]["raw"]["rmse_m"],
                            "baseline_percent": record["baseline"]["raw_rmse_percent_of_range"], "weighted_percent": record["weighted"]["raw_rmse_percent_of_range"]})
            writer.writerow(row)
    print(json.dumps({key: value for key, value in result.items() if key != "locations"}, indent=2))


if __name__ == "__main__":
    main()
