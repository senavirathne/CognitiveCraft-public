package dev.aivillages.fabric;

import java.util.List;
import dev.aivillages.core.kernel.Contracts.*;

/** Read-only geometric quotes. Actual native movement still debits the original Jobs allowance. */
final class FabricProductionTravel {
    // A point within the unchanged 2.5-block interaction radius can occupy a cell at L1 distance at most five.
    private static final int INTERACTION_CELLS=5;
    private FabricProductionTravel() { }
    private static long distance(ContainerRef a,ContainerRef b) {
        if(!a.dimension().equals(b.dimension()))throw new IllegalArgumentException("Planning travel dimension");
        return Math.abs((long)a.x()-b.x())+Math.abs((long)a.y()-b.y())+Math.abs((long)a.z()-b.z());
    }
    private static ContainerRef point(FacilityRef site){return new ContainerRef(site.dimension(),site.x(),site.y(),site.z());}
    static long recipe(ContainerRef start,ContainerRef source,ContainerRef destination,FacilityRef facility,FacilityRef precedingSite) {
        // Subsequent batches start near the last destination; a preceding placement can end near its site.
        long approach=Math.max(distance(start,source),distance(destination,source)+INTERACTION_CELLS);
        if(precedingSite!=null)approach=Math.max(approach,distance(point(precedingSite),source)+INTERACTION_CELLS);
        return facility==null?Math.addExact(approach,distance(source,destination)):
                Math.addExact(approach,Math.addExact(distance(source,point(facility)),distance(point(facility),destination)));
    }
    static long placement(ContainerRef start,ContainerRef source,FacilityRef site) {
        var destination=point(site);
        // Withdrawal can end at any reachable side of the chest. The next leg starts there,
        // and cannot pass through the chest or stand inside the prospective table footprint.
        return Math.addExact(recipe(start,source,destination,null,site),INTERACTION_CELLS);
    }
    static long existingTableOnClearFloor(ContainerRef start,ContainerRef source,ContainerRef destination,FacilityRef facility) {
        var table=point(facility);
        // On a witnessed level floor, the native worker can approach each face
        // inside its existing reach. Reserve two cells for the selected arrival side
        // before a repeated trip. The original allowance still meters actual motion.
        long approach=Math.max(interactionLeg(start,source),Math.addExact(distance(destination,source),2));
        return Math.addExact(approach,Math.addExact(interactionLeg(source,table),interactionLeg(table,destination)));
    }
    private static long interactionLeg(ContainerRef from,ContainerRef to) {
        return Math.max(0,distance(from,to)-2);
    }
    static long acquisition(ContainerRef start,ContainerRef staging,List<ContainerRef> eligible) {
        if(eligible.isEmpty() || eligible.size()>256)throw new IllegalArgumentException("Bounded actual acquisition targets required");
        long worst=0;
        for(var target:eligible) {
            long returning=distance(target,staging);
            long outgoing=Math.max(distance(start,target),Math.addExact(returning,INTERACTION_CELLS));
            worst=Math.max(worst,Math.addExact(outgoing,returning));
        }
        return worst;
    }
}
