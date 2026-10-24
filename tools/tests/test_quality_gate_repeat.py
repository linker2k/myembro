# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('repeat',Path(__file__).parents[1]/'quality_gate_repeat.py')
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
class MotionRepeatTest(unittest.TestCase):
    def test_t2_prefix_counts_zero_jump_and_needle(self):
        self.assertEqual(3,r.maximum_consecutive_positions([(10,10,1),(10,10,1),(10,10,0),(15,10,0)]))
    def test_threshold_two_has_no_implicit_origin(self):
        self.assertEqual(2,r.maximum_consecutive_positions([(0,0,1),(0,0,0)]))
    def test_controls_do_not_count_or_hide_repeats(self):
        self.assertEqual(3,r.maximum_consecutive_positions([(10,10,0),(10,10,2),(10,10,1),(10,10,5),(10,10,0)]))
    def test_moving_jump_breaks_run_and_revisits_do_not_accumulate(self):
        self.assertEqual(1,r.maximum_consecutive_positions([(0,0,0),(10,0,1),(0,0,0),(10,0,0)]))
    def test_control_only_is_zero(self):
        self.assertEqual(0,r.maximum_consecutive_positions([(0,0,2),(0,0,4)]))
if __name__=='__main__':unittest.main()
