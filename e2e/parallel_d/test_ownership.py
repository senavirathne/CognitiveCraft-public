import copy, struct, tempfile, unittest
from pathlib import Path
from common import HERE, read, registry
from ownership import check_changes,check_compose,core_boundary,class_utf8,allowed

class OwnershipTest(unittest.TestCase):
    def test_protected_source_edit_rejected(self):
        baseline={'build.gradle':{'mode':'100644','sha':'a'}}
        with self.assertRaisesRegex(ValueError,'protected'):
            check_changes(baseline,{'build.gradle':{'mode':'100644','sha':'b'}},set())
    def test_protected_deletion_rejected(self):
        with self.assertRaisesRegex(ValueError,'deleted'):
            check_changes({'protected':{'mode':'100644','sha':'a'}},{},set())
    def test_protected_mode_edit_rejected_even_for_ci(self):
        with self.assertRaisesRegex(ValueError,'protected'):
            check_changes({'ci':{'mode':'100644','sha':'a'}},{'ci':{'mode':'100755','sha':'a'}},{'ci'})
    def test_owned_new_path_and_peer_path_differ(self):
        check_changes({}, {'core/src/main/java/dev/aivillages/core/kernel/storage/Example.java':{}},set())
        for path in ['core/src/main/java/dev/aivillages/core/kernel/interaction/Example.java','fabric/src/main/resources/fabric.mod.json','.github/workflows/parallel-c.yml']:
            with self.assertRaises(ValueError):check_changes({},{path:{}},set())
    def test_compose_isolated_positive_control(self):
        check_compose(read(HERE/'compose.yml'))
    def test_extra_shared_volume_rejected(self):
        for foreign in ('parallel-c','parallel-a','parallel-b'):
            value=read(HERE/'compose.yml');value['services']['minecraft']['volumes'].append(f'/tmp/{foreign}:/foreign:rw')
            with self.assertRaisesRegex(ValueError,'volume'):check_compose(value)
    def test_host_or_external_network_rejected(self):
        for replacement in ['host','container:active-a']:
            value=read(HERE/'compose.yml');value['services']['client-a']['network_mode']=replacement
            with self.assertRaises(ValueError):check_compose(value)
    def test_unknown_or_future_stage_rejected(self):
        registry('D00')
        for stage in ('D01','D11','C00','unknown','../D00'):
            with self.assertRaisesRegex(ValueError,'unknown'):registry(stage)
    def test_core_native_import_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);source=root/'core/src/main/java/Example.java';source.parent.mkdir(parents=True)
            source.write_text('import net.minecraft.world.Container;\nclass Example {}')
            with self.assertRaisesRegex(ValueError,'native import'):core_boundary(root)
    def test_core_bytecode_native_descriptor_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);p=root/'core/build/classes/java/main/Example.class';p.parent.mkdir(parents=True)
            name=b'Lnet/minecraft/world/Container;'
            p.write_bytes(b'\xca\xfe\xba\xbe'+struct.pack('>HHH',0,69,2)+b'\x01'+struct.pack('>H',len(name))+name)
            with self.assertRaisesRegex(ValueError,'native bytecode'):core_boundary(root,True)
    def test_missing_compiled_core_rejected_when_required(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(ValueError,'missing'):core_boundary(Path(d),True)
