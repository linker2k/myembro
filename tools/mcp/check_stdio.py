#!/usr/bin/env python3
"""Actual process check: stdout is JSON-RPC only, exact schemas, parse errors and EOF exit."""
import json, subprocess, sys, tempfile
from pathlib import Path
launcher=Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix='myembro-mcp-stdio-') as out:
 requests=[{'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-06-18','capabilities':{},'clientInfo':{'name':'stdio-test','version':'1'}}},
           {'jsonrpc':'2.0','method':'notifications/initialized'},
           {'jsonrpc':'2.0','id':2,'method':'tools/list'}]
 data='\n'.join(json.dumps(q) for q in requests)+'\nnot-json\n'
 p=subprocess.run([str(launcher),out],input=data,text=True,capture_output=True,timeout=30,check=True)
 rows=[json.loads(line) for line in p.stdout.splitlines()]
 assert len(rows)==3, rows
 expected=json.loads((Path(__file__).resolve().parents[2]/'docs/agent/tools.json').read_text())
 assert rows[1]['result']==expected
 assert rows[2]['error']['code']==-32700
 assert rows[0]['result']['serverInfo']['name']=='myembro'
 print('MCP_STDIO PASS: JSON-only stdout; exact 69 schemas; parse error; EOF exit')
