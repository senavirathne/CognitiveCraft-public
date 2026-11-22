package dev.aivillages.fabric;

import dev.aivillages.core.kernel.Contracts.ActorRef;
import dev.aivillages.core.kernel.Contracts.ContainerRef;
import dev.aivillages.core.kernel.ResourceDelivery.Stage;
import dev.aivillages.core.kernel.StrictJson;
import dev.aivillages.core.kernel.production.RecipeChoice;
import dev.aivillages.core.kernel.production.RecipeChoice.ItemKey;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Marker;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.MarkerPhase;
import dev.aivillages.core.kernel.runtime.evidence.NativeRecipeJournal;
import dev.aivillages.fabric.runtime.evidence.DepositParticipant;
import dev.aivillages.fabric.runtime.evidence.DepositParticipantState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import static dev.aivillages.core.kernel.RecipeTransaction.fingerprint;

/** Independent of the recipe host's diagnostic before/after observations. */
final class FabricRecipeParticipants {
    private final ServerLevel level;
    private final FabricGatewayWorld world;
    FabricRecipeParticipants(FabricGatewayWorld world) { this.world=world; this.level=world.level(); }

    record Slot(ItemKey item,int count) {
        private static final Slot EMPTY=new Slot(ItemKey.EMPTY,0);
        Map<String,Object> value(){return Map.of("item",item.value(),"count",count);}
    }
    record Capture(String address,ContainerRef container,DepositParticipantState marker,Container inventory,
                   List<Slot> slots,NativeRecipeJournal.Participant evidence) {
        Capture {slots=List.copyOf(slots);}
        void mark(Marker value){marker.mark(value,inventory::setChanged);}
    }
    static String actorAddress(ActorRef actor){return "actor:"+actor.dimension()+"/"+actor.entityId();}
    static String chestAddress(ContainerRef chest){return "chest:"+chest.dimension()+"/"+chest.x()+","+chest.y()+","+chest.z();}

    List<Capture> capture(ActorRef actor,List<ContainerRef> containers) {
        var villager=world.recipeActor(actor);
        if(villager==null)throw new IllegalStateException("Native recipe actor unavailable");
        List<Capture> result=new ArrayList<>();
        result.add(capture(actorAddress(actor),null,(DepositParticipant)villager,villager.getInventory(),true));
        for(var container:containers) {
            var chest=container(container);
            if(chest==null || world.recipeContainer(container)!=chest)throw new IllegalStateException("Exact recipe requires loaded native storage");
            result.add(capture(chestAddress(container),container,(DepositParticipant)chest,chest,true));
        }
        return List.copyOf(result);
    }
    /** Read the original delivery address and persist its incarnation with the native chunk on save. */
    Capture deliveryTarget(ContainerRef ref, boolean track) {
        var current = container(ref);
        if (current == null || world.recipeContainer(ref) != current) throw new IllegalStateException("Native delivery target unavailable");
        var snapshot = capture(chestAddress(ref), ref, (DepositParticipant) current, current, track);
        if (track) level.getChunkSource().getChunkNow(ref.x() >> 4, ref.z() >> 4).markUnsaved();
        return snapshot;
    }
    private BaseContainerBlockEntity container(ContainerRef ref) {
        if(!level.dimension().identifier().toString().equals(ref.dimension()))return null;
        var pos=new BlockPos(ref.x(),ref.y(),ref.z());
        var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        if(chunk==null)return null;
        var state=chunk.getBlockState(pos);
        var entity=chunk.getBlockEntities().get(pos);
        if(state.is(Blocks.BARREL) && entity instanceof BarrelBlockEntity barrel)return barrel;
        return state.is(Blocks.CHEST) && state.getValue(ChestBlock.TYPE)==ChestType.SINGLE
                && entity instanceof ChestBlockEntity chest?chest:null;
    }
    private Capture capture(String address,ContainerRef ref,DepositParticipant participant,Container inventory,boolean track) {
        var state=participant.cognitivecraft$depositParticipant();
        if(track)state.activate();
        if(!state.active() || !state.valid())throw new IllegalStateException("Invalid inventory participant");
        int size=inventory.getContainerSize();
        if(size<1 || size>(ref==null?8:27))throw new IllegalStateException("Unsupported participant inventory");
        List<Slot> slots=new ArrayList<>(size);
        List<ItemStack> tracked=new ArrayList<>(size);
        for(int index=0;index<size;index++) {
            var stack=inventory.getItem(index);
            if(stack.isEmpty())slots.add(Slot.EMPTY);
            else {
                if(stack.getCount()>64)throw new IllegalStateException("Unbounded native stack");
                slots.add(new Slot(FabricConcreteRecipeChoices.itemKey(level,stack),stack.getCount()));
                if(track)tracked.add(stack);
            }
        }
        if(track)state.track(tracked);
        String digest=fingerprint(StrictJson.canonical(slots.stream().map(Slot::value).toList()));
        return new Capture(address,ref,state,inventory,slots,new NativeRecipeJournal.Participant(address,state.incarnation(),state.revision(),digest));
    }
    static List<NativeRecipeJournal.Participant> evidence(List<Capture> participants){return participants.stream().map(Capture::evidence).toList();}

    /** Convert an earlier plain, native H/P/D snapshot to this journal's full slot representation. */
    NativeRecipeJournal.Participant acquisitionSnapshot(dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Participant earlier,
                                                       String address) {
        var slots = new ArrayList<Slot>();
        for (var slot : earlier.slots()) {
            if (slot.count() == 0) slots.add(Slot.EMPTY);
            else {
                var type = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(slot.item()));
                if (type == net.minecraft.world.item.Items.AIR) throw new IllegalArgumentException("Unknown original item");
                slots.add(new Slot(FabricConcreteRecipeChoices.itemKey(level, new ItemStack(type)), slot.count()));
            }
        }
        return new NativeRecipeJournal.Participant(address, earlier.incarnation(), earlier.revision(),
                fingerprint(StrictJson.canonical(slots.stream().map(Slot::value).toList())));
    }

    /** Exact source slots and per-item conservation across actor and recipient. */
    static boolean applied(RecipeChoice choice,int batch,Stage phase,List<Capture> before,List<Capture> after) {
        if(before.size()!=after.size() || before.isEmpty())return false;
        var expectedActor=totals(before.getFirst().slots());
        var recipes=choice.phaseBatches(batch);
        var withdrawals=recipes.stream().flatMap(recipe->recipe.withdrawals().stream()).toList();
        int delivery=Math.toIntExact(choice.phaseDelivery(batch));
        switch(phase) {
            case WITHDRAW -> {
                if(recipes.getFirst().usesHeldInputs()) {
                    var held=new ArrayList<>(before.getFirst().slots());
                    for(var binding:recipes.getFirst().bindings())if(binding.heldBy()!=null) {
                        if(binding.sourceSlot()>=held.size())return false;
                        var slot=held.get(binding.sourceSlot());
                        if(slot.count()!=binding.expectedCount() || !slot.item().equals(binding.item()))return false;
                        held.set(binding.sourceSlot(),slot.count()==1?Slot.EMPTY:new Slot(slot.item(),slot.count()-1));
                    }
                }
                withdrawals.forEach(b->change(expectedActor,b.item(),1));
            }
            case CRAFT -> {
                recipes.stream().flatMap(recipe->recipe.grid().stream()).filter(item->!item.empty())
                        .forEach(item->change(expectedActor,item,-1));
                change(expectedActor,choice.result(),Math.toIntExact(choice.phaseProduction(batch)));
            }
            case DEPOSIT -> change(expectedActor,choice.result(),-delivery);
            default -> {return false;}
        }
        if(!expectedActor.equals(totals(after.getFirst().slots())))return false;
        for(int index=0;index<before.size();index++) {
            var prior=before.get(index);
            var actual=after.get(index);
            if(!prior.address().equals(actual.address()) || prior.inventory()!=actual.inventory()
                    || !prior.evidence().incarnation().equals(actual.evidence().incarnation()))return false;
            if(index==0)continue;
            if(phase==Stage.DEPOSIT) {
                var expected=totals(prior.slots());change(expected,choice.result(),delivery);
                if(!expected.equals(totals(actual.slots())))return false;
            } else {
                var expected=new ArrayList<>(prior.slots());
                if(phase==Stage.WITHDRAW)for(var binding:withdrawals) {
                    if(!binding.source().equals(prior.container()))continue;
                    if(binding.sourceSlot()>=expected.size())return false;
                    var slot=expected.get(binding.sourceSlot());
                    if(slot.count()!=binding.expectedCount() || !slot.item().equals(binding.item()))return false;
                    expected.set(binding.sourceSlot(),slot.count()==1?Slot.EMPTY:new Slot(slot.item(),slot.count()-1));
                }
                if(!expected.equals(actual.slots()))return false;
            }
        }
        return true;
    }

    /** A table uses one ordinary item unit. The actual cell transition is checked separately. */
    static boolean placement(ItemKey table,Stage phase,List<Capture> before,List<Capture> after,
                             dev.aivillages.core.kernel.PlacementTransaction.HeldTable held) {
        int expectedParticipants=phase==Stage.WITHDRAW?2:1;
        if(before.size()!=expectedParticipants || after.size()!=expectedParticipants)return false;
        for(int index=0;index<expectedParticipants;index++) {
            var prior=before.get(index);var actual=after.get(index);
            if(prior.inventory()!=actual.inventory() || !prior.evidence().incarnation().equals(actual.evidence().incarnation()))return false;
            if(held!=null) {
                var expected=new ArrayList<>(prior.slots());
                if(index==0) {
                    int source=phase==Stage.WITHDRAW?held.inputSlot():held.placementSlot();
                    int count=phase==Stage.WITHDRAW?held.inputCount():1;
                    var slot=expected.get(source);
                    if(!slot.item().equals(table) || slot.count()!=count)return false;
                    expected.set(source,count==1?Slot.EMPTY:new Slot(table,count-1));
                    if(phase==Stage.WITHDRAW) {
                        if(expected.get(held.placementSlot()).count()!=0)return false;
                        expected.set(held.placementSlot(),new Slot(table,1));
                    }
                }
                if(!expected.equals(actual.slots()))return false;
                continue;
            }
            var expected=totals(prior.slots());
            change(expected,table,phase==Stage.WITHDRAW && index==0?1:-1);
            if(!expected.equals(totals(actual.slots())))return false;
        }
        return true;
    }
    private static Map<ItemKey,Integer> totals(List<Slot> slots) {
        Map<ItemKey,Integer> result=new HashMap<>();
        for(var slot:slots)if(slot.count()>0)change(result,slot.item(),slot.count());
        return result;
    }
    private static void change(Map<ItemKey,Integer> totals,ItemKey item,int amount) {
        totals.compute(item,(key,count)->{int next=(count==null?0:count)+amount;return next==0?null:next;});
    }

    /** Trusts only restored native provenance and matching saved markers/slots. */
    boolean restored(NativeRecipeJournal.Entry entry,NativeRecipeJournal.Participant saved) {
        return matches(entry,saved,true);
    }
    /** Requires a separate private acknowledgement from the actual native writer. This is never saved proof. */
    boolean liveCurrent(NativeRecipeJournal.Entry entry,NativeRecipeJournal.Participant expected) {
        return matches(entry,expected,false);
    }
    private boolean matches(NativeRecipeJournal.Entry entry,NativeRecipeJournal.Participant saved,boolean requireSaved) {
        var result=entry.result();
        if(result==null || result.status()!=NativeRecipeJournal.Status.APPLIED || !result.after().contains(saved))return false;
        var expected=new Marker(fingerprint(entry.intent().token().toString()),entry.intent().digest(),MarkerPhase.RESULT,result.digest());
        try {
            var current=reopen(saved.address());
            return current!=null && (!requireSaved || current.marker().restored()) && expected.equals(current.marker().marker())
                    && saved.equals(current.evidence());
        }catch(RuntimeException unavailable){return false;}
    }
    private Capture reopen(String address) {
        String dimension=level.dimension().identifier().toString();
        String actor="actor:"+dimension+"/";
        String chest="chest:"+dimension+"/";
        if(address.startsWith(actor)) {
            var entity=level.getEntity(UUID.fromString(address.substring(actor.length())));
            if(!(entity instanceof Villager villager) || !villager.isAlive()
                    || level.getChunkSource().getChunkNow(villager.blockPosition().getX()>>4,villager.blockPosition().getZ()>>4)==null)return null;
            return capture(address,null,(DepositParticipant)villager,villager.getInventory(),false);
        }
        if(address.startsWith(chest)) {
            String[] coordinates=address.substring(chest.length()).split(",",-1);
            if(coordinates.length!=3)return null;
            var ref=new ContainerRef(dimension,Integer.parseInt(coordinates[0]),Integer.parseInt(coordinates[1]),Integer.parseInt(coordinates[2]));
            var current=container(ref);
            if(current!=null)return capture(address,ref,(DepositParticipant)current,current,false);
        }
        return null;
    }
}
