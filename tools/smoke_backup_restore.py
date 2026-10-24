#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Opt-in SAF restore smoke. ONLY the isolated backupsmoke app is cleared.
First create a synthetic backup through its settings UI; provide the device ZIP path.
"""
import argparse,time,subprocess,json,zipfile,io,hashlib,xml.etree.ElementTree as E,shlex,re,pathlib
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--zip',required=True,help='Device path of the synthetic ZIP already saved from backupsmoke')
parser.add_argument('--adb',default='/home/gosh2/Android/Sdk/platform-tools/adb')
parser.add_argument('--serial',default='emulator-5554')
parser.add_argument('--screenshots',default='docs/qa/backup-restore')
args=parser.parse_args()
A=[args.adb,'-s',args.serial]
P='io.github.linker2k.myembro.backupsmoke'
zip_name=pathlib.PurePosixPath(args.zip).name
out=pathlib.Path(args.screenshots);out.mkdir(parents=True,exist_ok=True)
def adb(*a):return subprocess.check_output(A+list(a),text=True)
def ui():
 for _ in range(5):
  adb('shell','uiautomator','dump','/sdcard/backup-smoke.xml')
  try:return list(E.fromstring(adb('exec-out','cat','/sdcard/backup-smoke.xml')).iter('node'))
  except E.ParseError:time.sleep(.2)
 raise AssertionError('No UI snapshot')
def tap(text):
 n=next(n for n in ui() if n.get('text')==text or n.get('content-desc')==text)
 x,y,r,b=map(int,re.findall(r'\d+',n.get('bounds')));adb('shell','input','tap',str((x+r)//2),str((y+b)//2))
def shot(name):
 with (out/(name+'.png')).open('wb') as f:subprocess.run(A+['exec-out','screencap','-p'],stdout=f,check=True)

assert P.endswith('.backupsmoke')
def wait(text):
 for i in range(12):
  if any(n.get('text')==text or n.get('content-desc')==text for n in ui()):return
  time.sleep(.2)
 raise AssertionError('Missing '+text)
def raw(*args):return subprocess.check_output(A+list(args))
def start():
 adb('shell','am','start','-n',P+'/io.github.linker2k.myembro.MainActivity');wait('Settings');tap('Settings');wait('Save backup')
def compare(manifest):
 expected={e['path']:e for e in manifest['files']}
 names=[p for p in adb('shell','run-as',P,'find','files','-type','f').splitlines() if p.removeprefix('files/').split('/')[0] in ('designs','projects','fonts','drafts','usb-transfers.json')]
 assert set(p.removeprefix('files/') for p in names)==set(expected),(len(names),len(expected))
 for path,e in expected.items():
  b=raw('exec-out','run-as',P,'cat',shlex.quote('files/'+path))
  assert len(b)==e['size'] and hashlib.sha256(b).hexdigest()==e['sha256']
 for name,prefs in manifest['preferences'].items():
  if not prefs:continue
  xml=E.fromstring(raw('exec-out','run-as',P,'cat','shared_prefs/'+name+'.xml'))
  actual={}
  for node in xml:
   kind=node.tag;key=node.get('name')
   if kind=='string':value=node.text or ''
   elif kind=='set':value=sorted(x.text or '' for x in node)
   elif kind=='boolean':value=node.get('value')=='true'
   elif kind in ('int','long'):value=int(node.get('value'))
   elif kind=='float':value=float(node.get('value'))
   else:raise AssertionError(kind)
   actual[key]={'type':kind,'value':value}
  assert actual==prefs,(name,actual,prefs)
 print('ZIP_MANIFEST_COMPARE_PASS files='+str(len(expected))+' sha256_equal=true typed_preferences_equal=true',flush=True)
zipbytes=raw('exec-out','cat',args.zip)
manifest=json.loads(zipfile.ZipFile(io.BytesIO(zipbytes)).read('manifest.json'))
adb('shell','am','force-stop',P);start();shot('settings-en');compare(manifest)
print('PM_CLEAR_ISOLATED '+adb('shell','pm','clear',P).strip(),flush=True)
start();tap('Restore ZIP');wait(zip_name);tap(zip_name);wait('Restore backup?');shot('restore-confirm-en');tap('Skip duplicates');wait('Close');shot('restore-result-en');compare(manifest);tap('Close')
adb('shell','cmd','locale','set-app-locales',P,'--user','0','--locales','ko-KR');wait('백업 저장');shot('settings-ko');tap('ZIP 복원');wait(zip_name);tap(zip_name);wait('백업을 복원할까요?');shot('restore-confirm-ko');tap('취소');wait('백업 저장');compare(manifest)
adb('shell','cmd','locale','set-app-locales',P,'--user','0','--locales','')
print('SAF_PM_CLEAR_RESTORE_AND_CANCEL_SMOKE_PASS en_ko=true',flush=True)
