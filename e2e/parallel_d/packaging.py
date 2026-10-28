"""Inspect actual ZIP contents, descriptors and class bytes, never packaging flags."""
import json
from zipfile import ZipFile
from common import HERE, REPO, BASE, git, require

ENTRY = 'dev.aivillages.e2e.paralleld.DGameTests'
ENTRIES = [ENTRY]+['dev.aivillages.e2e.paralleld.'+n for n in ('DStorageIncarnationGameTests','DStorageTopologyGameTests','DStorageTopologyReloadGameTests','DStorageLifecycleCostGameTests','DStorageBaselineGameTests','DStorageAdaptersGameTests','DComeGameTests')]
TEST_PREFIX = 'dev/aivillages/e2e/paralleld/'

def fixture_descriptor(value):
    require(value['id'] == 'cognitivecraft_parallel_d_tests','wrong fixture mod identity')
    require(value.get('entrypoints') == {'fabric-gametest':ENTRIES},'non-D or automatic fixture entrypoint')
    require(value.get('mixins') == ['parallel-d.mixins.json'],'wrong fixture mixins')

def inspect_release(path):
    with ZipFile(path) as archive:
        names = archive.namelist(); require(len(names) == len(set(names)),'duplicate release ZIP entries')
        for name in names:
            require(not any(x in name.lower() for x in ('e2e','gametest','testauthority','parallel-d.mixins','storagemetadataprobe','storagetestsupport','storageaclsequenceprobe','storagepolicytestsupport','storageresolutionprobe','storageresolutiontestsupport')),'test authority in release: '+name)
        expected = git('show',BASE+':fabric/src/main/resources/fabric.mod.json').replace('${version}','0.1.0')
        require(archive.read('fabric.mod.json').decode().strip() == expected.strip(),'production entrypoints/descriptor changed')
        require(archive.read('ai-villages.mixins.json').decode().strip() == git('show',BASE+':fabric/src/main/resources/ai-villages.mixins.json').strip(),'production mixins changed')
    return names

def inspect_fixture(path):
    with ZipFile(path) as archive:
        names = archive.namelist(); require(len(names) == len(set(names)),'duplicate fixture entries')
        fixture_descriptor(json.loads(archive.read('fabric.mod.json')))
        require(TEST_PREFIX+'DGameTests.class' in names and TEST_PREFIX+'DTestAuthority.class' in names,'fixture classes missing')
        require(all(TEST_PREFIX+entry.rsplit('.',1)[1]+'.class' in names for entry in ENTRIES),'physical fixture classes missing')
        require(all(not n.endswith('.class') or n.startswith(TEST_PREFIX) for n in names),'peer or production classes in fixture mod')
        mixin = json.loads(archive.read('parallel-d.mixins.json'))
        require(mixin['package'] == 'dev.aivillages.fabric.storage.mixin' and mixin['mixins'] == ['StorageMarkerMixin'],'unowned mixin registration')
