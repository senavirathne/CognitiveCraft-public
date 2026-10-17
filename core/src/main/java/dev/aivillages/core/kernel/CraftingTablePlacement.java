package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Direct PLACE requires a fresh effect at the exact approved address; ENSURE is a parent prerequisite. */
public final class CraftingTablePlacement {
    public static final CapabilityId ID=new CapabilityId("cognitivecraft:place_crafting_table",1);
    public static final CapabilitySpec SPEC=new CapabilitySpec(ID,List.of(new Parameter("actor",Type.ACTOR,0,0),
            new Parameter("amount",Type.INT,1,1),new Parameter("source",Type.CONTAINER,0,0),new Parameter("target",Type.FACILITY,0,0)),
            Type.INT,Set.of(Effect.OBSERVE,Effect.MOVE,Effect.WITHDRAW,Effect.PLACE),Completion.ATTRIBUTABLE_PLACEMENT);
    private CraftingTablePlacement() { }
    public static boolean is(CapabilityId id){return ID.equals(id);}
    public static FacilityRef target(CapabilityRequest request){return ((FacilityValue)request.arguments().get("target")).value();}
    public static ContainerRef address(CapabilityRequest request){var t=target(request);return new ContainerRef(t.dimension(),t.x(),t.y(),t.z());}
    public static Cuboid workspace(CapabilityRequest request){var t=target(request);return new Cuboid(t.dimension(),Math.subtractExact(t.x(),1),Math.subtractExact(t.y(),1),Math.subtractExact(t.z(),1),Math.addExact(t.x(),1),Math.addExact(t.y(),1),Math.addExact(t.z(),1));}
    public static boolean valid(CapabilityRequest request){
        try{var a=request.arguments();var actor=((ActorValue)a.get("actor")).value();var source=((ContainerValue)a.get("source")).value();var t=target(request);
            return is(request.capability()) && a.size()==4 && ((IntValue)a.get("amount")).value()==1
                    && actor.dimension().equals(source.dimension()) && actor.dimension().equals(t.dimension())
                    && Math.abs((long)t.x())<=30_000_000 && Math.abs((long)t.y())<=30_000_000 && Math.abs((long)t.z())<=30_000_000;
        }catch(RuntimeException invalid){return false;}
    }
    public static List<GatewayPrimitives.Operation> operations(){return List.of(GatewayPrimitives.Operation.OBSERVE_TABLE_SITE,
            GatewayPrimitives.Operation.MOVE_TO_INPUT,GatewayPrimitives.Operation.WITHDRAW_TABLE_ITEM,
            GatewayPrimitives.Operation.MOVE_TO_PLACEMENT,GatewayPrimitives.Operation.PLACE_CRAFTING_TABLE);}
    public static boolean permits(GatewayPrimitives.Operation op){return operations().contains(op);}
    public static boolean matches(ValidatedRequest bound,UUID run,ResourceDelivery.Receipt r){
        if(!valid(bound.request()) || r.placement()==null || r.transformation()!=null || !r.dropIds().isEmpty())return false;
        var a=bound.request().arguments();var source=((ContainerValue)a.get("source")).value();
        return r.origin().equals(bound.context()) && r.runId().equals(run) && r.actor().equals(((ActorValue)a.get("actor")).value())
                && r.item().equals(ResourceDelivery.CRAFTING_TABLE) && r.quantity()==1 && r.source().equals(ResourceLeases.point(source))
                && r.sourceBlock().inside(r.source()) && r.destination().equals(address(bound.request()))
                && r.placement().target().equals(target(bound.request()))
                && (r.stage()==ResourceDelivery.Stage.WITHDRAW || r.stage()==ResourceDelivery.Stage.PLACE);
    }
    public static long quantity(ValidatedRequest bound,UUID run,List<ResourceDelivery.Receipt> receipts){
        if(!valid(bound.request()) || receipts.size()>64)throw new IllegalArgumentException("Placement evidence bound");
        var seen=new HashMap<UUID,ResourceDelivery.Receipt>();var operations=new HashSet<UUID>();
        ResourceDelivery.Receipt withdrawal=null,placed=null;
        for(var r:receipts){
            if(!matches(bound,run,r))throw new IllegalArgumentException("Placement binding");
            var old=seen.putIfAbsent(r.receiptId(),r);if(old!=null){if(!old.equals(r))throw new IllegalArgumentException("Changed placement receipt");continue;}
            if(!operations.add(r.operationId()))throw new IllegalArgumentException("Repeated physical placement operation");
            if(r.stage()==ResourceDelivery.Stage.WITHDRAW){
                if(withdrawal!=null || placed!=null || !r.placement().blockState().equals("minecraft:air"))throw new IllegalArgumentException("One actual withdrawal required");withdrawal=r;
            }else{
                if(withdrawal==null || placed!=null || !r.batchId().equals(withdrawal.batchId())
                        || !r.placement().sourceIdentity().equals(withdrawal.placement().sourceIdentity())
                        || !r.placement().siteFingerprint().equals(withdrawal.placement().siteFingerprint())
                        || !r.placement().custodyBefore().equals(withdrawal.placement().custodyAfter())
                        || !r.placement().blockState().equals("minecraft:crafting_table"))throw new IllegalArgumentException("Actual consumed-item placement lineage");placed=r;
            }
        }
        return placed==null?0:1;
    }
    public static boolean completed(ValidatedRequest bound,UUID run,List<ResourceDelivery.Receipt> receipts){
        try{return quantity(bound,run,receipts)==1;}catch(RuntimeException invalid){return false;}
    }
}
