import copy,hashlib,unittest
from pathlib import Path
from recovery_cases import originals,positions
from recovery_control_fixture import CASES,expected_case,layout,setup

class RecoveryControlFixtureTests(unittest.TestCase):
    def test_fresh_requests_preserve_the_template_and_original_bytes(self):
        path=Path(__file__).with_name('a06_original_cases.json');before=path.read_bytes();cases=originals(path)
        template=next(c for c in cases if c['checkpoint']=='DEPOSIT' and c['item']!='minecraft:wheat');saved=copy.deepcopy(template)
        for name in CASES:
            actual=expected_case(name);expected=copy.deepcopy(template);expected.update(id=name,requested=2);expected['destination']['x']=78
            self.assertEqual(actual,expected);self.assertEqual(actual['destination'],{'dimension':'minecraft:overworld','x':78,'y':208,'z':74})
        self.assertEqual(template,saved);self.assertEqual(path.read_bytes(),before)
        self.assertEqual(hashlib.sha256(before).hexdigest(),'ac506eecff151b7d3dcf395b83d06c2b789324f0734a48700ae1e7b975432118')

    def test_walls_keep_all_work_and_destination_cells_clear_on_the_original_floor(self):
        self.assertEqual(len(layout()),180)
        for name in CASES:
            c=expected_case(name);points=[*positions(c),[c['destination'][k] for k in ('x','y','z')]]
            self.assertTrue(all(64<p[0]<80 and 64<p[2]<78 for p in points))
            self.assertFalse(layout().intersection(map(tuple,points)))
        self.assertTrue(all(63<=x<=84 and 63<=z<=78 for x,y,z in layout()))

    def test_original_and_other_control_names_cannot_use_this_fixture(self):
        for name in ('W6-004','heldout-W6-010','expired-original-accounting','handoff-positive'):
            with self.assertRaises(ValueError):expected_case(name)

    def test_setup_refuses_other_profiles_and_active_work_before_mutation(self):
        from types import SimpleNamespace
        for h in [SimpleNamespace(a10_profile='A07',actions_active=False),SimpleNamespace(a10_profile='A06',actions_active=True)]:
            with self.assertRaises(AssertionError):setup(h,expected_case(CASES[0]))

if __name__=='__main__':unittest.main()
