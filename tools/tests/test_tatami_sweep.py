# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('sweep', Path(__file__).resolve().parents[1] / 'sweep_tatami.py')
sweep = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sweep)


class RulesTest(unittest.TestCase):
    def test_nonmonotonic_failures_are_not_hidden_by_minimum(self):
        rows = [dict(size_mm=s, area_mm2=s*s, verdict=v) for s,v in [(5,'PASS'),(10,'BLOCK'),(15,'PASS')]]
        rule = sweep.shape_rule(rows)
        self.assertEqual([5,15], rule['allowed_sampled_sizes_mm'])
        self.assertEqual([10], rule['excluded_sampled_sizes_mm'])
        self.assertEqual(15, rule['all_larger_samples_pass_from_mm'])
        self.assertFalse(rule['interpolation_allowed'])

    def test_all_failed_has_no_invented_minimum(self):
        rule = sweep.shape_rule([dict(size_mm=5, verdict='BLOCK')])
        self.assertEqual([], rule['allowed_sampled_sizes_mm'])
        self.assertIsNone(rule['minimum_verified_area_mm2'])
        self.assertIsNone(rule['minimum_passing_sample_mm'])
        self.assertIsNone(rule['all_larger_samples_pass_from_mm'])

    def test_last_size_failure_invalidates_safe_suffix(self):
        rule = sweep.shape_rule([dict(size_mm=5,area_mm2=25,verdict='PASS'),dict(size_mm=10,verdict='BLOCK')])
        self.assertIsNone(rule['all_larger_samples_pass_from_mm'])


if __name__ == '__main__':
    unittest.main()
