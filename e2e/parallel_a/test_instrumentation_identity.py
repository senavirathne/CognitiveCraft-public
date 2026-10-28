import tempfile,unittest
from pathlib import Path
from zipfile import ZipFile
from instrumentation_identity import same_jar_entries,MANIFEST

class InstrumentationIdentity(unittest.TestCase):
    def compare(self,before=None,after=None):
        old={'Observer.class':b'original exact class','fabric.mod.json':b'{}',MANIFEST:b'Manifest-Version: 1.0\r\nCreated-By: 25.0.4.1 (Eclipse Adoptium)\r\n\r\n'}
        new=dict(old);new[MANIFEST]=b'Manifest-Version: 1.0\r\nCreated-By: 25.0.5 (Eclipse Adoptium)\r\n\r\n'
        if before:old.update(before)
        if after:new.update(after)
        with tempfile.TemporaryDirectory() as root:
            paths=[Path(root)/'old.jar',Path(root)/'new.jar']
            for path,entries in zip(paths,(old,new)):
                with ZipFile(path,'w') as z:
                    for name,body in entries.items():z.writestr(name,body)
            return same_jar_entries(*paths)

    def test_only_created_by_build_version_may_differ(self):
        value=self.compare();self.assertEqual(value['status'],'PASS');self.assertTrue(value['differentBuildManifest']);self.assertEqual(value['identicalClassAndResourceEntries'],2)

    def test_changed_executable_byte_is_rejected(self):
        with self.assertRaisesRegex(AssertionError,'class and resource'):self.compare(after={'Observer.class':b'changed class'})

    def test_changed_resource_byte_is_rejected(self):
        with self.assertRaisesRegex(AssertionError,'class and resource'):self.compare(after={'fabric.mod.json':b'{"different":true}'})

    def test_added_executable_entry_is_rejected(self):
        with self.assertRaisesRegex(AssertionError,'identical instrumentation entries'):self.compare(after={'Another.class':b'new entry'})

    def test_execution_manifest_attribute_is_rejected(self):
        with self.assertRaisesRegex(AssertionError,'execution attributes'):self.compare(after={MANIFEST:b'Manifest-Version: 1.0\r\nCreated-By: 25.0.5 (Eclipse Adoptium)\r\nMain-Class: Replacement\r\n\r\n'})

if __name__=='__main__':unittest.main()
