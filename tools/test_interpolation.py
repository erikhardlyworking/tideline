"""Checks for weighted tidal interpolation, including exact nodes and land barriers."""
import types
import unittest

import numpy as np
import shapely
from scipy.spatial import cKDTree

from benchmark import xyz
from coastal import complex_coefficients
from interpolation import inverse_distance_weights, support_from_candidates, weighted_support


class WeightedInterpolationTests(unittest.TestCase):
    def test_distance_weights_and_exact_node(self):
        np.testing.assert_allclose(inverse_distance_weights([1., 2.]), [.8, .2], atol=1e-15)
        np.testing.assert_array_equal(inverse_distance_weights([12., 0., 5.]), [0., 1., 0.])
        # Geographic units can be scaled without changing normalised weights.
        np.testing.assert_allclose(inverse_distance_weights([1e-4, 2e-4]), [.8, .2], atol=1e-15)
        for distances in ([], [-1, 2], [np.nan, 1], [np.inf]):
            with self.assertRaises(ValueError):
                inverse_distance_weights(distances)

    def test_complex_weights_preserve_phase_wrap_and_constants(self):
        weights = inverse_distance_weights([1., 1.])
        coefficients = complex_coefficients(np.array([[[100., 100.], [359., 1.]]]))[0]
        result = np.sum(weights * coefficients)
        self.assertGreater(result.real, .999)
        self.assertLess(abs(result.imag), 1e-12)
        self.assertAlmostEqual(np.sum(inverse_distance_weights([1., 3., 5.]) * (2. + 3.j)), 2. + 3.j)

    def test_enclosing_and_one_sided_nodes_are_distinguished(self):
        ring = [(10., 0, 0, 0., -.1), (10., 0, 1, 0., .1),
                (10., 1, 0, -.1, 0.), (10., 1, 1, .1, 0.)]
        result = support_from_candidates(0., 0., ring)
        self.assertTrue(result["position_enclosed_by_cells"])
        self.assertAlmostEqual(sum(c["weight"] for c in result["cells"]), 1.)
        edge = support_from_candidates(0., -.2, ring[:2])
        self.assertTrue(edge["one_sided_estimate"])
        exact = support_from_candidates(0., 0., [(0., 0, 0, 0., 0.), (10., 0, 1, 0., .1)])
        self.assertEqual(len(exact["cells"]), 1)
        self.assertEqual(exact["cells"][0]["weight"], 1.)

    def test_nearer_cells_across_land_are_excluded_before_weighting(self):
        lat, lon = np.array([-.125, 0., .125]), np.array([-.125, .02])
        rows, cols = np.where(np.ones((3, 2), dtype=bool))
        ocean = shapely.union_all([shapely.box(-1., -1., -.01, 1.), shapely.box(.01, -1., 1., 1.)])
        model = types.SimpleNamespace(lat=lat, lon=lon, rows=rows, cols=cols, ocean=ocean,
                                      water_paths=ocean, tree=cKDTree(xyz(lat[rows], lon[cols])))
        baseline = {"method": "nearest"}
        accepted = weighted_support(model, 0., -.02, baseline)
        self.assertEqual(len(accepted["cells"]), 3)
        self.assertTrue(all(c["lon"] < 0 for c in accepted["cells"]))
        self.assertTrue(accepted["water_connectivity_verified"])
        diagnostic = weighted_support(model, 0., -.02, baseline, diagnostic=True)
        self.assertFalse(diagnostic["water_connectivity_verified"])
        self.assertTrue(any(c["lon"] > 0 for c in diagnostic["cells"]))

    def test_bilinear_support_is_retained_without_altering_weights(self):
        baseline = {"status": "supported", "method": "complex bilinear", "cells": [{"weight": .2}, {"weight": .8}]}
        result = weighted_support(None, 0., 0., baseline)
        self.assertEqual(result["cells"], baseline["cells"])
        self.assertIsNot(result["cells"], baseline["cells"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
