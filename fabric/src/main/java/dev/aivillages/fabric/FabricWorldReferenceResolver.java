package dev.aivillages.fabric;

import dev.aivillages.core.kernel.CitizenRegistry;
import dev.aivillages.core.kernel.LanguageRequests;
import dev.aivillages.core.kernel.SurvivalGateway;
import dev.aivillages.core.kernel.WorldReferenceBinding;
import dev.aivillages.core.kernel.WorldReferenceDiscovery;
import dev.aivillages.core.kernel.WorldReferenceResolver.Point;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import java.time.Clock;
import java.util.Objects;
import static dev.aivillages.core.kernel.Contracts.*;

/** Game-thread loaded-only observation adapter for the reusable core binding policy. */
final class FabricWorldReferenceResolver implements LanguageRequests.ResolutionHandle {
    private final WorldReferenceBinding binding;

    FabricWorldReferenceResolver(FabricGatewayWorld world, CitizenRegistry citizens,
                                 TrustedContext caller, ServerPlayer player,
                                 LanguageRequests.Intent intent, Clock clock) {
        this(world,citizens,caller,player,intent,clock,null);
    }
    FabricWorldReferenceResolver(FabricGatewayWorld world,CitizenRegistry citizens,TrustedContext caller,ServerPlayer player,
            LanguageRequests.Intent intent,Clock clock,
            java.util.function.BiFunction<ContainerRef,Long,dev.aivillages.core.kernel.ResourceLeases.Result> inputAvailability) {
        Objects.requireNonNull(world); Objects.requireNonNull(player);
        if (!player.getUUID().equals(caller.principal().id()))
            throw new SecurityException("Language caller mismatch");
        BlockPos at = player.blockPosition();
        Point anchor = new Point(player.level().dimension().identifier().toString(),
                at.getX(),at.getY(),at.getZ());
        WorldReferenceDiscovery.Observation observations = new WorldReferenceDiscovery.Observation() {
            @Override public SurvivalGateway.ActorState actor(ActorRef actor) { return world.actor(actor); }
            @Override public SurvivalGateway.CropState crop(ActorRef actor, Point point) {
                var cell = new SurvivalGateway.Cell(point.dimension(),point.x(),point.y(),point.z());
                BlockPos pos = new BlockPos(point.x(),point.y(),point.z());
                if (!world.loadedForDiscovery(pos) || !world.discoverable(actor,pos,Effect.HARVEST))
                    return new SurvivalGateway.CropState(ObservationStatus.UNKNOWN,false,cell.identity());
                return world.crop(cell);
            }
            @Override public SurvivalGateway.ItemBlock itemBlock(ActorRef actor,Point point,dev.aivillages.core.kernel.ResourceDelivery.ItemIdentity item) {
                if(!item.equals(dev.aivillages.core.kernel.ResourceDelivery.OAK_LOG))
                    return WorldReferenceDiscovery.Observation.super.itemBlock(actor,point,item);
                var cell=new SurvivalGateway.Cell(point.dimension(),point.x(),point.y(),point.z());
                var pos=new BlockPos(point.x(),point.y(),point.z());
                if(!world.loadedForDiscovery(pos) || !world.discoverable(actor,pos,Effect.HARVEST))
                    return new SurvivalGateway.ItemBlock(ObservationStatus.UNKNOWN,false,cell.identity(),0);
                return world.resourceBlock(cell,item);
            }
            @Override public WorldReferenceDiscovery.ItemContainerSample itemContainer(ActorRef actor,ContainerRef target,
                    dev.aivillages.core.kernel.ResourceDelivery.ItemIdentity item) {
                if(item.equals(dev.aivillages.core.kernel.ResourceDelivery.OAK_PLANKS)) {
                    var pos=new BlockPos(target.x(),target.y(),target.z());
                    if(!world.loadedForDiscovery(pos) || !world.discoverable(actor,pos,Effect.TRANSFER))
                        return new WorldReferenceDiscovery.ItemContainerSample(item,ObservationStatus.UNKNOWN,0,0,"unavailable");
                    var c=world.inspectPlankContainer(target);
                    return new WorldReferenceDiscovery.ItemContainerSample(item,c.status(),c.capacity(),c.slotsInspected(),c.identity());
                }
                if(!item.equals(dev.aivillages.core.kernel.ResourceDelivery.OAK_LOG))
                    return WorldReferenceDiscovery.Observation.super.itemContainer(actor,target,item);
                var pos=new BlockPos(target.x(),target.y(),target.z());
                if(!world.loadedForDiscovery(pos) || !world.discoverable(actor,pos,Effect.TRANSFER))
                    return new WorldReferenceDiscovery.ItemContainerSample(item,ObservationStatus.UNKNOWN,0,0,"unavailable");
                var c=world.inspectOakContainer(target);
                return new WorldReferenceDiscovery.ItemContainerSample(item,c.status(),c.capacity(),c.slotsInspected(),c.identity());
            }
            @Override public WorldReferenceDiscovery.InputContainerSample inputContainer(ActorRef actor,ContainerRef source) {
                var pos=new BlockPos(source.x(),source.y(),source.z());
                if(!world.loadedForDiscovery(pos) || !world.discoverable(actor,pos,Effect.WITHDRAW) || inputAvailability==null)
                    return new WorldReferenceDiscovery.InputContainerSample(ObservationStatus.UNKNOWN,0,0,"unavailable");
                var box=world.recipeContainer(source);
                if(box==null)return new WorldReferenceDiscovery.InputContainerSample(ObservationStatus.ABSENT,0,0,"not-a-container");
                var sample=world.resourceContainer(source,dev.aivillages.core.kernel.ResourceDelivery.OAK_LOG);
                if(sample.status()!=ObservationStatus.PRESENT || sample.reason()!=null)
                    return new WorldReferenceDiscovery.InputContainerSample(ObservationStatus.UNKNOWN,0,box.getContainerSize(),"unknown-stock");
                var free=inputAvailability.apply(source,sample.quantity());
                if(!free.usable())return new WorldReferenceDiscovery.InputContainerSample(ObservationStatus.UNKNOWN,0,box.getContainerSize(),"stock-owner-pending");
                return new WorldReferenceDiscovery.InputContainerSample(ObservationStatus.PRESENT,free.available(),box.getContainerSize(),
                        "input-container:"+pos.asLong()+":"+System.identityHashCode(box)+":"+world.recipeContainerFingerprint(source).substring(0,16));
            }
            @Override public WorldReferenceDiscovery.ContainerSample container(ActorRef actor, ContainerRef target) {
                BlockPos pos = new BlockPos(target.x(),target.y(),target.z());
                if (!world.loadedForDiscovery(pos) || !world.discoverable(actor,pos,Effect.TRANSFER))
                    return new WorldReferenceDiscovery.ContainerSample(ObservationStatus.UNKNOWN,0,0,"unavailable");
                var inspected = world.inspectContainer(target);
                return new WorldReferenceDiscovery.ContainerSample(inspected.status(),inspected.wheatCapacity(),
                        inspected.slotsInspected(),inspected.identity());
            }
        };
        binding = new WorldReferenceBinding(observations,citizens,caller,anchor,intent,clock,
                () -> !player.hasDisconnected() && !player.isRemoved()
                        && player.getUUID().equals(caller.principal().id())
                        && player.level().dimension().identifier().toString().equals(anchor.dimension()));
    }
    @Override public LanguageRequests.Resolution poll() { return binding.poll(); }
    @Override public boolean cancellable() { return binding.cancellable(); }
    @Override public boolean cancel() { return binding.cancel(); }
}
