import hashlib,json,unittest
from deposit_fixture import canonical,pressure_journal

class SyntheticPressureTest(unittest.TestCase):
    def test_full_quota_cannot_copy_a_result_verification_or_executable_identity(self):
        world='10000000-0000-0000-0000-000000000001';epoch='20000000-0000-0000-0000-000000000001'
        original=canonical({'schema':1,'world':world,'epoch':epoch,'revision':2,'records':1,'sha256':'x'})+b'\n'
        intent={'key':{'world':world,'root':'original-root','step':'original-step','attempt':'original-attempt','generation':1,'ordinal':1},
                'run':'original-run','operation':'original-operation','actor':{'address':'original-actor','incarnation':'original-incarnation'},
                'chest':{'address':'77,208,72','incarnation':'original-chest'}}
        raw,report=pressure_journal(original,intent);head,payload=raw.split(b'\n',1);header=json.loads(head)
        rows=[json.loads(line) for line in payload.splitlines()]
        self.assertEqual(64,len(rows));self.assertEqual(64,header['records']);self.assertEqual(3,header['revision'])
        self.assertEqual(hashlib.sha256(payload).hexdigest(),header['sha256']);self.assertEqual(0,report['executableCapabilities'])
        self.assertEqual(64,len({r['intent']['run'] for r in rows}));self.assertEqual(64,len({r['intent']['key']['ordinal'] for r in rows}))
        for r in rows:
            self.assertEqual({'schema','intent'},set(r));self.assertNotEqual(intent['actor']['address'],r['intent']['actor']['address']);self.assertNotEqual(intent['chest']['address'],r['intent']['chest']['address'])
        self.assertEqual('original-root',intent['key']['root'])

    def test_foreign_world_input_is_refused(self):
        raw=canonical({'world':'other-world','epoch':'20000000-0000-0000-0000-000000000001'})+b'\n'
        with self.assertRaises(ValueError):pressure_journal(raw,{'key':{'world':'original-world'}})

if __name__=='__main__':unittest.main()
