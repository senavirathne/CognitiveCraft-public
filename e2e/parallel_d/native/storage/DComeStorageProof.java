package dev.aivillages.e2e.paralleld;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.*;
import dev.aivillages.core.kernel.logistics.*;
import dev.aivillages.core.kernel.logistics.MobilityObservation.*;
import dev.aivillages.core.kernel.storage.*;
import dev.aivillages.core.kernel.storage.contract.*;
import dev.aivillages.fabric.storage.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;

/** Native GO proof within D06-GT01: one authorized binding, rename survives, replacement stops. */
final class DComeStorageProof {
    static void run(GameTestHelper h,DComeScene scene,Runnable done){
        DNativeScene.open(h,"D06-STORAGE",false,s->{BlockPos pos=scene.origin.offset(17,0,7);s.level.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());var grants=List.of(new Grant(new Owner(OwnerKind.PUBLIC,null),Set.of(Permission.DEPOSIT)));
            s.registerShared(pos,StorageBindingRules.BARREL,"supplies",grants,id->s.bind(id,pos,true,b->{
                var scope=new StorageAccessPolicy.Scope(s.world,UUID.fromString("d0600000-0000-4000-8000-000000000005"),scene.identity.principal(),scene.identity.citizen());
                var policy=DStorageAdaptersGameTests.policy(s.level,s.registry,s.adapter,()->true);var reader=new MinecraftStorageAdapter(s.level,s.adapter,s.registry::snapshot,policy,(sc,storage,op,p)->true);
                var resolver=new StorageResolver(s.registry::snapshot,policy,new StorageResolver.NativePort(){
                    public StorageResolver.View view(StorageAccessPolicy.Scope sc,long tick){var p=new StorageResolver.Position(scene.identity.dimension(),Math.round(scene.actor.getX()*1000),Math.round(scene.actor.getY()*1000),Math.round(scene.actor.getZ()*1000));var caller=new StorageResolver.Position(scene.identity.dimension(),Math.round(scene.principal.getX()*1000),Math.round(scene.principal.getY()*1000),Math.round(scene.principal.getZ()*1000));return new StorageResolver.View(sc,b.epoch(),1,tick,caller,p,true);}
                    public StorageResolver.Observation observe(StorageAccessPolicy.Scope sc,StorageRegistry.Record row,Permission op,long tick){var c=reader.check(sc,row.id(),op,new ItemStack(Items.STONE));return c.eligible()?new StorageResolver.Observation(sc,c.binding(),tick,true,true,StorageResolver.Eligibility.ELIGIBLE):null;}
                },new StorageResolver.SelectionPort(){public StorageResolver.Selection consume(StorageAccessPolicy.Scope sc,UUID selected,long tick){return null;}public boolean current(StorageAccessPolicy.Scope sc,StorageResolver.Selection selected,StorageBindingRules.Binding binding,long tick){return false;}},new RecipientResolver(new RecipientResolver.Port(){public RecipientResolver.View view(StorageAccessPolicy.Scope sc,long tick){return null;}public RecipientResolver.Citizen lookup(StorageAccessPolicy.Scope sc,UUID citizen,long tick){return null;}},s.level::getGameTime),s.level::getGameTime);
                scene.deadline=s.level.getGameTime()+600;var bound=resolve(h,resolver,scope,"supplies");scene.route=scene.adapter.storage(scene.identity,scope,bound,Permission.DEPOSIT,reader);scene.frame("ACCEPTED",null,0);
                var snapshot=s.registry.snapshot();DNativeScene.when(h,s.registry.rename(s.caller,snapshot.revision(),id,snapshot.records().get(id).revision(),"renamed"),ack->{s.ack(ack);s.adapter.updateMetadata(s.registry.snapshot());step(h,scene,()->{
                    h.assertTrue(scene.route.observation().status()==Status.ARRIVED,"GO must approach same native barrel after rename");scene.deadline=s.level.getGameTime()+600;scene.route=scene.adapter.storage(scene.identity,scope,resolve(h,resolver,scope,"renamed"),Permission.DEPOSIT,reader);scene.frame("ACCEPTED",null,0);
                    s.level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());s.level.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());var stale=scene.tick();h.assertTrue(stale.blocker()==Blocker.STALE_STORAGE,"replacement must stop without namesake reroute");
                    DComeScene.write(scene.evidence.resolve("storage-controls.json"),Map.of("storage",id.toString(),"accepted_label","supplies","renamed_label",s.registry.snapshot().records().get(id).label(),"operation","DEPOSIT","inspect_denied",reader.observe(scope,id)==StorageSnapshot.Unavailable.INSTANCE,"replacement_blocker",stale.blocker().name(),"binding",b.wire().body()));s.adapter.close();s.store.closeAsync();done.run();
                });});
            }));
        });
    }
    private static StorageResolver.Bound resolve(GameTestHelper h,StorageResolver resolver,StorageAccessPolicy.Scope scope,String label){var selector=StorageCodec.decode(DComeScene.JSON.toJson(new TreeMap<>(Map.of("schema",StorageWire.VERSION,"kind","StorageSelector","body",new TreeMap<>(Map.of("kind","NAMED","label",label,"container_type","BARREL")))))+"\n");var result=resolver.begin(scope,selector,Permission.DEPOSIT).step();h.assertTrue(result.status()==StorageResolution.Status.RESOLVED,"native named-storage resolution: "+result.status());return result.endpoint();}
    private static void step(GameTestHelper h,DComeScene scene,Runnable done){var observed=scene.tick();if(observed.terminal()){done.run();return;}h.runAfterDelay(1,()->step(h,scene,done));}
}
