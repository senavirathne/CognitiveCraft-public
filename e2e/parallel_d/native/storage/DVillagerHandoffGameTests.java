package dev.aivillages.e2e.paralleld;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import dev.aivillages.core.kernel.logistics.*;
import dev.aivillages.core.kernel.logistics.RecipientDeliveryPolicy.*;
import dev.aivillages.fabric.logistics.VillagerTransferAdapter;
import static dev.aivillages.core.kernel.logistics.TransferObservation.*;

public final class DVillagerHandoffGameTests {
    static final List<String> CAMPAIGNS=List.of("EXISTING_SUCCESS","RENAMED_NAMESAKE","FULL_RECIPIENT","OUT_OF_REACH","NATIVE_REACH_LIMIT","MOVED_RECIPIENT","REVOKED_DEPOSIT","DEAD_RECIPIENT","UNLOADED_RECIPIENT","RIVAL_COURIER","CONTROL_LOSS","EFFECT_BOUNDARY_INTERFERENCE","UNAUTHORIZED_OLD_STOCK","UNSUPPORTED_INVENTORY","CUSTOM_COMPONENTS","STALE_GENERATION","EXISTING_Q1","EXISTING_Q64","STORAGE_SUCCESS","STORAGE_BATCH64","STORAGE_REVOKED","STORAGE_TARGET_MOVED","OFF_THREAD","NATIVE_PROTECTION","EXPIRED_SELECTION","FOREIGN_SELECTION","SWAPPED_SLOT");
    @GameTest(maxTicks=12000,structure="cognitivecraft_parallel_d_tests:mobility")
    public void atomicLiveHandoffPreservesStableRecipientAndRefusesUnsafeEffects(GameTestHelper h){var principal=h.makeMockServerPlayerInLevel();DVillagerHandoffScene.open(h.getLevel(),h.absolutePos(new BlockPos(0,2,0)),principal,Path.of(System.getProperty("cognitivecraft.parallelD.evidence")),"D09-GT01",s->h.runAfterDelay(10,()->campaign(h,s,0)));}
    static void campaign(GameTestHelper h,DVillagerHandoffScene s,int index){
        if(index==CAMPAIGNS.size()){s.base.identities.close();DChestCourierScene.when(s.level,s.base.store.closeAsync(),v->h.runAfterDelay(1,h::succeed));return;}
        String name=CAMPAIGNS.get(index);Runnable next=()->h.runAfterDelay(1,()->campaign(h,s,index+1));
        boolean storage=name.startsWith("STORAGE_");int q=name.equals("EXISTING_Q1")?1:name.equals("EXISTING_Q64") || name.equals("STORAGE_BATCH64")?64:4;
        s.reset(name,storage?Source.STORAGE:Source.EXISTING_CARRIER,name.equals("STORAGE_BATCH64")?Items.ENDER_PEARL:Items.OAK_PLANKS,q,name.equals("STORAGE_BATCH64")?64:10,storage?3:q==64?64:9,name.equals("FULL_RECIPIENT")?512:1,name.equals("OUT_OF_REACH") || name.equals("STORAGE_SUCCESS") || name.equals("STORAGE_REVOKED") || name.equals("STORAGE_TARGET_MOVED"),name.equals("UNSUPPORTED_INVENTORY"));
        if(name.equals("UNAUTHORIZED_OLD_STOCK"))s.sourceAuthorized=false;
        if(name.equals("NATIVE_PROTECTION"))s.protection=false;
        if(name.equals("CUSTOM_COMPONENTS")){s.base.arena.actor.getInventory().getItem(0).set(DataComponents.CUSTOM_NAME,Component.literal("non-default cargo"));s.frame("EXTERNAL_COMPONENT_CHANGED",null);}
        if(name.equals("EXPIRED_SELECTION") || name.equals("FOREIGN_SELECTION")){
            var before=images(s);var result=s.adapter.existing(s.session,s.bound,new ItemStack(s.item),(goal,tick)->{var selected=s.selection(goal,name.equals("EXPIRED_SELECTION")?tick-41:tick);var actual=name.equals("FOREIGN_SELECTION")?new VillagerTransferAdapter.Selection(UUID.randomUUID(),selected.request(),selected.sender(),selected.observedTick(),selected.slots()):selected;s.recordSelection(actual);return actual;});s.frame("REFUSED_PREPARATION",result.observation());h.assertTrue(result.cargo()==null && result.observation().reason()==Reason.SELECTION_REQUIRED && before.equals(images(s)),"untrusted or expired source slot authorization leaves native units intact");next.run();return;
        }
        if(name.equals("STORAGE_BATCH64")){batch(h,s,next);return;}
        var before=images(s);var prepared=s.prepare();
        if(List.of("FULL_RECIPIENT","UNAUTHORIZED_OLD_STOCK","UNSUPPORTED_INVENTORY","CUSTOM_COMPONENTS","NATIVE_PROTECTION").contains(name)){
            Reason expected=name.equals("FULL_RECIPIENT")?Reason.CAPACITY:name.equals("UNSUPPORTED_INVENTORY")?Reason.UNSUPPORTED_PRIMITIVE:name.equals("CUSTOM_COMPONENTS")?Reason.UNSUPPORTED_ITEM:Reason.UNAUTHORIZED;
            h.assertTrue(prepared.cargo()==null && prepared.observation().status()==Status.REFUSED && prepared.observation().reason()==expected && before.equals(images(s)),"unsafe preparation has zero physical effect "+name);redacted(h,prepared.observation());next.run();return;
        }
        h.assertTrue(prepared.cargo()!=null,"native cargo admission "+name+" "+prepared.observation());
        switch(name){
            case "EXISTING_SUCCESS","EXISTING_Q1","EXISTING_Q64"->{success(h,s,q==64?0:9-q,1+q);next.run();}
            case "RENAMED_NAMESAKE"->{s.renameAndNamesake();success(h,s,5,5);h.assertTrue(s.namesake.getInventory().getItem(0).getCount()==17,"renamed target never reroutes to namesake");next.run();}
            case "OUT_OF_REACH"->{refuse(h,s,Reason.OUT_OF_REACH);next.run();}
            case "NATIVE_REACH_LIMIT"->{s.interactionLimit=1.5;s.frame("EXTERNAL_NATIVE_REACH",null);refuse(h,s,Reason.OUT_OF_REACH);next.run();}
            case "MOVED_RECIPIENT"->{ready(h,s,0,()->{s.moveRecipient();h.assertTrue(s.recipientMoveAccepted,"actual native recipient route admitted");walk(h,s,0,()->{refuse(h,s,Reason.OUT_OF_REACH);next.run();});});}
            case "REVOKED_DEPOSIT"->{s.depositAllowed=false;s.policyRevision++;s.projectionRevision++;s.frame("EXTERNAL_DEPOSIT_REVOKED",null);refuse(h,s,Reason.STALE_ENDPOINT);next.run();}
            case "DEAD_RECIPIENT"->{s.receiver.kill(s.level);s.frame("EXTERNAL_RECIPIENT_DEAD",null);refuse(h,s,Reason.STALE_ENDPOINT);next.run();}
            case "UNLOADED_RECIPIENT"->{s.receiver.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);s.frame("EXTERNAL_RECIPIENT_UNLOADED",null);refuse(h,s,Reason.STALE_ENDPOINT);next.run();}
            case "RIVAL_COURIER"->{s.exclusiveRecipient=false;s.frame("EXTERNAL_RIVAL_CUSTODY",null);refuse(h,s,Reason.UNAUTHORIZED);next.run();}
            case "CONTROL_LOSS"->{s.controlled=false;s.controlRevision++;s.frame("EXTERNAL_CONTROL_LOST",null);refuse(h,s,Reason.UNAUTHORIZED);next.run();}
            case "STALE_GENERATION"->{s.generation++;s.projectionRevision++;s.frame("EXTERNAL_RECIPIENT_GENERATION",null);refuse(h,s,Reason.STALE_ENDPOINT);next.run();}
            case "EFFECT_BOUNDARY_INTERFERENCE"->{s.calls=0;s.boundary=()->{s.base.arena.actor.getInventory().getItem(0).shrink(1);s.frame("EXTERNAL_FINAL_SLOT_MUTATION",null);};var result=s.handoff();h.assertTrue(result.reason()==Reason.INTERFERENCE && s.base.arena.actor.getInventory().getItem(0).getCount()==8 && s.receiver.getInventory().getItem(0).getCount()==1 && s.session.delivered()==0,"native slot race cannot discharge unproved cargo");redacted(h,result);next.run();}
            case "SWAPPED_SLOT"->{ItemStack moved=s.base.arena.actor.getInventory().getItem(0);s.base.arena.actor.getInventory().getItems().set(0,ItemStack.EMPTY);s.base.arena.actor.getInventory().getItems().set(1,moved);s.frame("EXTERNAL_MOVED_CARGO_SLOT",null);refuse(h,s,Reason.INTERFERENCE);next.run();}
            case "OFF_THREAD"->{boolean blocked=CompletableFuture.supplyAsync(()->{try{s.adapter.handoff(s.cargo);return false;}catch(IllegalStateException expected){return true;}}).join();h.assertTrue(blocked,"native world handoff refuses foreign thread before cargo use");s.frame("OFF_THREAD_REFUSED",Map.of("exception","IllegalStateException"));success(h,s,5,5);next.run();}
            case "STORAGE_SUCCESS","STORAGE_REVOKED"->{s.travel();move(h,s,()->{if(name.equals("STORAGE_REVOKED")){s.depositAllowed=false;s.policyRevision++;s.frame("EXTERNAL_DEPOSIT_REVOKED",null);refuse(h,s,Reason.STALE_ENDPOINT);h.assertTrue(s.base.arena.actor.getInventory().getItem(0).getCount()==3 && s.base.arena.actor.getInventory().getItem(1).getCount()==4,"storage cargo retains old3 plus newly attributed4");}else success(h,s,3,5);next.run();});}
            case "STORAGE_TARGET_MOVED"->{ready(h,s,0,()->{s.travel();s.moveRecipient();h.assertTrue(s.recipientMoveAccepted,"actual changed target native route");movingTarget(h,s,0,next);});}
            default->throw new IllegalStateException("unregistered native handoff campaign "+name);
        }
    }
    static void batch(GameTestHelper h,DVillagerHandoffScene s,Runnable next){var p=s.prepare();h.assertTrue(p.cargo()!=null && p.cargo().quantity()==16,"bounded native storage batch16");var result=s.handoff();h.assertTrue(result.status()==Status.LIVE_DEPOSITED && s.base.arena.actor.getInventory().getItem(0).getCount()==3,"each native batch preserves old carrier stock");if(s.session.remaining()>0)h.runAfterDelay(1,()->batch(h,s,next));else{h.assertTrue(s.session.delivered()==64 && s.session.batches()==4,"four native batches one original request");next.run();}}
    static void move(GameTestHelper h,DVillagerHandoffScene s,Runnable next){var observation=s.tick();if(observation.terminal()){h.assertTrue(observation.status()==MobilityObservation.Status.ARRIVED,"guarded recipient native route "+observation);next.run();}else h.runAfterDelay(1,()->move(h,s,next));}
    static void ready(GameTestHelper h,DVillagerHandoffScene s,int ticks,Runnable next){if(s.receiver.onGround() && s.receiver.tickCount>=2){next.run();return;}h.assertTrue(ticks<40,"actual native recipient readiness");h.runAfterDelay(1,()->ready(h,s,ticks+1,next));}
    static void walk(GameTestHelper h,DVillagerHandoffScene s,int count,Runnable next){s.frame("RECIPIENT_MOTION",null);if(s.base.arena.actor.distanceTo(s.receiver)>2.5){s.receiver.getNavigation().stop();next.run();return;}h.assertTrue(count<100,"actual recipient native movement crosses handoff radius");h.runAfterDelay(1,()->walk(h,s,count+1,next));}
    static void movingTarget(GameTestHelper h,DVillagerHandoffScene s,int count,Runnable next){var o=s.tick();if(o.terminal()){h.assertTrue(o.status()==MobilityObservation.Status.BLOCKED && o.blocker()==MobilityObservation.Blocker.STALE_RECIPIENT,"moving bound recipient stops captured route");refuse(h,s,Reason.OUT_OF_REACH);h.assertTrue(s.base.arena.actor.getInventory().getItem(1).getCount()==4,"moved recipient retains actual storage cargo");next.run();}else{h.assertTrue(count<100,"finite changed-recipient guard");h.runAfterDelay(1,()->movingTarget(h,s,count+1,next));}}
    static List<Object> images(DVillagerHandoffScene s){return List.of(s.base.stacks(s.base.source()),s.base.stacks(s.base.arena.actor.getInventory()),s.base.stacks(s.receiver.getInventory()),s.items());}
    static void success(GameTestHelper h,DVillagerHandoffScene s,int sender,int recipient){var items=s.items();var result=s.handoff();h.assertTrue(result.status()==Status.LIVE_DEPOSITED && result.reason()==Reason.NONE && s.session.delivered()==s.session.goal().quantity() && result.after().carrier()==sender && result.after().destination()==recipient && items.equals(s.items()),"exact live handoff without an item entity");var before=images(s);var replay=s.adapter.handoff(s.cargo);s.frame("REPLAY_REFUSED",replay);h.assertTrue(replay.reason()==Reason.UNKNOWN_CUSTODY && before.equals(images(s)),"one-use native cargo cannot replay");}
    static void refuse(GameTestHelper h,DVillagerHandoffScene s,Reason reason){var before=images(s);var result=s.handoff();h.assertTrue(result.status()==Status.REFUSED && result.reason()==reason && s.session.delivered()==0 && before.equals(images(s)),"native handoff refusal preserves exact units "+s.campaign+" "+result);redacted(h,result);var replay=s.adapter.handoff(s.cargo);s.frame("REPLAY_REFUSED",replay);h.assertTrue(replay.reason()==Reason.UNKNOWN_CUSTODY && before.equals(images(s)),"refused cargo cannot retry without owner settlement");}
    static void redacted(GameTestHelper h,TransferObservation o){h.assertTrue(o.quantity()==0 && o.before()==null && o.after()==null && o.location().equals("handoff unavailable; owner revalidation required"),"refused handoff reveals no private endpoint/stock metadata");}
}
