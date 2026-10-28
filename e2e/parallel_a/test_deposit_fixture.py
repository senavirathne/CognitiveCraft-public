import copy,hashlib,json,unittest
from deposit_fixture import canonical,pressure_journal,tick_window,preparation_window,PREPARATION_COHORTS

class SyntheticPressureTest(unittest.TestCase):
    def test_full_quota_cannot_copy_a_result_verification_or_executable_identity(self):
        world='10000000-0000-0000-0000-000000000001';epoch='20000000-0000-0000-0000-000000000001'
        intent={'key':{'world':world,'root':'original-root','step':'original-step','attempt':'original-attempt','generation':1,'ordinal':1},
                'run':'original-run','operation':'original-operation','actor':{'address':'original-actor','incarnation':'original-incarnation'},
                'chest':{'address':'77,208,72','incarnation':'original-chest'}}
        payload=canonical({'schema':1,'intent':intent})+b'\n'
        original=canonical({'schema':1,'world':world,'epoch':epoch,'revision':2,'records':1,'sha256':hashlib.sha256(payload).hexdigest()})+b'\n'+payload
        raw,report=pressure_journal(original,intent);head,payload=raw.split(b'\n',1);header=json.loads(head)
        rows=[json.loads(line) for line in payload.splitlines()]
        self.assertEqual(64,len(rows));self.assertEqual(64,header['records']);self.assertEqual(3,header['revision'])
        self.assertEqual(hashlib.sha256(payload).hexdigest(),header['sha256']);self.assertEqual(0,report['executableCapabilities'])
        self.assertEqual(64,len({r['intent']['run'] for r in rows}));self.assertEqual(64,len({r['intent']['key']['ordinal'] for r in rows}))
        for r in rows:
            self.assertEqual({'schema','intent'},set(r));self.assertNotEqual(intent['actor']['address'],r['intent']['actor']['address']);self.assertNotEqual(intent['chest']['address'],r['intent']['chest']['address'])
        self.assertEqual('original-root',intent['key']['root'])

    def test_foreign_world_input_is_refused(self):
        raw=canonical({'world':'other-world','epoch':'20000000-0000-0000-0000-000000000001','sha256':'invalid'})+b'\n'
        with self.assertRaises(ValueError):pressure_journal(raw,{'key':{'world':'original-world'}})

    def test_exact_clock_window_excludes_prior_ticks_and_rejects_a_prior_process(self):
        begin={'observerProcess':'current','time':1000,'gameTick':100}
        raw={'process':'current','time':2000,'samples':[{'gameTick':n,'nanos':n} for n in range(90,105)]}
        self.assertEqual([101,102,103,104],[s['gameTick'] for s in tick_window(raw,begin,4)])
        self.assertIsNone(tick_window(dict(raw,process='previous'),begin,4));self.assertIsNone(tick_window(dict(raw,time=999),begin,4))
        self.assertIsNone(tick_window(dict(raw,samples=raw['samples'][:-1]),begin,4))

    def test_duplicate_sample_cannot_replace_a_missing_native_tick(self):
        raw={'process':'current','time':2000,'samples':[{'gameTick':n} for n in (101,102,102,104)]}
        with self.assertRaises(ValueError):tick_window(raw,{'observerProcess':'current','time':1000,'gameTick':100},4)

class ActualPreparationWindowTest(unittest.TestCase):
    def records(self):
        return [{'id':name,'process':'process-'+str(n),'metrics':{'beginNanos':[1000+n]}} for n,name in enumerate(PREPARATION_COHORTS)]
    def test_all_nine_actual_requests_preserve_every_original_raw_measurement(self):
        records=self.records();before=copy.deepcopy(records);samples=preparation_window(records)
        self.assertEqual(list(range(1000,1009)),[s['nanos'] for s in samples]);self.assertEqual(before,records)
    def test_missing_cohort_or_reused_process_cannot_qualify_the_window(self):
        records=self.records()
        with self.assertRaises(ValueError):preparation_window(records[:-1])
        records[-1]['process']=records[0]['process']
        with self.assertRaises(ValueError):preparation_window(records)
    def test_no_cherry_picking_or_missing_zero_duration_capture(self):
        for invalid in ([],[1000,2000],[0],[-1],[True]):
            records=self.records();records[-1]['metrics']['beginNanos']=invalid
            with self.subTest(invalid=invalid),self.assertRaises(ValueError):preparation_window(records)

if __name__=='__main__':unittest.main()
