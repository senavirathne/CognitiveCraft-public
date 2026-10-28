package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.ResourceDelivery;
import java.util.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;

/** Exact additive envelope for the existing gateway-issued log DEPOSIT receipt. */
final class DepositReceiptCodec {
    private DepositReceiptCodec() { }
    static Map<String,Object> encode(ResourceDelivery.Receipt r) {
        if(r.stage()!=ResourceDelivery.Stage.DEPOSIT || r.quantity()!=1 || r.transformation()!=null || r.placement()!=null
                || !Set.of("minecraft:oak_log","minecraft:birch_log").contains(r.item().id()) || !r.item().components().equals("default"))
            throw new IllegalArgumentException("Unsupported native deposit receipt");
        var row=new LinkedHashMap<String,Object>();row.put("schema",1L);row.put("id",r.receiptId().toString());row.put("principal",r.origin().principal().id().toString());
        row.put("world",r.origin().scope().worldId().toString());row.put("domain",r.origin().scope().domainId().toString());
        row.put("run",r.runId().toString());row.put("operation",r.operationId().toString());row.put("batch",r.batchId().toString());row.put("citizen",r.actor().citizenId().toString());
        row.put("entity",r.actor().entityId().toString());row.put("dimension",r.actor().dimension());var s=r.source();
        row.put("source",List.of(s.minX(),s.minY(),s.minZ(),s.maxX(),s.maxY(),s.maxZ()));var d=r.destination();row.put("destination",List.of(d.x(),d.y(),d.z()));
        row.put("item",r.item().id());row.put("quantity",r.quantity());var b=r.sourceBlock();row.put("sourceBlock",List.of(b.x(),b.y(),b.z()));
        row.put("drops",r.dropIds().stream().map(UUID::toString).toList());return row;
    }
    static ResourceDelivery.Receipt decode(Map<String,Object> row) {
        keys(row,Set.of("schema","id","principal","world","domain","run","operation","batch","citizen","entity","dimension","source","destination","item","quantity","sourceBlock","drops"));
        if(number(row,"schema")!=1 || number(row,"quantity")!=1)throw new IllegalArgumentException("Unknown deposit receipt schema/quantity");
        String dimension=string(row,"dimension");int[] s=cell(row,"source",6),d=cell(row,"destination",3),b=cell(row,"sourceBlock",3);
        if(!(row.get("drops") instanceof List<?> list) || list.isEmpty() || list.size()>64)throw new IllegalArgumentException("Deposit receipt drops");
        var drops=new ArrayList<UUID>();for(Object value:list){if(!(value instanceof String text))throw new IllegalArgumentException("Deposit receipt drop");UUID id=UUID.fromString(text);if(!id.toString().equals(text))throw new IllegalArgumentException("Deposit receipt UUID");drops.add(id);}
        var receipt=new ResourceDelivery.Receipt(id(row,"id"),new TrustedContext(new PrincipalRef(id(row,"principal")),new ScopeRef(id(row,"world"),id(row,"domain"))),
                id(row,"run"),id(row,"operation"),id(row,"batch"),new ActorRef(id(row,"citizen"),id(row,"entity"),dimension),new Cuboid(dimension,s[0],s[1],s[2],s[3],s[4],s[5]),
                new ContainerRef(dimension,d[0],d[1],d[2]),new ResourceDelivery.ItemIdentity(string(row,"item"),"default"),ResourceDelivery.Stage.DEPOSIT,1,
                new ResourceDelivery.BlockPosition(dimension,b[0],b[1],b[2]),drops);
        encode(receipt);return receipt;
    }
    private static int[] cell(Map<String,Object> row,String key,int size) {
        if(!(row.get(key) instanceof List<?> list) || list.size()!=size)throw new IllegalArgumentException("Deposit receipt coordinate");
        int[] result=new int[size];for(int n=0;n<size;n++){if(!(list.get(n) instanceof Long value))throw new IllegalArgumentException("Deposit receipt coordinate integer");result[n]=Math.toIntExact(value);}return result;
    }
}
