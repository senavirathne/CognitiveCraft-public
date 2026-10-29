package dev.aivillages.e2e.paralleld;
import java.nio.file.*;
import java.util.*;
import com.google.gson.JsonObject;
import dev.aivillages.core.kernel.logistics.*;
import dev.aivillages.core.kernel.logistics.RecipientDeliveryPolicy.*;
import dev.aivillages.fabric.logistics.VillagerTransferAdapter;
import dev.aivillages.core.kernel.storage.StorageRegistry.Permission;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import static dev.aivillages.core.kernel.logistics.TransferObservation.*;
final class DComponentHandoff extends DComponentCase {
 final DVillagerHandoffScene hand;Villager donor;
 DComponentHandoff(JsonObject s,DChestCourierScene b,Path e){super(s,b,e);hand=new DVillagerHandoffScene(b,e,id);}
 @Override void prepare(Runnable next){clearWall();hand.reset(id,Source.EXISTING_CARRIER,item,4,10,9,number==2?512:1,number==9,false);var original=hand.session.goal();hand.session=new Session(new Goal(request,original.carrier(),original.recipient(),original.source(),original.sourceCitizen(),original.storageSource(),original.item(),original.quantity()));entities.put("recipient",hand.receiver);if(number==4){hand.renameAndNamesake();hand.label="Receiver";hand.receiver.setCustomName(net.minecraft.network.chat.Component.literal("Receiver"));}
  if(number==10){donor=new Villager(EntityTypes.VILLAGER,level);donor.setUUID(DVillagerHandoffScene.id(id+"-third-party-donor"));donor.setPos(hand.receiver.getX()+1,hand.receiver.getY(),hand.receiver.getZ()+2);donor.setPersistenceRequired();donor.removeFreeWill();DVillagerHandoffScene.fill(donor.getInventory(),item,24);level.addFreshEntity(donor);entities.put("donor",donor);var g=hand.session.goal();hand.session=new Session(new Goal(g.request(),g.carrier(),g.recipient(),g.source(),DVillagerHandoffScene.id(id+"donor-citizen"),null,g.item(),4));}
  sync();next.run();}
 @Override Map<String,Container> containers(){var m=super.containers();if(hand.receiver!=null)m.put("recipient",hand.receiver.getInventory());if(hand.namesake!=null)m.put("namesake",hand.namesake.getInventory());if(donor!=null)m.put("donor",donor.getInventory());return m;}
 void sync(){facts.put("goal",hand.session.goal());facts.put("session_phase",hand.session.phase());facts.put("delivered",hand.session.delivered());facts.put("in_flight",hand.session.inFlight());facts.put("batches",hand.session.batches());facts.put("recipient_projection",hand.projection(level.getGameTime()));facts.put("control",hand.control());facts.put("selected_source",hand.selectedSource);}
 @Override void execute(ServerPlayer p){phase="EXECUTING";if(number==4){var result=hand.resolver.begin(base.scope,new RecipientResolver.Selector(RecipientResolver.Kind.NAMED,"Receiver",null),Permission.DEPOSIT,false).step();operation("FRESH_NAME_RESOLUTION",result);complete(result);return;}
  if(number==12){level.getServer().saveEverything(false,true,true);DComponentSaveCut.arm(level,hand.receiver,evidence);}
  long ns=System.nanoTime();var admitted=hand.prepare();stepNs=System.nanoTime()-ns;sync();operation(admitted.cargo()==null?"REFUSED_PREPARATION":"ADMITTED",admitted.observation());if(admitted.cargo()==null){complete(admitted.observation());return;}if(number>=5 && number<=8){phase="AWAIT_RECIPIENT_INTERFERENCE";operation("AWAIT_RECIPIENT_INTERFERENCE",null);return;}
  if(number==3){hand.renameAndNamesake();entities.put("namesake",hand.namesake);operation("EXTERNAL_RECIPIENT_RENAMED",Map.of("classification","NAME_ONLY_NEW_NAMESAKE_STOCK","external_new_units",17));}
  if(number==11){var second=new Session(new Goal(DVillagerHandoffScene.id(id+"-competing-request"),hand.session.goal().carrier(),hand.session.goal().recipient(),Source.EXISTING_CARRIER,hand.identity.citizen(),null,hand.session.goal().item(),4));var token=hand.adapter.existing(second,hand.bound,new ItemStack(item),hand::selection);operation("COMPETING_ADMITTED",token.observation());handoff(false);var result=hand.adapter.handoff(token.cargo());operation("COMPETING_HANDOFF",result);sync();complete(result);return;}
  handoff(number!=12);if(number==12){phase="AWAIT_NATIVE_SAVE";sync();operation("LIVE_AWAIT_NATIVE_SAVE",hand.last);}
 }
 void handoff(boolean finish){long ns=System.nanoTime();hand.last=hand.handoff();stepNs=System.nanoTime()-ns;sync();operation("HANDOFF",hand.last);if(finish)complete(hand.last);}
 @Override void interfere(ServerPlayer p,String action){if(p.getUUID().equals(base.scope.principal()) || !phase.equals("AWAIT_RECIPIENT_INTERFERENCE"))throw new SecurityException("actual second-player interference");facts.put("interference_principal",p.getUUID());switch(number){case 5->{var old=hand.receiver;old.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);extra.put("retired_recipient",old.getInventory());entities.put("retired_recipient",old);var replacement=new Villager(EntityTypes.VILLAGER,level);replacement.setUUID(DVillagerHandoffScene.id(id+"replacement"));replacement.setPos(old.position());replacement.removeFreeWill();replacement.setPersistenceRequired();level.addFreshEntity(replacement);hand.receiver=replacement;hand.generation++;hand.projectionRevision++;entities.put("recipient",replacement);}case 6->hand.receiver.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);case 7->hand.receiver.kill(level);case 8->{hand.depositAllowed=false;hand.policyRevision++;hand.projectionRevision++;}default->throw new IllegalStateException(id);}sync();operation("EXTERNAL_RECIPIENT_INTERFERENCE",Map.of("classification",number==7?"ACTUAL_NATIVE_DEATH":number==6?"NATIVE_UNLOAD":number==5?"NEW_NATIVE_ENTITY_GENERATION":"POLICY_REVISION_ONLY"));if(number==7){phase="WAIT_NATIVE_DEATH";}else handoff(true);}
 @Override void tick(){if(phase.equals("WAIT_NATIVE_DEATH")){if(hand.receiver.isRemoved() || level.getEntity(hand.receiver.getUUID())==null){sync();operation("NATIVE_RECIPIENT_DISAPPEARED",Map.of("classification","OBSERVED_NATIVE_DEATH_DISAPPEARANCE"));handoff(true);}else operation("NATIVE_DEATH_TICK",Map.of("alive",hand.receiver.isAlive(),"removed",hand.receiver.isRemoved()));}}
 JsonObject participants(){return DComeScene.JSON.toJsonTree(Map.of("participants",Map.of("handoff",Map.of("item",spec.get("item").getAsString(),"sender",DObservedParticipants.entity(base.arena.actor),"recipient",DObservedParticipants.entity(hand.receiver))))).getAsJsonObject();}
}
