# SPDX-License-Identifier: GPL-3.0-or-later
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from quality_gate_viewport import check_viewport, load_hoop, load_limits, integer_safety_findings, reassess_viewport


class ExactViewportTest(unittest.TestCase):
    def raw(self, width, height):
        data=bytearray(22+528)
        data[8:12]=(22).to_bytes(4,'little')
        data[22+520:22+522]=width.to_bytes(2,'little')
        data[22+522:22+524]=height.to_bytes(2,'little')
        return data

    def test_runtime_hoop_and_limits_are_exported_from_kotlin(self):
        config=Path(__file__).resolve().parents[2]/'engine/build/hoop-spec.json'
        self.assertTrue(config.exists(), 'Run :engine:exportGateHoop first')
        self.assertEqual((1000,1000),load_hoop(config))
        limits=load_limits(config)
        doc=(Path(__file__).resolve().parents[2]/'AGENTS.md').read_text()
        self.assertIn(f"{limits['max_stitch_units_0p1mm']/10:.1f}mm",doc)
        self.assertIn(f"{limits['max_consecutive_positions']}회 이하",doc)
        self.assertIn(f"바늘이 {limits['max_generated_penetrations']}회",doc)
        self.assertIn(f"가져온 PES는 {limits['max_imported_penetrations']}회",doc)

    def test_all_hoop_integer_boundaries(self):
        for hoop in [(1000,1000),(1300,1800),(1600,2600),(731,1247)]:
            for axis in (0,1):
                for delta in (-1,0,1):
                    maximum=hoop[axis]-1+delta
                    width,height=(maximum,20) if axis==0 else (20,maximum)
                    events=[(width,0,0),(0,height,0),(width,0,0)]
                    with self.subTest(hoop=hoop,axis=axis,delta=delta):
                        self.assertEqual(delta>0,any(e.startswith('PEC_HOOP:') for e in check_viewport(self.raw(width,height),events,hoop)['errors']))

    def test_minimum_and_header_no_tolerance(self):
        for minimum in (-1,0,1):
            events=[(20,0,1),(minimum,20,0)]
            self.assertEqual(minimum==0,check_viewport(self.raw(20-minimum,20),events,(1000,1000))['valid'])
        for axis in (0,1):
            for delta in (-1,0,1):
                width,height=(20+delta,20) if axis==0 else (20,20+delta)
                self.assertEqual(delta==0,check_viewport(self.raw(width,height),[(20,0,1),(0,20,0)],(1000,1000))['valid'])

    def test_selected_larger_hoop_replaces_default_margin_as_well_as_size(self):
        initial=check_viewport(self.raw(1500,20),[(1500,0,0),(0,20,0)],(1000,1000),20)
        self.assertFalse(initial['valid'])
        selected=reassess_viewport(initial,(1800,1800),1)
        self.assertTrue(selected['valid'])

    def test_no_implicit_origin_for_an_empty_stitch_block(self):
        result=check_viewport(self.raw(0,0),[],(1000,1000),20)
        self.assertFalse(result['valid'])
        self.assertTrue(any(e.startswith('PEC_EMPTY:') for e in result['errors']))

    def test_implicit_origin_does_not_satisfy_minimum(self):
        result=check_viewport(self.raw(10,10),[(10,10,0)],(1000,1000))
        self.assertFalse(result['valid'])
        self.assertTrue(any(e.startswith('PEC_ORIGIN:') for e in result['errors']))

    def test_reserved_endpoint_n_minus_one_n_n_plus_one(self):
        limits=load_limits(Path(__file__).resolve().parents[2]/'engine/build/hoop-spec.json')
        margin=limits['hoop_safety_margin_units_0p1mm']
        for hoop in [(1000,1000),(1300,1800),(731,1247)]:
            for axis in (0,1):
                for delta in (-1,0,1):
                    maximum=hoop[axis]-margin+delta
                    width,height=(maximum,20) if axis==0 else (20,maximum)
                    self.assertEqual(delta<=0,check_viewport(self.raw(width,height),[(width,0,0),(0,height,0)],hoop,margin)['valid'])

    def test_zero_command_count_boundary(self):
        limits=load_limits(Path(__file__).resolve().parents[2]/'engine/build/hoop-spec.json')
        n=limits['max_zero_length_motion_commands']
        self.assertEqual(0,n)
        with self.assertRaises(ValueError):integer_safety_findings(1,1,0,'APP_GENERATED',limits,n-1)
        for value in (n,n+1):
            hard,soft=integer_safety_findings(1,1,0,'APP_GENERATED',limits,value)
            self.assertEqual(value>n,any(x.startswith('ZERO_LENGTH_MOTION:') for x in hard))

    def test_all_count_and_length_thresholds_n_minus_one_n_n_plus_one(self):
        config=Path(__file__).resolve().parents[2]/'engine/build/hoop-spec.json'
        limits=load_limits(config)
        for origin in ('APP_GENERATED','IMPORTED'):
            for n in (limits['max_generated_penetrations'],limits['max_imported_penetrations']):
                for value in (n-1,n,n+1):
                    hard,soft=integer_safety_findings(value,1,0,origin,limits)
                    maximum=limits['max_imported_penetrations'] if origin=='IMPORTED' else limits['max_generated_penetrations']
                    self.assertEqual(value>maximum,any(x.startswith('CELL_DENSITY:') for x in hard))
            n=limits['max_consecutive_positions']
            for value in (n-1,n,n+1):
                hard,soft=integer_safety_findings(1,value,0,origin,limits)
                self.assertEqual(value>n,any(x.startswith('SAME_POINT_REPEAT:') for x in hard+soft))
                self.assertEqual(value>n and origin=='APP_GENERATED',any(x.startswith('SAME_POINT_REPEAT:') for x in hard))
            n=limits['max_stitch_units_0p1mm']
            for value in (n-1,n,n+1):
                hard,soft=integer_safety_findings(1,1,value*value,origin,limits)
                self.assertEqual(value>n,any(x.startswith('LONG_STITCH:') for x in hard+soft))
                self.assertEqual(value>n and origin=='APP_GENERATED',any(x.startswith('LONG_STITCH:') for x in hard))


if __name__=='__main__':
    unittest.main()
