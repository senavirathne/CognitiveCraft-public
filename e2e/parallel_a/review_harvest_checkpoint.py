"""Verify the immutable first positive H archives offline; no native replay or new owner."""
import argparse,hashlib,json,shutil,tempfile
from pathlib import Path
from review_receipt_prefix import checked_archive
from review_harvest_current import review

SOURCE='119c5d6242ee0a3a5176cb2ffd9a911261ce0faa'
ARTIFACTS={
 'native':{'id':12872212591,'size_in_bytes':65579612,'digest':'sha256:348e8c36a80364becbe20ab9b9280fe35ac3a16872640ddab499d6d5fe085295'},
 'contracts':{'id':12872441805,'size_in_bytes':3393247,'digest':'sha256:947b9b0349788bc74905513692a451604a1d3c53ec3708000a29819d9c38dcbd'}}
ORACLE_BLOBS={
 "e2e/parallel_a/a06_original_cases.json": "e71f635050c6cc85c30cb7282cbe1ed596d67cb3",
 "e2e/parallel_a/harvest_fixture.py": "b4adb36478f1b2e7a76e7c2ac4f80433750f6443",
 "e2e/parallel_a/native_nbt.py": "698f64d05fe4a5476a6c04ad1b5347dedfea24b2",
 "e2e/parallel_a/review_receipt_prefix.py": "b2f5ef9a902368cc8e8481a92066945d5ddc2b19"
}
def verify_inputs(repo):
    for path,expected in ORACLE_BLOBS.items():
        raw=(repo/path).read_bytes();actual=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        assert actual==expected,('Changed frozen first-H oracle/input',path,actual,expected)
def checkpoint(native_archive,contracts_archive,out):
    repo=Path(__file__).resolve().parents[2];verify_inputs(repo);out.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='a06-h-positive-review-') as directory:
        work=Path(directory)
        archives={name:checked_archive(ARTIFACTS[name],path,work/name) for name,path in [('native',native_archive),('contracts',contracts_archive)]}
        frozen=work/'contracts/e2e/parallel_a/a06_original_cases.json';frozen.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(repo/'e2e/parallel_a/a06_original_cases.json',frozen)
        value=review(work/'native',work/'contracts',out,SOURCE,
                     expected_modules={'core':[2243,0,0,0],'providers':[131,0,0,0],'fabric':[58,0,0,0]},removal_cases=0)
        assert value['status']=='PASS_FIRST_H_REVIEWED_ONLY' and value['originalOwnerSettlements']==1 and value['a06Qualified'] is False
        value.update(archives=archives,oracleGitBlobs=ORACLE_BLOBS,checkpointRun=40814989837,
                     transportStatus='PASS_IMMUTABLE_ZIP_DIGEST_SIZE_CRC_AND_PATH_BOUNDARIES',
                     pickupRemovalState='NOT_ATTACHED_OR_REQUIRED_FOR_THIS_H_PROOF')
        (out/'checkpoint-review.json').write_text(json.dumps(value,indent=2)+'\n')
        job=value['originalJob'];attempt=job['attempts'][-1]
        print('A06_FIRST_H_ARCHIVE_REVIEW '+json.dumps({'status':value['status'],'source':SOURCE,'moduleCases':value['moduleCases'],
              'originalOwnerSettlements':1,'key':value['key'],'jobState':job['state'],'attempt':attempt['id'],
              'effects':attempt['effects'],'credited':attempt['credited'],'uncertain':attempt['uncertain'],
              'releaseSha256':value['releaseSha256'],'observerSha256':value['observerSha256'],'a06Qualified':False}))
        return value
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--native-archive',type=Path,required=True);p.add_argument('--contracts-archive',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    a=p.parse_args();checkpoint(a.native_archive,a.contracts_archive,a.out)
if __name__=='__main__':main()
