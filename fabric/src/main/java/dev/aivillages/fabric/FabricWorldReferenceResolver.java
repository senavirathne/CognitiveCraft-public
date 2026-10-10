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
