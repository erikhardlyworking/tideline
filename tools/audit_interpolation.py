"""Recompute weighted campaign scores from saved heights and verify paired eligibility."""
import csv
import json
from collections import Counter, defaultdict

import numpy as np

from benchmark import Model, errors
from common import RESULTS, sha256, write_json


def main():
    path = RESULTS / "interpolation_comparison.json"
    result = json.loads(path.read_text())
    for filename, digest in result["input_sha256"].items():
        if sha256((RESULTS / filename).read_bytes()) != digest:
            raise AssertionError(f"Original campaign input changed: {filename}")
    samples = defaultdict(list)
    with (RESULTS / "interpolation_samples.csv").open() as file:
        for row in csv.DictReader(file):
            samples[(row["provider"], row["id"])].append((float(row["model_m"]), float(row["official_m"])))
    model, compared, matched_samples, bands, samples_by_cohort = Model(), 0, 0, Counter(), Counter()
    for record in result["locations"]:
        before, after = record["baseline"], record["weighted"]
        expected = "raw" in before or "events" in before
        actual = "raw" in after or "events" in after
        if actual != expected:
            raise AssertionError(f"Weighted campaign changed paired availability: {record['name']}")
        if not actual:
            continue
        compared += 1
        cells = after["support"]["cells"]
        assert len(cells) <= 4 and all(0 < c["weight"] <= 1 and c["distance_km"] <= 20 for c in cells)
        assert abs(sum(c["weight"] for c in cells) - 1) < 1e-12
        full = model.coefficients(after["support"])
        for band in (1, 2, 4):
            decoded = model.coefficients(after["support"], band)
            if decoded is not None:
                np.testing.assert_array_equal(full, decoded)
                bands[(record["cohort"], band)] += 1
        if "raw" not in after:
            continue
        values = np.asarray(samples[(record["provider"], record["id"])])
        assert len(values) == before["samples"] == after["samples"]
        recomputed = errors(values[:, 0], values[:, 1])
        for metric, value in recomputed.items():
            assert abs(value - after["raw"][metric]) < 1e-12
        matched_samples += len(values)
        samples_by_cohort[record["cohort"]] += len(values)
        assert [w["start"] for w in before["windows"]] == [w["start"] for w in after["windows"]]
        assert [w["samples"] for w in before["windows"]] == [w["samples"] for w in after["windows"]]
    assert compared == 103 and matched_samples == result["weighted_continuous_samples"] == 159360
    output = {"all_checks_passed": True, "paired_locations": compared, "same_official_samples": matched_samples,
              "sample_counts_by_cohort": dict(samples_by_cohort), "no_pair_dropped_or_promoted_from_diagnostic": True,
              "csv_raw_metrics_independently_recomputed": True, "positive_normalized_weights_and_distance_limits": True,
              "weighted_coefficients_equal_full_source_by_band": {cohort: {str(b): bands[(cohort, b)] for b in (1, 2, 4)} for cohort in ("accepted", "diagnostic_only")},
              "paired_input_hashes_verified": True, "lossless_packaging_height_error_m": 0.}
    write_json(RESULTS / "interpolation_audit.json", output)
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
