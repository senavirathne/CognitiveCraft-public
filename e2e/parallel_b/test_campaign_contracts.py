import json
from pathlib import Path
import unittest
from campaign_contracts import HERE,contracts,validate,held_out,sha


class CampaignContractTests(unittest.TestCase):
    def test_original_assertions_and_all_ids_are_byte_pinned(self):
        value=json.loads((HERE/'qualification-specs.json').read_text())
        self.assertEqual(334,validate(value)['originalDefinitions'])
        self.assertEqual(0,value['nativePassesFromDefinitions'])
        for r in value['original']:self.assertEqual(r['definitionSha256'],sha(r['expectedDefinition']))

    def test_changed_expectation_or_lost_original_refuses(self):
        value=contracts();value['original'][0]['expectedDefinition']['quantity']=999
        with self.assertRaises(ValueError):validate(value)
        value=contracts();value['original'].pop()
        with self.assertRaises(ValueError):validate(value)

    def test_fixture_seed_is_deterministic_and_axes_vary_independently(self):
        rows=held_out(123);self.assertEqual(rows,held_out(123));self.assertNotEqual(rows,held_out(124))
        self.assertEqual(len(rows),len({r['id'] for r in rows}))
        base={r['axis']:r['bindings'] for r in rows}
        for axis in base:
            selected=[r for r in rows if r['axis']==axis]
            for r in selected:
                self.assertEqual({k:v for k,v in selected[0]['bindings'].items() if k!=axis},
                                 {k:v for k,v in r['bindings'].items() if k!=axis})
                e=r['expected'];self.assertEqual(e['physicalPlanks'],e['delivered']+e['actorSurplus'])

    def test_resource_quantities_and_adversarial_denominators(self):
        value=contracts();self.assertEqual(12,len(value['resourceInterruptions']));self.assertEqual(30,len(value['adversarial']))
        for c in value['resourceInterruptions']:
            self.assertEqual(4,sum(c['after'][k] for k in ('sourceBlocks','drops','actor','destination')))
            self.assertFalse(c['expected']['privateOracleFeedsRuntime']);self.assertFalse(c['expected']['automaticReplay'])
        self.assertEqual(20,value['craftPlacementSurplus']['count'])
        self.assertEqual(12,len(value['cohorts']))
