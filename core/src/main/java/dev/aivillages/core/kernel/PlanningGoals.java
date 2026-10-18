package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** One explicit broad outcome. It adds no executable method, primitive or worker policy. */
public final class PlanningGoals {
    public static final CapabilityId ID = new CapabilityId("cognitivecraft:supply_wheat_pair", 1);
    public static final CapabilitySpec SPEC = new CapabilitySpec(ID, List.of(
            new Parameter("actor", Type.ACTOR, 0, 0), new Parameter("source", Type.AREA, 0, 0),
            new Parameter("destination", Type.CONTAINER, 0, 0), new Parameter("otherDestination", Type.CONTAINER, 0, 0),
            new Parameter("amount", Type.INT, 2, 64), new Parameter("firstAmount", Type.INT, 1, 63)),
            Type.INT, CropDelivery.SPEC.effects(), Completion.ALL_CHILDREN);
    private PlanningGoals() { }
    public static CapabilityCatalog catalog() {
        return id -> id.equals(ID) ? Optional.of(SPEC) : id.equals(BlueprintMaterials.ID) ? Optional.of(BlueprintMaterials.SPEC) : ResourceDelivery.spec(id);
    }
    public static CapabilityResolver.SupportPolicy support() {
        return spec -> spec.equals(SPEC) ? new CapabilityResolver.Envelope(CapabilityResolver.Domain.BROAD_GOAL, SPEC.effects())
                : CapabilityResolver.cropDeliverySupport().forCapability(spec);
    }
    public static boolean valid(CapabilityRequest request) {
        try {
            if (!request.capability().equals(ID) || !request.arguments().keySet().equals(Set.of(
                    "actor", "source", "destination", "otherDestination", "amount", "firstAmount"))) return false;
            var a=request.arguments(); var actor=((ActorValue)a.get("actor")).value();
            var source=((AreaValue)a.get("source")).value(); var target=((ContainerValue)a.get("destination")).value();
            var other=((ContainerValue)a.get("otherDestination")).value();
            long total=((IntValue)a.get("amount")).value(), first=((IntValue)a.get("firstAmount")).value();
            return total>=2 && total<=64 && first>=1 && first<total && !target.equals(other)
                    && actor.dimension().equals(source.dimension()) && actor.dimension().equals(target.dimension())
                    && actor.dimension().equals(other.dimension());
        } catch (RuntimeException invalid) { return false; }
    }
    public static List<CapabilityRequest> slots(CapabilityRequest request) {
        if (!valid(request)) throw new IllegalArgumentException("Recognized bound goal required");
        var args=request.arguments(); long total=((IntValue)args.get("amount")).value(),first=((IntValue)args.get("firstAmount")).value();
        return List.of(crop(args,first,(ContainerValue)args.get("destination")),
                crop(args,total-first,(ContainerValue)args.get("otherDestination")));
    }
    private static CapabilityRequest crop(Map<String,Value> a,long quantity,ContainerValue destination) {
        return new CapabilityRequest(CropDelivery.ID,Map.of("actor",a.get("actor"),"source",a.get("source"),
                "destination",destination,"amount",new IntValue(quantity)));
    }
    public static boolean covers(CapabilityRequest goal,List<CapabilityRequest> children) {
        if (!valid(goal) || children.size()<2 || children.size()>8) return false;
        long[] quantities=new long[2]; var slots=slots(goal);
        try {
            for(var child:children) {
                if(!child.capability().equals(CropDelivery.ID) || child.arguments().size()!=4) return false;
                long q=((IntValue)child.arguments().get("amount")).value(); if(q<1 || q>64)return false;
                int selected=-1;
                for(int i=0;i<2;i++) {
                    var expected=slots.get(i).arguments();
                    if(List.of("actor","source","destination").stream().allMatch(
                            key->Objects.equals(child.arguments().get(key),expected.get(key))))selected=i;
                }
                if(selected<0)return false; quantities[selected]=Math.addExact(quantities[selected],q);
            }
            return quantities[0]==((IntValue)slots.get(0).arguments().get("amount")).value()
                    && quantities[1]==((IntValue)slots.get(1).arguments().get("amount")).value();
        } catch(RuntimeException invalid){return false;}
    }
}
