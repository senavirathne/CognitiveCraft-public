"""Artifact retries and production provenance, without a game or model runtime."""
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('archive_review',ROOT/'scripts/review-dropped-logs-archive.py')
review=importlib.util.module_from_spec(spec);spec.loader.exec_module(review)

class ArtifactSelection(unittest.TestCase):
    def artifact(self,kind,attempt,ident,head='head',run='42',expired=False):
        return {'name':f'dropped-logs-{kind}-{run}-{attempt}','id':ident,
                'workflow_run':{'head_sha':head},'expired':expired}
    def test_retry_keeps_successful_native_and_uses_latest_connected_attempt(self):
        artifacts=[self.artifact('native',1,1),self.artifact('connected',1,2),self.artifact('connected',2,3)]
        self.assertEqual([1,3],[a['id'] for a in review.current_artifacts(artifacts,'42',2,'head')])
    def test_newest_producer_is_selected_even_if_an_older_attempt_passed(self):
        artifacts=[self.artifact('native',1,1),self.artifact('connected',1,2),self.artifact('connected',2,3)]
        artifacts[1]['status']='PASS';artifacts[2]['status']='FAIL'
        self.assertEqual(3,review.current_artifacts(artifacts,'42',2,'head')[1]['id'])
    def test_wrong_run_wrong_source_expired_and_future_cannot_supply_missing_proof(self):
        native=self.artifact('native',1,1)
        for invalid in [self.artifact('connected',1,2,head='other'),self.artifact('connected',1,2,run='43'),
                        self.artifact('connected',1,2,expired=True),self.artifact('connected',3,2)]:
            with self.subTest(invalid=invalid),self.assertRaises(AssertionError):
                review.current_artifacts([native,invalid],'42',2,'head')
    def test_duplicate_latest_producer_is_rejected(self):
        with self.assertRaises(AssertionError):
            review.current_artifacts([self.artifact('native',1,1),self.artifact('connected',2,2),self.artifact('connected',2,3)],'42',2,'head')

class QualifiedProduction(unittest.TestCase):
    def setUp(self):
        base=ROOT/'build/archive-review-tests';base.mkdir(parents=True,exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(dir=base);self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.git('init','-q','-b','main')
        self.path='core/src/main/java/Runtime.java';p=self.root/self.path;p.parent.mkdir(parents=True);p.write_text('class Runtime {}\n')
        self.commit('qualified production');self.qualified=self.git('rev-parse','HEAD')
        self.manifest={'startingMain':self.qualified,'requiredHeads':[]}
        self.record={'profile':'all-published-branches-integration-v1','sourceCommit':self.qualified,
                     'sourceTree':self.git('rev-parse','HEAD^{tree}'),'startingMain':self.qualified,'requiredHeads':[],
                     'releaseJarSha256':'a'*64,'corpusSha256':'b'*64,'historicalBaselineFileSha256':'c'*64,
                     'productionFileCount':1,'productionFiles':[{'path':self.path,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),
                                                              'gitBlob':self.git('hash-object',self.path)}]}
    def git(self,*args):
        return subprocess.check_output(['git',*args],cwd=self.root,text=True,stderr=subprocess.DEVNULL).strip()
    def commit(self,message):
        self.git('add','-A');self.git('-c','user.name=Archive Tests','-c','user.email=archive@example.invalid','commit','-q','-m',message)
    def validate(self,jar='a'*64,corpus='b'*64):
        return review.validate_qualified_source(self.record,self.manifest,self.git('rev-parse','HEAD'),self.root,
                                               [self.path],jar,corpus,'c'*64)
    def test_verification_only_descendant_reuses_the_same_qualified_production(self):
        (self.root/'review-note.md').write_text('archive review metadata\n');self.commit('review update')
        proof=self.validate();self.assertTrue(proof['productionUnchanged']);self.assertEqual(self.qualified,proof['qualificationSourceCommit'])
    def test_changed_production_cannot_borrow_ancestor_qualification(self):
        (self.root/self.path).write_text('class Runtime { int changed; }\n');self.commit('production change')
        with self.assertRaises(AssertionError):self.validate()
    def test_changed_regression_cannot_borrow_old_suite_inventory(self):
        p=self.root/'core/src/test/java/RuntimeTest.java';p.parent.mkdir(parents=True);p.write_text('class RuntimeTest {}\n');self.commit('test change')
        with self.assertRaises(AssertionError):self.validate()
    def test_wrong_binary_or_frozen_corpus_is_rejected(self):
        with self.assertRaises(AssertionError):self.validate(jar='d'*64)
        with self.assertRaises(AssertionError):self.validate(corpus='d'*64)
    def test_unrelated_history_cannot_supply_qualification(self):
        self.git('checkout','-q','--orphan','unrelated');(self.root/'review-note.md').write_text('unrelated root\n');self.commit('unrelated')
        with self.assertRaises(subprocess.CalledProcessError):self.validate()

if __name__=='__main__':unittest.main()
