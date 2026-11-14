package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.MarkerPhase;
import static dev.aivillages.core.kernel.runtime.evidence.NativeHarvest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic authority-free contract data; native source/drop save behavior requires Minecraft qualification. */
final class NativeHarvestTest {
    static Intent intent() {
        var d=NativeDepositTest.intent();var actor=new ActorRef(d.conditions().citizen(),UUID.fromString(d.actor().address()),d.actor().dimension());
        var key=new NativeDeposit.Key(d.key().world(),d.key().root(),d.key().step(),d.key().attempt(),d.key().generation(),3);
        return new Intent(key,d.origin(),d.run(),d.operation(),actor,new Cuboid(actor.dimension(),2,64,2,2,64,2),new ContainerRef(actor.dimension(),10,64,10),NativeDepositTest.LOG,
                new Source(actor.dimension(),2,64,2,UUID.randomUUID(),4,"minecraft:oak_log[axis=y]"),d.conditions(),d.actor());
    }
    static Entry completed(Intent i) {
        var drop=new Drop(i.actor().dimension(),UUID.randomUUID(),UUID.randomUUID(),0,i.item(),1);var s=i.source();
        var after=new Source(s.dimension(),s.x(),s.y(),s.z(),s.incarnation(),s.revision()+1,"minecraft:air");
        var receipt=new ResourceDelivery.Receipt(UUID.randomUUID(),i.origin(),i.run(),i.operation(),UUID.randomUUID(),i.actor(),i.area(),i.destination(),new ResourceDelivery.ItemIdentity(i.item(),"default"),ResourceDelivery.Stage.HARVEST,1,
                new ResourceDelivery.BlockPosition(s.dimension(),s.x(),s.y(),s.z()),List.of(drop.entity()));
        var usage=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);usage.putAll(i.conditions().usage());usage.merge(Budgets.Kind.ATTEMPTED_EFFECTS,1L,Math::addExact);usage.merge(Budgets.Kind.COMMITTED_EFFECTS,1L,Math::addExact);
        return new Entry(i,observed(i,after,List.of(drop),receipt,usage,"",i.actorBefore()));
    }
    static NativeDeposit.Marker marker(Entry e){return new NativeDeposit.Marker(e.token(),e.intent().digest(),MarkerPhase.RESULT,e.result().digest());}
    static NativeDeposit.Witness actor(Entry e,boolean restored){return new NativeDeposit.Witness(e.result().actor(),marker(e),restored);}
    static SourceWitness source(Entry e,boolean restored){return new SourceWitness(e.result().source(),marker(e),restored);}
    static List<DropWitness> drops(Entry e,boolean restored){return e.result().drops().stream().map(d->new DropWitness(d,marker(e),restored)).toList();}
    @Test void exactOriginalOperationReceiptAndSavedWitnessesRoundTripWithoutNewAuthority() {
        Entry e=completed(intent());assertEquals(e,decode(encode(e)));assertEquals(Reconciliation.UNPROVEN,classify(e,actor(e,true),source(e,false),drops(e,false)));
        assertEquals(Reconciliation.SAVED_COMPLETE,classify(e,actor(e,true),source(e,true),drops(e,true)));
        var proof=new Verification(Reconciliation.SAVED_COMPLETE,actor(e,true),source(e,true),drops(e,true),UUID.randomUUID(),100);
        Entry verified=new Entry(e.intent(),e.result(),proof);assertEquals(verified,decode(encode(verified)));
        assertEquals(e.intent().operation(),verified.result().receipt().operationId());assertEquals(e.intent().key(),verified.intent().key());
    }
    @Test void savedPreparedBlockCannotExcludeAnIndependentlySavedSpawnedDrop() {
        Intent i=intent();Entry prepared=new Entry(i,null);var marker=new NativeDeposit.Marker(prepared.token(),i.digest(),MarkerPhase.PREPARED,"");
        assertEquals(Reconciliation.PARTIAL_UNRESOLVED,classify(prepared,new NativeDeposit.Witness(i.actorBefore(),marker,true),new SourceWitness(i.source(),marker,true),List.of()));
    }
    @Test void missingUnloadedMergedOrReplacedDropsCannotBecomeCountOnlyProof() {
        Entry e=completed(intent());assertEquals(Reconciliation.WAIT_WORLD,classify(e,actor(e,true),source(e,true),List.of()));assertEquals(Reconciliation.WAIT_WORLD,classify(e,null,null,drops(e,true)));
        var old=e.result().drops().getFirst();var changed=new Drop(old.dimension(),old.entity(),old.incarnation(),old.revision()+1,old.item(),old.quantity());
        assertEquals(Reconciliation.UNPROVEN,classify(e,actor(e,true),source(e,true),List.of(new DropWitness(changed,marker(e),true))));
        var replacement=new Drop(old.dimension(),UUID.randomUUID(),UUID.randomUUID(),old.revision(),old.item(),old.quantity());
        assertEquals(Reconciliation.UNPROVEN,classify(e,actor(e,true),source(e,true),List.of(new DropWitness(replacement,marker(e),true))));
    }
    @Test void sameCoordinateBlockReplacementOrEqualStateAfterMutationCannotRegainIncarnation() {
        Entry e=completed(intent());var s=e.result().source();var replacement=new Source(s.dimension(),s.x(),s.y(),s.z(),UUID.randomUUID(),s.revision(),s.state());
        assertEquals(Reconciliation.UNPROVEN,classify(e,actor(e,true),new SourceWitness(replacement,marker(e),true),drops(e,true)));
        var changed=new Source(s.dimension(),s.x(),s.y(),s.z(),s.incarnation(),s.revision()+1,s.state());
        assertEquals(Reconciliation.TORN_SAVED_COHORT,classify(e,actor(e,true),new SourceWitness(changed,marker(e),true),drops(e,true)));
    }
    @Test void tornOrLiveDropMarkerNeverAuthorizesSavedSettlement() {
        Entry e=completed(intent());var d=e.result().drops().getFirst();var wrong=new NativeDeposit.Marker(e.token(),e.intent().digest(),MarkerPhase.MUTATING,"");
        assertEquals(Reconciliation.TORN_SAVED_COHORT,classify(e,actor(e,true),source(e,true),List.of(new DropWitness(d,wrong,true))));
        assertEquals(Reconciliation.UNPROVEN,classify(e,actor(e,true),source(e,true),drops(e,false)));
        assertThrows(IllegalArgumentException.class,()->new Entry(e.intent(),e.result(),new Verification(Reconciliation.SAVED_COMPLETE,actor(e,true),source(e,true),drops(e,false),UUID.randomUUID(),100)));
    }
    @Test void laterActorMutationCannotUseUnchangedCountsToCloseAHarvestCheckpoint() {
        Entry e=completed(intent());var original=e.result().actor();
        var changed=new NativeDeposit.Participant(original.kind(),original.dimension(),original.address(),original.incarnation(),original.revision()+1,original.slots());
        var witness=new NativeDeposit.Witness(changed,marker(e),true);
        assertEquals(Reconciliation.TORN_SAVED_COHORT,classify(e,witness,source(e,true),drops(e,true)));
        assertThrows(IllegalArgumentException.class,()->new Entry(e.intent(),e.result(),new Verification(Reconciliation.SAVED_COMPLETE,witness,source(e,true),drops(e,true),UUID.randomUUID(),100)));
    }
    @Test void exceptionAfterNativeWritesRetainsPhysicalUncertaintyWithoutInventedReceipt() {
        Entry e=completed(intent());Result unreceipted=observed(e.intent(),e.result().source(),e.result().drops(),null,e.result().usage(),"after native writes before receipt",e.result().actor());
        Entry unknown=new Entry(e.intent(),unreceipted);assertEquals(Live.UNRECEIPTED_EFFECT,unreceipted.live());assertEquals(unknown,decode(encode(unknown)));
        assertEquals(Reconciliation.PARTIAL_UNRESOLVED,classify(unknown,actor(unknown,true),source(unknown,true),drops(unknown,true)));
    }
    @Test void actualReceiptCannotChangeOriginalRunOperationOrDropCustody() {
        Entry e=completed(intent());var r=e.result().receipt();var foreign=new ResourceDelivery.Receipt(r.receiptId(),r.origin(),UUID.randomUUID(),r.operationId(),r.batchId(),r.actor(),r.source(),r.destination(),r.item(),r.stage(),r.quantity(),r.sourceBlock(),r.dropIds());
        var result=new Result(e.result().intent(),e.result().live(),e.result().source(),e.result().drops(),foreign,e.result().usage(),"",e.result().actor());
        assertThrows(IllegalArgumentException.class,()->new Entry(e.intent(),result));
        var decreased=new Result(e.result().intent(),e.result().live(),e.result().source(),e.result().drops(),r,e.intent().conditions().usage(),"",e.result().actor());
        assertThrows(IllegalArgumentException.class,()->new Entry(e.intent(),decreased));
    }
    @Test void unknownSchemaMalformedUtf8AndNoncanonicalRecordsFailClosed() {
        Entry e=completed(intent());byte[] raw=encode(e);byte[] extra=Arrays.copyOf(raw,raw.length+1);extra[extra.length-1]='\n';assertThrows(IllegalArgumentException.class,()->decode(extra));
        byte[] broken=raw.clone();broken[1]=(byte)0xc3;broken[2]=0x28;assertThrows(IllegalArgumentException.class,()->decode(broken));
        String text=new String(raw,java.nio.charset.StandardCharsets.UTF_8);String future=text.replace("\"schema\":1","\"schema\":9");
        assertThrows(IllegalArgumentException.class,()->decode(future.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
