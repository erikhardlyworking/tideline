"""Coast-aware weighted interpolation of complex tidal harmonic constants."""
import copy

import numpy as np
import shapely
from shapely.ops import nearest_points

from benchmark import distance_km, xyz


def inverse_distance_weights(distances, power=2):
    """Normalised positive weights, with exact nodes preserved without smoothing."""
    distances = np.asarray(distances, dtype=np.float64)
    if distances.ndim != 1 or not len(distances) or not np.isfinite(distances).all() or np.any(distances < 0):
        raise ValueError("Distances must be a nonempty vector of finite nonnegative values")
    if not np.isfinite(power) or power <= 0:
        raise ValueError("Distance power must be finite and positive")
    exact = np.flatnonzero(distances <= 1e-6)  # 1 mm, only a numerical coincidence tolerance.
    if len(exact):
        weights = np.zeros_like(distances)
        weights[exact[0]] = 1.
        return weights
    # Dividing by the nearest distance before exponentiation avoids overflow.
    weights = (distances.min() / distances) ** power
    return weights / weights.sum()


def support_from_candidates(latitude, longitude, candidates, diagnostic=False):
    """Build an auditable support record; candidates are (distance_km, row, col, lat, lon)."""
    if not candidates:
        return {"status": "unsupported", "reason": "No eligible wet cells within 20 km"}
    candidates = sorted(candidates)[:4]
    weights = inverse_distance_weights([c[0] for c in candidates])
    cells = [{"row": r, "col": c, "lat": lat, "lon": lon, "weight": float(weight), "distance_km": distance}
             for (distance, r, c, lat, lon), weight in zip(candidates, weights) if weight > 0]
    # A coastal weighted estimate may be one-sided. Record whether the nodes
    # actually enclose the requested position rather than imply bracketing.
    local_points = [((((cell["lon"] - longitude + 180) % 360) - 180) * np.cos(np.radians(latitude)),
                     cell["lat"] - latitude) for cell in cells]
    hull = shapely.MultiPoint(local_points).convex_hull
    enclosed = bool(hull.distance(shapely.Point(0., 0.)) <= 1e-10)
    return {"status": "supported", "method": ("DIAGNOSTIC ONLY: " if diagnostic else "") + "complex IDW, up to 4 wet cells, power 2, <=20 km",
            "cells": cells, "maximum_model_distance_km": max(cell["distance_km"] for cell in cells),
            "position_enclosed_by_cells": enclosed, "one_sided_estimate": not enclosed,
            "water_connectivity_verified": not diagnostic, "distance_power": 2, "maximum_cells": 4}


def weighted_support(model, latitude, longitude, baseline_support, diagnostic=False):
    """Retain valid bilinear interpolation; replace nearest fallback with fixed IDW4.

    The experiment uses the same 20 km / 1 km shoreline policy and same first
    64 candidate wet cells as the original run. It does not change eligibility
    after seeing reference tide heights.
    """
    if not diagnostic and baseline_support.get("method") == "complex bilinear":
        result = copy.deepcopy(baseline_support)
        result["weighting_note"] = "Existing four-node bilinear weighting retained"
        result.update({"position_enclosed_by_cells": True, "one_sided_estimate": False, "water_connectivity_verified": True})
        return result
    point = shapely.Point(longitude, latitude)
    sea = point if model.ocean.covers(point) else nearest_points(point, model.ocean)[1]
    shore_snap = distance_km((latitude, longitude), (sea.y, sea.x))
    if not diagnostic and shore_snap > 1:
        return {"status": "unsupported", "reason": "Shoreline snap exceeds the unchanged 1 km limit", "shore_snap_km": shore_snap}
    if not len(model.rows):
        return {"status": "unsupported", "reason": "No wet model cells"}
    _, indices = model.tree.query(xyz(latitude, longitude), k=min(64, len(model.rows)))
    candidates = []
    for index in np.atleast_1d(indices):
        r, c = int(model.rows[index]), int(model.cols[index])
        lat = float(model.lat[r])
        lon = float(((model.lon[c] + 180) % 360) - 180)
        distance = distance_km((latitude, longitude), (lat, lon))
        if distance > 20:
            continue
        if not diagnostic and not model.water_paths.covers(shapely.LineString([(sea.x, sea.y), (lon, lat)])):
            continue
        candidates.append((distance, r, c, lat, lon))
    result = support_from_candidates(latitude, longitude, candidates, diagnostic)
    result["shore_snap_km"] = shore_snap
    return result
