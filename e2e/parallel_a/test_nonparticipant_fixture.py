import copy,unittest
from pathlib import Path
from nonparticipant_fixture import layout,protects
from recovery_cases import originals,positions

class NonparticipantFixtureTests(unittest.TestCase):
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
