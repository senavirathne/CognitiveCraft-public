package dev.aivillages.e2e.paralleld;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import dev.aivillages.core.kernel.logistics.*;
import dev.aivillages.core.kernel.logistics.MobilityPolicy.Actor;
import dev.aivillages.core.kernel.logistics.RecipientDeliveryPolicy.*;
import dev.aivillages.core.kernel.storage.*;
import dev.aivillages.fabric.logistics.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.chat.Component;
import static dev.aivillages.core.kernel.logistics.TransferObservation.*;

/** Explicit test-only enrollment/custody port. Shares one native storage owner per isolated level. */
final class DVillagerHandoffScene {
    final DChestCourierScene base;final ServerLevel level;final Path evidence;final String caseId;final UUID citizen,epoch=UUID.randomUUID();
    Villager receiver,namesake;long generation=0,policyRevision=1,projectionRevision=0,custodyRevision=0,controlRevision=1;
    boolean controlled=true,exclusiveSender=true,exclusiveRecipient=true,sourceAuthorized=true,protection=true,depositAllowed=true,visible=true;
    boolean recipientMoveAccepted=false;double interactionLimit=2.5;String label="Receiver",campaign="";int sequence=0,calls=0;Item item=Items.OAK_PLANKS;
    Actor identity;RecipientResolver resolver;RecipientResolver.Bound bound;Session session;VillagerTransferAdapter adapter;VillagerTransferAdapter.Cargo cargo;
    MinecraftNavigationAdapter navigation;MinecraftNavigationAdapter.Route route;Runnable boundary;TransferObservation last;Map<String,Object> selectedSource;long nanos=0;
    private DVillagerHandoffScene(DChestCourierScene base,Path evidence,String caseId){this.base=base;level=base.level;this.evidence=evidence;this.caseId=caseId;citizen=id(caseId+base.sourcePos+"recipient");}
    static UUID id(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));}
    static void open(ServerLevel level,BlockPos origin,ServerPlayer principal,Path evidence,String caseId,DVillagerHandoffScene previous,Consumer<DVillagerHandoffScene> next){DChestCourierScene.open(level,origin,principal,evidence,caseId,previous==null?null:previous.base,origin.getX()+"_"+origin.getZ(),b->next.accept(new DVillagerHandoffScene(b,evidence,caseId)));}
    static void open(ServerLevel level,BlockPos origin,ServerPlayer principal,Path evidence,String caseId,Consumer<DVillagerHandoffScene> next){open(level,origin,principal,evidence,caseId,null,next);}
    void reset(String name,Source source,Item item,int quantity,int sourceCount,int senderCount,int recipientCount,boolean far,boolean unsafe){
        if(route!=null && !route.observation().terminal())route.cancel();base.arena.actor.getNavigation().stop();base.arena.actor.setPos(base.sourcePos.getX()+1.5,base.sourcePos.getY(),base.sourcePos.getZ()+.5);
        if(receiver!=null && !receiver.isRemoved())receiver.discard();if(namesake!=null && !namesake.isRemoved())namesake.discard();namesake=null;
        campaign=name;this.item=item;sequence++;generation++;projectionRevision++;policyRevision++;custodyRevision++;controlled=exclusiveSender=exclusiveRecipient=sourceAuthorized=protection=depositAllowed=visible=true;interactionLimit=2.5;label="Receiver";calls=0;boundary=null;route=null;cargo=null;last=null;selectedSource=null;recipientMoveAccepted=false;
        receiver=unsafe?new UnsupportedVillager(level):new Villager(EntityTypes.VILLAGER,level);receiver.setUUID(id(caseId+base.sourcePos+"receiver-"+sequence));receiver.setPos(base.sourcePos.getX()+(far?14.5:3.5),base.sourcePos.getY(),base.sourcePos.getZ()+.5);receiver.setPersistenceRequired();receiver.removeFreeWill();receiver.setCustomName(Component.literal(label));level.addFreshEntity(receiver);
        fill(base.source(),item,sourceCount);fill(base.arena.actor.getInventory(),item,senderCount);fill(receiver.getInventory(),item,recipientCount);
        identity=new Actor(base.arena.identity.world(),base.scope.principal(),base.arena.identity.citizen(),base.arena.actor.getUUID(),1,controlRevision,base.arena.identity.dimension());
        resolver=new RecipientResolver(new RecipientResolver.Port(){
            public RecipientResolver.View view(StorageAccessPolicy.Scope scope,long tick){List<RecipientResolver.Citizen> rows=new ArrayList<>();rows.add(projection(tick));if(namesake!=null)rows.add(new RecipientResolver.Citizen(scope,epoch,id(caseId+"namesake"),namesake.getUUID(),1,1,"Receiver",identity.dimension(),revision(namesake.getInventory()),tick,true,false,true,Set.of(StorageRegistry.Permission.DEPOSIT)));return new RecipientResolver.View(scope,epoch,projectionRevision,tick,rows,true);}
            public RecipientResolver.Citizen lookup(StorageAccessPolicy.Scope scope,UUID id,long tick){return citizen.equals(id)?projection(tick):null;}
        },level::getGameTime);
        var resolved=resolver.begin(base.scope,new RecipientResolver.Selector(RecipientResolver.Kind.NAMED,label,null),StorageRegistry.Permission.DEPOSIT,false).step();if(resolved.status()!=RecipientResolver.Status.RESOLVED)throw new IllegalStateException("explicit enrolled native fixture recipient");bound=resolved.endpoint();
        var goal=new Goal(id(caseId+"-"+sequence),identity,Target.of(bound.fresh(base.scope)),source,source==Source.EXISTING_CARRIER?identity.citizen():null,source==Source.STORAGE?base.endpoint(base.source):null,base.reader.describeItem(new ItemStack(item)),quantity);session=new Session(goal);
        adapter=new VillagerTransferAdapter(level,base.arena.actor,identity,base.scope,base.reader,(g,order)->{if(!order.equals(order.stream().sorted().toList()) || order.size()!=(g.source()==Source.STORAGE?3:2))throw new IllegalStateException("canonical native handoff resources");if(++calls==2 && boundary!=null){Runnable action=boundary;boundary=null;action.run();}return control();});
        long deadline=level.getGameTime()+600;navigation=new MinecraftNavigationAdapter(level,base.arena.actor,(actor,tick)->new MinecraftNavigationAdapter.Control(identity,controlled,MobilityPolicy.Limits.maximum(),deadline));frame("BEFORE",null);
    }
    void reset(String name,Source source,int senderCount,boolean far){reset(name,source,Items.OAK_PLANKS,4,10,senderCount,1,far,false);}
    RecipientResolver.Citizen projection(long tick){return new RecipientResolver.Citizen(base.scope,epoch,citizen,receiver.getUUID(),generation,policyRevision,label,identity.dimension(),loaded(receiver)?revision(receiver.getInventory()):"0".repeat(64),tick,visible,false,loaded(receiver),depositAllowed?Set.of(StorageRegistry.Permission.DEPOSIT):Set.of());}
    VillagerTransferAdapter.Control control(){return new VillagerTransferAdapter.Control(identity,controlled,exclusiveSender,exclusiveRecipient,sourceAuthorized,protection,custodyRevision,interactionLimit);}
    VillagerTransferAdapter.Selection selection(Goal goal,long tick){List<VillagerTransferAdapter.SelectedSlot> slots=new ArrayList<>();int left=session.remaining();for(int i=0;i<8 && left>0;i++){ItemStack stack=base.arena.actor.getInventory().getItem(i);if(stack.is(item)){int amount=Math.min(left,stack.getCount());slots.add(new VillagerTransferAdapter.SelectedSlot(i,stack,amount));left-=amount;}}var selected=new VillagerTransferAdapter.Selection(base.scope.principal(),goal.request(),identity.citizen(),tick,slots);recordSelection(selected);return selected;}
    void recordSelection(VillagerTransferAdapter.Selection selected){List<Map<String,Object>> rows=new ArrayList<>();for(var slot:selected.slots())rows.add(Map.of("slot",slot.slot(),"quantity",slot.quantity(),"native_count",slot.nativeStack().getCount(),"native_object",System.identityHashCode(slot.nativeStack()),"item",base.reader.describeItem(slot.nativeStack())));selectedSource=Map.of("principal",selected.principal(),"request",selected.request(),"sender",selected.sender(),"observed_tick",selected.observedTick(),"slots",rows);}
    List<Map<String,Object>> nativeSlots(SimpleContainer inventory){var rows=base.stacks(inventory);for(var row:rows){int slot=((Number)row.get("slot")).intValue();row.put("native_object",System.identityHashCode(inventory.getItem(slot)));}return rows;}
    VillagerTransferAdapter.Preparation prepare(){long start=System.nanoTime();var prepared=session.goal().source()==Source.EXISTING_CARRIER?adapter.existing(session,bound,new ItemStack(item),this::selection):adapter.withdraw(session,base.source,bound,new ItemStack(item));nanos=System.nanoTime()-start;cargo=prepared.cargo();last=prepared.observation();frame(cargo==null?"REFUSED_PREPARATION":last==null?"ADMITTED":"WITHDRAWN",last);return prepared;}
    TransferObservation handoff(){frame("BEFORE_HANDOFF",null);long start=System.nanoTime();last=adapter.handoff(cargo);nanos=System.nanoTime()-start;frame("HANDOFF",last);return last;}
    void travel(){route=navigation.recipient(identity,base.scope,bound);frame("TRAVEL_ACCEPTED",null);}
    MobilityObservation tick(){long start=System.nanoTime();MobilityObservation o=route.step();nanos=System.nanoTime()-start;frame("MOVE",o);return o;}
    void renameAndNamesake(){label="Renamed receiver";receiver.setCustomName(Component.literal(label));projectionRevision++;namesake=new Villager(EntityTypes.VILLAGER,level);namesake.setUUID(id(caseId+base.sourcePos+"namesake"));namesake.setPos(receiver.getX()+2,receiver.getY(),receiver.getZ()+2);namesake.setPersistenceRequired();namesake.removeFreeWill();namesake.setCustomName(Component.literal("Receiver"));fill(namesake.getInventory(),item,17);level.addFreshEntity(namesake);frame("RENAMED_AND_NAMESAKE",null);}
    void moveRecipient(){recipientMoveAccepted=receiver.getNavigation().moveTo(receiver.getX()+6,receiver.getY(),receiver.getZ(),.3);frame("RECIPIENT_MOTION_STARTED",null);}
    boolean loaded(Villager v){return v.isAlive() && !v.isRemoved() && level.getEntity(v.getUUID())==v && level.hasChunkAt(v.blockPosition());}
    static void fill(SimpleContainer c,Item item,int count){for(int i=0;i<8;i++)c.getItems().set(i,ItemStack.EMPTY);for(int i=0;i<8 && count>0;i++){int amount=Math.min(count,new ItemStack(item).getMaxStackSize());c.getItems().set(i,new ItemStack(item,amount));count-=amount;}if(count!=0)throw new IllegalArgumentException("native fixture inventory bound");}
    static void fill(net.minecraft.world.Container c,Item item,int count){if(c instanceof SimpleContainer simple)fill(simple,item,count);else DChestCourierScene.fill(c,item,count);}
    String revision(SimpleContainer c){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(DComeScene.JSON.toJson(base.stacks(c)).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new RuntimeException(e);}}
    List<Map<String,Object>> items(){List<Map<String,Object>> rows=new ArrayList<>();var box=new AABB(base.sourcePos.getX()-6,base.sourcePos.getY()-3,base.sourcePos.getZ()-8,base.sourcePos.getX()+24,base.sourcePos.getY()+6,base.sourcePos.getZ()+24);for(ItemEntity entity:level.getEntitiesOfClass(ItemEntity.class,box))rows.add(Map.of("entity",entity.getUUID(),"count",entity.getItem().getCount(),"item",base.reader.describeItem(entity.getItem())));rows.sort(Comparator.comparing(x->x.get("entity").toString()));return rows;}
    void frame(String event,Object detail){Map<String,Object> r=new TreeMap<>();r.put("case",caseId);r.put("campaign",campaign);r.put("event",event);r.put("tick",level.getGameTime());r.put("seed",level.getSeed());r.put("world",identity.world());r.put("principal",base.scope.principal());r.put("sender",nativeSlots(base.arena.actor.getInventory()));r.put("recipient",nativeSlots(receiver.getInventory()));r.put("selected_source",selectedSource);r.put("source_native",base.nativeBlock(base.sourcePos));r.put("source",base.stacks(base.source()));r.put("source_binding",base.source.wire().body());r.put("sender_pos",List.of(base.arena.actor.getX(),base.arena.actor.getY(),base.arena.actor.getZ()));r.put("recipient_pos",List.of(receiver.getX(),receiver.getY(),receiver.getZ()));r.put("sender_loaded",loaded(base.arena.actor));r.put("sender_navigation_done",base.arena.actor.getNavigation().isDone());r.put("recipient_loaded",loaded(receiver));r.put("recipient_alive",receiver.isAlive());r.put("recipient_on_ground",receiver.onGround());r.put("recipient_entity_ticks",receiver.tickCount);r.put("recipient_navigation_done",receiver.getNavigation().isDone());r.put("recipient_move_accepted",recipientMoveAccepted);r.put("recipient_removed",receiver.isRemoved());r.put("recipient_entity",receiver.getUUID());r.put("recipient_label",receiver.getCustomName()==null?null:receiver.getCustomName().getString());r.put("recipient_projection",projection(level.getGameTime()));r.put("native_sender_class",base.arena.actor.getClass().getName());r.put("native_recipient_class",receiver.getClass().getName());r.put("sender_inventory_class",base.arena.actor.getInventory().getClass().getName());r.put("recipient_inventory_class",receiver.getInventory().getClass().getName());r.put("item_entities",items());r.put("control",control());r.put("goal",session.goal());r.put("phase",session.phase());r.put("delivered",session.delivered());r.put("in_flight",session.inFlight());r.put("batches",session.batches());r.put("detail",detail);r.put("operation_ns",nanos);r.put("namesake",namesake==null?null:Map.of("entity",namesake.getUUID(),"slots",base.stacks(namesake.getInventory())));r.put("route",route==null?null:route.goal());r.put("path_nodes",route==null?0:route.pathNodes());r.put("path_length",route==null?0:route.pathLength());r.put("checked_chunks",route==null?0:route.checkedChunks());r.put("forced_chunks",level.getChunkSource().getForceLoadedChunks().size());r.put("heap_used_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());r.put("integration_root",null);r.put("save_proof",null);DComeScene.writeLine(evidence.resolve("handoff.jsonl"),r);System.out.println("CC_D09_FRAME "+DComeScene.JSON.toJson(r));}
    static final class UnsupportedVillager extends Villager {
        private final SimpleContainer unsupported=new SimpleContainer(8){@Override public void setItem(int slot,ItemStack stack){throw new IllegalStateException("unsupported callback inventory");}};
        UnsupportedVillager(ServerLevel level){super(EntityTypes.VILLAGER,level);}
        @Override public SimpleContainer getInventory(){return unsupported==null?super.getInventory():unsupported;}
    }
}
