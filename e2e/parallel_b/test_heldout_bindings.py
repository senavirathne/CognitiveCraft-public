import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from parallel_b.heldout_bindings import resolve


class HeldoutBindingTests(unittest.TestCase):
    def binding(self):return dict(material='minecraft:birch_wood',quantity=11,actor=0,sourceBinding=0,
                                  destinationBinding=0,geometry='near',dimension='minecraft:overworld')

    def test_source_destination_and_principal_axes_do_not_move_other_bindings(self):
        base=self.binding();original=resolve(base)
        for key in ('sourceBinding','destinationBinding','actor'):
            changed=resolve({**base,key:1})
            self.assertEqual(original['actorPose'],changed['actorPose'])
            self.assertEqual(original['goal'],changed['goal'])
            for field,axis in (('source','sourceBinding'),('destination','destinationBinding'),('player','actor')):
                self.assertEqual(key==axis,original[field]!=changed[field])

    def test_species_quantity_and_unwired_authority_are_explicit(self):
        value=resolve(self.binding());self.assertEqual({'minecraft:birch_wood':3},value['startingSource'])
        self.assertEqual({'name':'cognitivecraft:produce_birch_planks','version':2},value['goal']['capability'])
        self.assertEqual(11,value['goal']['quantity']);self.assertEqual('INTEGRATION_PENDING_DISABLED_ON_B',value['routeStatus'])

    def test_unknown_axes_and_implicit_dimensions_refuse(self):
        for field,value in (('actor',True),('sourceBinding',3),('dimension','minecraft:the_nether'),('geometry','unknown')):
            with self.assertRaises(ValueError):resolve({**self.binding(),field:value})
