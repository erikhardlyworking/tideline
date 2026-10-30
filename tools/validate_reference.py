"""Compare our data conversion with pinned pyTMD's own EOT20 reader/predictor."""
import json
from pathlib import Path

from common import ROOT, RESULTS, write_json
from coastal import MODEL, NAMES, complex_coefficients
from benchmark import predict

import numpy as np
import pyTMD
import timescale


def main():
    geometry = np.load(ROOT / "data/model_geometry.npz")
    ap = np.load(ROOT / "data/source_ap.npy", mmap_mode="r")
    rows, cols = np.where(geometry["band4"])
    rng = np.random.default_rng(20261030)
    selected = rng.choice(len(rows), 100, replace=False)
    rr, cc = rows[selected], cols[selected]
    source = complex_coefficients(ap[:, :, rr, cc]).T
    independent_reader = []
    for name in NAMES:
        path = MODEL / (name.upper() + "_ocean_eot20.nc")
        hc, lon, lat = pyTMD.io.FES.read_netcdf_file(path, version="EOT20", type="z")
        independent_reader.append(hc[rr, cc].filled(np.nan) * .01)
    independent_reader = np.stack(independent_reader, axis=1)
    if not np.isfinite(independent_reader).all():
        raise ValueError("Unexpected pyTMD mask at selected reference cells")
    dates = np.concatenate([np.arange(np.datetime64(f"2026-{month:02d}-01T00:00"), np.datetime64(f"2026-{month:02d}-03T00:00"), np.timedelta64(1, "h")) for month in (1, 4, 7, 10)])
    ts = timescale.time.Timescale().from_datetime(dates)
    differences = []
    for own, theirs in zip(source, independent_reader):
        actual = predict(own, dates)
        reference = pyTMD.predict.time_series(ts.tide, np.ma.array(theirs[None], mask=False), NAMES, corrections="FES", deltat=ts.tt_ut1)
        reference += pyTMD.predict.infer_minor(ts.tide, np.ma.array(theirs[None], mask=False), NAMES, corrections="FES", deltat=ts.tt_ut1, infer_long_period=False, raise_exception=True)
        differences.extend(np.abs(actual - reference).tolist())
    maximum = float(np.max(differences))
    write_json(RESULTS / "calculation_validation.json", {"pyTMD_version": pyTMD.version.full_version,
               "type": "Host-side conversion and pyTMD integration check; not independent model validation and not a Kotlin/Android test",
               "cells": 100, "instants_per_cell": len(dates), "height_comparisons": len(differences), "seed": 20261030,
               "maximum_complex_coefficient_difference_m": float(np.max(np.abs(source - independent_reader))),
               "maximum_height_difference_m": maximum, "p99_height_difference_m": float(np.percentile(differences, 99)),
               "target_m": .001, "passed": maximum <= .001})
    if maximum > .001:
        raise AssertionError("Reference agreement exceeds 1 mm")
    print(f"pyTMD reader/predictor agreement: {maximum:.3g} m maximum over {len(differences)} predictions")


if __name__ == "__main__":
    main()
