#!/usr/bin/env python3
"""Final C-only readiness. The CLI derives evidence through the independent raw oracle, never a PASS JSON input."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from common import CONTRACT,HERE,JAR,ROOT,EvidenceError,check,read,write
from predecessor import configuration

ADAPTERS=[
    dict(name='InteractionIngress',owner='J00/J01',requires='Native authenticated world/principal/citizen/session identity and validated server selection; no identifiers or coordinates trusted from language.'),
    dict(name='ActivityEvidencePort',owner='J03',requires='Read actual qualified A/B observations with exact original root/owner/attempt/generation/revision/budget and effect-time disclosure.'),
    dict(name='A assistance persistence',owner='J03',requires='A-owned durable wait, original root and cumulative deadline/budgets, custody/save lineage, authoritative cancel/amend and reobservation.'),
    dict(name='D selector bridge',owner='J04',requires='Translate C candidates to qualified D storage/citizen resolution, revalidate ACL/identity generations and distinguish inspect from deposit/withdraw/admin.'),
    dict(name='Native storage selection',owner='J01/J04',requires='Server-produced raycast/reach/type/incarnation/expiry witness; no client/model coordinates or invented native storage facts.'),
    dict(name='Terminal-status projection',owner='J03/J05',requires='Authenticate actual A original-owner settlement and participant-save proof; messages, claims, live counts and journal acknowledgements cannot substitute.')]

def expected_inventory(registry):
    """Bind final coverage to the immutable qualified C07 source, not a mutable preceding registry."""
    pins,_,_,_=configuration('C07')
    raw=subprocess.check_output(['git','-C',str(ROOT),'show',pins['source_sha']+':e2e/parallel_c/registries/C07.json'])
    check(hashlib.sha256(raw).hexdigest()==pins['registry_sha256'],'qualified predecessor registry changed')
    previous=json.loads(raw)
    units=previous['unit_cases']+previous['regression_unit_cases']
    python=previous['python_cases']+previous['regression_python_cases']
    check(registry['baseline_module_cases']==previous['baseline_module_cases']==2307,'frozen module baseline changed')
    check(registry['unit_cases']==[] and registry['regression_unit_cases']==units,'C08 must retain every qualified C Java method')
    check(registry['regression_python_cases']==python,'C08 must retain every qualified C Python method')
    for field in ('provider_cases','dialogue_sequence_cases','selector_variation_cases','client_cases','language_cases'):
        check(registry[field]==previous[field],'final inherited schedule changed: '+field)
    expected=dict(unit=[c['xml_id'] for c in units],python=python+registry['python_cases'],
                  clients=[c['id'] for c in previous['client_cases']],language=[c['id'] for c in previous['language_cases']],
                  provider=[c['id'] for c in previous['provider_cases']],selectors=[c['id'] for c in previous['selector_variation_cases']],
                  dialogue=[c['id'] for c in previous['dialogue_sequence_cases']])
    for key,values in expected.items():check(len(values)==len(set(values)),'duplicate final registry identity: '+key)
    return expected

def promote(verified,observed,required):
    """Pure final consistency gate. Observations passed here are derived by verify.qualify's raw readers."""
    check(verified['stage']=='C08' and verified['gate_status']=='OWNED_GATES_PASS','complete independent owned oracle required')
    check(verified['contract_sha256']==observed['contract_sha256']==CONTRACT,'invalid contract cannot become BRANCH_READY')
    for key in ('source_sha','tree_sha','docs_sha'):
        check(re.fullmatch('[0-9a-f]{40}',observed.get(key) or '') is not None,'missing immutable final '+key)
    check(observed['source_sha']==verified['source_sha'] and observed['tree_sha']==verified['tree_sha'],'final source/tree mismatch')
    check(re.fullmatch('[0-9a-f]{64}',verified['jar_sha256']) is not None,'missing final release hash')
    check(set(observed['jars'])=={'candidate','native_regression','native_safety','provider','compiler','dialogue','selectors','benchmark'},'missing single-JAR phase identity')
    check(all(sha==verified['jar_sha256'] for sha in observed['jars'].values()),'mismatched JAR blocks BRANCH_READY')
    for key,expected in required.items():
        actual=observed['cases'][key]
        check(len(actual)==len(set(actual))==len(expected) and set(actual)==set(expected),'missing/duplicate final case: '+key)
    counts=verified['counts']
    for key,n in [('unit',2307+len(required['unit'])),('python',len(required['python']))]:
        m=counts[key];check(m['planned']==m['executed']==m['passed']==n and m['failed']==m['refused_expected']==m['inconclusive']==0,'incomplete final '+key)
    check(verified['client_executed']==len(required['clients'])==3 and verified['client_tier']=='COMPONENT_CLIENT','real final client replay required')
    check(verified['language_messages_executed']==len(required['language'])==60 and observed['language_counts']==dict(planned=60,executed=60,passed=42,refused_expected=18,failed=0,inconclusive=0),'final language outcomes changed')
    check(verified['genuine_provider_executed']==len(required['provider'])==6 and verified['selector_variations_executed']==len(required['selectors'])==100
          and verified['dialogue_sequences_executed']==len(required['dialogue'])==100,'required final provider/parser campaigns missing')
    check(verified['native_executed']==0 and observed['job_calls']==observed['world_operation_calls']==0,'C cannot claim physical effects')
    check(all(observed.get(k) is None for k in ('original_root_id','original_owner_id','a_settlement')) and observed['production_activation'] is False,'false A settlement/production activation blocks readiness')
    check(verified['workflow_run'] is not None and verified['attempt'] is not None and verified['workflow_run']>0 and verified['attempt']>0,'C08 requires actual CI')
    check(re.fullmatch('sha256:[0-9a-f]{64}',verified.get('container_image_id') or '') is not None,'final Docker identity required')
    check(observed['ownership_status']=='OWNED_GATES_PASS' and observed['predecessor_status']==observed['docs_status']=='PASS','complete ownership/predecessor/docs gates required')
    return dict(verified,gate_status='BRANCH_READY',branch_ready=True,scope='C_OWNED_COMPONENT_ONLY',docs_sha=observed['docs_sha'],docs_sha_policy='Immutable qualification-input docs; final report commit is pinned externally after publication.',
                production_activation=False,main_merged=False,joint_stages_started=False,original_root_id=None,original_owner_id=None,a_settlement=None,
                replay_suite=dict(id='C08-CL01',status='PASS',child_suites=required['clients'],transport_steps=78,language_messages=60,additional_executed_credit=0),
                physical_activity=dict(status='BLOCKED_DEPENDENCY',executed=0,owner='J02/J03/J04/J05'),restart_proof=dict(status='BLOCKED_DEPENDENCY',executed=0,owner='J03/J05'),
                required_j_adapters=ADAPTERS,joint_stage_status='DEFERRED_NOT_EXECUTED',joint_prerequisites=['C08','D11','A10','qualified A+B I00'],
                claim='BRANCH_READY for C-owned gates only; no production player assistance, physical activity, restart or A settlement proof')

def compatibility(result,live,native_faults):
    return dict(schema='cc.c.language-provider-compatibility/1',stage='C08',source_sha=result['source_sha'],tree_sha=result['tree_sha'],jar_sha256=result['jar_sha256'],
                wire_schema='cc.cd/1.0.0',contract_sha256=CONTRACT,candidate_contract='Strict untrusted DialogueIntent/PlayerReplyIntent and exact source grounding; no model/native authority constructors',
                language=dict(implemented='Finite English grammar and English phrasing/templates',other_locales='Explicit English fallback',names='NFC labels; frozen C05 role/anchor/type/casefold policy; names confer no identity/access'),
                spi=dict(input='LlmProvider.Request(system,user,jsonSchema,maxOutputTokens)',output='Response(text,provider,model)',operation='generate(Request): CompletableFuture<Response>',actual_usage='UNKNOWN/null',remote_stop_confirmed=False),
                model=dict(model_sha256=live['model_sha256'],tokenizer_sha256=live['tokenizer_sha256'],build=live['configured_build'],profile=live['backend_profile'],model_alias=live['model_alias'],
                           accepted_responses=live['accepted_model_responses'],actual_generation_cases=6,grammar='response_format=json_object with schema; finite replies and exact facts digest'),
                limits=dict(input_tokens=2048,output_tokens=256,provider_calls=2,deadline_ms=10000,concurrency=2,pair_interval_ms=2000),
                native_client_path='C03 OFFLINE template; separate declared synthetic malformed/cancelled outputs use actual pinned tokenizer and receive no genuine-generation credit',
                fault_controls=native_faults,privacy='Raw chat, private labels, roots and model-proposed authority never enter the phrasing fact packet',
                activity_facts_tier='SYNTHETIC_CONTRACT',client_tier='COMPONENT_CLIENT',physical_activity=result['physical_activity'],restart_proof=result['restart_proof'],required_j_adapters=ADAPTERS)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--source',required=True);p.add_argument('--tree',required=True);p.add_argument('--jar',type=Path,default=JAR);p.add_argument('--docs-root',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    try:
        from verify import qualify
        result=qualify(a.root,'C08',a.source,a.tree,a.jar,a.docs_root);code=0
    except (EvidenceError,OSError,ValueError,KeyError) as failure:
        result=dict(stage='C08',gate_status='FAIL',branch_ready=False,error=str(failure),native_executed=0,client_executed=0,production_activation=False);code=2
    write(a.output,result);print(json.dumps(result,sort_keys=True));return code
if __name__=='__main__':raise SystemExit(main())
