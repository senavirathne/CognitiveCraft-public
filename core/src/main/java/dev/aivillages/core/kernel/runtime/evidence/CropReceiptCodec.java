package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.CropDelivery;
import dev.aivillages.core.kernel.Contracts.*;
import java.util.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;

/** Genuine legacy crop receipt data; never manufactures a resource receipt. */
final class CropReceiptCodec {
    private CropReceiptCodec(){}
    static Map<String,Object> encode(CropDelivery.CropReceipt r) {
        return Map.of("receipt",r.receiptId().toString(),"run",r.runId().toString(),"batch",r.batchId().toString(),
                "actor",Map.of("citizen",r.actor().citizenId().toString(),"entity",r.actor().entityId().toString(),"dimension",r.actor().dimension()),
                "source",List.of(r.source().minX(),r.source().minY(),r.source().minZ(),r.source().maxX(),r.source().maxY(),r.source().maxZ()),
                "destination",List.of(r.destination().x(),r.destination().y(),r.destination().z()),"stage",r.stage().name(),"wheat",r.wheat());
    }
    static CropDelivery.CropReceipt decode(Map<String,Object> r) {
        keys(r,Set.of("receipt","run","batch","actor","source","destination","stage","wheat"));var a=object(r,"actor");keys(a,Set.of("citizen","entity","dimension"));
        String dim=string(a,"dimension");var source=coordinates(r.get("source"),6);var destination=coordinates(r.get("destination"),3);
        return new CropDelivery.CropReceipt(id(r,"receipt"),id(r,"run"),id(r,"batch"),new ActorRef(id(a,"citizen"),id(a,"entity"),dim),
                new Cuboid(dim,source[0],source[1],source[2],source[3],source[4],source[5]),new ContainerRef(dim,destination[0],destination[1],destination[2]),CropDelivery.Stage.valueOf(string(r,"stage")),number(r,"wheat"));
    }
    private static int[] coordinates(Object value,int count) {
        if(!(value instanceof List<?> list) || list.size()!=count)throw new IllegalArgumentException("Crop coordinates");
        var result=new int[count];for(int i=0;i<count;i++){if(!(list.get(i) instanceof Long v))throw new IllegalArgumentException("Crop coordinate type");result[i]=Math.toIntExact(v);}return result;
    }
}
