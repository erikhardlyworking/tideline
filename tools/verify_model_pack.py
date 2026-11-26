"""Check the distributed, licensed model pack before building (stdlib only)."""
from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / 'data/packs/tideline-global.tpk'
EXPECTED = '2d0dca532e7191feb1d07b9bdf58554be3a1c2cb50892c4cfd275601576c2491'

def main():
    if not PACK.is_file():
        raise SystemExit('Missing real model pack. See README.md for data preparation.')
    actual = hashlib.file_digest(PACK.open('rb'), 'sha256').hexdigest()
    if actual != EXPECTED:
        raise SystemExit(f'Model pack SHA-256 mismatch: {actual}')
    print(f'Original licensed model pack verified: {PACK.stat().st_size:,} bytes')

if __name__ == '__main__':
    main()
