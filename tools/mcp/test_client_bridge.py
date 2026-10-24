#!/usr/bin/env python3
"""TEST ONLY: explicit user authorization covers one new named PES, no other action.
Production hosts must obtain user consent and pass the token in tools/call _meta.
This bridge audits the first CONFIRMATION_REQUIRED response and token replay.
"""
import json, subprocess, sys
from pathlib import Path
launcher,destination,approved_name,audit=sys.argv[1:]
assert Path(approved_name).name==approved_name and approved_name.endswith('.pes')
child=subprocess.Popen([launcher,destination],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True,encoding='utf-8')
used=False
with open(audit,'a',encoding='utf-8') as log:
 def exchange(q):
  child.stdin.write(json.dumps(q,ensure_ascii=False)+'\n');child.stdin.flush()
  if 'id' not in q:return None
  r=json.loads(child.stdout.readline());log.write(json.dumps({'request':q,'response':r},ensure_ascii=False)+'\n');log.flush();return r
 try:
  for line in sys.stdin:
   q=json.loads(line);r=exchange(q)
   if r is None:continue
   if q.get('method')=='initialize':r['result']['instructions']+=f' Test host: user explicitly authorized ONE new file {approved_name}, destinationId=output, overwrite=false; host handles _meta handshake for that save only.'
   if q.get('method')=='tools/call' and q.get('params',{}).get('name')=='save_pes' and not used:
    a=q['params'].get('arguments',{});result=json.loads(r.get('result',{}).get('content',[{'text':'{}'}])[0].get('text','{}'))
    if result.get('code')=='CONFIRMATION_REQUIRED' and a.get('name')==approved_name and a.get('destinationId')=='output' and a.get('overwrite') is False:
     used=True;retry=dict(q);retry['id']='trusted-host-confirm';retry['params']=dict(q['params'],_meta={'myembro.confirmToken':result['confirmToken']})
     log.write(json.dumps({'authorization':'Explicit user CLI-test request; one new PES only','name':approved_name})+'\n');log.flush()
     r=exchange(retry);r['id']=q['id']
   print(json.dumps(r,ensure_ascii=False),flush=True)
 finally:
  child.stdin.close()
  try:child.wait(timeout=5)
  except subprocess.TimeoutExpired:child.terminate();child.wait(timeout=5)
