"""Verify that the existing driver really receives its bounded config through the private overlay."""
import unittest,tempfile,json,shutil,hashlib
from pathlib import Path
from client_launch import prepare_args
class ClientLaunchTest(unittest.TestCase):
    def test_mounted_box_overlay_preserves_mailbox_and_bounded_native_observation(self):
        with tempfile.TemporaryDirectory()as d:
            root=Path(d);box=root/'clients/PlayerD_A';box.mkdir(parents=True);args=['client','--root',str(root),'--player','PlayerD_A'];self.assertEqual(prepare_args(args),args);data={'minX':0,'footY':201,'minZ':6,'maxX':84,'maxZ':11};p=box/'observation-area.json';p.write_text(json.dumps(data));actual=prepare_args(args);overlay=Path(actual[actual.index('--root')+1])
            try:
                self.assertEqual((overlay/'observation-area.json').read_bytes(),p.read_bytes());(overlay/'clients/PlayerD_A/request.json').write_text('same actual mailbox');self.assertEqual((box/'request.json').read_text(),'same actual mailbox');self.assertEqual(json.loads((box/'root-overlay.json').read_text())['observation_sha256'],hashlib.sha256(p.read_bytes()).hexdigest());self.assertEqual(args[2],str(root))
            finally:shutil.rmtree(overlay)
    def test_oversized_or_foreign_client_configuration_refused(self):
        with tempfile.TemporaryDirectory()as d:
            root=Path(d);box=root/'clients/PlayerD_A';box.mkdir(parents=True);p=box/'observation-area.json';p.write_text(json.dumps({'minX':0,'footY':201,'minZ':0,'maxX':84,'maxZ':24}));args=['client','--root',str(root),'--player','PlayerD_A']
            with self.assertRaises(ValueError):prepare_args(args)
            with self.assertRaises(ValueError):prepare_args(['client','--root',str(root),'--player','Foreign'])
