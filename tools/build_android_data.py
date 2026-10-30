"""Build a native-readable lossless pack and bounded astronomical tables for Android.

Astronomical basis tables contain slowly varying nodal/phase envelopes, not
predicted local tide curves. Fixed Doodson carrier frequencies are evaluated at
every requested UTC instant by the Kotlin engine. No live forecast API is used.
"""
import ast
import inspect
import io
import json
import struct
import time
import zlib
from pathlib import Path

from common import ROOT, RESULTS, sha256, write_json
from coastal import NAMES, TILE, encode_words, ocean_geometry, tile_iterator

import numpy as np
import pyTMD
import shapely
import timescale

START = "2020-01-01"
END = "2041-01-01"


def minor_matrix():
    # Adapt the pinned MIT-licensed routine's return value, preserving its exact
    # FES admittance coefficients. This happens only in host-side preparation.
    source = inspect.getsource(pyTMD.predict._infer_short_period)
    tree = ast.parse(source)
    tree.body[0].body[-1] = ast.parse("return zmin, minor_indices").body[0]
    namespace = dict(pyTMD.predict.__dict__)
    exec(compile(ast.fix_missing_locations(tree), "<pyTMD minor matrix export>", "exec"), namespace)
    export = namespace["_infer_short_period"]
    identity = np.ma.array(np.eye(17, dtype=complex), mask=False)
    matrix, selected = export(np.array([0.]), identity, NAMES, corrections="FES", raise_exception=True)
    imaginary, _ = export(np.array([0.]), 1j * identity, NAMES, corrections="FES", raise_exception=True)
    np.testing.assert_allclose(imaginary, 1j * matrix, atol=1e-7)
    return matrix[:, selected].astype(complex), selected, sha256(source.encode())


def astronomy():
    dates = np.arange(np.datetime64(START), np.datetime64(END) + np.timedelta64(1, "D"), np.timedelta64(1, "D"))
    ts = timescale.time.Timescale().from_datetime(dates)
    major_u, major_f, major_g = pyTMD.arguments.arguments(ts.MJD, NAMES, corrections="FES", deltat=ts.tt_ut1)
    minor_u, minor_f, minor_g = pyTMD.arguments.minor_arguments(ts.MJD, corrections="FES", deltat=ts.tt_ut1)
    matrix, selected, source_hash = minor_matrix()
    # The minor argument table uses m1a/l2a aliases, not the public major m1/l2.
    minor_names = ["2q1", "sigma1", "rho1", "m1b", "m1a", "chi1", "pi1", "phi1", "theta1", "j1", "oo1", "2n2", "mu2", "nu2", "lambda2", "l2a", "l2b", "t2", "eps2", "eta2"]
    names = NAMES + [minor_names[i] for i in selected]
    omega = np.r_[pyTMD.arguments.frequency(NAMES, corrections="FES"), pyTMD.arguments._frequency(pyTMD.arguments._minor_table(), method="ASTRO5")[selected]]
    phase = np.c_[np.deg2rad(major_g) + major_u, np.deg2rad(minor_g[:, selected]) + minor_u[:, selected]]
    amplitude = np.c_[major_f, minor_f[:, selected]]
    carrier_seconds = np.asarray(ts.tide) * 86400.
    envelope = amplitude * np.exp(1j * (phase - carrier_seconds[:, None] * omega[None, :]))
    mapping = np.concatenate([np.eye(17, dtype=complex), matrix], axis=1).T
    metadata = {"start_epoch_seconds": int(np.datetime64(START, "s").astype(np.int64)), "step_seconds": 86400,
                "node_count": len(dates), "basis_count": len(names), "names": names, "omega_rad_per_second": omega.tolist(),
                "carrier_epoch_seconds": int(np.datetime64("1992-01-01", "s").astype(np.int64)),
                "mapping_real": mapping.real.tolist(), "mapping_imag": mapping.imag.tolist(),
                "pyTMD_version": pyTMD.version.full_version, "minor_source_sha256": source_hash,
                "corrections": "FES", "minor_inference": "short period only, supplied constituents excluded",
                "time_basis": "UTC approximates UT1; TT-UT1 from pinned timescale tables included in daily envelopes",
                "date_range": [START, END], "envelope_interpolation": "linear; exact carrier evaluated for every requested instant"}
    values = np.stack([envelope.real, envelope.imag], axis=-1).astype("<f8")
    return metadata, values


def polygons(shape):
    if shape.is_empty:
        return []
    if shape.geom_type == "Polygon":
        return [shape]
    return [p for part in shape.geoms for p in polygons(part)] if hasattr(shape, "geoms") else []


def water_tile(shape):
    output = io.BytesIO()
    pieces = polygons(shape)
    output.write(struct.pack("<I", len(pieces)))
    for piece in pieces:
        rings = [piece.exterior, *piece.interiors]
        output.write(struct.pack("<I", len(rings)))
        for ring in rings:
            points = np.asarray(ring.coords, dtype="<f8")
            output.write(struct.pack("<I", len(points)))
            output.write(points.tobytes())
    return output.getvalue()


def main():
    started = time.perf_counter()
    geometry = np.load(ROOT / "data/model_geometry.npz")
    values = np.load(ROOT / "data/source_ap.npy", mmap_mode="r")
    entries, blocks, offset = [], [], 0
    def add(key, raw, **attributes):
        nonlocal offset
        compressed = zlib.compress(raw, level=9)
        entries.append({"key": key, "offset": offset, "length": len(compressed), "raw_length": len(raw),
                        "sha256": sha256(raw), **attributes})
        blocks.append(compressed)
        offset += len(compressed)
    for row, col, mask in tile_iterator(geometry["band1"]):
        ap = values[:, :, row:row + mask.shape[0], col:col + mask.shape[1]][:, :, mask]
        raw = np.packbits(mask, bitorder="little").tobytes() + encode_words(ap.astype("<f8"), "xor_shuffle")
        add(f"tide/{row}/{col}", raw, rows=mask.shape[0], cols=mask.shape[1], cells=int(mask.sum()), mask_bytes=(mask.size + 7) // 8)
    print("Lossless tide tiles encoded", flush=True)
    ocean = ocean_geometry()
    for row in range(36):
        south = -90 + row * 5
        for col in range(72):
            west = -180 + col * 5
            rectangle = shapely.box(west, south, west + 5, south + 5)
            if not ocean.intersects(rectangle):
                continue
            clipped = ocean.intersection(rectangle)
            if not clipped.is_empty:
                add(f"water/{row}/{col}", water_tile(clipped))
    print("Ocean/Caspian shoreline tiles encoded", flush=True)
    astro, basis = astronomy()
    add("astronomy", basis.tobytes())
    header = {"version": 1, "model": "EOT20 final", "constituents": NAMES, "lat0": float(geometry["lat"][0]),
              "lon0": float(geometry["lon"][0]), "step": .125, "rows": len(geometry["lat"]), "cols": len(geometry["lon"]),
              "tile_side": TILE, "band_extra_cells": 1, "byte_order": "little", "representation": "original float64 amplitude cm / phase degrees",
              "encoding": "bitwise xor + byte shuffle, lossless zlib; independently compressed tiles", "quantization": "none",
              "astronomy": astro, "license": "CC BY 4.0", "attribution": "Hart-Davis et al. (2021), doi:10.17882/79489; converted and subsetted",
              "water_source": "Natural Earth 10m ocean 5.1.1, public domain; Caspian explicitly included", "entries": entries}
    header_bytes = json.dumps(header, separators=(",", ":")).encode()
    path = ROOT / "data/packs/tideline-global.tpk"
    with path.open("wb") as file:
        file.write(b"TIDEPK01" + struct.pack("<I", len(header_bytes)) + header_bytes)
        for block in blocks:
            file.write(block)
    # A small genuine fixture keeps ordinary core unit tests independent of the global archive.
    fixture = ROOT / "tide-core/src/test/resources/astronomy_fixture.json"
    fixture.parent.mkdir(parents=True, exist_ok=True)
    start = int((np.datetime64("2026-01-01") - np.datetime64(START)) / np.timedelta64(1, "D"))
    small = {**astro, "start_epoch_seconds": int(np.datetime64("2026-01-01", "s").astype(np.int64)), "node_count": 5,
             "envelopes": basis[start:start + 5].tolist()}
    fixture.write_text(json.dumps(small, separators=(",", ":")))
    write_json(RESULTS / "android_data_manifest.json", {"path": str(path.relative_to(ROOT)), "bytes": path.stat().st_size,
               "sha256": sha256(path.read_bytes()), "entries": len(entries), "header_bytes": len(header_bytes), "astronomy_nodes": astro["node_count"],
               "astronomy_basis_terms": astro["basis_count"], "date_range": astro["date_range"], "build_seconds": time.perf_counter() - started,
               "lossless_tide_coefficients": True, "compression": "zlib for native JVM/Android decoding without native third-party libraries"})
    print(f"Android pack: {path.stat().st_size/1e6:.2f} MB", flush=True)


if __name__ == "__main__":
    main()
