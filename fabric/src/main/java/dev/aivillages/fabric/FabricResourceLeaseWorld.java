package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.ResourceLeases.*;
import dev.aivillages.core.kernel.Outcomes.Reason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Loaded, permitted, bounded observations only. Never obtains a chunk or changes the world. */
public final class FabricResourceLeaseWorld implements ResourceLeaseService.Observer {
    private final MinecraftServer server;
    private final LeaseTargetWitnesses witnesses=new LeaseTargetWitnesses(128);
    public FabricResourceLeaseWorld(MinecraftServer server){this.server=java.util.Objects.requireNonNull(server);}
    @Override public Observation observe(Resource resource,OwnerFacts owner) {
        if(!server.isSameThread())throw new IllegalStateException("Lease observation game thread");
        ServerLevel level=server.getLevel(ResourceKey.create(Registries.DIMENSION,Identifier.parse(resource.dimension())));
        long tick=server.overworld().getGameTime();
        if(level==null)return observation(resource,ObservationStatus.UNKNOWN,0,"unloaded",tick,0,Reason.TARGET_UNAVAILABLE);
        FabricGatewayWorld world=new FabricGatewayWorld(level);
        Cuboid area=resource.area();int scanned=0;long quantity=0;
        for(long x=area.minX();x<=area.maxX();x++)for(long y=area.minY();y<=area.maxY();y++)
            for(long z=area.minZ();z<=area.maxZ();z++) {
                BlockPos pos=new BlockPos((int)x,(int)y,(int)z);scanned++;
                if(scanned>256)throw new IllegalArgumentException("Lease cell work");
                if(!world.loadedForDiscovery(pos))
                    return observation(resource,ObservationStatus.UNKNOWN,0,"unloaded",tick,scanned,Reason.TARGET_UNAVAILABLE);
                if(!world.discoverable(owner.worker(),pos,Effect.OBSERVE))
                    return observation(resource,ObservationStatus.UNKNOWN,0,"denied",tick,scanned,Reason.AUTHORITY_DENIED);
                if(resource.kind()==Kind.SPACE)continue;
                if(resource.item().equals("minecraft:mature_wheat")) {
                    var state=level.getBlockState(pos);
                    if(state.is(Blocks.WHEAT)&&((CropBlock)Blocks.WHEAT).isMaxAge(state))quantity++;
                }
            }
        String spatial=resource.dimension()+":"+area.minX()+","+area.minY()+","+area.minZ()+":"
                +area.maxX()+","+area.maxY()+","+area.maxZ();
        if(resource.kind()==Kind.SPACE)return observation(resource,ObservationStatus.PRESENT,1,spatial,tick,scanned,null);
        if(resource.item().equals("minecraft:mature_wheat"))
            return observation(resource,quantity==0?ObservationStatus.ABSENT:ObservationStatus.PRESENT,quantity,spatial,tick,scanned,null);
        BlockPos pos=new BlockPos(area.minX(),area.minY(),area.minZ());
        var block=level.getBlockState(pos);var entity=level.getBlockEntity(pos);
        if(block.is(Blocks.CHEST)&&block.getValue(ChestBlock.TYPE)!=ChestType.SINGLE)
            return observation(resource,ObservationStatus.ABSENT,0,spatial,tick,scanned,Reason.TARGET_INVALID);
        if(resource.item().equals("minecraft:crafting_table")) {
            boolean present=block.is(Blocks.CRAFTING_TABLE);
            return observation(resource,present?ObservationStatus.PRESENT:ObservationStatus.ABSENT,
                    present?1:0,spatial,tick,scanned,null);
        }
        if(!(entity instanceof Container container) || !block.is(Blocks.CHEST)&&!block.is(Blocks.BARREL)
                || container.getContainerSize()>128
                || entity instanceof BaseContainerBlockEntity base&&base.isLocked()
                || entity instanceof RandomizableContainerBlockEntity random&&random.getLootTable()!=null)
            return observation(resource,ObservationStatus.ABSENT,0,spatial,tick,scanned,Reason.TARGET_INVALID);
        String identity;
        try { identity=witnesses.identity(spatial,entity); }
        catch(IllegalStateException full) {
            return observation(resource,ObservationStatus.UNKNOWN,0,"witness-limit",tick,scanned,Reason.STORAGE_LIMIT_REACHED);
        }
        if(resource.kind()==Kind.FACILITY)return observation(resource,ObservationStatus.PRESENT,1,identity,tick,scanned,null);
        if(resource.kind()==Kind.EQUIPMENT) {
            if(resource.slot()>=container.getContainerSize())
                return observation(resource,ObservationStatus.ABSENT,0,identity,tick,scanned,Reason.TARGET_INVALID);
            var stack=container.getItem(resource.slot());boolean present=stack.is(Items.IRON_HOE)&&stack.getCount()==1;
            if(present) {
                try { identity=identity+":"+witnesses.identity(spatial+":slot"+resource.slot(),stack); }
                catch(IllegalStateException full) {
                    return observation(resource,ObservationStatus.UNKNOWN,0,"witness-limit",tick,scanned+1,Reason.STORAGE_LIMIT_REACHED);
                }
            }
            return observation(resource,present?ObservationStatus.PRESENT:ObservationStatus.ABSENT,
                    present?1:0,identity+":"+stack.getDamageValue(),tick,scanned+1,null);
        }
        for(int slot=0;slot<container.getContainerSize();slot++) {
            var stack=container.getItem(slot);if(stack.is(Items.WHEAT))quantity+=stack.getCount();
        }
        return observation(resource,quantity==0?ObservationStatus.ABSENT:ObservationStatus.PRESENT,quantity,
                identity,tick,scanned+container.getContainerSize(),null);
    }
    private static Observation observation(Resource r,ObservationStatus status,long quantity,String identity,
                                           long tick,int work,Reason reason) {
        return new Observation(r,status,quantity,identity,tick,work,reason);
    }
}
