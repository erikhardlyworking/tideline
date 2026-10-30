"""Run the host-side EOT20 feasibility campaign, keeping every attempted location."""
import csv
import json
import time
from pathlib import Path

from common import ROOT, RESULTS, write_json

import numpy as np
import pyTMD
import shapely
from shapely.ops import nearest_points
from scipy.spatial import cKDTree
from scipy.signal import find_peaks
import timescale

from coastal import NAMES, Pack, complex_coefficients, ocean_geometry


def xyz(lat, lon):
    a, b = np.radians(lat), np.radians(lon)
    return np.stack((np.cos(a) * np.cos(b), np.cos(a) * np.sin(b), np.sin(a)), axis=-1)


def distance_km(a, b):
    return float(2 * 6371.0088 * np.arcsin(min(1., np.linalg.norm(xyz(*a) - xyz(*b)) / 2)))


class Model:
    def __init__(self):
        geometry = np.load(ROOT / "data/model_geometry.npz")
        self.lat, self.lon, self.wet = geometry["lat"], geometry["lon"], geometry["wet"]
        self.masks = {b: geometry[f"band{b}"] for b in (1, 2, 4)}
        self.ap = np.load(ROOT / "data/source_ap.npy", mmap_mode="r")
        self.rows, self.cols = np.where(self.wet)
        self.tree = cKDTree(xyz(self.lat[self.rows], self.lon[self.cols]))
        self.ocean = ocean_geometry()
        # Test water paths against the prepared original geometry. Gauge/pier
        # offsets are handled by the separately recorded <=1 km coastal snap.
        self.water_paths = self.ocean
        shapely.prepare(self.water_paths)
        self.packs = {b: Pack(ROOT / f"data/packs/coast_band{b}_native_ap64_xor_shuffle.eotz") for b in (1, 2, 4)}

    def support(self, latitude, longitude):
        if latitude < self.lat[0] or latitude > self.lat[-1]:
            return {"status": "unsupported", "reason": "Outside model latitude extent"}
        point = shapely.Point(longitude, latitude)
        sea = point if self.ocean.covers(point) else nearest_points(point, self.ocean)[1]
        shore_snap = distance_km((latitude, longitude), (sea.y, sea.x))
        if shore_snap > 1:
            return {"status": "unsupported", "reason": "Gauge is over 1 km from the independent ocean/Caspian shoreline", "shore_snap_km": shore_snap}
        y, x = latitude, (longitude - self.lon[0]) % 360 + self.lon[0]
        r = int(np.searchsorted(self.lat, y, side="right") - 1)
        c = int(np.searchsorted(self.lon, x, side="right") - 1)
        r = min(r, len(self.lat) - 2)
        cells = [(r, c), (r, (c + 1) % len(self.lon)), (r + 1, c), (r + 1, (c + 1) % len(self.lon))]
        fx, fy = (x - self.lon[c]) / .125, (y - self.lat[r]) / .125
        weights = [(1 - fy) * (1 - fx), (1 - fy) * fx, fy * (1 - fx), fy * fx]
        paths_clear = True
        for rr, cc in cells:
            xx = float(((self.lon[cc] + 180) % 360) - 180)
            if not self.water_paths.covers(shapely.LineString([(sea.x, sea.y), (xx, self.lat[rr])])):
                paths_clear = False
                break
        # Checking the whole interpolation quadrilateral also catches islands
        # between wet corners. Near the dateline use the bounded fallback.
        x0 = ((self.lon[c] + 180) % 360) - 180
        box_clear = x0 + .125 <= 180 and self.water_paths.covers(shapely.box(x0, self.lat[r], x0 + .125, self.lat[r + 1]))
        if all(self.wet[rr, cc] for rr, cc in cells) and paths_clear and box_clear:
            method = "complex bilinear"
        else:
            _, indices = self.tree.query(xyz(latitude, longitude), k=64)
            candidates = []
            for index in np.atleast_1d(indices):
                rr, cc = int(self.rows[index]), int(self.cols[index])
                ll = float(((self.lon[cc] + 180) % 360) - 180)
                dist = distance_km((latitude, longitude), (float(self.lat[rr]), ll))
                if dist > 20:
                    continue
                line = shapely.LineString([(sea.x, sea.y), (ll, self.lat[rr])])
                if self.water_paths.covers(line):
                    candidates.append((dist, rr, cc))
            if not candidates:
                return {"status": "unsupported", "reason": "No valid wet cell reachable without crossing mapped land within 20 km", "shore_snap_km": shore_snap}
            dist, rr, cc = min(candidates)
            cells, weights, method = [(rr, cc)], [1.], "nearest wet cell, clear water path, <=20 km"
        return {"status": "supported", "method": method, "shore_snap_km": shore_snap,
                "cells": [{"row": rr, "col": cc, "lat": float(self.lat[rr]), "lon": float(((self.lon[cc] + 180) % 360) - 180),
                           "weight": float(weight), "distance_km": distance_km((latitude, longitude), (float(self.lat[rr]), float(self.lon[cc])))}
                          for (rr, cc), weight in zip(cells, weights)],
                "maximum_model_distance_km": max(distance_km((latitude, longitude), (float(self.lat[rr]), float(self.lon[cc]))) for rr, cc in cells)}

    def coefficients(self, support, band=None):
        result = np.zeros(17, dtype=np.complex128)
        for cell in support["cells"]:
            r, c = cell["row"], cell["col"]
            if band is None:
                hc = complex_coefficients(self.ap[:, :, r, c])
            else:
                hc = self.packs[band].cell(r, c)
                if hc is None:
                    return None
            result += cell["weight"] * hc
        return result

    def diagnostic_nearest(self, latitude, longitude):
        _, index = self.tree.query(xyz(latitude, longitude))
        r, c = int(self.rows[index]), int(self.cols[index])
        lat, lon = float(self.lat[r]), float(((self.lon[c] + 180) % 360) - 180)
        dist = distance_km((latitude, longitude), (lat, lon))
        if dist > 20:
            return None
        return {"status": "supported", "method": "DIAGNOSTIC ONLY: nearest wet cell <=20 km; water connectivity not established",
                "cells": [{"row": r, "col": c, "lat": lat, "lon": lon, "weight": 1., "distance_km": dist}],
                "maximum_model_distance_km": dist}


def predict(hc, dates, minor=True):
    dates = np.asarray(dates, dtype="datetime64[s]")
    ts = timescale.time.Timescale().from_datetime(dates)
    constants = np.ma.array(hc[None, :], mask=False)
    values = pyTMD.predict.time_series(ts.tide, constants, NAMES, deltat=ts.tt_ut1, corrections="FES")
    if minor:
        # pyTMD excludes constituents already present; this does not add them twice.
        # EOT20 lacks the nodal-period constituent required by pyTMD's long-
        # period inference. Retain its supplied SA/SSA/MM/MF; infer short only.
        values += pyTMD.predict.infer_minor(ts.tide, constants, NAMES, deltat=ts.tt_ut1, corrections="FES", infer_long_period=False, raise_exception=True)
    if np.ma.getmaskarray(values).any() or not np.isfinite(values).all():
        raise ValueError("Invalid astronomical prediction")
    return values.data


def errors(actual, reference):
    error = np.asarray(actual) - np.asarray(reference)
    return {"bias_m": float(np.mean(error)), "mae_m": float(np.mean(np.abs(error))),
            "rmse_m": float(np.sqrt(np.mean(error ** 2))), "p95_absolute_m": float(np.percentile(np.abs(error), 95)),
            "maximum_absolute_m": float(np.max(np.abs(error)))}


def extrema(dates, heights):
    dates = np.array(dates, dtype="datetime64[s]")
    heights = np.array(heights)
    result = []
    for sign, kind in [(1, "H"), (-1, "L")]:
        positions, _ = find_peaks(sign * heights, prominence=.005)
        for i in positions:
            denominator = heights[i - 1] - 2 * heights[i] + heights[i + 1]
            offset = .5 * (heights[i - 1] - heights[i + 1]) / denominator if denominator else 0
            offset = np.clip(offset, -.5, .5)
            step = float((dates[i + 1] - dates[i]) / np.timedelta64(1, "s"))
            instant = dates[i] + np.timedelta64(int(round(offset * step)), "s")
            height = heights[i] - .25 * (heights[i - 1] - heights[i + 1]) * offset
            result.append({"time": str(instant), "height_m": float(height), "kind": kind})
    return sorted(result, key=lambda x: x["time"])


def match_events(reference, model):
    matches, used, ambiguous, cursor = [], set(), 0, -1
    for event in reference:
        if event["kind"] not in ("H", "L"):
            ambiguous += 1
            continue
        options = []
        for i, prediction in enumerate(model):
            delta = float((np.datetime64(prediction["time"]) - np.datetime64(event["time"])) / np.timedelta64(1, "m"))
            if i > cursor and prediction["kind"] == event["kind"] and abs(delta) <= 120:
                options.append((abs(delta), i, delta))
        if len(options) > 1:
            ambiguous += 1
        if options:
            _, index, delta = min(options)
            cursor = index
            used.add(index)
            matches.append({"reference": event, "model": model[index], "timing_error_minutes": delta})
    metrics = {"reference_events": len(reference), "model_events": len(model), "matched_events": len(matches),
               "unmatched_reference_events": len(reference) - len(matches), "extra_model_events": len(model) - len(used),
               "ambiguous_events": ambiguous}
    for kind in ("H", "L"):
        delta = np.array([m["timing_error_minutes"] for m in matches if m["reference"]["kind"] == kind])
        metrics[kind] = {"count": len(delta), "bias_minutes": float(np.mean(delta)), "median_absolute_minutes": float(np.median(np.abs(delta))),
                         "p95_absolute_minutes": float(np.percentile(np.abs(delta), 95)), "maximum_absolute_minutes": float(np.max(np.abs(delta)))} if len(delta) else {"count": 0}
    ranges = []
    for first, second in zip(matches[:-1], matches[1:]):
        reference_range = abs(second["reference"]["height_m"] - first["reference"]["height_m"])
        dt_hours = float((np.datetime64(second["reference"]["time"]) - np.datetime64(first["reference"]["time"])) / np.timedelta64(1, "h"))
        if first["reference"]["kind"] == second["reference"]["kind"] or dt_hours > 15 or reference_range < .1:
            continue
        predicted_range = abs(second["model"]["height_m"] - first["model"]["height_m"])
        ranges.append({"reference_range_m": reference_range, "model_range_m": predicted_range,
                       "absolute_error_m": abs(predicted_range - reference_range),
                       "absolute_error_percent": 100 * abs(predicted_range - reference_range) / reference_range})
    metrics["range_pairs"] = len(ranges)
    if ranges:
        metrics["median_absolute_range_error_percent"] = float(np.median([r["absolute_error_percent"] for r in ranges]))
    return metrics, matches, ranges


def run_station(station, model, support_override=None):
    support = support_override or model.support(station["lat"], station["lon"])
    result = {key: station[key] for key in ("provider", "id", "name", "lat", "lon", "type", "datum")}
    if "source_coordinate_correction" in station:
        result["source_coordinate_correction"] = station["source_coordinate_correction"]
        result["catalog_coordinates"] = station["catalog_coordinates"]
    result["support"] = support
    if support["status"] != "supported":
        return result, []
    hc = model.coefficients(support)
    result["pack_bands_available"] = {str(b): model.coefficients(support, b) is not None for b in (1, 2, 4)}
    # The broader 4-cell package is used for the headline accuracy comparison.
    packaged = model.coefficients(support, 4)
    if packaged is None:
        result["support"] = {**support, "status": "unsupported", "reason": "Supported by full model but missing from four-cell coastal package"}
        return result, []
    if not np.array_equal(packaged, hc):
        raise AssertionError(f"Coefficients changed during lossless packaging: {station['name']}")
    details, full_y, reference_y, total_agree, total_eligible = [], [], [], 0, 0
    official_events, predicted_events, all_dates = [], [], []
    windows = station.get("windows", [{"start": f"2026-{month:02d}-01", "end": f"2026-{month:02d}-03"} for month in (1, 4, 7, 10)])
    for window in windows:
        start, end = np.datetime64(window["start"], "s"), np.datetime64(window["end"], "s")
        if station["type"] == "continuous":
            dates = np.array(window["times"], dtype="datetime64[s]")
            reference = np.array(window["heights_m"])
            if not len(dates):
                continue
            t0 = time.perf_counter()
            y = predict(packaged, dates)
            elapsed = time.perf_counter() - t0
            y_no_minor = predict(hc, dates, minor=False)
            all_dates.append(dates)
            full_y.extend(y.tolist())
            reference_y.extend(reference.tolist())
            step_hours = float((dates[1] - dates[0]) / np.timedelta64(1, "h"))
            reference_slope, model_slope = np.gradient(reference, step_hours), np.gradient(y, step_hours)
            eligible = np.abs(reference_slope) >= .01
            agrees = (np.sign(reference_slope) == np.sign(model_slope)) & (np.abs(model_slope) >= .01)
            total_agree += int((eligible & agrees).sum())
            total_eligible += int(eligible.sum())
            official_events.extend(extrema(dates, reference))
            predicted_events.extend(extrema(dates, y))
            details.append({"start": str(start), "end_exclusive": str(end), "samples": len(dates),
                            "expected_samples": int((end - start) / np.timedelta64(station["interval_minutes"], "m")),
                            "raw": errors(y, reference), "reference_range_m": float(np.ptp(reference)),
                            "model_range_m": float(np.ptp(y)), "model_without_minor_rmse_m": errors(y_no_minor, reference)["rmse_m"],
                            "predict_seconds": elapsed})
            sample_rows = [{"provider": station["provider"], "id": station["id"], "name": station["name"], "comparison_role": "diagnostic_only" if support_override else "supported", "time_utc": str(t),
                            "official_m": float(v), "model_m": float(p), "residual_m": float(p - v)} for t, v, p in zip(dates, reference, y)]
            model.sample_rows.extend(sample_rows)
        else:
            # Event tables remain event tables: no interpolated official curve.
            margin = np.timedelta64(3, "h")
            dates = np.arange(start - margin, end + margin, np.timedelta64(1, "m"))
            y = predict(packaged, dates)
            official_events.extend([e for e in station["events"] if start <= np.datetime64(e["time"]) < end])
            predicted_events.extend([e for e in extrema(dates, y) if start <= np.datetime64(e["time"]) < end])
            details.append({"start": str(start), "end_exclusive": str(end), "model_samples_for_event_search": len(dates)})
    result["windows"] = details
    if full_y:
        result["samples"] = len(full_y)
        result["raw"] = errors(full_y, reference_y)
        # Explicitly supplemental constant mean-centering over all four windows.
        result["mean_centered"] = errors(np.array(full_y) - np.mean(full_y), np.array(reference_y) - np.mean(reference_y))
        reference_range = float(np.mean([w["reference_range_m"] for w in details]))
        result["mean_reference_48h_range_m"] = reference_range
        result["weak_tide_range_under_10cm"] = reference_range < .1
        result["raw_rmse_percent_of_range"] = 100 * result["raw"]["rmse_m"] / reference_range if reference_range >= .1 else None
        result["centered_rmse_percent_of_range"] = 100 * result["mean_centered"]["rmse_m"] / reference_range if reference_range >= .1 else None
        result["rising_falling_agreement_percent"] = 100 * total_agree / total_eligible if total_eligible else None
        result["direction_eligible_samples"] = total_eligible
        result["direction_deadband_m_per_hour"] = .01
    result["events"], matches, ranges = match_events(official_events, predicted_events)
    result["event_details"] = matches
    result["range_details"] = ranges
    result["packaging_maximum_height_difference_m"] = 0.
    return result, all_dates


def packaging_stress(model):
    rng = np.random.default_rng(20261030)
    rows, cols = np.where(model.masks[4])
    choices = rng.choice(len(rows), size=min(1000, len(rows)), replace=False)
    max_difference, checked = 0., 0
    for index in choices:
        r, c = int(rows[index]), int(cols[index])
        source = complex_coefficients(model.ap[:, :, r, c])
        restored = model.packs[4].cell(r, c)
        if restored is None or not np.array_equal(source, restored):
            raise AssertionError(f"Random package cell differs: {r},{c}")
        checked += 1
    return {"random_retained_cells_checked": checked, "seed": 20261030, "coefficients_bitwise_identical": True,
            "maximum_height_error_m": max_difference, "explanation": "Identical decoded native coefficient bits give identical deterministic predictions at every instant; no quantisation. Every tile was also fully round-tripped during pack creation."}


def main():
    model = Model()
    model.sample_rows = []
    references = json.loads((RESULTS / "references.json").read_text())
    results = []
    for i, station in enumerate(references["stations"]):
        result, _ = run_station(station, model)
        if result["support"]["status"] != "supported":
            diagnostic = model.diagnostic_nearest(station["lat"], station["lon"])
            if diagnostic:
                result["diagnostic_only"], _ = run_station(station, model, support_override=diagnostic)
                result["diagnostic_only"]["warning"] = "Not supported coverage: nearest-cell comparison ignores the failed shoreline/connectivity check. Excluded from the primary accuracy score."
        results.append(result)
        print(f"{i + 1}/{len(references['stations'])} {station['provider']} {station['name']}: {result['support']['status']} "
              f"{result.get('raw_rmse_percent_of_range', '')}", flush=True)
    caspian = []
    for name, lat, lon in [("Baku", 40.36, 49.88), ("Aktau", 43.65, 51.14), ("Bandar Anzali", 37.47, 49.46),
                            ("Makhachkala", 42.98, 47.52), ("Turkmenbashi", 40.00, 52.98), ("Caspian centre", 42., 50.)]:
        caspian.append({"name": name, "lat": lat, "lon": lon, "support": model.support(lat, lon)})
    cy = (model.lat >= 36) & (model.lat <= 48)
    cx = (model.lon >= 46) & (model.lon <= 55)
    caspian_cells = int(model.wet[np.ix_(cy, cx)].sum())
    write_json(RESULTS / "caspian_coverage.json", {"requested_by_user": True, "common_valid_wet_cells_in_36_48N_46_55E": caspian_cells,
               "places": caspian, "external_accuracy_test": "Not performed: no official Caspian astronomical prediction reference retrieved; coverage only."})
    packaging = packaging_stress(model)
    write_json(RESULTS / "packaging_validation.json", packaging)
    metadata = {"purpose": "Data feasibility only; no Android or Kotlin engine implemented or validated", "pyTMD_version": pyTMD.version.full_version,
                "constituents": NAMES, "nodal_corrections": "FES", "minor_inference": "Short-period inference enabled exactly once, excluding supplied constituents. Long-period inference disabled: EOT20 lacks required node constituent; supplied SA/SSA/MM/MF retained. Without-inference sensitivity retained per window.",
                "time_basis": "UTC input; timescale TT-UT1 table for astronomical arguments; UTC used as UT1 approximation (subsecond UT1-UTC difference not applied)",
                "tested_dates": "Four 48h windows beginning 2026-01-01, 04-01, 07-01, 10-01; no indefinite validity claim",
                "ten_percent_definition": "Raw RMSE / mean of four official 48-hour peak-to-trough ranges; weak tides with range <0.10 m have no percentage score. Mean-centred shape RMSE is supplemental, using one constant over all windows. Event-only LINZ uses high-low range error and is reported separately.",
                "extrema": "5 mm prominence; quadratic local refinement of dense 6/10 minute series; LINZ compared to 1-minute model search. Chronological one-to-one matching by type within 120 min; reference sampling/rounding limits timing precision.",
                "primary_package_band": 4, "source_failures": references["failures"], "locations": results}
    write_json(RESULTS / "benchmark.json", metadata)
    with (RESULTS / "samples.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["provider", "id", "name", "comparison_role", "time_utc", "official_m", "model_m", "residual_m"])
        writer.writeheader()
        writer.writerows(model.sample_rows)
    fields = ["provider", "id", "name", "lat", "lon", "status", "reason", "samples", "method", "distance_km", "bias_m", "mae_m", "rmse_m", "p95_absolute_m", "maximum_absolute_m", "range_m", "raw_rmse_percent_of_range", "centered_rmse_percent_of_range", "direction_agreement_percent", "high_timing_median_absolute_minutes", "low_timing_median_absolute_minutes", "range_error_percent", "unmatched_reference_events", "extra_model_events"]
    with (RESULTS / "locations.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for s in results:
            row = {k: s[k] for k in ("provider", "id", "name", "lat", "lon")}
            row.update({"status": s["support"]["status"], "reason": s["support"].get("reason", ""),
                        "samples": s.get("samples", ""), "method": s["support"].get("method", ""),
                        "distance_km": s["support"].get("maximum_model_distance_km", "")})
            row.update(s.get("raw", {}))
            row.update({"range_m": s.get("mean_reference_48h_range_m", ""), "raw_rmse_percent_of_range": s.get("raw_rmse_percent_of_range", ""),
                        "centered_rmse_percent_of_range": s.get("centered_rmse_percent_of_range", ""),
                        "direction_agreement_percent": s.get("rising_falling_agreement_percent", "")})
            events = s.get("events", {})
            row.update({"high_timing_median_absolute_minutes": events.get("H", {}).get("median_absolute_minutes", ""),
                        "low_timing_median_absolute_minutes": events.get("L", {}).get("median_absolute_minutes", ""),
                        "range_error_percent": events.get("median_absolute_range_error_percent", ""),
                        "unmatched_reference_events": events.get("unmatched_reference_events", ""), "extra_model_events": events.get("extra_model_events", "")})
            writer.writerow(row)
    print(f"Saved {len(results)} locations and {len(model.sample_rows)} compared heights", flush=True)


if __name__ == "__main__":
    main()
