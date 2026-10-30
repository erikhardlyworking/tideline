"""Paired nearest-versus-weighted follow-up using the frozen official campaign."""
import csv
import json
from collections import Counter

import numpy as np

from common import ROOT, RESULTS, sha256, write_json
from benchmark import Model, run_station
from interpolation import weighted_support
from report import summary


class SampleSink:
    def __init__(self, file):
        self.writer = csv.DictWriter(file, fieldnames=["provider", "id", "name", "comparison_role", "time_utc", "official_m", "model_m", "residual_m"])
        self.writer.writeheader()
        self.role = ""
        self.count = 0

    def extend(self, rows):
        for row in rows:
            self.writer.writerow({**row, "comparison_role": self.role})
            self.count += 1


def paired_summary(records, cohort, baseline_quality=None):
    paired = [r for r in records if r["cohort"] == cohort and "raw" in r["baseline"] and "raw" in r["weighted"]]
    if baseline_quality is not None:
        paired = [r for r in paired if baseline_quality == ("within_10_percent" if r["baseline"].get("raw_rmse_percent_of_range", float("inf")) <= 10 else "over_10_percent")]
    changes = [r["weighted"]["raw"]["rmse_m"] - r["baseline"]["raw"]["rmse_m"] for r in paired]
    affected = [r for r in paired if r["coefficient_changed"]]
    material = .001  # Report >1 mm RMSE changes separately from round-off/tiny effects.
    return {"paired_continuous_sites": len(paired), "baseline": summary([r["baseline"] for r in paired]),
            "weighted": summary([r["weighted"] for r in paired]),
            "improved_rmse": sum(x < -1e-10 for x in changes), "worsened_rmse": sum(x > 1e-10 for x in changes),
            "unchanged_rmse": sum(abs(x) <= 1e-10 for x in changes),
            "improved_rmse_over_1mm": sum(x < -material for x in changes),
            "worsened_rmse_over_1mm": sum(x > material for x in changes),
            "median_paired_rmse_change_cm": float(np.median(changes) * 100) if changes else None,
            "affected_continuous_sites": len(affected),
            "median_paired_rmse_change_cm_among_affected": float(np.median([r["weighted"]["raw"]["rmse_m"] - r["baseline"]["raw"]["rmse_m"] for r in affected]) * 100) if affected else None}


def main():
    model = Model()
    references_path, baseline_path = RESULTS / "references.json", RESULTS / "benchmark.json"
    references = json.loads(references_path.read_text())
    baseline = json.loads(baseline_path.read_text())
    baseline_by_id = {(s["provider"], s["id"]): s for s in baseline["locations"]}
    records, pack_counts, cell_counts = [], Counter(), Counter()
    with (RESULTS / "interpolation_samples.csv").open("w", newline="") as file:
        model.sample_rows = SampleSink(file)
        for i, station in enumerate(references["stations"]):
            original = baseline_by_id[(station["provider"], station["id"])]
            accepted = original["support"]["status"] == "supported"
            old = original if accepted else original.get("diagnostic_only", {})
            cohort = "accepted" if accepted else "diagnostic_only"
            record = {"provider": station["provider"], "id": station["id"], "name": station["name"], "cohort": cohort,
                      "baseline": old, "baseline_coverage_status": original["support"]}
            if "raw" not in old and "events" not in old:
                record["weighted"] = {"support": {"status": "unsupported", "reason": "Original site unavailable; no coverage relaxation in paired experiment"}}
                record["coefficient_changed"] = False
                records.append(record)
                continue
            support = weighted_support(model, station["lat"], station["lon"], old["support"], diagnostic=not accepted)
            if support["status"] == "supported":
                source = model.coefficients(support)
                record["coefficient_changed"] = not np.array_equal(source, model.coefficients(old["support"]))
                for band in (1, 2, 4):
                    decoded = model.coefficients(support, band)
                    if decoded is not None:
                        if not np.array_equal(decoded, source):
                            raise AssertionError(f"Weighted coefficients differ after packaging: {station['name']}, band {band}")
                        pack_counts[(cohort, band)] += 1
                cell_counts[(cohort, len(support["cells"]))] += 1
                model.sample_rows.role = cohort
                record["weighted"], _ = run_station(station, model, support_override=support)
            else:
                record["weighted"], record["coefficient_changed"] = {"support": support}, False
            records.append(record)
            print(f"{i+1}/108 {station['name']} ({cohort}): {len(support.get('cells', []))} nodes", flush=True)
    primary = [r["weighted"] for r in records if r["cohort"] == "accepted" and ("raw" in r["weighted"] or "events" in r["weighted"])]
    diagnostic = [r["weighted"] for r in records if r["cohort"] == "diagnostic_only" and ("raw" in r["weighted"] or "events" in r["weighted"])]
    result = {"configuration": {"maximum_wet_cells": 4, "distance_weight": "1/distance_km^2, normalised", "maximum_distance_km": 20,
               "shoreline_snap_limit_km": 1, "exact_node_tolerance_km": 1e-6, "candidate_limit": 64,
               "bilinear": "Retained at the one originally accepted bilinear site; replaced nearest fallback elsewhere",
               "complex_interpolation": "Weighted real and imaginary harmonic components before prediction; no averaging of wrapped phases",
               "selection": "Fixed before scoring; no distance, power or count tuned to official predictions", "same_sites_dates_datums_and_astronomy": True,
               "coverage": "Original accepted/diagnostic cohorts fixed; blocked paths remain excluded from accepted supports; one-sided geometry explicitly flagged"},
              "input_sha256": {"references.json": sha256(references_path.read_bytes()), "benchmark.json": sha256(baseline_path.read_bytes())},
              "paired_accepted": paired_summary(records, "accepted"), "paired_diagnostic": paired_summary(records, "diagnostic_only"),
              "baseline_quality_strata": {cohort: {quality: paired_summary(records, cohort, quality)
                    for quality in ("within_10_percent", "over_10_percent")} for cohort in ("accepted", "diagnostic_only")},
              "strata_definition": "Good means baseline raw RMSE <=10% of local range; poorer means >10%. Groups fixed by baseline result, not weighted result. Geographic acceptance and accuracy are separate classifications; diagnostics do not become accepted coverage.",
              "weighted_accepted_including_event_sites": summary(primary), "weighted_diagnostic_including_event_sites": summary(diagnostic),
              "weighted_continuous_samples": model.sample_rows.count,
              "cell_count_distribution": {cohort: {str(n): cell_counts[(cohort, n)] for n in range(1, 5)} for cohort in ("accepted", "diagnostic_only")},
              "positions_enclosed": {cohort: sum(r["weighted"].get("support", {}).get("position_enclosed_by_cells", False) for r in records if r["cohort"] == cohort) for cohort in ("accepted", "diagnostic_only")},
              "exact_grid_node_matches": sum(any(cell["distance_km"] <= 1e-6 for cell in r["baseline"].get("support", {}).get("cells", [])) for r in records),
              "pack_exact_matches": {cohort: {str(b): pack_counts[(cohort, b)] for b in (1, 2, 4)} for cohort in ("accepted", "diagnostic_only")},
              "locations": records}
    write_json(RESULTS / "interpolation_comparison.json", result)
    fields = ["provider", "id", "name", "cohort", "cells", "one_sided", "baseline_rmse_cm", "weighted_rmse_cm", "rmse_change_cm", "baseline_percent_of_range", "weighted_percent_of_range", "baseline_range_error_percent", "weighted_range_error_percent", "weighted_status"]
    with (RESULTS / "interpolation_comparison.csv").open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        for record in records:
            before, after = record["baseline"], record["weighted"]
            row = {key: record[key] for key in ("provider", "id", "name", "cohort")}
            row.update({"cells": len(after["support"].get("cells", [])), "one_sided": after["support"].get("one_sided_estimate", ""),
                        "baseline_percent_of_range": before.get("raw_rmse_percent_of_range", ""), "weighted_percent_of_range": after.get("raw_rmse_percent_of_range", ""),
                        "baseline_range_error_percent": before.get("events", {}).get("median_absolute_range_error_percent", ""),
                        "weighted_range_error_percent": after.get("events", {}).get("median_absolute_range_error_percent", ""), "weighted_status": after["support"]["status"]})
            if "raw" in after:
                row.update({"baseline_rmse_cm": 100 * before["raw"]["rmse_m"], "weighted_rmse_cm": 100 * after["raw"]["rmse_m"],
                            "rmse_change_cm": 100 * (after["raw"]["rmse_m"] - before["raw"]["rmse_m"])})
            writer.writerow(row)
    print(json.dumps({key: value for key, value in result.items() if key != "locations"}, indent=2), flush=True)


if __name__ == "__main__":
    main()
