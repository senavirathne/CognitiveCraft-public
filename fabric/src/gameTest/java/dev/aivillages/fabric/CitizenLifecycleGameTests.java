package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static dev.aivillages.core.kernel.CitizenLifecycle.*;

/** Actual Fabric callbacks and pinned engine removal reasons; no conversion/cure fixtures. */
public final class CitizenLifecycleGameTests {
    private static final Map<UUID, FabricCitizenLifecycle> ADAPTERS = new HashMap<>();
    private static boolean registered;
    static void relay(UUID entity, FabricCitizenLifecycle adapter) {
        if (!registered) {
            registered = true;
            ServerEntityEvents.ENTITY_LOAD.register((e,w) -> { var a=ADAPTERS.get(e.getUUID()); if(a!=null)a.loaded(e); });
            ServerEntityEvents.ENTITY_UNLOAD.register((e,w) -> { var a=ADAPTERS.get(e.getUUID()); if(a!=null)a.unloaded(e); });
            ServerLivingEntityEvents.AFTER_DEATH.register((e,d) -> { var a=ADAPTERS.get(e.getUUID()); if(a!=null)a.died(e); });
        }
        ADAPTERS.put(entity, adapter);
    }
    static CompoundTag unload(Villager entity) {
        var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        if (!entity.save(output)) throw new IllegalStateException("Fixture entity must serialize");
        entity.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        return output.buildResult();
    }
    static Villager reload(GameTestHelper h, CompoundTag saved) {
        var entity=(Villager)EntityType.loadEntityRecursive(EntityTypes.VILLAGER, saved, h.getLevel(),
                EntitySpawnReason.LOAD, EntityProcessor.NOP);
        if (entity==null || !h.getLevel().addFreshEntity(entity)) throw new IllegalStateException("Exact saved fixture reload");
        return entity;
    }
    @GameTest(maxTicks=200) public void chunkUnloadReloadUsesActualFabricEvents(GameTestHelper h) { run(h, Entity.RemovalReason.UNLOADED_TO_CHUNK); }
    @GameTest(maxTicks=200) public void discardedEntityIsConfirmedRemoval(GameTestHelper h) { run(h, Entity.RemovalReason.DISCARDED); }
    @GameTest(maxTicks=200) public void killedEntityIsConfirmedDeath(GameTestHelper h) { run(h, Entity.RemovalReason.KILLED); }
    @GameTest(maxTicks=200) public void dimensionRemovalRemainsUnresolved(GameTestHelper h) { run(h, Entity.RemovalReason.CHANGED_DIMENSION); }
    @GameTest(maxTicks=200) public void ambiguousCallbackNeverInfersDeath(GameTestHelper h) { run(h, null); }
    @GameTest(maxTicks=200) public void delayedChunkRemovalReasonIsRevisitedWithoutAnotherTrackingCallback(GameTestHelper h) {
        run(h, Entity.RemovalReason.UNLOADED_TO_CHUNK, true);
    }
    private void run(GameTestHelper h, Entity.RemovalReason reason) {
        run(h, reason, false);
    }
    private void run(GameTestHelper h, Entity.RemovalReason reason, boolean delayedReason) {
        h.setBlock(1,0,1,Blocks.STONE);
        Villager original=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,1,1,1); original.setPersistenceRequired();
        UUID world=UUID.randomUUID(), principal=UUID.randomUUID();
        var owner=new TrustedContext(new PrincipalRef(principal),new ScopeRef(world,principal));
        var actor=new ActorRef(UUID.randomUUID(),original.getUUID(),h.getLevel().dimension().identifier().toString());
        var registry=new CitizenRegistry(new CitizenRegistry.Snapshot(world,1,List.of(new CitizenRegistry.Citizen(
                actor,owner,"Ada",CitizenRegistry.Availability.UNKNOWN)),null),
                (a,b)->CompletableFuture.completedFuture(b),CitizenRegistry.privateAddresses(),Clock.systemUTC(),false);
        var coordinator=new CitizenLifecycle(registry,new CitizenLifecycle.Owners() {
            public boolean stop(CitizenRegistry.Citizen c,Marker m){return true;}
            public boolean jobs(CitizenRegistry.Citizen c,Marker m){return true;}
            public boolean leases(CitizenRegistry.Citizen c,Marker m){return true;}
        },Settings.production(),h.getLevel().getGameTime());
        var adapter=new FabricCitizenLifecycle(h.getLevel().getServer(),registry,coordinator);
        relay(original.getUUID(),adapter);
        int[] phase={0}; CompoundTag[] saved={null}; Villager[] current={original}; long[] generation={0};
        h.succeedWhen(()->{
            registry.tick(); adapter.step();
            h.assertTrue(adapter.lastLookups()<=1,"Lifecycle forced or exceeded loaded lookups");
            var marker=coordinator.marker(actor.citizenId());
            if(phase[0]==0) {
                h.assertTrue(registry.available(actor),"Waiting for verified live binding");
                h.assertTrue(marker.source()==Source.LOADED_LOOKUP,"A bounded lookup was mislabeled as a native callback");
                generation[0]=marker.generation(); phase[0]=1;
                if(reason==Entity.RemovalReason.UNLOADED_TO_CHUNK) {
                    if(delayedReason) {
                        // Match vanilla's stopTracking -> save -> setRemoved ordering.
                        // No second ENTITY_UNLOAD callback reaches this adapter.
                        adapter.unloaded(original);
                        h.assertTrue(coordinator.marker(actor.citizenId()).event()==Event.MISSING,
                                "An empty native reason must remain ambiguous");
                        ADAPTERS.remove(original.getUUID());
                    }
                    saved[0]=unload(original);
                    if(delayedReason) relay(original.getUUID(),adapter);
                }
                else if(reason==null)adapter.unloaded(original); // Null reason is explicitly ambiguous, not a fabricated death.
                else original.remove(reason);
                h.assertTrue(!registry.available(actor),"Real callback failed to fence synchronously");
            }
            if(phase[0]==1) {
                Event expected=reason==null?Event.MISSING:reason==Entity.RemovalReason.UNLOADED_TO_CHUNK?Event.UNLOAD:
                        reason==Entity.RemovalReason.KILLED?Event.DEATH:reason==Entity.RemovalReason.DISCARDED?Event.REMOVE:Event.DIMENSION;
                if(reason==null) {
                    // The still-loaded same instance may be reverified; absence never becomes death.
                    h.assertTrue(!marker.terminal(),"Unknown removal inferred terminal evidence");
                    ADAPTERS.remove(original.getUUID()); h.succeed(); return;
                }
                h.assertTrue(marker.event()==expected&&!marker.pending(),"Waiting for actual removal and owner checkpoints: "+marker);
                if(expected==Event.UNLOAD) { current[0]=reload(h,saved[0]); phase[0]=2; }
                else {
                    adapter.loaded(original); registry.observeAvailability(actor.citizenId(),CitizenRegistry.Availability.LOADED);
                    h.assertTrue(!registry.available(actor),"Terminal/unresolved binding revived");
                    ADAPTERS.remove(original.getUUID()); h.succeed(); return;
                }
            }
            if(phase[0]==2) {
                h.assertTrue(registry.available(actor),"Waiting for reloaded native entity");
                h.assertTrue(coordinator.marker(actor.citizenId()).generation()==generation[0]+1,"Generation not fenced");
                adapter.unloaded(original); // An obsolete actual object cannot affect the new generation.
                h.assertTrue(registry.available(actor),"Stale native callback stopped current generation");
                h.assertTrue("Ada".equals(registry.query(actor.citizenId(),owner).displayName())
                        &&current[0].getUUID().equals(actor.entityId()),"Saved entity or identity changed");
                ADAPTERS.remove(original.getUUID()); h.succeed(); return;
            }
            h.fail("Waiting for lifecycle event phase="+phase[0]);
        });
    }
}
