"""Rasterize the existing Natural Earth ocean geometry for the offline globe.

The same public-domain shoreline is already used in the tide pack. This small
mask is for orientation only and is independent of the polar tide-data limits.
"""
from pathlib import Path
import numpy as np
import shapely
from PIL import Image
from coastal import ocean_geometry

ROOT = Path(__file__).resolve().parents[1]


def main():
    width, height = 1440, 720
    lon = -180 + (np.arange(width) + .5) * 360 / width
    lat = 90 - (np.arange(height) + .5) * 180 / height
    sea = shapely.contains_xy(ocean_geometry(), lon[None, :], lat[:, None])
    output = ROOT / "app/src/main/assets/globe-ocean.png"
    output.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray((sea * 255).astype(np.uint8)).save(output, optimize=True)
    print(f"Offline shoreline mask: {output.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
