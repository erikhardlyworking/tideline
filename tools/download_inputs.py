"""Explicit large-download step; never runs during offline tests."""
import json
from datetime import datetime, timezone

import requests

from common import RAW, fetch, sha256, write_json


def main():
    source = fetch("https://www.seanoe.org/data/00683/79489/", RAW / "eot20-source.html")
    url = "https://www.seanoe.org/data/00683/79489/data/85762.zip"
    if url not in source.read_text():
        raise ValueError("Upstream download identity changed: inspect the current publisher page")
    archive = RAW / "85762.zip"
    expected = 2330678793
    if not archive.exists() or archive.stat().st_size != expected:
        partial = archive.with_suffix(".zip.partial")
        with requests.get(url, stream=True, timeout=120) as response:
            response.raise_for_status()
            with partial.open("wb") as file:
                for block in response.iter_content(8 * 1024 * 1024):
                    file.write(block)
        if partial.stat().st_size != expected:
            raise ValueError("Archive size changed: inspect upstream identity before extracting")
        partial.replace(archive)
    write_json(archive.with_suffix(".zip.meta.json"), {"url": url, "retrieved_or_verified_utc": datetime.now(timezone.utc).isoformat(),
               "bytes": archive.stat().st_size, "sha256": sha256(archive.read_bytes()), "license": "CC BY 4.0"})
    fetch("https://naturalearth.s3.amazonaws.com/10m_physical/ne_10m_ocean.zip", RAW / "ne_10m_ocean.zip")
    print("Official EOT20 archive and Natural Earth ocean/Caspian shoreline cached")


if __name__ == "__main__":
    main()
