package dev.aivillages.core.kernel;

import java.util.*;
import java.util.function.LongSupplier;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.Prerequisites.*;

/** Uses the authoritative lease owner. Inspections never reserve stock or persist a second ledger. */
public final class LeasePrerequisiteSupplies implements Supplies {
    private final ResourceLeaseService leases;
    private final LongSupplier ticks;
    public LeasePrerequisiteSupplies(ResourceLeaseService leases,LongSupplier ticks) {
        this.leases=Objects.requireNonNull(leases);this.ticks=Objects.requireNonNull(ticks);
    }
    @Override public Supply inspect(Need need,String item) {
        ResourceLeases.Resource resource;
        var arguments=need.bound().request().arguments();
        UUID world=need.origin().scope().worldId();
        if(item.equals("minecraft:wheat") && arguments.get("destination") instanceof ContainerValue destination)
            resource=ResourceLeases.Resource.stock(world,destination.value());
        else if(item.equals("minecraft:oak_log") && arguments.get("destination") instanceof ContainerValue destination)
            resource=ResourceLeases.Resource.oakStock(world,destination.value());
        else if(item.equals("minecraft:mature_wheat") && arguments.get("source") instanceof AreaValue source)
            resource=ResourceLeases.Resource.crops(world,source.value());
        else return new Supply(item,ObservationStatus.UNKNOWN,0,"unsupported-accounting",need.bound().observation(),
                ticks.getAsLong(),0,Reason.UNSUPPORTED_PRIMITIVE);
        var inspected=leases.inspectWithEvidence(new ResourceLeases.Owner(need.demand(),need.guard().generation()),resource,need.origin());
        var seen=inspected.observation();var result=inspected.decision();
        if(seen==null)return new Supply(item,ObservationStatus.UNKNOWN,0,"unavailable",need.bound().observation(),
                ticks.getAsLong(),0,result.reason());
        // A currently observed empty approved stock is known zero. Unloaded/denied/stale is not.
        Reason reason=result.reason();
        if(reason==Reason.RESOURCE_MISSING && seen.status()==ObservationStatus.ABSENT && seen.reason()==null)reason=null;
        var ref=new ObservationRef(UUID.randomUUID(),0,resource.dimension());
        return new Supply(item,seen.status(),result.usable()?result.available():0,seen.identity(),ref,
                seen.tick(),seen.inspected(),reason);
    }
}
