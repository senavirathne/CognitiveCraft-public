package dev.aivillages.core.kernel.storage;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import static dev.aivillages.core.kernel.storage.StorageResolutionTestSupport.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;

/** Actual bounded Java queries using synthetic read-only ports; emits observations, never pass flags. */
public final class StorageResolutionProbe {
    public static void main(String[] args)throws Exception{
        JsonObject schedule=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();Path root=Path.of(args[1]);Files.createDirectories(root);var output=new StringBuilder();
        for(JsonElement item:schedule.getAsJsonArray("cases")){
            JsonObject spec=item.getAsJsonObject();var e=new Env();int count=spec.get("rows").getAsInt();long seed=spec.get("seed").getAsLong();var order=new ArrayList<Integer>();for(int i=0;i<count;i++)order.add(i);Collections.shuffle(order,new Random(seed));
            var inputs=new ArrayList<Map<String,Object>>();
            for(int i:order){int x,z,y;
                // Distinct tie-break controls: x, then y, then z, then UUID at the same coordinate.
                if(i<8){String tie=spec.get("tie").getAsString();x=tie.equals("x")?(i%2==0?-1:1):0;y=tie.equals("y")?(i%2==0?63:65):64;z=tie.equals("z")?(i%2==0?-1:1):0;}
                else{x=2+i%8;y=64;z=(i/8)%5-2;}
                UUID id=e.add(100+i,"stock-"+i,x,y,z,true);inputs.add(Map.of("storage_id",id.toString(),"x",x,"y",y,"z",z));
            }
            var body=count==257?named("absent"):nearest("CALLER");var q=e.resolver.begin(SCOPE,selector(body),Permission.DEPOSIT);var slices=new ArrayList<Map<String,Object>>();StorageResolution result;
            do{long tick=e.tick.get(),start=System.nanoTime();result=q.step();long elapsed=System.nanoTime()-start;int observed=e.observations;var again=q.step();if(again.status()!=result.status() || e.observations!=observed)throw new AssertionError("same tick performed more work");slices.add(Map.of("tick",tick,"elapsed_ns",elapsed,"native_observations",observed,"metadata_inspected",q.work().metadataInspected(),"eligible_candidates",q.work().eligibleCandidates(),"status",result.status().name()));e.tick.incrementAndGet();}while(result.status()==StorageResolution.Status.PENDING);
            var observation=new TreeMap<String,Object>();observation.put("id",spec.get("id").getAsString());observation.put("seed",seed);observation.put("tier","PURE_RESOLUTION_PORTS");observation.put("input",inputs);observation.put("selector",body);observation.put("scope",SCOPE.body());observation.put("anchor",Map.of("x_milli",500L,"y_milli",64500L,"z_milli",500L));observation.put("slices",slices);observation.put("status",result.status().name());observation.put("storage_id",result.endpoint()==null?null:result.endpoint().storageId().toString());observation.put("integration_root",null);observation.put("participant_save",null);output.append(new GsonBuilder().serializeNulls().create().toJson(observation)).append('\n');
        }
        Files.writeString(root.resolve("observations.jsonl"),output);
    }
    private StorageResolutionProbe(){}
}
