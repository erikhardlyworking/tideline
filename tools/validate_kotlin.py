"""Exercise the production Kotlin/JVM engine against pinned pyTMD and cached authorities."""
import json
import os
import subprocess

from common import ROOT, RESULTS, write_json
from benchmark import predict, Model

import numpy as np


def main():
    rng = np.random.default_rng(20261030)
    model = Model()
    requests, expected = [], []
    geometry = np.load(ROOT / "data/model_geometry.npz")
    rows, cols = np.where(geometry["band1"])
    for i, index in enumerate(rng.choice(len(rows), 100, replace=False)):
        row, col = int(rows[index]), int(cols[index])
        # Whole supported date range, leap years, UTC boundaries and fractional days.
        seconds = rng.integers(np.datetime64("2020-01-02", "s").astype(int), np.datetime64("2040-12-30", "s").astype(int), size=200)
        seconds[:8] = np.array(["2026-01-01T00:00:00", "2026-04-01T00:00:00", "2026-07-01T00:00:00", "2026-10-01T00:00:00", "2028-02-29T23:59:59", "2032-02-29T00:00:01", "2036-12-31T23:59:59", "2040-01-01T00:00:01"], dtype="datetime64[s]").astype(int)
        cells = [{"row": row, "col": col, "weight": 1.}]
        support = {"cells": cells}
        truth = predict(model.coefficients(support), seconds.astype("datetime64[s]"))
        requests.append({"id": f"math-{i}", "cells": cells, "epoch_seconds": seconds.tolist()})
        expected.append(truth)
    baseline = json.loads((RESULTS / "benchmark.json").read_text())
    references = {(s["provider"], s["id"]): s for s in json.loads((RESULTS / "references.json").read_text())["stations"]}
    external = []
    for station in baseline["locations"]:
        result = station if "raw" in station else station.get("diagnostic_only", {})
        if "raw" not in result:
            continue
        ref = references[(station["provider"], station["id"])]
        dates = np.concatenate([np.asarray(w["times"], dtype="datetime64[s]") for w in ref["windows"]])
        heights = np.concatenate([w["heights_m"] for w in ref["windows"]])
        support = result["support"]
        requests.append({"id": f"official-{station['provider']}-{station['id']}", "cells": support["cells"], "epoch_seconds": dates.astype(int).tolist()})
        expected.append(predict(model.coefficients(support), dates))
        external.append((result, heights))
    request_path = RESULTS / "kotlin_requests.json"
    request_path.write_text(json.dumps(requests))
    environment = {**os.environ, "JAVA_HOME": str(ROOT / ".build-tools/jdk")}
    subprocess.run([str(ROOT / "tide-cli/build/install/tide-cli/bin/tide-cli"), str(ROOT / "data/packs/tideline-global.tpk"), str(request_path), str(RESULTS / "kotlin_predictions.json")], env=environment, check=True)
    actual = json.loads((RESULTS / "kotlin_predictions.json").read_text())
    differences = np.concatenate([np.abs(np.array(a["heights"]) - e) for a, e in zip(actual, expected)])
    maximum = float(differences.max())
    summary = {"maximum_height_difference_m": maximum, "p99_height_difference_m": float(np.percentile(differences, 99)),
               "height_comparisons": len(differences), "random_cells": 100, "tested_date_range": ["2020-01-02", "2040-12-30"],
               "reference": "pyTMD 2.2.9 with matching source cells, FES nodal factors and short-period minor inference", "target_m": .001,
               "passed": maximum <= .001, "official_continuous_sites": len(external), "official_samples": sum(len(x[1]) for x in external)}
    external_rows = []
    for prediction, (station, reference) in zip(actual[100:], external):
        residual = np.array(prediction["heights"]) - reference
        external_rows.append({"provider": station["provider"], "id": station["id"], "name": station["name"],
                              "kotlin_raw_rmse_m": float(np.sqrt(np.mean(residual ** 2))), "python_raw_rmse_m": station["raw"]["rmse_m"],
                              "normalized_percent": float(np.sqrt(np.mean(residual ** 2)) / station["mean_reference_48h_range_m"] * 100)})
    summary["official_results"] = external_rows
    write_json(RESULTS / "kotlin_validation.json", summary)
    print(json.dumps({k: v for k, v in summary.items() if k != "official_results"}, indent=2))
    if maximum > .001:
        raise AssertionError("Production engine does not meet the 1 mm reference agreement target")


if __name__ == "__main__":
    main()
