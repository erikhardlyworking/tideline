"""Shared reproducibility and public-source caching helpers."""
import hashlib
import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault("XDG_CACHE_HOME", str(ROOT / "data/cache"))
os.environ.setdefault("MPLCONFIGDIR", str(ROOT / "data/cache/matplotlib"))
RAW = ROOT / "data/raw"
RESULTS = ROOT / "results"
for directory in (RAW, RESULTS):
    directory.mkdir(parents=True, exist_ok=True)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, obj):
    Path(path).write_text(json.dumps(obj, indent=2, ensure_ascii=False, allow_nan=False) + "\n")


def fetch(url, path=None, params=None):
    import requests
    request = requests.Request("GET", url, params=params).prepare()
    url = request.url
    path = Path(path) if path else RAW / "http" / (sha256(url.encode()) + ".body")
    path.parent.mkdir(parents=True, exist_ok=True)
    meta = path.with_suffix(path.suffix + ".meta.json")
    if path.exists() and meta.exists():
        if sha256(path.read_bytes()) != json.loads(meta.read_text())["sha256"]:
            raise ValueError(f"Cache integrity failure: {path}")
        return path
    for attempt in range(3):
        try:
            response = requests.get(url, timeout=90, headers={"User-Agent": "CoastalTideFeasibility/1.0"})
            response.raise_for_status()
            content = response.content
            path.write_bytes(content)
            write_json(meta, {"url": url, "retrieved_utc": datetime.now(timezone.utc).isoformat(),
                              "sha256": sha256(content), "bytes": len(content),
                              "last_modified": response.headers.get("Last-Modified"),
                              "content_type": response.headers.get("Content-Type")})
            time.sleep(0.15)
            return path
        except requests.RequestException:
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)


def utc64(text):
    import numpy as np
    dt = datetime.fromisoformat(text).astimezone(timezone.utc).replace(tzinfo=None)
    return np.datetime64(dt, "s")
