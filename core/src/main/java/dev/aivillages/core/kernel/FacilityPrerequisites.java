package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Exact table-only projections. These values grant neither stock nor facility truth. */
public final class FacilityPrerequisites {
    private FacilityPrerequisites() { }
    public static boolean root(CapabilityRequest request){
        try{return request.capability().equals(WoodenAxeProduction.ID) && request.arguments().size()==5
                && ((IntValue)request.arguments().get("amount")).value()==1
                && request.arguments().get("table") instanceof FacilityValue;}catch(RuntimeException invalid){return false;}
    }
    public static CapabilityRequest placement(CapabilityRequest root){
        if(!root(root))throw new IllegalArgumentException("Bound single axe required");var a=root.arguments();
        return new CapabilityRequest(CraftingTablePlacement.ID,Map.of("actor",a.get("actor"),"amount",new IntValue(1),"source",a.get("source"),"target",a.get("table")));
    }
    public static CapabilityRequest item(CapabilityRequest root){
        if(!root(root))throw new IllegalArgumentException("Bound single axe required");var a=root.arguments();
        return new CapabilityRequest(CraftingTableProduction.ID,Map.of("actor",a.get("actor"),"amount",new IntValue(1),"source",a.get("source"),"destination",a.get("source")));
    }
    public static boolean completedChildren(Jobs.Job root,Map<UUID,Jobs.Job> rows){
        if(root.children().isEmpty())return root.dependencies().isEmpty();
        if(!root(root.request().request()) || root.children().size()>2 || !new HashSet<>(root.dependencies()).equals(new HashSet<>(root.children())))return false;
        var capabilities=new HashSet<CapabilityId>();
        for(UUID id:root.children()){
            var child=rows.get(id);if(child==null || child.state()!=Jobs.State.SUCCEEDED || !root.id().equals(child.parent()) || !root.origin().equals(child.origin()))return false;
            var request=child.request().request();
            if(!request.equals(item(root.request().request())) && !request.equals(placement(root.request().request())) || !capabilities.add(request.capability()))return false;
        }
        return true;
    }
}
