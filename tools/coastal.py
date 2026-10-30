"""Inspect EOT20, retain ocean coast cells, and benchmark strictly lossless tiled encodings."""
import io
import json
import struct
import time
import zipfile
from pathlib import Path

import netCDF4
import numpy as np
import shapefile
import shapely
import zstandard as zstd
from scipy import ndimage

from common import RAW, ROOT, RESULTS, sha256, write_json

MODEL = ROOT / "data/EOT20/ocean_tides"
NAMES = ["2n2", "j1", "k1", "k2", "m2", "m4", "mf", "mm", "n2", "o1", "p1", "q1", "s1", "s2", "sa", "ssa", "t2"]
TILE = 40
MAGIC = b"EOTLSS01"


def extract_archive():
    archive = RAW / "85762.zip"
    with zipfile.ZipFile(archive) as outer:
        listing = [{"name": x.filename, "bytes": x.file_size, "compressed_bytes": x.compress_size, "crc32": x.CRC} for x in outer.infolist()]
        print(listing, flush=True)
        ocean = [n for n in outer.namelist() if Path(n).name == "ocean_tides.zip"]
        if len(ocean) == 1:
            nested = ROOT / "data/ocean_tides.zip"
            if not nested.exists():
                with outer.open(ocean[0]) as source, nested.open("wb") as dest:
                    import shutil
                    shutil.copyfileobj(source, dest, 8 * 1024 * 1024)
            source_zip = zipfile.ZipFile(nested)
        else:
            source_zip = outer
        MODEL.mkdir(parents=True, exist_ok=True)
        for name in source_zip.namelist():
            base = Path(name).name
            if not base.startswith(".") and base.lower().endswith("_ocean_eot20.nc"):
                dest = MODEL / base
                if not dest.exists():
                    with source_zip.open(name) as source, dest.open("wb") as target:
                        import shutil
                        shutil.copyfileobj(source, target, 8 * 1024 * 1024)
        if source_zip is not outer:
            source_zip.close()
    write_json(RESULTS / "download_manifest.json", {"model": "EOT20 final (post July 2021 conversion fix)",
               "doi": "10.17882/79489", "url": "https://www.seanoe.org/data/00683/79489/data/85762.zip",
               "archive_bytes": archive.stat().st_size, "archive_sha256": sha256(archive.read_bytes()), "archive_members": listing,
               "license": "CC BY 4.0", "attribution": "Hart-Davis et al. (2021), EOT20, doi:10.17882/79489 and doi:10.5194/essd-13-3869-2021",
               "changes": "Ocean-height constituents only; coastal subsetting and lossless re-encoding; loading-tide files not used."})


def read_model():
    arrays, valid_all, metadata = [], None, []
    lat = lon = None
    for name in NAMES:
        files = [p for p in MODEL.glob("*.nc") if p.name.split("_")[0].lower() == name]
        if len(files) != 1:
            raise ValueError(f"Missing/duplicate {name}: {files}")
        path = files[0]
        with netCDF4.Dataset(path) as nc:
            y, x = np.array(nc["lat"][:]), np.array(nc["lon"][:])
            av, pv = nc["amplitude"], nc["phase"]
            a, p = av[:], pv[:]
            if av.dimensions == ("lon", "lat"):
                a, p = a.T, p.T
            elif av.dimensions != ("lat", "lon"):
                raise ValueError(av.dimensions)
            if len(x) > 1 and np.isclose(x[-1] - x[0], 360):
                first_valid = ~np.ma.getmaskarray(a)[:, 0] & ~np.ma.getmaskarray(p)[:, 0]
                last_valid = ~np.ma.getmaskarray(a)[:, -1] & ~np.ma.getmaskarray(p)[:, -1]
                both = first_valid & last_valid
                if not np.array_equal(a.data[both, 0], a.data[both, -1]) or not np.array_equal(p.data[both, 0], p.data[both, -1]):
                    raise ValueError("Duplicate longitude seam has inconsistent coefficients")
                # EOT20 has 232 rows valid at 360 but masked at 0. Merge the
                # duplicate meridian without discarding those genuine wet cells.
                repair = ~first_valid & last_valid
                a[repair, 0], p[repair, 0] = a[repair, -1], p[repair, -1]
                seam_repaired = int(repair.sum())
                x, a, p = x[:-1], a[:, :-1], p[:, :-1]
            if y[1] < y[0]:
                y, a, p = y[::-1], a[::-1], p[::-1]
            if lat is not None and (not np.array_equal(y, lat) or not np.array_equal(x, lon)):
                raise ValueError("Constituent grids differ")
            lat, lon = y, x
            valid = ~np.ma.getmaskarray(a) & ~np.ma.getmaskarray(p) & np.isfinite(a.data) & np.isfinite(p.data)
            valid_all = valid if valid_all is None else valid_all & valid
            arrays.append(np.stack([a.data, p.data]))
            metadata.append({"name": name, "filename": path.name, "sha256": sha256(path.read_bytes()),
                             "duplicate_0_360_meridian_cells_recovered": seam_repaired,
                             "dimensions": dict((key, len(val)) for key, val in nc.dimensions.items()),
                             "amplitude_attrs": {key: str(av.getncattr(key)) for key in av.ncattrs()},
                             "phase_attrs": {key: str(pv.getncattr(key)) for key in pv.ncattrs()},
                             "amplitude_dtype": str(av.dtype), "phase_dtype": str(pv.dtype),
                             "valid_cells": int(valid.sum()), "global_attrs": {key: str(nc.getncattr(key)) for key in nc.ncattrs()}})
    values = np.stack(arrays)
    if values.dtype != np.float64:
        raise ValueError(f"Revisit lossless format for source dtype {values.dtype}")
    if not np.allclose(np.diff(lat), 0.125) or not np.allclose(np.diff(lon), 0.125):
        raise ValueError("Unexpected grid resolution")
    write_json(RESULTS / "model_inspection.json", {"lat_count": len(lat), "lon_count": len(lon),
               "latitude_limits": [float(lat[0]), float(lat[-1])], "longitude_limits": [float(lon[0]), float(lon[-1])],
               "common_valid_cells": int(valid_all.sum()), "source_values_dtype": str(values.dtype), "constituents": metadata})
    return lat, lon, values, valid_all


def ocean_geometry():
    directory = ROOT / "data/coastline"
    directory.mkdir(exist_ok=True)
    with zipfile.ZipFile(RAW / "ne_10m_ocean.zip") as archive:
        for name in archive.namelist():
            if Path(name).name == name:
                dest = directory / name
                if not dest.exists():
                    dest.write_bytes(archive.read(name))
    reader = shapefile.Reader(str(directory / "ne_10m_ocean.shp"))
    union = shapely.union_all([shapely.geometry.shape(s.__geo_interface__) for s in reader.shapes()])
    # The user explicitly requested Caspian coverage during this experiment.
    # Natural Earth's ocean layer includes it; other inland lakes remain excluded.
    geom = union
    shapely.prepare(geom)
    return geom


def shift(mask, dy, dx):
    output = np.roll(mask, (dy, dx), axis=(0, 1))
    if dy > 0:
        output[:dy] = False
    elif dy < 0:
        output[dy:] = False
    return output


def ocean_mask(wet, lat, lon, geom):
    labels, count = ndimage.label(wet)
    # Union component IDs across the longitude seam, with no latitude wrapping.
    parent = np.arange(count + 1)
    def root(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    for left, right in zip(labels[:, 0], labels[:, -1]):
        if left and right:
            parent[root(int(right))] = root(int(left))
    roots = np.array([root(i) for i in range(count + 1)])
    labels = roots[labels]
    ids, counts = np.unique(labels[wet], return_counts=True)
    accepted, excluded = [], []
    main = int(ids[np.argmax(counts)])
    for component, size in zip(ids, counts):
        rows, cols = np.where(labels == component)
        x = ((lon[cols] + 180) % 360) - 180
        y = lat[rows]
        # The largest component is the global ocean; disconnected model components
        # are retained only if the independent ocean shoreline intersects a cell.
        marine = component == main or bool(np.any(shapely.intersects_xy(geom, x, y)))
        if not marine:
            marine = bool(np.any(shapely.intersects(geom, shapely.box(x - .0625, y - .0625, x + .0625, y + .0625))))
        record = {"component": int(component), "cells": int(size), "example_lat": float(y[0]), "example_lon": float(x[0])}
        (accepted if marine else excluded).append(record)
    result = np.isin(labels, [x["component"] for x in accepted]) & wet
    write_json(RESULTS / "ocean_selection.json", {"shoreline": "Natural Earth ne_10m_ocean 5.1.1 (public domain)",
               "shoreline_sha256": sha256((RAW / "ne_10m_ocean.zip").read_bytes()),
               "method": "4-connected EOT20 common-valid wet components, periodic longitude seam; largest component plus disconnected components intersecting Natural Earth ocean polygons/cell footprints. Caspian explicitly included at user request; other inland lakes excluded.",
               "retained_ocean_wet_cells": int(result.sum()), "removed_landlocked_wet_cells": int((wet & ~result).sum()),
               "ocean_components": accepted, "excluded_components": excluded,
               "checks": {"caspian_center_is_ocean": bool(shapely.intersects_xy(geom, 50, 42)),
                          "lake_superior_center_is_ocean": bool(shapely.intersects_xy(geom, -88, 47.5)),
                          "mediterranean_center_is_ocean": bool(shapely.intersects_xy(geom, 17, 35))}})
    return result


def coastal_masks(wet, lat, lon, geom):
    interior = wet.copy()
    for dy, dx in [(0, 1), (0, -1), (1, 0), (-1, 0)]:
        interior &= shift(wet, dy, dx)
    model_coast = wet & ~interior
    # Seed the independent shoreline too: otherwise sub-grid islands such as
    # atolls have no model land cell and disappear from a model-mask-only pack.
    shoreline = np.zeros_like(wet)
    boundary = geom.boundary
    parts = list(boundary.geoms) if hasattr(boundary, "geoms") else [boundary]
    for line in parts:
        coords = shapely.get_coordinates(line)
        first, last = coords[:-1], coords[1:]
        # Natural Earth closes the world polygon at the map edges.
        artificial = ((np.abs(first[:, 0]) == 180) & (first[:, 0] == last[:, 0])) | ((np.abs(first[:, 1]) == 90) & (first[:, 1] == last[:, 1]))
        first, last = first[~artificial], last[~artificial]
        if not len(first):
            continue
        counts = np.maximum(1, np.ceil(np.max(np.abs(last - first), axis=1) / .0625).astype(int))
        # Most shoreline segments are already much shorter than a grid cell.
        points = [first]
        for n in np.unique(counts[counts > 1]):
            selected = counts == n
            for step in range(1, n):
                points.append(first[selected] + (last[selected] - first[selected]) * (step / n))
        coords = np.concatenate(points)
        cols = np.floor(((coords[:, 0] - lon[0]) % 360) / .125 + .5).astype(int) % len(lon)
        rows = np.floor((coords[:, 1] - lat[0]) / .125 + .5).astype(int)
        valid = (rows >= 0) & (rows < len(lat))
        shoreline[rows[valid], cols[valid]] = True
    band = (model_coast | shoreline) & wet
    write_json(RESULTS / "coastal_selection.json", {"model_coast_wet_cells": int(model_coast.sum()),
               "additional_shoreline_seed_cells": int((shoreline & wet & ~model_coast).sum()),
               "method": "Wet cells with a dry cardinal neighbour, plus wet cells intersecting sampled Natural Earth ocean/Caspian shoreline (spacing <=0.0625 degrees); 8-neighbour expansion restricted to wet ocean/Caspian cells.",
               "tile_halo": "No independent tile clipping; global band selection precedes tiling, all neighbours read across tile boundaries.",
               "geographic_limits": "0.125 degree north-south spacing is about 13.9 km; east-west spacing shrinks with cosine(latitude). Neither the shoreline nor extra bands add model resolution."})
    masks = {0: band.copy()}
    for extra in range(1, 5):
        grown = band.copy()
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                grown |= shift(band, dy, dx)
        band = grown & wet
        if extra in (1, 2, 4):
            masks[extra] = band.copy()
    return masks


def complex_coefficients(ap):
    return ap[:, 0].astype(np.float64) * 0.01 * np.exp(-1j * np.deg2rad(ap[:, 1].astype(np.float64)))


def encode_words(values, method):
    values = np.ascontiguousarray(values)
    width = values.dtype.itemsize
    words = values.view(f"<u{width}").copy()
    if "xor" in method:
        words[..., 1:] ^= words[..., :-1].copy()
    raw = words.tobytes()
    if "shuffle" in method:
        raw = np.frombuffer(raw, dtype=np.uint8).reshape(-1, width).T.copy().tobytes()
    return raw


def decode_words(raw, shape, dtype, method):
    width = np.dtype(dtype).itemsize
    if "shuffle" in method:
        raw = np.frombuffer(raw, dtype=np.uint8).reshape(width, -1).T.copy().tobytes()
    words = np.frombuffer(raw, dtype=f"<u{width}").reshape(shape).copy()
    if "xor" in method:
        np.bitwise_xor.accumulate(words, axis=-1, out=words)
    return words.view(dtype)


def tile_iterator(mask):
    ny, nx = mask.shape
    for row in range(0, ny, TILE):
        for col in range(0, nx, TILE):
            tile = mask[row:row + TILE, col:col + TILE]
            if tile.any():
                yield row, col, tile


def pack(values, mask, lat, lon, band, representation, method, save=False):
    compressor = zstd.ZstdCompressor(level=9, write_checksum=True)
    decompressor = zstd.ZstdDecompressor()
    blocks, entries = [], []
    source_bytes, encode_seconds, decode_seconds, maximum_tile = 0, 0., 0., 0
    for row, col, tile in tile_iterator(mask):
        ap = values[:, :, row:row + tile.shape[0], col:col + tile.shape[1]][:, :, tile]
        if representation == "native_ap64":
            samples = ap.astype("<f8", copy=False)
        else:
            hc = complex_coefficients(ap)
            samples = np.stack((hc.real, hc.imag), axis=1).astype("<f8")
        bitmap = np.packbits(tile, bitorder="little").tobytes()
        start = time.perf_counter()
        payload = encode_words(samples, method)
        block = compressor.compress(payload)
        encode_seconds += time.perf_counter() - start
        start = time.perf_counter()
        restored = decode_words(decompressor.decompress(block), samples.shape, samples.dtype, method)
        decode_seconds += time.perf_counter() - start
        if samples.tobytes() != restored.tobytes():
            raise AssertionError(f"Lossless round trip failed at tile {row},{col}")
        source_bytes += samples.nbytes
        maximum_tile = max(maximum_tile, samples.nbytes)
        entries.append({"row": row, "col": col, "shape": list(tile.shape), "cells": int(tile.sum()),
                        "bitmap_bytes": len(bitmap), "compressed_bytes": len(block), "sha256": sha256(bitmap + block)})
        blocks.append(bitmap + block)
    header = {"version": 1, "model": "EOT20-final", "license": "CC BY 4.0", "band_extra_cells": band,
              "lat0": float(lat[0]), "lon0": float(lon[0]), "step": 0.125, "shape": list(mask.shape),
              "tile_side_cells": TILE, "constituents": NAMES, "representation": representation, "method": method,
              "dtype": "<f8", "quantization": "none",
              "phase": "Greenwich lag, degrees; h=A exp(-i phase); interpolate complex values",
              "units": "amplitude cm, phase degrees" if representation == "native_ap64" else "metres",
              "compressor": "zstd level 9, checksums", "mask_bit_order": "little", "entries": entries}
    header_bytes = json.dumps(header, separators=(",", ":")).encode()
    index = compressor.compress(header_bytes)
    size = 8 + 8 + len(index) + sum(map(len, blocks))
    filename = f"coast_band{band}_{representation}_{method}.eotz"
    if save:
        destination = ROOT / "data/packs" / filename
        destination.parent.mkdir(exist_ok=True)
        with destination.open("wb") as file:
            file.write(MAGIC + struct.pack("<Q", len(index)) + index)
            for block in blocks:
                file.write(block)
        assert destination.stat().st_size == size
    return {"band_extra_cells": band, "representation": representation, "encoding": method,
            "retained_cells": int(mask.sum()), "tiles": len(blocks), "uncompressed_coefficient_bytes": source_bytes,
            "compressed_package_bytes": size, "index_compressed_bytes": len(index), "index_json_bytes": len(header_bytes),
            "compression_ratio": source_bytes / size, "encode_seconds": encode_seconds, "decode_all_tiles_seconds": decode_seconds,
            "maximum_tile_coefficient_bytes": maximum_tile, "bitwise_roundtrip": True, "filename": filename}


class Pack:
    """Read the actual independently compressed package, validating integrity on demand."""
    def __init__(self, path):
        self.file = open(path, "rb")
        if self.file.read(8) != MAGIC:
            raise ValueError("Unrecognised package version/magic")
        length, = struct.unpack("<Q", self.file.read(8))
        self.header = json.loads(zstd.ZstdDecompressor().decompress(self.file.read(length)))
        if self.header["version"] != 1:
            raise ValueError("Unsupported package version")
        self.entries, offset = {}, self.file.tell()
        for entry in self.header["entries"]:
            self.entries[(entry["row"], entry["col"])] = (entry, offset)
            offset += entry["bitmap_bytes"] + entry["compressed_bytes"]
        self.cache = {}

    def close(self):
        self.file.close()

    def __del__(self):
        if hasattr(self, "file"):
            self.file.close()

    def tile(self, row, col):
        key = (row // TILE * TILE, col // TILE * TILE)
        if key in self.cache:
            return self.cache[key]
        if key not in self.entries:
            return None
        entry, offset = self.entries[key]
        self.file.seek(offset)
        body = self.file.read(entry["bitmap_bytes"] + entry["compressed_bytes"])
        if sha256(body) != entry["sha256"]:
            raise ValueError("Truncated/corrupt tile")
        bitmap = np.unpackbits(np.frombuffer(body[:entry["bitmap_bytes"]], dtype=np.uint8), bitorder="little")
        bitmap = bitmap[:np.prod(entry["shape"])].reshape(entry["shape"]).astype(bool)
        raw = zstd.ZstdDecompressor().decompress(body[entry["bitmap_bytes"]:])
        ap = decode_words(raw, (17, 2, entry["cells"]), self.header["dtype"], self.header["method"])
        # A bounded cache: retain the most recently decoded eight geographic tiles.
        if len(self.cache) >= 8:
            del self.cache[next(iter(self.cache))]
        self.cache[key] = (bitmap, ap)
        return bitmap, ap

    def cell(self, row, col):
        col %= self.header["shape"][1]
        result = self.tile(row, col)
        if result is None:
            return None
        bitmap, data = result
        r, c = row % TILE, col % TILE
        if r >= bitmap.shape[0] or c >= bitmap.shape[1] or not bitmap[r, c]:
            return None
        position = int(np.sum(bitmap.ravel()[:r * bitmap.shape[1] + c]))
        ap = data[:, :, position]
        return complex_coefficients(ap) if self.header["representation"] == "native_ap64" else ap[:, 0] + 1j * ap[:, 1]


def main():
    extract_archive()
    lat, lon, values, wet = read_model()
    geom = ocean_geometry()
    wet = ocean_mask(wet, lat, lon, geom)
    masks = coastal_masks(wet, lat, lon, geom)
    np.savez(ROOT / "data/model_geometry.npz", lat=lat, lon=lon, wet=wet, **{f"band{b}": m for b, m in masks.items()})
    # Preserve the exact native coefficients for later full-grid comparisons.
    np.save(ROOT / "data/source_ap.npy", values)
    results = []
    for band in (1, 2, 4):
        for representation in ("native_ap64", "complex128"):
            for method in ("plain", "shuffle", "xor_shuffle"):
                result = pack(values, masks[band], lat, lon, band, representation, method,
                              save=(representation == "native_ap64" and method == "xor_shuffle"))
                results.append(result)
                print(json.dumps(result), flush=True)
                write_json(RESULTS / "compression.json", results)


if __name__ == "__main__":
    main()
