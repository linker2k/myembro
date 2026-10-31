#!/usr/bin/env python3
"""Aggregate only locally generated public-font probe records; no personal artwork."""
import collections
import json
import sys
import unicodedata
from pathlib import Path

def load(folder):
    rows=list(map(json.loads,(Path(folder)/'rows.jsonl').read_text().splitlines()))
    assert rows[-1]['complete'] and rows[-1]['completed']==1584
    rows=[row for row in rows if row.get('kind')=='matrix']
    assert len(rows)==len({(r['font'],r['glyph'],r['height']) for r in rows})==1584
    return rows
before,after=map(load,sys.argv[1:3])
assert {(r['font'],r['glyph'],r['height']) for r in before}=={(r['font'],r['glyph'],r['height']) for r in after}
print('| Height | Cases | Before PASS | Before errors | After PASS | Satin | Running fallback | Peak cell |')
print('|---|---:|---:|---:|---:|---:|---:|---:|')
for h in (15,20,30):
    b=[r for r in before if r['height']==h];a=[r for r in after if r['height']==h]
    print(f'| {h}mm | {len(a)} | {sum(r["status"]=="PASS" for r in b)} | {sum(r["status"]=="ERROR" for r in b)} | {sum(r["status"]=="PASS" for r in a)} | {sum(r.get("method")=="SATIN" for r in a)} | {sum(r.get("method")=="RUNNING" for r in a)} | {max(r["peak"] for r in a)} |')
hand=[r for r in after if unicodedata.normalize('NFD',r['glyph'])[-1]=='\u309a']
summary={
 'before':dict(collections.Counter(r['status'] for r in before)),
 'after':dict(collections.Counter(r['status'] for r in after)),
 'before_cycles':sum('Conflicting component row-order' in r.get('error','') for r in before),
 'after_cycles':sum('Conflicting component row-order' in (r.get('error','') or r.get('fallback_reason','') or '') for r in after),
 'uncovered_underpaths':sum(r.get('uncovered_underpaths',0) for r in after),
 'peak_cell':max(r['peak'] for r in after),
 'handakuten':{'bean':sum(int(r.get('bean_marks') or 0)>0 for r in hand),
               'satin':sum(int(r.get('satin_marks') or 0)>0 for r in hand),
               'running_fallback':sum(r.get('method')=='RUNNING' for r in hand)},
 'fallback_reasons':dict(collections.Counter(r.get('fallback_reason','no exception message') for r in after if r.get('method')=='RUNNING'))}
print(json.dumps(summary,ensure_ascii=False,indent=2))
assert all(r['status'] not in ('ERROR','BLOCK','UNSUPPORTED') for r in after)
assert summary['after_cycles']==summary['uncovered_underpaths']==0
