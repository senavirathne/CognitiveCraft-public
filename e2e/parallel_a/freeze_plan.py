"""A10's complete branch-local gate denominator, separate from I00's integrated campaign."""
from pathlib import Path
from activity_cases import CASES as ACTIVITY
from claims_cases import CASES as CLAIMS
from lifecycle_cases import LIVE_CASES,DEATH_CASES,HANDOFF_CASES,contexts
from recovery_cases import originals

HERE=Path(__file__).resolve().parent
BASE='ea7de8000b2b484b885576c34ed5358274936ce9'
MODULES={'core':2439,'providers':131,'fabric':133}
REQUIRED_JOBS=('required_accomplishment_evidence_acceptance','required_knowledge_apprenticeship_acceptance',
 'required_primitive_diagnostics_diagnostics','required_goal_planning_acceptance','required_observed_knowledge_observations',
 'required_world_retention_retention','required_save_compatibility_compatibility','required_blueprint_boundary_acceptance','required_prerequisites_prerequisites')
STORAGE_CUTS=('after_segment_ack','after_directory_ack','before_hot_publication','after_hot_acknowledgement')
CONTROLS=('source-replacement-HARVEST','source-replacement-PICKUP','source-replacement-DEPOSIT',
          'unrelated-native-progress','unsaved-pickup-containment','two-unit-prepared-no-effect',
          'two-unit-delivered-remaining-one','expired-original-accounting','representative-active-cost')
CASES={
 'A00':('supported-gather-deposit','foreign-principal-negative','unflushed-probe','saved-restart-signature'),
 'A01':('legacy-positive','repeated-adoption-cancel-private-view'),
 'A02':('legacy-positive-and-baseline','real-world-compatibility-and-acknowledged-history',
        'reserved-metadata-under-ordinary-pressure','history-enabled-native-positive','unknown-history-fence-and-whole-world-rollback'),
 'A03':('legacy-native-positive','continuous-129-retired-native-leases',
        *('native-cut-'+s for s in STORAGE_CUTS),'native-archive-pressure-no-effect-refusal'),
 'A04':('native-positive-control','actual-old-job-wall','continuous-native-over-64-terminal-jobs',
        *('native-job-cut-'+s for s in STORAGE_CUTS),*('native-required-'+s for s in ('segment-missing','segment-corrupt','version-missing'))),
 'A05':('saved-positive-repeated-adoption','negative-equal-count-swap','negative-components','negative-replacement','unloaded-participants',
        'unsaved-before-mutation','unsaved-after-mutation','unsaved-after-journal','saved-prepared-no-effect',
        'journal-ahead-saved-prepared','actor-saved-chest-prepared','chest-saved-actor-prepared',
        'native-storage-full-quota','native-storage-corrupt-current','required-reader-baseline-and-envelope-candidate-400-tick-cost'),
 'A06':tuple(c['id'] for c in originals(HERE/'a06_original_cases.json'))+CONTROLS,
 'A07':LIVE_CASES+DEATH_CASES+HANDOFF_CASES+tuple(r['case']['id'] for r in contexts(HERE/'a07_w04_contexts.json')),
 'A08':CLAIMS,
 'A09':ACTIVITY,
}
MODULE_NAMES={'A00':'baseline','A01':'activity','A02':'persistence','A03':'leases','A04':'jobs',
              'A05':'deposits','A06':'recovery_native','A07':'lifecycle_connected','A08':'claims_connected','A09':'activity_connected'}
COMPOSE={
 'A00':(), 'A01':('activity',), 'A02':('activity','persistence'), 'A03':('activity','leases'),
 'A04':('activity','leases','jobs'), 'A05':('deposits',), 'A06':('deposits',),
 'A07':('deposits','lifecycle'), 'A08':('deposits','lifecycle','claims'),
 'A09':('deposits','lifecycle','claims','activity-runtime'),
}
JAVA_IMAGE='eclipse-temurin@sha256:80f67fb3d6f76e2d817d98a4cc2a9cf05f054d4e6adc5e2144bde671d722e63f'
CLIENT_IMAGE='mcr.microsoft.com/playwright/python@sha256:a7f6cf3ae520c9d670ad956572c13747ed5abdbba5123a01526f873ed1662528'

def compose_files(profile):
    if profile not in CASES:raise ValueError('Unknown A10 profile')
    return ['e2e/compose.yml',*('e2e/parallel_a/compose-'+s+'.yml' for s in COMPOSE[profile]),'e2e/parallel_a/compose-freeze.yml']

def ledger(profile,rows):
    expected=CASES[profile];seen={};out=[]
    for row in rows:
        key=row.get('id') or row.get('originalCase',{}).get('id')
        if key not in expected or key in seen:raise ValueError('Duplicate/unplanned native case: '+str(key))
        seen[key]=row
    for key in expected:
        row=seen.get(key)
        out.append({'id':key,'collectionStatus':row.get('status') if row else 'NOT_EXECUTED_REQUIRED'})
    return out

def plan():
    assert set(CASES)==set(MODULE_NAMES)==set(COMPOSE) and all(len(v)==len(set(v)) for v in CASES.values())
    return {'schema':1,'stage':'A10','commonSource':BASE,'profiles':{k:list(v) for k,v in CASES.items()},
            'requiredProfiles':len(CASES),'requiredGateCases':sum(map(len,CASES.values())),'targetModules':MODULES,
            'nativeLifecycleDiagnostics':14,'requiredRegressionSuites':9,'sourceRegressionJobs':12,'sourceRegressionSteps':140,
            'sameProductionJarRequired':True,'sameCompiledInstrumentationRequired':True,'plannerEnabled':False,
            'omittedTasks':0,'omittedTests':0,'claim':'Mixed native E2E, native host and metadata-fault gates retain their original classifications; not121 gameplay successes or I00.'}
