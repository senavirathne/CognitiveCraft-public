package dev.aivillages.core.kernel.storage;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.storage.StorageCapabilities.*;

class StorageEligibilityTest {
    private static final UUID A=UUID.fromString("d0500000-0000-4000-8000-000000000001"),B=UUID.fromString("d0500000-0000-4000-8000-000000000002");
    private static final Item PLANK=new Item("minecraft:oak_planks","0".repeat(64),64,true,false),STONE=new Item("minecraft:stone","1".repeat(64),64,true,false);
    private static List<Slot> slots(boolean doubled){var rows=new ArrayList<Slot>();for(var id:doubled?List.of(A,B):List.of(A))for(int i=0;i<27;i++)rows.add(new Slot(id,i,null,0,64,64,true,true));return rows;}
    private static Facts facts(Type type,List<Slot> slots){return new Facts(type,true,true,true,false,false,true,slots);}
    @ParameterizedTest(name="supported-{0}") @EnumSource(value=Type.class,names={"SINGLE_CHEST","DOUBLE_CHEST","BARREL"})
    void normalMvpContainersAreEligible(Type type){var e=evaluate(facts(type,slots(type==Type.DOUBLE_CHEST)),PLANK);assertTrue(e.eligible());assertEquals(type==Type.DOUBLE_CHEST?3456:1728,e.insertionCapacity());assertEquals(0,e.matchingItems());}
    @ParameterizedTest(name="refused-{0}") @EnumSource(value=Type.class,names={"TRAPPED_CHEST","ENDER_CHEST","SHULKER","HOPPER","FURNACE","MODDED","UNKNOWN"})
    void otherContainerKindsNeverGainMvpSupport(Type type){assertEquals(Refusal.UNSUPPORTED,evaluate(facts(type,slots(false)),PLANK).refusal());}
    @Test void partialFullAndDifferentStacksUseExactCapacity(){var s=slots(false);s.set(0,new Slot(A,0,PLANK,32,64,64,true,true));s.set(1,new Slot(A,1,PLANK,64,64,64,true,true));s.set(2,new Slot(A,2,STONE,64,64,64,true,true));var result=evaluate(facts(Type.SINGLE_CHEST,s),PLANK);assertEquals(1568,result.insertionCapacity());assertEquals(96,result.matchingItems());}
    @Test void unstackableAndSixteenStackProbeDoNotInvalidateOtherValidFullStacks(){var s=slots(false);s.set(0,new Slot(A,0,STONE,64,64,16,true,true));var pearl=new Item("minecraft:ender_pearl","2".repeat(64),16,true,false);for(int i=1;i<27;i++)s.set(i,new Slot(A,i,null,0,64,16,true,true));assertEquals(416,evaluate(facts(Type.BARREL,s),pearl).insertionCapacity());var tool=new Item("minecraft:wooden_pickaxe","3".repeat(64),1,true,false);assertEquals(26,evaluate(facts(Type.BARREL,s),tool).insertionCapacity());}
    @Test void insertionAndRemovalVetoesAreIndependent(){var s=slots(false);s.set(0,new Slot(A,0,PLANK,32,64,64,false,true));s.set(1,new Slot(A,1,PLANK,8,64,64,true,false));var e=evaluate(facts(Type.BARREL,s),PLANK);assertEquals(32,e.matchingItems());assertEquals(25*64+56,e.insertionCapacity());}
    @Test void unresolvedLootLocksProtectionAndUnknownAreNotEmpty(){for(var refusal:List.of(Refusal.UNKNOWN,Refusal.UNLOADED,Refusal.TOPOLOGY,Refusal.LOOT_TABLE,Refusal.LOCKED,Refusal.PROTECTION)){var f=new Facts(Type.BARREL,refusal!=Refusal.UNKNOWN,refusal!=Refusal.UNLOADED,refusal!=Refusal.TOPOLOGY,refusal==Refusal.LOOT_TABLE,refusal==Refusal.LOCKED,refusal!=Refusal.PROTECTION,slots(false));var e=evaluate(f,PLANK);assertEquals(refusal,e.refusal());assertEquals(0,e.insertionCapacity());}}
    @Test void modifiedAndContainerItemsAreExplicitlyRefused(){for(var item:List.of(new Item("minecraft:oak_planks","0".repeat(64),64,false,false),new Item("minecraft:shulker_box","1".repeat(64),1,true,true))){assertEquals(Refusal.COMPONENTS,evaluate(facts(Type.BARREL,slots(false)),item).refusal());var rows=slots(false);rows.set(0,new Slot(A,0,item,1,64,64,true,true));assertEquals(Refusal.COMPONENTS,evaluate(facts(Type.BARREL,rows),PLANK).refusal());}}
    @Test void duplicateHalfInvalidSlotAndOverfullStackNeverCountTwice(){var doubled=slots(true);for(int i=27;i<54;i++)doubled.set(i,new Slot(A,i-27,null,0,64,64,true,true));assertEquals(Refusal.SLOT_POLICY,evaluate(facts(Type.DOUBLE_CHEST,doubled),PLANK).refusal());var s=slots(false);s.removeLast();assertEquals(Refusal.SLOT_POLICY,evaluate(facts(Type.BARREL,s),PLANK).refusal());s=slots(false);s.set(0,new Slot(A,0,PLANK,65,64,64,true,true));assertEquals(Refusal.SLOT_POLICY,evaluate(facts(Type.BARREL,s),PLANK).refusal());}
    @Test void differentFingerprintCannotMergeOrFulfill(){var s=slots(false);s.set(0,new Slot(A,0,new Item("minecraft:oak_planks","f".repeat(64),64,true,false),32,64,64,true,true));var e=evaluate(facts(Type.SINGLE_CHEST,s),PLANK);assertEquals(0,e.matchingItems());assertEquals(26*64,e.insertionCapacity());}
    @Test void liveExportsAreImmutableAndUnknownHasNoSlotsOrSaveClaim(){var rows=slots(false);var visible=new StorageSnapshot.Visible(A,B,1,A,100,List.of(new StorageSnapshot.Participant(A,1,27)),rows.stream().map(s->new StorageSnapshot.Slot(s.participant(),s.index(),s.item(),s.count(),s.limit())).toList(),"0".repeat(64));assertThrows(UnsupportedOperationException.class,()->visible.slots().clear());assertThrows(UnsupportedOperationException.class,()->visible.participants().clear());assertEquals(27,visible.slots().size());assertTrue(Arrays.stream(StorageSnapshot.Visible.class.getRecordComponents()).noneMatch(c->Set.of("saveConfirmed","receiptId").contains(c.getName())));}
}
