package dev.aivillages.e2e.paralleld;

import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import dev.aivillages.core.kernel.logistics.RecipientDeliveryPolicy.Source;
import dev.aivillages.core.kernel.logistics.TransferObservation.Status;
import dev.aivillages.core.kernel.storage.*;
import dev.aivillages.core.kernel.storage.contract.StorageCodec;
import dev.aivillages.fabric.storage.StorageIncarnationAdapter;

/** Actual partial native saves in fresh processes. Does not reconstruct an original owner. */
public final class DObservedRestartGameTests {
    static final BlockPos COURIER=new BlockPos(128,81,160),HANDOFF=new BlockPos(140,81,128);
    @GameTest(maxTicks=10000)
    public void observedNativeSavesRemainUnknownWithoutOriginalOwnerSettlement(GameTestHelper h){
        var level=h.getLevel();
        var root=Path.of(System.getProperty("cognitivecraft.parallelD.evidence"));
        var shared=Path.of(System.getProperty("cognitivecraft.parallelD.shared")).resolve("D10-observed");
        for(int x=8;x<=10;x++)for(int z=8;z<=11;z++){
            level.setChunkForced(x,z,true);level.getChunkAt(new BlockPos(x*16,81,z*16));
        }
        if(System.getProperty("cognitivecraft.parallelD.phase").endsWith("read")){
            h.runAfterDelay(40,()->awaitReload(h,root,shared,0));return;
        }
        var p=h.makeMockServerPlayerInLevel();
        h.runAfterDelay(40,()->DChestCourierScene.open(level,COURIER,p,shared.resolve("courier"),"D10-RESTART-COURIER",c->
            DChestCourierScene.open(level,HANDOFF,p,shared.resolve("handoff"),"D10-RESTART-HANDOFF",c,b->{
                // Host GameTests advance faster than disk IO. Witness actual entity tracking;
                // a fixed number of test ticks alone cannot establish chunk entity readiness.
                var warm=new Villager(EntityTypes.VILLAGER,level);
                warm.setPos(HANDOFF.getX()+5.5,HANDOFF.getY(),HANDOFF.getZ()+7.5);
                warm.removeFreeWill();warm.setPersistenceRequired();
                h.assertTrue(level.addFreshEntity(warm),"native preparation entity admitted");
                h.runAfterDelay(1,()->tracked(h,c,b,warm,0,()->write(h,c,b,root,shared)));
            })));
    }
    static void tracked(GameTestHelper h,DChestCourierScene c,DChestCourierScene b,Villager warm,int n,Runnable next){
        var level=h.getLevel();
        if(level.getEntity(warm.getUUID())==warm && level.getEntity(c.arena.actor.getUUID())==c.arena.actor && level.getEntity(b.arena.actor.getUUID())==b.arena.actor){
            warm.discard();next.run();return;
        }
        h.assertTrue(n<8000,"native entity chunk tracking readiness before component admission");
        h.runAfterDelay(1,()->tracked(h,c,b,warm,n+1,next));
    }
    static void write(GameTestHelper h,DChestCourierScene c,DChestCourierScene b,Path root,Path shared){
        var level=h.getLevel();var hand=new DVillagerHandoffScene(b,root,"D10-GT01");
        c.reset("D10",Items.OAK_PLANKS,4,10,3,2);hand.reset("D10",Source.EXISTING_CARRIER,9,false);
        var descriptor=DObservedParticipants.descriptor(c,hand);
        descriptor.add("bindings",DComeScene.JSON.toJsonTree(List.of(c.source.wire(),c.destination.wire(),b.source.wire(),b.destination.wire()).stream().map(StorageCodec::encode).toList()));
        DComeScene.write(shared.resolve("participants.json"),descriptor);DComeScene.write(root.resolve("participants.json"),descriptor);
        level.getServer().saveEverything(false,true,true);
        DObservedParticipants.capture(level,descriptor,"SAVED_BASELINE",root);
        h.assertTrue(c.withdraw().status()==Status.LIVE_WITHDRAWN,"actual native pickup");
        h.assertTrue(hand.prepare().cargo()!=null && hand.handoff().status()==Status.LIVE_DEPOSITED,"actual native handoff");
        h.assertTrue(!c.arena.actor.chunkPosition().equals(hand.receiver.chunkPosition()) && !b.arena.actor.chunkPosition().equals(hand.receiver.chunkPosition()),"fault targets only recipient entity chunk");
        DComponentSaveCut.arm(level,hand.receiver,root);level.getServer().saveEverything(false,true,true);
        h.assertTrue(DComponentSaveCut.withheld()>0,"native entity write boundary witnessed");
        DObservedParticipants.capture(level,descriptor,"LIVE_AFTER_NATIVE_SAVE_CUT",root);
        DComeScene.write(root.resolve("kill-ready.json"),Map.of("expected_disk_courier",List.of(6,7,2),"expected_disk_handoff",List.of(5,1),"receipt",false));
    }
    static void awaitReload(GameTestHelper h,Path root,Path shared,int n){
        var descriptor=DObservedParticipants.load(shared.resolve("participants.json"));boolean loaded=true;
        for(var c:descriptor.getAsJsonObject("participants").entrySet())for(var entry:c.getValue().getAsJsonObject().entrySet()){
            if(!entry.getValue().isJsonObject())continue;var participant=entry.getValue().getAsJsonObject();
            if(participant.has("entity"))loaded &= h.getLevel().getEntity(UUID.fromString(participant.get("entity").getAsString())) instanceof Villager;
            else {var pos=participant.getAsJsonArray("pos");loaded &= h.getLevel().getBlockEntity(new BlockPos(pos.get(0).getAsInt(),pos.get(1).getAsInt(),pos.get(2).getAsInt())) instanceof net.minecraft.world.Container;}
        }
        if(loaded){read(h,root,shared);return;}
        h.assertTrue(n<8000,"actual saved participants loaded before read-only observation");
        h.runAfterDelay(1,()->awaitReload(h,root,shared,n+1));
    }
    static void read(GameTestHelper h,Path root,Path shared){
        var level=h.getLevel();h.assertTrue(!DComponentSaveCut.armed(),"fresh process has no inherited save fault");
        var d=DObservedParticipants.load(shared.resolve("participants.json"));
        DChestCourierScene.when(level,StorageMetadataStore.open(shared.resolve("courier/metadata"),DTestAuthority.acquireTaggedWorld().world()),store->h.runAfterDelay(1,()->{
            var identities=new StorageIncarnationAdapter(level,DTestAuthority.acquireTaggedWorld().world(),(action,w,id,pos)->action==StorageIncarnationAdapter.Action.OBSERVE);
            identities.updateMetadata(store.snapshot());var bindings=new ArrayList<Object>();
            for(var raw:d.getAsJsonArray("bindings")){
                var before=StorageBindingRules.Binding.from(StorageCodec.decode(raw.getAsString()));
                var fresh=identities.resolve(before.storage()).orElseThrow();
                h.assertTrue(before.topology().equals(fresh.topology()) && before.generation()==fresh.generation() && !before.epoch().equals(fresh.epoch()),"actual saved native marker survives with fresh lifecycle epoch");
                bindings.add(Map.of("before",before.wire().body(),"fresh",fresh.wire().body()));
            }
            DComeScene.write(root.resolve("binding-reload.json"),bindings);
            DObservedParticipants.capture(level,d,"FRESH_RESTORED_UNKNOWN",root);
            h.runAfterDelay(100,()->{
                var vectors=DObservedParticipants.capture(level,d,"NO_AUTOMATIC_COMPONENT_REPLAY",root);
                h.assertTrue(vectors.get("courier").equals(List.of(6,7,2)) && vectors.get("handoff").equals(List.of(5,1)),"actual independent restored vectors");
                identities.close();DChestCourierScene.when(level,store.closeAsync(),v->h.runAfterDelay(1,h::succeed));
            });
        }));
    }
}
