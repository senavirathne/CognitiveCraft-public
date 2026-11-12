import copy,unittest
from unittest.mock import patch
from handoff_fixture import layout,expected_case,review

class HandoffFixtureTest(unittest.TestCase):
    def test_original_two_units_and_destination_stay_outside_the_static_window(self):
        case=expected_case('handoff-positive');cells=layout()
        self.assertEqual(2,case['requested']);self.assertEqual([81,208,74],[case['destination'][k] for k in ('x','y','z')])
        self.assertEqual(192,len(cells));self.assertNotIn((81,208,74),cells)
        for z in (73,74):
            self.assertEqual(('minecraft:air',{}),cells[80,208,z]);self.assertEqual(('minecraft:stone_slab',{'type':'top','waterlogged':'false'}),cells[80,209,z])
        for point in ((73,208,73),(75,208,74)):self.assertNotIn(point,cells)
    def test_changed_task_is_refused_without_reading_any_saved_world(self):
        case=expected_case('handoff-positive');case['requested']=1
        with patch('handoff_fixture.native_block') as native,self.assertRaises(AssertionError):review(None,case,'old','new',{})
        native.assert_not_called()
    def test_native_bottom_slab_cannot_qualify_the_supported_static_geometry(self):
        case=expected_case('handoff-positive');record={'case':case,'schema':1,'kind':'ORDINARY_STATIC_SAME_ROOT_HANDOFF_ARENA',
            'entityChunk':[4,4],'blocks':192,'normalAI':True,'metadataRepaired':False,'destinationUnchanged':[81,208,74]}
        def native(_,pos,cache):
            name,props=copy.deepcopy(layout()[pos])
            if name=='minecraft:stone_slab':props['type']='bottom'
            return name,props
        with patch('handoff_fixture.native_block',side_effect=native),self.assertRaises(AssertionError):review(None,case,'old','new',record)

if __name__=='__main__':unittest.main()
