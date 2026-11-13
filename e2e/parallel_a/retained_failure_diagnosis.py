"""Read-only transport of exact retained failures; never imports qualification passes."""
import argparse,base64,hashlib,json,tempfile
from pathlib import Path
from downloaded_replay import api_json,acquire,ROOT

SOURCE='d19f438cde0ed4608524f435e4212c145c5e2fbb'

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--artifact',action='append',required=True)
    parser.add_argument('--out',type=Path,required=True);args=parser.parse_args()
    result={'schema':1,'kind':'READ_ONLY_RETAINED_FAILURE_DIAGNOSIS','source':SOURCE,
            'importedCurrentPasses':0,'productionMutation':False,'profiles':{}}
    for selected in args.artifact:
        profile,identifier=selected.split(':');identifier=int(identifier)
        artifact=api_json('/actions/artifacts/'+str(identifier))
        assert artifact['name'].startswith('cc-runtime-a-a10-'+profile+'-diagnostics-43388269046-1')
        with tempfile.TemporaryDirectory(prefix='a10-retained-failure-') as directory:
            files,receipt=acquire(artifact,Path(directory),SOURCE)
            names=['a06-original-results.json','a06-controls-results.json'] if profile=='A06' else ['a07-results.json']
            records=[];campaigns=[]
            for name in names:
                paths=list(files.rglob(name));assert len(paths)==1
                body=paths[0].read_bytes();value=json.loads(body)
                campaigns.append({'name':name,'bytes':len(body),'sha256':hashlib.sha256(body).hexdigest(),
                                  'status':value.get('status'),'reason':value.get('reason')})
                failed=[]
                for row in value.get('cases',[]):
                    status=row.get('status','');status=status.get('status','') if isinstance(status,dict) else status
                    if not str(status).startswith('PASS'):failed.append(row)
                if not failed and not str(value.get('status','')).startswith('PASS'):
                    failed=[{'id':name,'classification':'EXACT_CAMPAIGN_FAILURE_WITHOUT_FAILED_CASE_ROW','originalCampaign':value}]
                records += failed
            assert records
            result['profiles'][profile]={'transport':receipt,'campaigns':campaigns,'failedRows':records,
                'scope':'Exact saved failure rows only; mixed gates retain original failed/inconclusive classifications.'}
    args.out.parent.mkdir(parents=True,exist_ok=True);args.out.write_text(json.dumps(result,indent=2)+'\n')
    body=json.dumps(result,sort_keys=True,separators=(',',':')).encode()
    encoded=base64.b64encode(body).decode();chunks=[encoded[i:i+12000] for i in range(0,len(encoded),12000)]
    print('A10_RETAINED_FAILURE_SHA256 '+hashlib.sha256(body).hexdigest(),flush=True)
    for i,chunk in enumerate(chunks):print('A10_RETAINED_FAILURE_PART '+str(i)+'/'+str(len(chunks))+' '+chunk,flush=True)

if __name__=='__main__':main()
