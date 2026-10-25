#!/usr/bin/env python3
"""Check actual tool arguments, measured placement, consent replay and saved files; no model prose."""
import hashlib,json,sys
from pathlib import Path
base=Path(sys.argv[1]);seed=json.loads((base/'seed.json').read_text());results=[]
for client in ('claude','codex'):
 rows=[json.loads(line) for line in (base/(client+'-rpc.jsonl')).read_text().splitlines()]
 calls=[r for r in rows if r.get('request',{}).get('method')=='tools/call']
 def named(name):return [r for r in calls if r['request']['params']['name']==name]
 def output(r):return json.loads(r['response']['result']['content'][0]['text'])
 def arguments(r):return r['request']['params']['arguments']
 design=arguments(named('add_design')[-1]);text=arguments(named('add_text')[-1]);emoji=arguments(named('prepare_emoji')[-1]);art=arguments(named('add_generated_art')[-1])
 assert design['assetId']=='builtin:myembro-logo'
 assert text['text']=='안녕' and text['heightMm']==15
 assert emoji['emojiId']=='red-heart' and emoji['widthMm']==20
 assert art['resultId']==output(named('prepare_emoji')[-1])['resultId']
 states=[output(r) for r in named('get_project') if output(r).get('ok')]
 state=states[-1];project=state['project'];objects={o['id']:o for o in project['objects']}
 assert len(objects)==3 and state['undoCount']==1
 logo,label,heart=[objects[k] for k in (design['objectId'],text['objectId'],art['objectId'])]
 assert label['text']=='안녕' and label['heightMm']==15
 assert logo['transform']['scaleX']==logo['transform']['scaleY']==1
 b=[o['placedBoundsMm'] for o in (logo,label,heart)]
 assert b[1]['minYMm']>b[0]['maxYMm']
 assert b[2]['minXMm']>b[0]['maxXMm']
 assert b[2]['minYMm']+b[2]['maxYMm'] < b[0]['minYMm']+b[0]['maxYMm']
 for bounds in b:
  for axis,dimension in [('X','widthMm'),('Y','heightMm')]:
   units=round(project['hoop'][dimension]*10)
   assert round(bounds['min'+axis+'Mm']*10)+units//2>=0
   assert round(bounds['max'+axis+'Mm']*10)+units//2<=units-1
 pes=output(named('prepare_pes')[-1]);assert pes['ok'] and pes['checks']['report']['verdict']=='PASS'
 saves=named('save_pes');first=output(saves[0]);last=output(saves[-1])
 assert first['code']=='CONFIRMATION_REQUIRED' and first['needsUserConfirmation'] is True
 assert saves[-1]['request']['params']['_meta']['myembro.confirmToken']==first['confirmToken']
 assert arguments(saves[-1])==first['retryArguments'] and last['ok'] and last['overwritten']
 authorization=[r for r in rows if 'authorization' in r][-1]
 assert authorization['seedSha256']==seed['sha256'] and authorization['unchangedBeforeConfirmation'] is True
 path=base/client/'composition.pes';payload=path.read_bytes();digest=hashlib.sha256(payload).hexdigest()
 assert payload.startswith(b'#PES') and digest!=seed['sha256']
 results.append({'client':client,'verdict':'PASS','path':str(path),'bytes':len(payload),'sha256':digest,'objects':3,'singleUndo':True,'geometry':'below and upper-right; in hoop; original logo scale','overwrite':'seed unchanged before confirmation; token replay; replaced'})
restored=json.loads((base/'config-restored.json').read_text());assert restored['restored'] and restored['temporaryEntryRemoved']
(base/'composition-assertions.json').write_text(json.dumps({'verdict':'PASS','results':results,'configRestored':True},ensure_ascii=False,indent=2))
for r in results:print('CLIENT_COMPOSITION PASS',r['client'],r['bytes'],'bytes; geometry + overwrite verified')
print('CONFIG_RESTORED PASS')
