import copy,unittest
from pathlib import Path
from nonparticipant_fixture import layout,protects,setup
from recovery_cases import originals,positions

class NonparticipantFixtureTests(unittest.TestCase):
    def test_original_a05_source_and_destination_and_work_cells_stay_clear(self):
        from generalization_support import at,DST
        points=[at(x,2) for x in range(6,14)]
        task={'source':{prefix+axis:fn(p[i] for p in points) for prefix,fn in [('min',min),('max',max)]
                        for i,axis in enumerate(('X','Y','Z'))},
              'destination':dict(zip(('x','y','z'),DST))}
        before=copy.deepcopy(task);self.assertTrue(protects(task))
        for wall in layout():
            self.assertTrue(all(abs(wall[0]-p[0])+abs(wall[2]-p[2])>1 for p in [*points,DST]))
        self.assertEqual(task,before)

    def test_participating_actor_profiles_are_refused_before_any_setup(self):
        from types import SimpleNamespace
        for profile in ('A07','A08'):
            with self.assertRaises(AssertionError):setup(SimpleNamespace(actions_active=False,a10_profile=profile))

    def test_every_frozen_task_and_its_work_positions_stay_clear(self):
        cases=originals(Path(__file__).with_name('a06_original_cases.json'));before=copy.deepcopy(cases)
        for case in cases:
            self.assertTrue(protects(case))
            targets=[*positions(case),[case['destination'][k] for k in ('x','y','z')]]
            for wall in layout():
                self.assertTrue(all(abs(wall[0]-p[0])+abs(wall[2]-p[2])>1 for p in targets))
        self.assertEqual(cases,before)

    def test_an_overlapping_task_is_refused_without_changing_it(self):
        case=copy.deepcopy(originals(Path(__file__).with_name('a06_original_cases.json'))[0])
        case['source'].update(minX=81,maxX=84,minZ=64,maxZ=67);before=copy.deepcopy(case)
        with self.assertRaisesRegex(ValueError,'overlaps'):protects(case)
        self.assertEqual(case,before)

if __name__=='__main__':unittest.main()
