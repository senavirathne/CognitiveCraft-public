"""Read-only accounting of actual native unload/load cycles, including repeated callbacks."""
def review(initial,final,entity,before_generation,after_generation):
    assert initial['process']==final['process'] and initial['tick']<=final['tick']
    assert initial['entities'][entity]['loaded'] and final['entities'][entity]['loaded']
    loaded=True;cycles=0;events=[];duplicates=[];previous=initial['tick']
    for event in final['nativeEvents']:
        if event.get('entity')!=entity or event['tick']<=initial['tick']:continue
        assert previous<=event['tick']<=final['tick'];previous=event['tick']
        if event['event'] not in ('ENTITY_UNLOAD','ENTITY_LOAD'):continue
        present=event['event']=='ENTITY_LOAD'
        if present==loaded:duplicates.append(event);continue
        loaded=present;events.append(event)
        if loaded:cycles+=1
    assert loaded and cycles>=1,'No complete native unload/reload cycle'
    assert after_generation==before_generation+cycles,'Generation differs from actual native reload count'
    return {'status':'PASS_EXACT_NATIVE_LIFECYCLE_CYCLES','entity':entity,'process':initial['process'],
            'beforeGeneration':before_generation,'afterGeneration':after_generation,'reloadCycles':cycles,
            'nativeTransitions':events,'duplicateCallbacksWithoutAdditionalGeneration':duplicates}
