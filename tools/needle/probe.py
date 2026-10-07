"""Engine/schema diagnostic on synthetic acceptance text; never imported by runtime."""
import json, os, re, subprocess, sys
from pathlib import Path
assets = Path(os.environ['COGNITIVECRAFT_NEEDLE_DIR'])
evidence = Path('fabric/build/needle-evidence'); evidence.mkdir(parents=True, exist_ok=True)
query = 'Ada, harvest 4 wheat from 0,64,0 through 2,64,2 and deliver it to the container at 5,64,0.'
base = {
 'citizen': {'type':'string', 'description':'Citizen name or ID stated verbatim. Permitted references: Ada, Mira'},
 'amount': {'type':'integer', 'description':'Number of wheat to harvest and deliver'},
 'from': {'type':'string', 'description':'First source corner as x,y,z coordinates from the input'},
 'through': {'type':'string', 'description':'Second source corner as x,y,z coordinates from the input'},
 'destination': {'type':'string', 'description':'Destination container coordinates as x,y,z from the input'}
}
records=[]
for mode in range(8):
    props = json.loads(json.dumps(base))
    if mode >= 2:
        props['citizen']['enum']=['Ada','Mira']
        for key,val in [('from','0,64,0'),('through','2,64,2'),('destination','5,64,0')]: props[key]['enum']=[val]
    current_query = query
    if mode >= 4:
        props = {
            'citizen': {'type':'string', 'enum':['Ada','Mira'], 'description':'Citizen who will harvest'},
            'amount': {'type':'integer', 'description':'How many wheat to harvest and deliver'},
            'source': {'type':'string', 'enum':['farm'], 'description':'Farm to harvest wheat from'},
            'destination': {'type':'string', 'enum':['chest'], 'description':'Container to deliver wheat into'}
        }
        current_query = 'Ada, harvest 4 wheat from farm and deliver it to chest.'
        if mode >= 6: props.pop('source'); props.pop('destination')
    tools=[{'name' :'harvest_wheat','description':'Harvest wheat from a source area and deliver it to a container. Copy only stated arguments.',
            'parameters':{'type':'object','properties':props,'required':list(props) if mode % 2 else []}}]
    path=evidence / f'probe-tools-{mode}.json'; path.write_text(json.dumps(tools))
    run=subprocess.run([str(assets/'needle'),'--model',str(assets/'needle3.cact'),'--tools',str(path),
                        '--prompt',current_query,'--max','384','--threads','1','--fail-input-overflow'],capture_output=True,text=True,timeout=30,
                        env={'NEEDLE_TELEMETRY':'0','DO_NOT_TRACK':'1'})
    record={'mode':mode,'stdout':run.stdout,'stderr':run.stderr[-2000:],'returncode':run.returncode}
    records.append(record); print(json.dumps(record))
(evidence/'schema-probe.json').write_text(json.dumps(records,indent=2))
