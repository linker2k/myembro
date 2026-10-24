# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('korean_sweep',Path(__file__).resolve().parents[1]/'sweep_korean_ttf.py')
sweep=importlib.util.module_from_spec(spec)
spec.loader.exec_module(sweep)


class KoreanSweepTest(unittest.TestCase):
    def test_pinned_strict_stroke_table_goldens(self):
        self.assertEqual(14,sum(sweep.strokes(ord(c)) for c in '김민수'))
        self.assertEqual(47,sum(sweep.strokes(ord(c)) for c in '지상 최강의 개발자 쥬니니' if c!=' '))
        self.assertEqual(3,sweep.strokes(ord('가')))
        self.assertEqual(16,sweep.strokes(ord('빪')))

    def test_only_interior_failures_trigger_boundary_refinement(self):
        self.assertEqual([],sweep.refinement_heights({5.0:'BLOCK',90.0:'BLOCK'}))
        self.assertEqual([float(n) for n in range(6,48)],sweep.refinement_heights({5.0:'PASS',26.25:'BLOCK',47.5:'PASS'}))
        self.assertEqual([float(n) for n in range(27,48)],sweep.refinement_heights({26.25:'PASS',47.5:'BLOCK',68.75:'BLOCK',90.0:'BLOCK'}))
        self.assertEqual([float(n) for n in range(48,69)],sweep.refinement_heights({47.5:'PASS',68.75:'BLOCK',90.0:'BLOCK'}))
        self.assertEqual([],sweep.refinement_heights({26.25:'BLOCK',47.5:'PENDING'}))

    def test_failures_and_pending_split_ranges(self):
        self.assertEqual(dict(min_mm=47.5,max_mm=90.0,sampled_heights=3),sweep.sampled_run({
            5.0:'PASS',26.25:'BLOCK',47.5:'PASS',68.75:'PASS',90.0:'PASS'}))
        self.assertEqual(dict(min_mm=5,max_mm=5,sampled_heights=1),sweep.sampled_run({5:'PASS',6:'PENDING',7:'PASS'}))
        self.assertIsNone(sweep.sampled_run({5:'BLOCK',6:'PENDING'}))

    def test_width_wins_over_number_of_refined_samples(self):
        states={float(n):'PASS' for n in range(5,20)}
        states.update({20.0:'BLOCK',26.25:'PASS',47.5:'PASS',68.75:'PASS',90.0:'PASS'})
        self.assertEqual(26.25,sweep.sampled_run(states)['min_mm'])
        self.assertEqual(90.0,sweep.sampled_run(states)['max_mm'])

    def test_prepare_cannot_reuse_results_with_new_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'cases').mkdir()
            (root/'cases'/'existing.csv').write_text('checkpoint')
            with self.assertRaisesRegex(RuntimeError,'Existing result checkpoints'):
                sweep.prepare(root)

    def test_modified_runtime_aborts_resume(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);artifact=root/'classes.jar';artifact.write_bytes(b'old')
            identity=dict(runtime=[dict(path=str(artifact),sha256=sweep.sha(artifact))])
            (root/'frozen-classpath.txt').write_text(str(artifact))
            sweep.validate_runtime(root,identity)
            artifact.write_bytes(b'changed')
            with self.assertRaisesRegex(AssertionError,'Runtime changed'):
                sweep.validate_runtime(root,identity)

    def test_refinement_exits_on_proven_blocker_but_never_on_partial_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'cases').mkdir()
            sweep.write(root/'identity.json',{})
            sweep.write(root/'frozen-fonts.json',dict(fonts=[dict(id='test',sha256='fixture')]))
            sweep.write(root/'characters.json',dict(characters=[dict(codepoint=0xac00),dict(codepoint=0xac01)]))
            header='codepoint,verdict,stitches,elapsed_ms,reasons\n'
            for h in sweep.ANCHORS:
                verdict='PASS' if h<47 else 'BLOCK';reason='' if verdict=='PASS' else 'HOOP_EXCEEDED'
                (root/'cases'/f'test--{h}mm.csv').write_text(header+f'44032,{verdict},100,1,{reason}\n44033,{verdict},100,1,{reason}\n')
            for h in range(27,48):
                (root/'cases'/f'test--{float(h)}mm.csv').write_text(header+'44032,BLOCK,100,1,HOOP_EXCEEDED\n')
            result=sweep.summarize(root,final=True)
            self.assertEqual('COMPLETE',result['status'])
            self.assertEqual(21,result['omitted_after_first_blocker'])
            self.assertEqual(0,result['pending_cases'])
            self.assertEqual(26.25,result['profiles'][0]['longest_sampled_passing_interval']['max_mm'])
            (root/'cases'/'test--27.0mm.csv').write_text(header+'44032,PASS,100,1,\n')
            result=sweep.summarize(root)
            self.assertEqual('INCOMPLETE',result['status'])
            self.assertEqual([27.0],result['profiles'][0]['pending_heights_mm'])

    def test_missing_character_never_approves_height_or_range(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'cases').mkdir()
            sweep.write(root/'identity.json',{})
            sweep.write(root/'frozen-fonts.json',dict(fonts=[dict(id='test',sha256='fixture')]))
            sweep.write(root/'characters.json',dict(characters=[dict(codepoint=0xac00),dict(codepoint=0xac01)]))
            for h in sweep.ANCHORS:
                (root/'cases'/f'test--{h}mm.csv').write_text('codepoint,verdict,stitches,elapsed_ms,reasons\n44032,PASS,100,1,\n')
            result=sweep.summarize(root,final=True)
            self.assertEqual('INCOMPLETE',result['status'])
            self.assertEqual(5,result['pending_cases'])
            self.assertIsNone(result['profiles'][0]['longest_sampled_passing_interval'])
            rules=json.loads((root/'allowed-height-ranges.json').read_text())
            self.assertFalse(rules['deployment_enabled'])


if __name__=='__main__': unittest.main()
