package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Exact registered prerequisite projections. No program, scheduler, inventory or model. */
public final class ResourceComposition {
    private ResourceComposition() { }
    public static final List<String> RECIPE_KEYS=List.of(OakPlankProduction.RECIPE,StickProduction.RECIPE,WoodenAxeProduction.RECIPE);
    public static boolean make(Prerequisites.Need need) {
        return need.bound().request().capability().equals(WoodenAxeProduction.ID)
                && need.item().equals(ResourceDelivery.WOODEN_AXE.id())
                && need.allocation()==Prerequisites.AllocationPolicy.MATERIAL_ONLY;
    }
    public static CapabilityRequest project(Prerequisites.Need need,CapabilityId capability,long quantity) {
        if(!make(need))throw new IllegalArgumentException("Registered axe MAKE goal required");
        var root=need.bound().request();var a=root.arguments();
        if(capability.equals(WoodenAxeProduction.ID))return new CapabilityRequest(capability,Map.of(
                "actor",a.get("actor"),"amount",new IntValue(quantity),"source",a.get("source"),
                "destination",a.get("destination"),"table",a.get("table")));
        if(capability.equals(OakLogDelivery.ID)) {
            if(need.acquisitionSource()==null)throw new IllegalArgumentException("Missing bounded oak acquisition source");
            return new CapabilityRequest(capability,Map.of("actor",a.get("actor"),"amount",new IntValue(quantity),
                    "source",new AreaValue(need.acquisitionSource()),"destination",a.get("source")));
        }
        if(capability.equals(OakPlankProduction.ID)||capability.equals(StickProduction.ID))
            return new CapabilityRequest(capability,Map.of("actor",a.get("actor"),"amount",new IntValue(quantity),
                    "source",a.get("source"),"destination",a.get("source")));
        throw new IllegalArgumentException("Unsupported producer projection");
    }
    public static Prerequisites.Production oakAcquisition() {
        return new Prerequisites.Production(OakLogDelivery.ID.name(),ResourceDelivery.OAK_LOG.id(),OakLogDelivery.ID,"",1,List.of());
    }
    public static boolean registered(Prerequisites.Production production) {
        return production.equals(oakAcquisition()) || RecipeProduction.is(production.capability())
                && production.equals(RecipeProduction.of(production.capability()).relationship());
    }
    public static ResourceDelivery.ItemIdentity item(String id) {
        return List.of(ResourceDelivery.OAK_LOG,ResourceDelivery.OAK_PLANKS,ResourceDelivery.STICK,ResourceDelivery.WOODEN_AXE)
                .stream().filter(i->i.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unsupported material"));
    }
    public static long inputQuantity(CapabilityRequest request,String item) {
        if(!RecipeProduction.is(request.capability()))return 0;
        var d=RecipeProduction.of(request.capability());long q=((IntValue)request.arguments().get("amount")).value();
        return d.input().id().equals(item) ? d.inputs(q) : d.axe() && ResourceDelivery.STICK.id().equals(item) ? d.secondaryInputs(q) : 0;
    }
    /** Preselected shared root envelope; each physical attempt retains its independent producer cap. */
    public static Budgets.Limits allowance(long now) {
        return new Budgets.Limits(Map.of(Budgets.Kind.CALLS,1024L,Budgets.Kind.INSTRUCTIONS,32768L,
                Budgets.Kind.OBSERVATIONS,32768L,Budgets.Kind.TRAVEL_BLOCKS,512L,
                Budgets.Kind.ATTEMPTED_EFFECTS,256L,Budgets.Kind.COMMITTED_EFFECTS,256L,
                Budgets.Kind.ELAPSED_TICKS,7200L),Math.addExact(now,600000));
    }
}
