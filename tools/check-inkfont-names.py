#!/usr/bin/env python3
"""S2/S3 names: existing independent gate + count/shape oracle comparison. No Ink/Stitch execution."""
import argparse,copy,hashlib,json,pathlib,subprocess,sys,xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--root',default='/tmp/myembro-port-out/letter/jungmin');p.add_argument('--oracle',default='/tmp/myembro-inkstitch-oracle/out/letter-jungmin');a=p.parse_args()
root=pathlib.Path(a.root);inp=root/'inputs';oracle=pathlib.Path(a.oracle)
cases=json.loads((root/'cases.json').read_text())['cases'];assert 0<len(cases)<=3
SVG='http://www.w3.org/2000/svg';INK='http://inkstitch.org/namespace';ISC='http://www.inkscape.org/namespaces/inkscape'
font=ET.parse(inp/'ltr.svg').getroot();assert font.attrib['viewBox']=='0 0 100 100' and font.attrib['width']=='100'
glyphs={e.get('{'+ISC+'}label')[11:]:e for e in font.iter() if e.get('{'+ISC+'}label','').startswith('GlyphLayer-')}
# This fixture has direct root glyph layers: avoid silently dropping ancestor transforms/styles.
assert all(glyphs[c] in list(font) for c in 'JungMin')
rows=[]
python=pathlib.Path('/tmp/myembro-quality-gate/quality-gate').read_text().splitlines()[0][2:]
def needles(path):
 code='import json,pyembroidery as e,sys;p=e.read_pes(sys.argv[1]);print(json.dumps([[x/10,y/10] for x,y,c in p.stitches if (int(c)&255)==e.STITCH]))'
 return json.loads(subprocess.check_output([python,'-c',code,str(path)],text=True))
for c in cases:
 tree=ET.Element('{'+SVG+'}svg',{'width':'1000','height':'1000','viewBox':'0 0 1000 1000','version':'1.1'})
 md=ET.SubElement(tree,'{'+SVG+'}metadata');ET.SubElement(md,'{'+INK+'}inkstitch_svg_version').text='4'
 boundary=True
 for i,place in enumerate(c['placements']):
  wrap=ET.SubElement(tree,'{'+SVG+'}g',{'transform':f"translate({place['dx_px']},{place['dy_px']}) scale({place['scale']})"})
  g=copy.deepcopy(glyphs[place['glyph']]);g.set('style',g.get('style','').replace('display:none','display:inline'))
  for node in g.iter('{'+SVG+'}path'):
   if boundary and c.get('force_boundary_locks'):node.set('{'+INK+'}force_lock_stitches','true')
   boundary=node.get('{'+INK+'}trim_after','').lower() in ('true','1','yes') or node.get('{'+INK+'}stop_after','').lower() in ('true','1','yes')
  for el in g.iter():
   if 'id' in el.attrib:el.set('id',f"glyph{i}-"+el.get('id'))
  wrap.append(g)
 svg=inp/(c['id']+'.svg');ET.ElementTree(tree).write(svg,encoding='utf-8',xml_declaration=True)
 c['input']=str(svg);c['input_sha256']=hashlib.sha256(svg.read_bytes()).hexdigest()
 report=root/(c['id']+'-quality.json')
 cmd=['/tmp/myembro-quality-gate/quality-gate','check',c['path'],'--text','--style','satin','--json',str(report)]
 cp=subprocess.run(cmd,capture_output=True,text=True);(root/(c['id']+'-quality.log')).write_text(cp.stdout+cp.stderr)
 result=json.loads(report.read_text());c['gate']=result['verdict'];c['gate_reasons']=result['results'][0]['reasons']
 reference=oracle/(c['id']+'.pes');c['oracle_path']=str(reference)
 c['oracle']={'verdict':'미판정','reason':'official PES pending'}
 if reference.exists():
  import numpy as np
  from scipy.spatial import cKDTree
  actual=np.asarray(needles(pathlib.Path(c['path'])));ref=np.asarray(needles(reference))
  actual-= (actual.min(0)+actual.max(0))/2;ref-=(ref.min(0)+ref.max(0))/2
  count=abs(len(actual)-len(ref))/len(ref);haus=max(cKDTree(actual).query(ref)[0].max(),cKDTree(ref).query(actual)[0].max())
  criteria=json.loads(pathlib.Path('/tmp/myembro-inkstitch-oracle/equivalence-criteria.json').read_text())
  c['oracle']={'verdict':'동등' if count<=criteria['stitch_count_relative_difference_max'] and haus<=criteria['symmetric_needle_coordinate_hausdorff_max_mm'] else '불일치',
   'count_relative_delta':count,'hausdorff_mm':float(haus),'scope':'stitch count and shape only; bbox center translation only',
   'reference_sha256':hashlib.sha256(reference.read_bytes()).hexdigest()}
 c['sha256']=hashlib.sha256(pathlib.Path(c['path']).read_bytes()).hexdigest()
 rows.append(c);print(c['id'],'gate',c['gate'],'oracle',c['oracle']['verdict'],c['gate_reasons'])
manifest={'font_id':'aventurina','text':'Jung Min','font_data':'official v3.3.0 release data; local only, not bundled',
 'license_file':str(inp/'LICENSE'),'cases':rows}
(inp/'request.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
(root/'validation.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
# A missing oracle or failed gate is never success. Reference processing is explicitly independent.
sys.exit(1 if any(c['gate']!='PASS' or c['oracle']['verdict']=='불일치' for c in rows) else 2 if any(c['oracle']['verdict']=='미판정' for c in rows) else 0)
