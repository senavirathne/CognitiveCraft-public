import copy,unittest
from receipt_prefix_fixture import retained_prefix

class OriginalPrefixOracleTest(unittest.TestCase):
    def entry(self):
        base={'origin':'synthetic-origin','runId':'synthetic-run','batchId':'synthetic-batch','actor':'synthetic-actor',
              'source':'synthetic-source','destination':'synthetic-destination','item':'synthetic-item',
              'sourceBlock':'synthetic-source-block','dropIds':['synthetic-drop'],'quantity':1}
        chain=[dict(base,stage=stage,receiptId='fixture-receipt-'+str(n),operationId='fixture-operation-'+str(n))
               for n,stage in enumerate(('HARVEST','PICKUP','DEPOSIT'))]
        return {'intent':{'receiptPrefix':{'receipts':chain[:2]}},'result':{'receipt':chain[-1]}}
    def test_exact_actual_publication_is_required_without_claiming_native_saved_lineage(self):
        entry=self.entry();before=copy.deepcopy(entry);r=retained_prefix(entry,entry['intent']['receiptPrefix']['receipts'])
        self.assertFalse(r['nativeSavedLineageProved']);self.assertTrue(r['actualCurrentReceiptExcluded']);self.assertEqual(before,entry)
        with self.assertRaises(ValueError):retained_prefix(entry,[])
    def test_missing_changed_or_replayed_prefix_cannot_qualify_collection(self):
        for mode in ('missing','changed-drop','repeated-effect','current-in-prefix','wrong-quantity'):
            entry=self.entry();prefix=entry['intent']['receiptPrefix']['receipts']
            if mode=='missing':del entry['intent']['receiptPrefix']
            elif mode=='changed-drop':prefix[0]['dropIds']=['replacement-drop']
            elif mode=='repeated-effect':prefix[0]['operationId']=prefix[1]['operationId']
            elif mode=='current-in-prefix':prefix.append(entry['result']['receipt'])
            else:prefix[0]['quantity']=2
            with self.subTest(mode=mode),self.assertRaises(ValueError):retained_prefix(entry)

if __name__=='__main__':unittest.main()
