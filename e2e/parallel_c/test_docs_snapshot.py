import copy
import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest

from common import EvidenceError, HERE, read
from materialize_docs import PREFIX, materialize, restore


class DocsSnapshotTests(unittest.TestCase):
    def fixture(self):
        def oid(kind, raw): return hashlib.sha1(kind.encode()+b' '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        def tree(entries):
            raw=b''.join(mode.encode()+b' '+name.encode()+b'\0'+bytes.fromhex(sha) for mode,name,sha in sorted(entries,key=lambda x:x[1]+('/' if x[0]=='40000' else '\0')))
            return oid('tree',raw),raw.hex()
        text='# Owned docs\n';blob=oid('blob',text.encode());private=oid('blob',b'private baseline bytes stay absent')
        leaf,lr=tree([('100644','README.md',blob)]);implementation,ir=tree([('40000','parallel-c',leaf)])
        docs,dr=tree([('40000','implementation',implementation)]);base_tree,br=tree([('100644','private.md',private)])
        head_tree,hr=tree([('40000','docs',docs),('100644','private.md',private)])
        base=f'tree {base_tree}\nauthor Fixture <fixture@example.invalid> 1 +0000\ncommitter Fixture <fixture@example.invalid> 1 +0000\n\nbase\n'
        base_sha=oid('commit',base.encode());commit=f'tree {head_tree}\nparent {base_sha}\nauthor Fixture <fixture@example.invalid> 2 +0000\ncommitter Fixture <fixture@example.invalid> 2 +0000\n\nowned\n'
        head=oid('commit',commit.encode())
        return dict(schema='cc.c.docs-snapshot/1',repository='senavirathne/CognitiveCraft-Documentation',base_sha=base_sha,candidate_sha=head,base_commit=base,
                    commits={head:commit},trees={leaf:lr,implementation:ir,docs:dr,base_tree:br,head_tree:hr},blobs={blob:text},files={PREFIX+'README.md':blob})

    def execute(self,snapshot):
        with tempfile.TemporaryDirectory() as temp:
            source=Path(temp)/'contracts';source.mkdir();destination=Path(temp)/'docs'
            result=restore(snapshot,destination,source)
            self.assertEqual(snapshot['candidate_sha'],result['docs_sha'])
            self.assertFalse((destination/'private.md').exists())
            self.assertEqual('S private.md\n',subprocess.check_output(['git','-C',str(destination),'ls-files','-t','--','private.md'],text=True))
            self.assertEqual('',subprocess.check_output(['git','-C',str(destination),'status','--porcelain'],text=True))
            return result

    def test_exact_sparse_checkout_keeps_real_commit_identity_and_protected_blob_metadata(self):
        result=self.execute(self.fixture());self.assertEqual((2,1,1),(result['verified_commits'],result['materialized_paths'],result['sparse_paths']))

    def test_changed_owned_blob_is_rejected_before_it_can_qualify(self):
        snapshot=self.fixture();key=next(iter(snapshot['blobs']));snapshot['blobs'][key]+='changed'
        with self.assertRaisesRegex(EvidenceError,'blob identity'):self.execute(snapshot)

    def test_changed_tree_bytes_are_rejected(self):
        snapshot=self.fixture();key=next(iter(snapshot['trees']));snapshot['trees'][key]+='00'
        with self.assertRaisesRegex(EvidenceError,'tree identity'):self.execute(snapshot)

    def test_changed_ancestor_or_candidate_commit_bytes_are_rejected(self):
        for base in (False,True):
            snapshot=self.fixture()
            if base:snapshot['base_commit']+='changed'
            else:snapshot['commits'][snapshot['candidate_sha']]+='changed'
            with self.assertRaisesRegex(EvidenceError,'commit identity'):self.execute(snapshot)

    def test_unowned_or_parent_path_cannot_be_materialized(self):
        for path in ('private.md',PREFIX+'../../escape','/absolute'):
            snapshot=self.fixture();snapshot['files'][path]=next(iter(snapshot['blobs']))
            with self.assertRaisesRegex(EvidenceError,'unowned snapshot'):self.execute(snapshot)

    def test_existing_checkout_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'existing';root.mkdir();(root/'keep').write_text('user bytes')
            with self.assertRaisesRegex(EvidenceError,'overwrite'):restore(self.fixture(),root,Path(temp)/'contracts')
            self.assertEqual('user bytes',(root/'keep').read_text())

    def test_real_snapshot_must_match_requested_commit_and_frozen_baseline(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(EvidenceError,'request/docs snapshot'):materialize(Path(temp)/'docs',expected_sha='0'*40)

    def test_missing_candidate_commit_or_wrong_repository_is_rejected(self):
        snapshot=self.fixture();snapshot['commits']={}
        with self.assertRaisesRegex(EvidenceError,'candidate commit'):self.execute(snapshot)
        snapshot=self.fixture();snapshot['repository']='someone/else'
        with self.assertRaisesRegex(EvidenceError,'repository'):self.execute(snapshot)
