package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Bounded native observations only; blueprint, lease and Jobs owners retain all authority. */
final class FabricBlueprintMaterials {
    private final MinecraftServer server;
    private final ResourceLeaseService leases;
    private final FabricResourceLeaseWorld observer;
    FabricBlueprintMaterials(MinecraftServer server,ResourceLeaseService leases) {
        this.server=server;this.leases=leases;observer=new FabricResourceLeaseWorld(server);
    }
    BlueprintMaterials.Report capture(BlueprintStore.Inspection inspection,CapabilityRequest request,TrustedContext owner,long held) {
        thread();var a=request.arguments();var actor=((ActorValue)a.get("actor")).value();
        var source=((ContainerValue)a.get("source")).value();var destination=((ContainerValue)a.get("destination")).value();
        var stock=stock(destination,actor,owner);if(held>0 && stock.reason()==null)stock=new BlueprintMaterials.Stock(stock.status(),
                Math.addExact(stock.free(),held),stock.identity(),stock.tick(),stock.inspected(),null);
        var site=site(inspection,request,actor);
        return BlueprintMaterials.project(inspection,BlueprintMaterials.binding(request),site,stock,source,destination);
    }
    BlueprintMaterials.Stock stock(ContainerRef destination,ActorRef actor,TrustedContext owner) {
        var resource=ResourceLeases.Resource.plankStock(owner.scope().worldId(),destination);
        var seen=observer.observe(resource,new ResourceLeases.OwnerFacts(owner,actor,null,true,null));
        var free=leases.availableStock(resource,seen.quantity(),owner);
        return new BlueprintMaterials.Stock(seen.status(),free.usable()?free.available():0,seen.identity(),seen.tick(),seen.inspected(),
                seen.reason()!=null?seen.reason():free.reason());
    }
    Prerequisites.Supply supply(Prerequisites.Need need,String item) {
        thread();long tick=server.overworld().getGameTime();
        if(item.equals(BlueprintMaterials.PLANKS))return new Prerequisites.Supply(item,ObservationStatus.ABSENT,0,"make-new-planks",need.bound().observation(),tick,0,null);
        if(!item.equals("minecraft:oak_log"))return new Prerequisites.Supply(item,ObservationStatus.UNKNOWN,0,"unsupported",need.bound().observation(),tick,0,Reason.UNSUPPORTED_PRIMITIVE);
        var source=((ContainerValue)need.bound().request().arguments().get("source")).value();
        var actor=((ActorValue)need.bound().request().arguments().get("actor")).value();
        var resource=ResourceLeases.Resource.oakStock(need.origin().scope().worldId(),source);
        var seen=observer.observe(resource,new ResourceLeases.OwnerFacts(need.origin(),actor,null,true,null));
        var free=leases.availableStock(resource,seen.quantity(),need.origin());
        return new Prerequisites.Supply(item,seen.status(),free.usable()?free.available():0,seen.identity(),need.bound().observation(),tick,seen.inspected(),
                seen.reason()!=null?seen.reason():free.reason());
    }
    private BlueprintMaterials.Site site(BlueprintStore.Inspection inspection,CapabilityRequest request,ActorRef actor) {
        long tick=server.overworld().getGameTime();int work=0;
        try {
            var area=((AreaValue)request.arguments().get("site")).value();
            if(ResourceLeases.cells(area)>256)return unavailable(tick,work,Reason.BUDGET_EXHAUSTED);
            var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,Identifier.parse(actor.dimension())));
            if(level==null)return unavailable(tick,work,Reason.TARGET_UNAVAILABLE);
            var world=new FabricGatewayWorld(level);
            var observed=new HashMap<BlockPos,Blueprint.State>();
            // Inspect every approved cell, including sparse geometry gaps. No getChunk/force-load path.
            for(long x=area.minX();x<=area.maxX();x++)for(long y=area.minY();y<=area.maxY();y++)for(long z=area.minZ();z<=area.maxZ();z++) {
                var pos=new BlockPos((int)x,(int)y,(int)z);work++;
                if(!world.loadedForDiscovery(pos))return unavailable(tick,work,Reason.TARGET_UNAVAILABLE);
                if(!world.discoverable(actor,pos,Effect.OBSERVE))return unavailable(tick,work,Reason.AUTHORITY_DENIED);
                observed.put(pos,state(level.getBlockState(pos)));
            }
            if(!inspection.compatible())return unavailable(tick,work,Reason.ARTIFACT_INCOMPATIBLE);
            var view=BlueprintValidator.bind(inspection.body(),BlueprintMaterials.binding(request));var states=new ArrayList<Blueprint.State>();
            for(var cell:view.geometry().cells()) {
                int x=Math.addExact(view.binding().x(),cell.x()),y=Math.addExact(view.binding().y(),cell.y()),z=Math.addExact(view.binding().z(),cell.z());
                if(x<area.minX() || x>area.maxX() || y<area.minY() || y>area.maxY() || z<area.minZ() || z>area.maxZ())return unavailable(tick,work,Reason.REQUEST_INVALID);
                states.add(observed.get(new BlockPos(x,y,z)));
            }
            return new BlueprintMaterials.Site(ObservationStatus.PRESENT,states,tick,work,null);
        }catch(IllegalArgumentException|ArithmeticException invalid){return unavailable(tick,work,Reason.REQUEST_INVALID);}
    }
    private static Blueprint.State state(BlockState state) {
        var properties=new TreeMap<String,String>();for(var p:state.getProperties())property(properties,state,p);
        return new Blueprint.State(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),properties);
    }
    private static <T extends Comparable<T>> void property(Map<String,String> target,BlockState state,Property<T> property) {
        target.put(property.getName(),property.getName(state.getValue(property)));
    }
    private static BlueprintMaterials.Site unavailable(long tick,int work,Reason reason){return new BlueprintMaterials.Site(ObservationStatus.UNKNOWN,List.of(),tick,work,reason);}
    private void thread(){if(!server.isSameThread())throw new IllegalStateException("Blueprint observation game thread");}
}
