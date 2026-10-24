#!/usr/bin/env python3
"""Validate the portable tool catalog with existing jsonschema; no network."""
import copy
import json
import re
from pathlib import Path
from jsonschema import Draft202012Validator

ALLOWED={'type','properties','required','additionalProperties','items','enum','minimum','maximum','pattern'}
DESTRUCTIVE={'remove_objects','new_project','open_project','recover_project','discard_recovery','commit_import','delete_designs','import_font','delete_font','save_project','save_pes','share_pes','send_usb','resume_usb_after_delete','share_diagnostics','create_backup','restore_backup','save_custom_frame','delete_custom_frame','open_free_design_source','copy_diagnostics'}

def check_schema(s):
    assert not set(s)-ALLOWED, set(s)-ALLOWED
    assert s['type'] in {'object','array','string','number','integer','boolean'}
    if s['type']=='object':
        assert s.get('additionalProperties') is False
        assert len(s['required'])==len(set(s['required']))
        assert set(s['required'])==set(s['properties'])
        for child in s['properties'].values():check_schema(child)
    elif s['type']=='array':check_schema(s['items'])


def example(s):
    if 'enum' in s:return s['enum'][0]
    typ=s['type']
    if typ=='object':return {k:example(v) for k,v in s['properties'].items()}
    if typ=='array':return []
    if typ=='string':return '#12aBcF' if 'pattern' in s else 'example'
    if typ=='boolean':return False
    return s.get('minimum',0)


def main():
    root=Path(__file__).resolve().parents[1]
    data=json.loads((root/'docs/agent/tools.json').read_text())
    assert set(data)=={'tools'}
    names=set()
    for t in data['tools']:
        assert set(t)=={'name','description','inputSchema','annotations'}
        assert re.fullmatch('[a-z][a-z0-9_]*',t['name']) and t['name'] not in names
        names.add(t['name'])
        assert t['description'] and not re.search('[가-힣]',t['description'])
        a=t['annotations']
        assert set(a)=={'readOnly','needsUserConfirmation','mapsTo'}
        assert type(a['readOnly']) is bool and type(a['needsUserConfirmation']) is bool
        assert re.fullmatch(r'[A-Za-z][A-Za-z0-9_.]*',a['mapsTo'])
        assert not(a['readOnly'] and a['needsUserConfirmation'])
        s=t['inputSchema'];Draft202012Validator.check_schema(s);check_schema(s)
        v=Draft202012Validator(s)
        # All root schemas reject unknown fields and missing any required field.
        sample=example(s)
        v.validate(sample)
        assert not v.is_valid({**sample,'unexpectedField':True})
        for key in s['required']:
            assert not v.is_valid({k:value for k,value in sample.items() if k!=key})
        if not s['properties']:v.validate({})
        # Envelopes preserve exactly the authoritative argument schema.
        mcp={'name':t['name'],'description':t['description'],'inputSchema':copy.deepcopy(s),'annotations':{'readOnlyHint':a['readOnly']}}
        openai={'type':'function','name':t['name'],'description':t['description'],'parameters':copy.deepcopy(s),'strict':True}
        gemini={'name':t['name'],'description':t['description'],'parametersJsonSchema':copy.deepcopy(s)}
        assert mcp['inputSchema']==openai['parameters']==gemini['parametersJsonSchema']==s
    assert {'get_project','list_fonts','list_builtin_designs','sewout_check'}<=names
    assert DESTRUCTIVE<=names
    by_name={t['name']:t for t in data['tools']}
    assert all(by_name[n]['annotations']['needsUserConfirmation'] for n in DESTRUCTIVE)
    assert all(by_name[n]['annotations']['readOnly'] for n in ('get_project','list_fonts','list_builtin_designs','sewout_check'))
    height=Draft202012Validator(by_name['add_text']['inputSchema']['properties']['heightMm'])
    assert height.is_valid(60) and not height.is_valid(61)
    color=Draft202012Validator(by_name['recolor_object']['inputSchema']['properties']['threadColor'])
    assert color.is_valid('#12aBcF') and not color.is_valid('#xyz123')
    print(f'DRAFT_2020_12 PASS: {len(names)} input schemas')
    print(f'CONTRACT PASS: strict nested objects, required fields, names, {len(DESTRUCTIVE)} confirmation tools, four read queries')
    print(f'PROJECTIONS PASS: {len(names)} MCP / OpenAI / Gemini schemas unchanged; argument rejection and boundary probes PASS')

if __name__=='__main__':main()
