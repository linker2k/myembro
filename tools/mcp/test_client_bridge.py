#!/usr/bin/env python3
"""TEST ONLY: one explicitly authorized new PES, or replacement of a test-owned seed.
Production hosts obtain human consent and attach tokens in tools/call _meta.
No other name, destination or overwrite is approved. Audit both handshake messages.
"""
import hashlib,json,subprocess,sys
from pathlib import Path
launcher,destination,approved_name,audit,*extra=sys.argv[1:]
assert Path(approved_name).name==approved_name and approved_name.endswith('.pes')
seed_sha=extra[0] if extra else None
target=Path(destination)/approved_name
if seed_sha:assert target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest()==seed_sha
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
   if q.get('method')=='initialize':
    scope=f'replace ONE test-owned seed {approved_name} (sha256={seed_sha}) after confirmation' if seed_sha else f'create ONE new file {approved_name} with overwrite=false'
    r['result']['instructions']+=f' Test host: explicit user integration-test authorization covers only {scope}, destinationId=output. Host handles the _meta handshake only within this scope.'
   if q.get('method')=='tools/call' and q.get('params',{}).get('name')=='save_pes' and not used:
    a=q['params'].get('arguments',{});result=json.loads(r.get('result',{}).get('content',[{'text':'{}'}])[0].get('text','{}'))
    bounded=a.get('name')==approved_name and a.get('destinationId')=='output'
    unchanged=bool(seed_sha and target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest()==seed_sha)
    authorized=(unchanged if seed_sha else a.get('overwrite') is False and not target.exists())
    if result.get('code')=='CONFIRMATION_REQUIRED' and bounded and authorized:
     retry_args=result.get('retryArguments',a)
     assert retry_args.get('name')==approved_name and retry_args.get('destinationId')=='output'
     assert retry_args.get('overwrite') is bool(seed_sha)
     used=True;retry=dict(q);retry['id']='trusted-host-confirm';retry['params']=dict(q['params'],arguments=retry_args,_meta={'myembro.confirmToken':result['confirmToken']})
     log.write(json.dumps({'authorization':'Explicit user CLI-test request; only this test-owned target','name':approved_name,'seedSha256':seed_sha,'unchangedBeforeConfirmation':unchanged if seed_sha else not target.exists()})+'\n');log.flush()
     r=exchange(retry);r['id']=q['id']
   print(json.dumps(r,ensure_ascii=False),flush=True)
 finally:
  child.stdin.close()
  try:child.wait(timeout=5)
  except subprocess.TimeoutExpired:child.terminate();child.wait(timeout=5)
