import copy,unittest
from unittest.mock import patch
from handoff_fixture import layout,expected_case,review

class HandoffFixtureTest(unittest.TestCase):
    def test_two_units_and_source_are_kept_with_a_fresh_inside_root_destination(self):
        case=expected_case('handoff-positive');cells=layout()
        self.assertEqual(2,case['requested']);self.assertEqual([78,208,74],[case['destination'][k] for k in ('x','y','z')])
        self.assertEqual(192,len(cells));self.assertNotIn((78,208,74),cells)
        for z in (73,74):
            self.assertEqual(('minecraft:stone',{}),cells[80,208,z]);self.assertEqual(('minecraft:stone',{}),cells[80,209,z])
        for point in ((73,208,73),(75,208,74)):self.assertNotIn(point,cells)
    def test_changed_task_is_refused_without_reading_any_saved_world(self):
        case=expected_case('handoff-positive');case['requested']=1
        with patch('handoff_fixture.native_block') as native,self.assertRaises(AssertionError):review(None,case,'old','new',{})
        native.assert_not_called()
    def test_native_perimeter_gap_cannot_qualify_the_supported_static_geometry(self):
        case=expected_case('handoff-positive');record={'case':case,'schema':2,'kind':'ORDINARY_STATIC_SAME_ROOT_HANDOFF_ARENA',
            'entityChunk':[4,4],'blocks':192,'normalAI':True,'metadataRepaired':False,
            'destinationFixedBeforeJobCreation':[78,208,74],'copiedTemplateDestination':[81,208,74]}
        def native(_,pos,cache):
            name,props=copy.deepcopy(layout()[pos])
            if pos==(80,209,74):name='minecraft:air'
            return name,props
        with patch('handoff_fixture.native_block',side_effect=native),self.assertRaises(AssertionError):review(None,case,'old','new',record)

if __name__=='__main__':unittest.main()
