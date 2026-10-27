"""Read-only continuation bindings; these fixtures create no native actor or Job."""
import unittest
from continuation import client_binding

class ContinuationBindingsTest(unittest.TestCase):
    def frame(self,actors):
        return {'knownPlayers':{'PlayerB':{'uuid':'fixture:principal-b','citizens':{'citizens':[{'actor':a} for a in actors]}}}}
    def test_missing_owner_dictionary_can_bind_the_existing_private_citizen(self):
        actor={'citizenId':'fixture:citizen-b','entityId':'fixture:entity-b'}
        self.assertEqual(actor,client_binding(self.frame([actor]),'PlayerB','fixture:principal-b'))
    def test_preselected_companion_is_preserved_among_two_owned_actors(self):
        old={'citizenId':'fixture:interrupted','entityId':'fixture:old'};companion={'citizenId':'fixture:companion','entityId':'fixture:other'}
        self.assertEqual(companion,client_binding(self.frame([old,companion]),'PlayerB','fixture:principal-b','fixture:companion'))
    def test_another_principal_or_missing_observation_is_not_a_binding(self):
        self.assertIsNone(client_binding(self.frame([]),'PlayerB','fixture:foreign'))
        self.assertIsNone(client_binding(None,'PlayerB','fixture:principal-b'))
    def test_ambiguity_or_absent_original_cannot_select_a_replacement(self):
        actors=[{'citizenId':'fixture:first','entityId':'fixture:one'},{'citizenId':'fixture:second','entityId':'fixture:two'}]
        with self.assertRaises(AssertionError):client_binding(self.frame(actors),'PlayerB','fixture:principal-b')
        with self.assertRaises(AssertionError):client_binding(self.frame(actors),'PlayerB','fixture:principal-b','fixture:missing')

if __name__=='__main__':unittest.main()
