"""Small offline checks for the numerical and lossless data-handling experiment."""
import tempfile
import unittest
from pathlib import Path
from zoneinfo import ZoneInfo
from datetime import datetime, timezone

import numpy as np

from common import ROOT
from coastal import NAMES, Pack, decode_words, encode_words, complex_coefficients
from benchmark import predict, match_events


class FeasibilityTests(unittest.TestCase):
    def test_lossless_word_transforms_preserve_every_bit(self):
        rng = np.random.default_rng(42)
        words = rng.integers(0, np.iinfo(np.uint64).max, size=(17, 2, 113), dtype=np.uint64)
        # Include signed zero and a NaN with a non-default payload.
        words[0, 0, :3] = [0, 0x8000000000000000, 0x7ff8000000001234]
        values = words.view("<f8")
        for method in ("plain", "shuffle", "xor_shuffle"):
            restored = decode_words(encode_words(values, method), values.shape, "<f8", method)
            self.assertEqual(values.tobytes(), restored.tobytes())

    def test_solar_semidiurnal_analytic_answer_and_phase_sign(self):
        dates = np.arange(np.datetime64("2026-01-01T00:00"), np.datetime64("2026-01-03T00:00"), np.timedelta64(1, "h"))
        hours = np.arange(len(dates))
        for phase in (0, 73, 359):
            coefficients = np.zeros(17, dtype=complex)
            coefficients[NAMES.index("s2")] = complex_coefficients(np.array([[123.4, phase]]))[0]
            answer = 1.234 * np.cos(hours * 2 * np.pi / 12 - np.deg2rad(phase))
            predicted = predict(coefficients, dates, minor=False)
            # Fractional MJD subtraction introduces ~3e-11 m round-off here.
            np.testing.assert_allclose(predicted, answer, atol=1e-9, rtol=0)
            # An intentionally inverted phase must fail the same analytic check.
            if phase == 73:
                wrong = 1.234 * np.cos(hours * 2 * np.pi / 12 + np.deg2rad(phase))
                self.assertGreater(float(np.max(np.abs(predicted - wrong))), 1)

    def test_complex_interpolation_across_phase_wrap(self):
        ap = np.array([[100., 100.], [359., 1.]])
        hc = complex_coefficients(ap[None, :, :])[0]
        self.assertGreater(float(np.mean(hc).real), .999)
        self.assertLess(abs(float(np.mean(hc).imag)), 1e-12)

    def test_new_zealand_dst_and_utc_midnight(self):
        january = datetime(2026, 1, 1, 5, 47, tzinfo=ZoneInfo("Pacific/Auckland"))
        july = datetime(2026, 7, 1, 5, 47, tzinfo=ZoneInfo("Pacific/Auckland"))
        self.assertEqual(january.astimezone(timezone.utc).isoformat(), "2025-12-31T16:47:00+00:00")
        self.assertEqual(july.astimezone(timezone.utc).isoformat(), "2026-06-30T17:47:00+00:00")

    def test_event_match_keeps_unmatched_and_type(self):
        official = [{"time": "2026-01-01T01:00", "kind": "H", "height_m": 1.},
                    {"time": "2026-01-01T07:00", "kind": "L", "height_m": 0.}]
        model = [{"time": "2026-01-01T01:15", "kind": "H", "height_m": .9},
                 {"time": "2026-01-01T07:00", "kind": "H", "height_m": 1.}]
        metrics, matches, _ = match_events(official, model)
        self.assertEqual(metrics["matched_events"], 1)
        self.assertEqual(metrics["unmatched_reference_events"], 1)
        self.assertEqual(metrics["extra_model_events"], 1)
        self.assertEqual(matches[0]["timing_error_minutes"], 15)

    def test_package_corruption_and_version_rejected(self):
        path = ROOT / "data/packs/coast_band1_native_ap64_xor_shuffle.eotz"
        if not path.exists():
            self.skipTest("Run coastal.py to create the real package first")
        with tempfile.TemporaryDirectory() as tmp:
            contents = bytearray(path.read_bytes())
            bad_version = Path(tmp) / "version.eotz"
            changed = bytearray(contents)
            changed[:8] = b"EOTLSS99"
            bad_version.write_bytes(changed)
            with self.assertRaises(ValueError):
                Pack(bad_version)
            original = Pack(path)
            key = next(iter(original.entries))
            _, offset = original.entries[key]
            contents[offset + 8] ^= 1
            corrupt = Path(tmp) / "corrupt.eotz"
            corrupt.write_bytes(contents)
            with self.assertRaisesRegex(ValueError, "corrupt"):
                Pack(corrupt).tile(*key)
            truncated = Path(tmp) / "truncated.eotz"
            truncated.write_bytes(contents[:offset + 3])
            with self.assertRaisesRegex(ValueError, "Truncated"):
                Pack(truncated).tile(*key)


if __name__ == "__main__":
    unittest.main(verbosity=2)
