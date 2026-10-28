import copy, json, os, tempfile, unittest
from pathlib import Path
from zipfile import ZipFile
from common import HERE, REPO, RELEASE, BASE, git
from packaging import fixture_descriptor,inspect_release,inspect_fixture

class NativePackagingTest(unittest.TestCase):
    def test_descriptor_names_only_d_tests(self):
        fixture_descriptor(json.loads((HERE/'native/resources/fabric.mod.json').read_text()))
    def test_peer_or_main_entrypoint_rejected(self):
        value=json.loads((HERE/'native/resources/fabric.mod.json').read_text())
        for key,entries in [('main',['dev.aivillages.e2e.paralleld.DGameTests']),('fabric-gametest',['dev.aivillages.fabric.FarmingGameTests'])]:
            bad=copy.deepcopy(value);bad['entrypoints'][key]=entries
            with self.assertRaises(ValueError):fixture_descriptor(bad)
    def test_test_authority_in_release_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            jar=Path(d)/'release.jar'
            with ZipFile(jar,'w') as z:z.writestr('dev/aivillages/e2e/paralleld/DTestAuthority.class',b'fixture:forbidden')
            with self.assertRaisesRegex(ValueError,'test authority'):inspect_release(jar)
    def test_new_automatic_production_registration_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            jar=Path(d)/'release.jar'
            descriptor=json.loads(git('show',BASE+':fabric/src/main/resources/fabric.mod.json').replace('${version}','0.1.0'))
            descriptor['entrypoints']['main'].append('dev.aivillages.fabric.storage.UnrequestedInitializer')
            with ZipFile(jar,'w') as z:z.writestr('fabric.mod.json',json.dumps(descriptor))
            with self.assertRaisesRegex(ValueError,'entrypoints'):inspect_release(jar)
    def test_actual_built_release_and_fixture_are_separate(self):
        if not RELEASE.exists():
            if os.getenv('CC_D_REQUIRE_BUILT')=='1':self.fail('mandatory release build missing')
            self.skipTest('local Java 25/SDK blocker; required without skips in Actions')
        inspect_release(RELEASE);inspect_fixture(REPO/'build/parallel-d-native/parallel-d-fixtures.jar')

    def test_metadata_test_entrypoint_cannot_enter_the_release(self):
        with tempfile.TemporaryDirectory() as d:
            jar=Path(d)/'release.jar'
            with ZipFile(jar,'w') as z:z.writestr('dev/aivillages/core/kernel/storage/StorageMetadataProbe.class',b'fixture:test-only-entrypoint')
            with self.assertRaisesRegex(ValueError,'test authority'):inspect_release(jar)
