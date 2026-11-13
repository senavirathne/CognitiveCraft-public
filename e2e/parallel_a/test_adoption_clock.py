import copy,json,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from adoption_clock import request,check,release,cleanup,REQUEST,RECEIPT

class AdoptionClockTests(unittest.TestCase):
    def fixture(self,root):
        (root/'server').mkdir(exist_ok=True)
        key={'world':'world','root':'job','step':'job','attempt':'attempt','generation':1,'ordinal':2}
        proof={'status':'PASS_INDEPENDENT_NATIVE_PICKUP','entry':{'intent':{'key':key},'result':{'live':'COMPLETE'}}}
        case={'id':'W6-003','checkpoint':'PICKUP'}
        expected=request(root,case,proof)
        native={'namespaceProcess':{'pid':17,'start':1,'kind':'server'}}
        value={'schema':1,'request':expected,'pid':17,'process':'new-jvm','time':1001,'gameTick':10,
               'commands':[{'command':'tick freeze','result':1}],'key':key,'marker':expected['expected'],
               'classification':'SAVED_COMPLETE','originalJob':{'id':'job','generation':1,'state':'INTERRUPTED',
               'attempts':[{'id':'attempt','uncertain':True}]}}
        return case,proof,expected,native,value

    def test_request_requires_exact_saved_original_pickup_before_any_control(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);case,proof,expected,native,value=self.fixture(root);cleanup(root)
            for changed in ({**case,'id':'other'},{**case,'checkpoint':'HARVEST'}):
                with self.assertRaises(AssertionError):request(root,changed,proof)
                self.assertFalse((root/'server'/REQUEST).exists())
            wrong=copy.deepcopy(proof);wrong['status']='UNPROVEN'
            with self.assertRaises(AssertionError):request(root,case,wrong)
            self.assertFalse((root/'server'/REQUEST).exists())

    def test_current_native_saved_proof_and_original_owner_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            _,_,expected,native,value=self.fixture(Path(directory));before=copy.deepcopy(value)
            self.assertIs(check(value,expected,native),value);self.assertEqual(value,before)
            for key,bad in [('classification','TORN_SAVED_COHORT'),('commands',[]),('marker',{})]:
                changed=copy.deepcopy(value);changed[key]=bad
                with self.subTest(key=key),self.assertRaises(AssertionError):check(changed,expected,native)

    def test_stale_or_foreign_native_process_cannot_hold_this_cohort(self):
        with tempfile.TemporaryDirectory() as directory:
            _,_,expected,native,value=self.fixture(Path(directory))
            for field,bad in [('pid',18),('time',999)]:
                changed=copy.deepcopy(value);changed[field]=bad
                with self.assertRaises(AssertionError):check(changed,expected,native)

    def test_closed_or_reassigned_job_cannot_borrow_the_original_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            _,_,expected,native,value=self.fixture(Path(directory))
            for field,bad in [('generation',2),('state','CANCELLED')]:
                changed=copy.deepcopy(value);changed['originalJob'][field]=bad
                with self.assertRaises(AssertionError):check(changed,expected,native)
            for field,bad in [('id','replacement'),('uncertain',False)]:
                changed=copy.deepcopy(value);changed['originalJob']['attempts'][0][field]=bad
                with self.assertRaises(AssertionError):check(changed,expected,native)

    def test_release_requires_actual_world_progress_before_original_cancellation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);_,_,_,_,held=self.fixture(root)
            (root/'server'/RECEIPT).write_text(json.dumps(held));observations=iter([10,11]);results=[]
            def console(commands,lifecycle):
                self.assertTrue(lifecycle)
                (root/'server/control.json').write_text(json.dumps({'id':'release','commands':commands}))
                (root/'server/control-ack.json').write_text(json.dumps({'id':'release','sent':True}))
            def wait(predicate,*args):
                for _ in range(2):
                    value=predicate();results.append(value)
                    if value:return value
                self.fail('Physical clock never resumed')
            h=SimpleNamespace(root=root,console=console,wait=wait,snapshot=lambda:{'pid':17,'gameTick':next(observations)})
            value=release(h);self.assertIsNone(results[0]);self.assertEqual(value['snapshot']['gameTick'],11)
            self.assertTrue(value['beforeOriginalCancellation']);self.assertFalse((root/'server'/REQUEST).exists())

    def test_cleanup_preserves_actual_receipts_and_world_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.fixture(root)
            receipt=root/'server'/RECEIPT;receipt.write_bytes(b'preserved native receipt')
            cleanup(root);cleanup(root)
            self.assertEqual(receipt.read_bytes(),b'preserved native receipt')

    def test_held_snapshot_bytes_and_actual_world_time_are_independently_verified(self):
        import hashlib
        from adoption_clock import check_snapshot
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);_,_,_,_,held=self.fixture(root)
            path=root/'held.json';raw=json.dumps({'pid':17,'time':1002,'gameTick':10}).encode();path.write_bytes(raw)
            held['heldSnapshot']={'path':'held.json','sha256':hashlib.sha256(raw).hexdigest()}
            self.assertEqual(check_snapshot(root,held)['gameTick'],10)
            path.write_bytes(raw+b' ')
            with self.assertRaises(AssertionError):check_snapshot(root,held)
            raw=json.dumps({'pid':17,'time':1002,'gameTick':11}).encode();path.write_bytes(raw)
            held['heldSnapshot']['sha256']=hashlib.sha256(raw).hexdigest()
            with self.assertRaises(AssertionError):check_snapshot(root,held)

if __name__=='__main__':unittest.main()
