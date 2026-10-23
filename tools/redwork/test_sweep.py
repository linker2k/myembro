# SPDX-License-Identifier: GPL-3.0-or-later
import unittest
from sweep import LEVELS, intervals, policy


class DetailPolicyTest(unittest.TestCase):
    def corpus(self):
        return [{'id':f'fixture-{i}', 'category':str(i//4), 'edgePx':e, 'status':'pass'}
                for i in range(20) for e in LEVELS]

    def test_all_pass_preserves_all_levels(self):
        self.assertEqual(LEVELS, policy(self.corpus())['allowedEdgePx'])

    def test_failure_at_middle_does_not_bridge_hole(self):
        rows = self.corpus()
        rows[2]['status'] = 'blocked'
        result = policy(rows)
        self.assertEqual([128,160,224,256], result['allowedEdgePx'])
        self.assertEqual([[128,160],[224,256]], result['allowedContiguousTestedLevels'])

    def test_generation_error_is_not_discarded(self):
        rows = self.corpus()
        for row in rows[:5]:
            row['status'] = 'generation_error'
        result = policy(rows)
        self.assertFalse(result['enabled'])
        self.assertEqual([], result['allowedEdgePx'])
        self.assertEqual([], result['perFixture']['fixture-0'])

    def test_missing_or_duplicate_case_rejected(self):
        with self.assertRaises(AssertionError):
            policy(self.corpus()[:-1])
        rows = self.corpus()
        rows[0] = rows[1]
        with self.assertRaises(AssertionError):
            policy(rows)


if __name__ == '__main__':
    unittest.main()
