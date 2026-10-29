package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.runtime.evidence.NativePickup.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic first-H/P data only. No native pickup or original P owner pass is claimed. */
final class NativePickupTest {
    static final String DIM="minecraft:overworld",LOG="minecraft:oak_log";
    static NativeHarvest.Entry harvest(int quantity) {return harvest(LOG,quantity);}
    static NativeHarvest.Entry harvest(String item,int quantity) {
        UUID world=UUID.randomUUID(),job=UUID.randomUUID();var actor=new ActorRef(UUID.randomUUID(),UUID.randomUUID(),DIM);
        var origin=new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(world,UUID.randomUUID()));
        var empty=new ArrayList<NativeDeposit.Slot>();for(int n=0;n<8;n++)empty.add(new NativeDeposit.Slot("minecraft:air",0));
        var participant=new NativeDeposit.Participant(NativeDeposit.Kind.ACTOR,DIM,actor.entityId().toString(),UUID.randomUUID(),4,empty);
        var usage=Map.of(Budgets.Kind.OBSERVATIONS,6L,Budgets.Kind.INSTRUCTIONS,2L,Budgets.Kind.CALLS,1L,Budgets.Kind.ELAPSED_TICKS,9L,
                Budgets.Kind.TRAVEL_BLOCKS,4L,Budgets.Kind.ATTEMPTED_EFFECTS,0L,Budgets.Kind.COMMITTED_EFFECTS,0L);
        var claims=List.of(new NativeDeposit.Claim(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1));
        var conditions=new NativeDeposit.Conditions(actor.citizenId(),4,100_000,10_000,64,10,2500,1000,claims,usage);
        var source=new NativeHarvest.Source(DIM,2,64,2,UUID.randomUUID(),3,item+"[axis=y]");
        var i=new NativeHarvest.Intent(new NativeDeposit.Key(world,job,job,UUID.randomUUID(),1,1),origin,UUID.randomUUID(),UUID.randomUUID(),
                actor,new Cuboid(DIM,2,64,2,2,64,2),new ContainerRef(DIM,10,64,10),item,source,conditions,participant);
        var d=new NativeHarvest.Drop(DIM,UUID.randomUUID(),UUID.randomUUID(),0,item,quantity);
        var after=new NativeHarvest.Source(DIM,source.x(),source.y(),source.z(),source.incarnation(),4,"minecraft:air");
        var receipt=new ResourceDelivery.Receipt(UUID.randomUUID(),origin,i.run(),i.operation(),UUID.randomUUID(),actor,i.area(),i.destination(),
                new ResourceDelivery.ItemIdentity(item,"default"),ResourceDelivery.Stage.HARVEST,quantity,
                new ResourceDelivery.BlockPosition(DIM,2,64,2),List.of(d.entity()));
        var spent=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);spent.putAll(usage);spent.put(Budgets.Kind.ATTEMPTED_EFFECTS,1L);spent.put(Budgets.Kind.COMMITTED_EFFECTS,1L);
        return new NativeHarvest.Entry(i,NativeHarvest.observed(i,after,List.of(d),receipt,spent,"",participant));
    }
    static Intent intent(NativeHarvest.Entry h) {return intent(h,2);}
    static Intent intent(NativeHarvest.Entry h,int requested) {
        var k=h.intent().key();var d=h.result().drops().getFirst();var actor=h.result().actor();var chunks=List.of(new Chunk(DIM,0,0),new Chunk(DIM,1,0));
        var usage=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);usage.putAll(h.result().usage());usage.merge(Budgets.Kind.OBSERVATIONS,2L,Math::addExact);
        var before=h.intent().conditions();var c=new NativeDeposit.Conditions(before.citizen(),5,before.deadlineEpochMillis(),12_000,63,25,2500,1000,before.claims(),usage);
        return new Intent(new NativeDeposit.Key(k.world(),k.root(),k.step(),k.attempt(),k.generation(),2),h,UUID.randomUUID(),requested,c,
                new Trail(UUID.fromString(actor.address()),actor.incarnation(),chunks,chunks.getLast()),new Trail(d.entity(),d.incarnation(),chunks,chunks.getLast()));
    }
    static Entry completed(Intent i,int remaining) {
        var h=i.harvest().result();var before=h.drops().getFirst();int moved=before.quantity()-remaining;
        var slots=new ArrayList<NativeDeposit.Slot>(h.actor().slots());if(moved>0)slots.set(0,new NativeDeposit.Slot(before.item(),moved));
        var actor=moved==0?h.actor():new NativeDeposit.Participant(h.actor().kind(),DIM,h.actor().address(),h.actor().incarnation(),h.actor().revision()+1,slots);
        var drop=remaining==0?null:moved==0?before:new NativeHarvest.Drop(DIM,before.entity(),before.incarnation(),before.revision()+1,before.item(),remaining);
        var original=h.receipt();
        var receipt=moved==0?null:new ResourceDelivery.Receipt(UUID.randomUUID(),original.origin(),original.runId(),i.operation(),original.batchId(),original.actor(),original.source(),
                original.destination(),original.item(),ResourceDelivery.Stage.PICKUP,moved,original.sourceBlock(),original.dropIds());
        var usage=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);usage.putAll(i.conditions().usage());usage.merge(Budgets.Kind.ATTEMPTED_EFFECTS,1L,Math::addExact);
        if(moved>0)usage.merge(Budgets.Kind.COMMITTED_EFFECTS,1L,Math::addExact);
        return new Entry(i,observed(i,h.source(),actor,drop,receipt,usage,moved==0?"TARGET_UNAVAILABLE":""));
    }
    static NativeDeposit.Marker marker(Entry e){return new NativeDeposit.Marker(e.token(),e.intent().digest(),NativeDeposit.MarkerPhase.RESULT,e.result().digest());}
    static Observation saved(Entry e) {
        var r=e.result();var marker=marker(e);var witnesses=new ArrayList<MembershipWitness>();var old=e.intent().harvest().result().drops().getFirst();
        for(Member m:NativePickup.members(e.intent())) {
            boolean actor=m.role()==Role.ACTOR;
            boolean present=actor?m.chunk().equals(e.intent().actorTrail().current()):r.remaining()!=null&&m.chunk().equals(e.intent().dropTrail().current());
            UUID incarnation=actor?r.actor().incarnation():old.incarnation();long revision=actor?r.actor().revision():r.remaining()==null?old.revision():r.remaining().revision();
            witnesses.add(new MembershipWitness(m,incarnation,revision,marker,true,present,present,NativeDeposit.sha(m.chunk().toString())));
        }
        return new Observation(new NativeDeposit.Witness(r.actor(),marker,true),new NativeHarvest.SourceWitness(r.source(),marker,true),
                r.remaining()==null?null:new NativeHarvest.DropWitness(r.remaining(),marker,true),
                new TrailWitness(e.intent().actorTrail(),marker,true),r.remaining()==null?null:new TrailWitness(e.intent().dropTrail(),marker,true),witnesses);
    }
    static Observation members(Observation o,List<MembershipWitness> m){return new Observation(o.actor(),o.source(),o.drop(),o.actorTrail(),o.dropTrail(),m);}
    static MembershipWitness witness(MembershipWitness w,boolean saved,boolean current,String digest){
        return new MembershipWitness(w.member(),w.incarnation(),w.revision(),w.marker(),w.restoredFromNativeSave(),saved,current,digest);
    }
    static NativeDeposit.Conditions conditions(Intent i,long deadline,long remaining,List<NativeDeposit.Claim> claims,Map<Budgets.Kind,Long> usage) {
        var c=i.conditions();return new NativeDeposit.Conditions(c.citizen(),c.jobRevision(),deadline,Math.min(c.actionDeadlineEpochMillis(),deadline),remaining,c.observedTick(),2500,1000,claims,usage);
    }
    static Intent copy(Intent i,NativeDeposit.Key key,UUID operation,int requested,NativeDeposit.Conditions c){
        return new Intent(key,i.harvest(),operation,requested,c,i.actorTrail(),i.dropTrail());
    }
    static ResourceDelivery.Receipt receipt(ResourceDelivery.Receipt r,UUID id,UUID run,UUID operation,UUID batch,ResourceDelivery.ItemIdentity item,long quantity,List<UUID> drops){
        return new ResourceDelivery.Receipt(id,r.origin(),run,operation,batch,r.actor(),r.source(),r.destination(),item,r.stage(),quantity,r.sourceBlock(),drops);
    }
    static void refusesReceipt(Entry e,ResourceDelivery.Receipt changed){
        var r=e.result();assertThrows(IllegalArgumentException.class,()->new Entry(e.intent(),new Result(r.intent(),r.live(),r.source(),r.actor(),r.remaining(),changed,r.usage(),r.failure())));
    }
    @Test void exactOriginalHAndPBindingsDebitsAndNativeCohortRoundTrip() {
        for(String item:List.of(LOG,"minecraft:birch_log"))for(int requested:List.of(1,2,3,4)) {
            var i=intent(harvest(item,1),requested);var e=completed(i,0);var o=saved(e);
            assertEquals(Reconciliation.SAVED_COMPLETE,classify(e,o));assertEquals(e,decode(encode(e)));
            var verified=new Entry(i,e.result(),new Verification(Reconciliation.SAVED_COMPLETE,o,UUID.randomUUID(),100));
            assertEquals(verified,decode(encode(verified)));assertEquals(2L,e.result().usage().get(Budgets.Kind.COMMITTED_EFFECTS));
            assertEquals(1L,i.harvest().result().usage().get(Budgets.Kind.COMMITTED_EFFECTS));assertEquals(requested,i.requested());
            assertEquals(item,e.result().receipt().item().id());
            assertEquals(i.harvest().result().receipt().batchId(),e.result().receipt().batchId());
        }
    }
    @Test void bareAbsenceWithoutEveryNativeFrameNeverProvesPickup() {
        var e=completed(intent(harvest(1)),0);var o=saved(e);
        assertEquals(Reconciliation.WAIT_WORLD,classify(e,members(o,List.of())));
        assertEquals(Reconciliation.WAIT_WORLD,classify(e,members(o,o.memberships().subList(0,3))));
        assertEquals(Reconciliation.WAIT_WORLD,classify(e,new Observation(null,o.source(),null,o.actorTrail(),null,o.memberships())));
    }
    @Test void equalCountActorReplacementOrChangedRevisionCannotBorrowLineage() {
        var e=completed(intent(harvest(1)),0);var o=saved(e);var a=e.result().actor();
        for(var replacement:List.of(new NativeDeposit.Participant(a.kind(),a.dimension(),a.address(),UUID.randomUUID(),a.revision(),a.slots()),
                new NativeDeposit.Participant(a.kind(),a.dimension(),a.address(),a.incarnation(),a.revision()+1,a.slots()))) {
            var altered=new Observation(new NativeDeposit.Witness(replacement,marker(e),true),o.source(),null,o.actorTrail(),null,o.memberships());
            assertEquals(Reconciliation.TORN_SAVED_COHORT,classify(e,altered));
        }
    }
    @Test void sourceReplacementOrReturnedAirCannotRecreateTheOldRevision() {
        var e=completed(intent(harvest(1)),0);var o=saved(e);var s=e.result().source();
        for(var changed:List.of(new NativeHarvest.Source(DIM,s.x(),s.y(),s.z(),UUID.randomUUID(),s.revision(),s.state()),
                new NativeHarvest.Source(DIM,s.x(),s.y(),s.z(),s.incarnation(),s.revision()+2,s.state()))) {
            assertEquals(Reconciliation.TORN_SAVED_COHORT,classify(e,new Observation(o.actor(),new NativeHarvest.SourceWitness(changed,marker(e),true),null,o.actorTrail(),null,o.memberships())));
        }
    }
    @Test void liveMarkersOrTornResultDigestsCannotBecomeSavedProof() {
        var e=completed(intent(harvest(1)),0);var o=saved(e);
        assertEquals(Reconciliation.UNPROVEN,classify(e,new Observation(new NativeDeposit.Witness(e.result().actor(),marker(e),false),o.source(),null,o.actorTrail(),null,o.memberships())));
        var wrong=new NativeDeposit.Marker(e.token(),e.intent().digest(),NativeDeposit.MarkerPhase.MUTATING,"");
        assertEquals(Reconciliation.TORN_SAVED_COHORT,classify(e,new Observation(new NativeDeposit.Witness(e.result().actor(),wrong,true),o.source(),null,o.actorTrail(),null,o.memberships())));
    }
    @Test void everyDepartedActorAndDropChunkIsChecked() {
        var e=completed(intent(harvest(1)),0);var o=saved(e);
        for(int index:List.of(0,2)) {
            var list=new ArrayList<MembershipWitness>(o.memberships());var w=list.get(index);
            list.set(index,witness(w,true,w.currentPresent(),w.frameDigest()));
            assertEquals(Reconciliation.TORN_SAVED_COHORT,classify(e,members(o,list)));
        }
    }
    @Test void oneNativeEntityChunkCannotMixDifferentFrameDigests() {
        var e=completed(intent(harvest(1)),0);var o=saved(e);var list=new ArrayList<MembershipWitness>(o.memberships());var w=list.getLast();
        list.set(list.size()-1,witness(w,w.savedPresent(),w.currentPresent(),"f".repeat(64)));
        assertEquals(Reconciliation.TORN_SAVED_COHORT,classify(e,members(o,list)));
    }
    @Test void savedAndCurrentMembershipMustAgreeForEachExactParticipant() {
        var e=completed(intent(harvest(1)),0);var o=saved(e);
        for(int index:List.of(1,3)) {
            var list=new ArrayList<MembershipWitness>(o.memberships());var w=list.get(index);
            list.set(index,witness(w,w.savedPresent(),!w.currentPresent(),w.frameDigest()));
            assertEquals(Reconciliation.TORN_SAVED_COHORT,classify(e,members(o,list)));
        }
    }
    @Test void missingImportedOrPostCaptureActorTrailsRemainUnproven() {
        var e=completed(intent(harvest(1)),0);var o=saved(e);var t=e.intent().actorTrail();
        var extended=new ArrayList<Chunk>(t.chunks());extended.add(new Chunk(DIM,2,0));
        for(var witness:Arrays.asList(null,new TrailWitness(t,marker(e),false),
                new TrailWitness(new Trail(t.entity(),t.incarnation(),extended,t.current()),marker(e),true))) {
            assertEquals(Reconciliation.UNPROVEN,classify(e,new Observation(o.actor(),o.source(),null,witness,null,o.memberships())));
        }
    }
    @Test void aFullyRemovedDropCannotReappearOrBorrowAnIncarnation() {
        var e=completed(intent(harvest(1)),0);var o=saved(e);var d=e.intent().harvest().result().drops().getFirst();
        assertEquals(Reconciliation.TORN_SAVED_COHORT,classify(e,new Observation(o.actor(),o.source(),new NativeHarvest.DropWitness(d,marker(e),true),o.actorTrail(),null,o.memberships())));
        var list=new ArrayList<MembershipWitness>(o.memberships());var w=list.getLast();
        list.set(list.size()-1,new MembershipWitness(w.member(),UUID.randomUUID(),w.revision(),w.marker(),true,false,false,w.frameDigest()));
        assertEquals(Reconciliation.UNPROVEN,classify(e,members(o,list)));
    }
    @Test void knownPartialStackPickupConservesExactRemainingQuantity() {
        var e=completed(intent(harvest(2)),1);assertEquals(Live.COMPLETE,e.result().live());assertEquals(1,e.result().receipt().quantity());assertEquals(1,e.result().remaining().quantity());
        assertEquals(Reconciliation.SAVED_COMPLETE,classify(e,saved(e)));
        assertEquals(e,decode(encode(e)));
        var r=e.result();var d=r.remaining();var changed=new NativeHarvest.Drop(d.dimension(),d.entity(),d.incarnation(),d.revision()-1,d.item(),d.quantity());
        assertThrows(IllegalArgumentException.class,()->new Entry(e.intent(),new Result(r.intent(),Live.COMPLETE,r.source(),r.actor(),changed,r.receipt(),r.usage(),"")));
    }
    @Test void savedNoEffectHasNoPickupReceiptOrExtraCommittedDebit() {
        var e=completed(intent(harvest(2)),2);assertEquals(Live.NO_EFFECT,e.result().live());assertNull(e.result().receipt());
        assertEquals(1L,e.result().usage().get(Budgets.Kind.COMMITTED_EFFECTS));assertEquals(Reconciliation.SAVED_NO_EFFECT,classify(e,saved(e)));
        assertEquals(e,decode(encode(e)));
        var verified=new Entry(e.intent(),e.result(),new Verification(Reconciliation.SAVED_NO_EFFECT,saved(e),UUID.randomUUID(),101));
        assertEquals(verified,decode(encode(verified)));
    }
    @Test void partialAndUnreceiptedEffectsRemainUnresolved() {
        var full=completed(intent(harvest(1)),0);var r=full.result();
        var unknown=new Entry(full.intent(),observed(full.intent(),r.source(),r.actor(),null,null,r.usage(),"actual receipt unavailable"));
        assertEquals(Live.UNRECEIPTED_EFFECT,unknown.result().live());assertEquals(Reconciliation.PARTIAL_UNRESOLVED,classify(unknown,saved(unknown)));
        var slots=new ArrayList<NativeDeposit.Slot>(r.actor().slots());slots.set(0,new NativeDeposit.Slot(LOG,2));
        var actor=new NativeDeposit.Participant(r.actor().kind(),DIM,r.actor().address(),r.actor().incarnation(),r.actor().revision()+1,slots);
        var partial=new Entry(full.intent(),observed(full.intent(),r.source(),actor,null,null,r.usage(),"unexplained delta"));
        assertEquals(Live.PARTIAL,partial.result().live());assertEquals(Reconciliation.PARTIAL_UNRESOLVED,classify(partial,saved(partial)));
    }
    @Test void savedPreparedMarkersCannotInventAStoppedPickupOutcome() {
        var e=completed(intent(harvest(1)),0);var o=saved(e);var prepared=new Entry(e.intent(),null);
        assertEquals(Reconciliation.PARTIAL_UNRESOLVED,classify(prepared,o));
        assertThrows(IllegalArgumentException.class,()->new Entry(e.intent(),null,new Verification(Reconciliation.SAVED_NO_EFFECT,o,UUID.randomUUID(),100)));
    }
    @Test void changedAncestorOwnerGenerationOrOperationRefuses() {
        var i=intent(harvest(1));var k=i.key();
        for(var key:List.of(new NativeDeposit.Key(k.world(),k.root(),k.step(),UUID.randomUUID(),k.generation(),2),
                new NativeDeposit.Key(k.world(),k.root(),k.step(),k.attempt(),k.generation()+1,2),
                new NativeDeposit.Key(k.world(),k.root(),k.step(),k.attempt(),k.generation(),1))) {
            assertThrows(IllegalArgumentException.class,()->copy(i,key,i.operation(),2,i.conditions()));
        }
        assertThrows(IllegalArgumentException.class,()->copy(i,k,i.harvest().intent().operation(),2,i.conditions()));
        assertThrows(IllegalArgumentException.class,()->copy(i,k,i.operation(),0,i.conditions()));
        assertThrows(IllegalArgumentException.class,()->copy(i,k,i.operation(),65,i.conditions()));
        var h=i.harvest();var v=new NativeHarvest.Verification(NativeHarvest.Reconciliation.SAVED_COMPLETE,NativeHarvestTest.actor(h,true),NativeHarvestTest.source(h,true),NativeHarvestTest.drops(h,true),UUID.randomUUID(),100);
        var verified=new NativeHarvest.Entry(h.intent(),h.result(),v);
        assertThrows(IllegalArgumentException.class,()->new Intent(k,verified,i.operation(),2,i.conditions(),i.actorTrail(),i.dropTrail()));
    }
    @Test void originalDeadlineRemainingBudgetAndClaimsCannotBeRefreshed() {
        var i=intent(harvest(1));var c=i.conditions();
        assertThrows(IllegalArgumentException.class,()->copy(i,i.key(),i.operation(),2,conditions(i,c.deadlineEpochMillis()+1,63,c.claims(),c.usage())));
        assertThrows(IllegalArgumentException.class,()->copy(i,i.key(),i.operation(),2,conditions(i,c.deadlineEpochMillis(),64,c.claims(),c.usage())));
        var decreased=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);decreased.putAll(c.usage());decreased.put(Budgets.Kind.COMMITTED_EFFECTS,0L);
        assertThrows(IllegalArgumentException.class,()->copy(i,i.key(),i.operation(),2,conditions(i,c.deadlineEpochMillis(),63,c.claims(),decreased)));
        var claim=c.claims().getFirst();var changed=List.of(new NativeDeposit.Claim(claim.id(),UUID.randomUUID(),claim.group(),claim.generation()));
        assertThrows(IllegalArgumentException.class,()->copy(i,i.key(),i.operation(),2,conditions(i,c.deadlineEpochMillis(),63,changed,c.usage())));
    }
    @Test void actualPickupReceiptMustMatchTheOriginalRunOperationBatchItemAndDrops() {
        var e=completed(intent(harvest(1)),0);var r=e.result().receipt();
        refusesReceipt(e,receipt(r,r.receiptId(),UUID.randomUUID(),r.operationId(),r.batchId(),r.item(),1,r.dropIds()));
        refusesReceipt(e,receipt(r,r.receiptId(),r.runId(),UUID.randomUUID(),r.batchId(),r.item(),1,r.dropIds()));
        refusesReceipt(e,receipt(r,r.receiptId(),r.runId(),r.operationId(),UUID.randomUUID(),r.item(),1,r.dropIds()));
        refusesReceipt(e,receipt(r,e.intent().harvest().result().receipt().receiptId(),r.runId(),r.operationId(),r.batchId(),r.item(),1,r.dropIds()));
        refusesReceipt(e,receipt(r,r.receiptId(),r.runId(),r.operationId(),r.batchId(),ResourceDelivery.BIRCH_LOG,1,r.dropIds()));
        refusesReceipt(e,receipt(r,r.receiptId(),r.runId(),r.operationId(),r.batchId(),r.item(),2,r.dropIds()));
        refusesReceipt(e,receipt(r,r.receiptId(),r.runId(),r.operationId(),r.batchId(),r.item(),1,List.of(UUID.randomUUID())));
    }
    @Test void canonicalSchemaStrictUtf8UnknownFieldsAndRecordQuotaAreEnforced() throws Exception {
        var e=completed(intent(harvest(1)),0);byte[] bytes=encode(e);var row=StrictJson.object(new String(bytes,StandardCharsets.UTF_8));
        row.put("schema",2L);assertThrows(IllegalArgumentException.class,()->decode(StrictJson.canonical(row).getBytes(StandardCharsets.UTF_8)));
        row.put("schema",1L);row.put("unknown",true);assertThrows(IllegalArgumentException.class,()->decode(StrictJson.canonical(row).getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->decode((new String(bytes,StandardCharsets.UTF_8)+"\n").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->decode(new byte[]{(byte)0xc3,(byte)0x28}));
        assertThrows(IllegalArgumentException.class,()->decode(new byte[MAX_RECORD_BYTES+1]));
    }
    @Test void duplicateFramesWrongReferencesAndOverlargeTrailsRefuse() {
        var e=completed(intent(harvest(1)),0);var o=saved(e);var duplicate=new ArrayList<MembershipWitness>(o.memberships());duplicate.set(1,duplicate.getFirst());
        assertEquals(Reconciliation.UNPROVEN,classify(e,members(o,duplicate)));
        var wrong=new ArrayList<MembershipWitness>(o.memberships());var w=wrong.getLast();
        wrong.set(wrong.size()-1,new MembershipWitness(new Member(w.member().role(),UUID.randomUUID(),w.member().chunk()),w.incarnation(),w.revision(),w.marker(),true,false,false,w.frameDigest()));
        assertEquals(Reconciliation.UNPROVEN,classify(e,members(o,wrong)));
        var t=e.intent().actorTrail();var chunks=new ArrayList<Chunk>();for(int n=0;n<9;n++)chunks.add(new Chunk(DIM,n,0));
        assertThrows(IllegalArgumentException.class,()->new Trail(t.entity(),t.incarnation(),chunks,chunks.getFirst()));
        assertThrows(IllegalArgumentException.class,()->new Trail(t.entity(),t.incarnation(),List.of(t.current(),t.current()),t.current()));
        var tooMany=new ArrayList<MembershipWitness>();for(int n=0;n<17;n++)tooMany.add(w);
        assertThrows(IllegalArgumentException.class,()->members(o,tooMany));
        var maximum=chunks.subList(0,MAX_TRAIL_CHUNKS);var original=e.intent();
        var bounded=new Intent(original.key(),original.harvest(),original.operation(),2,original.conditions(),
                new Trail(original.actorTrail().entity(),original.actorTrail().incarnation(),maximum,maximum.getFirst()),
                new Trail(original.dropTrail().entity(),original.dropTrail().incarnation(),maximum,maximum.getFirst()));
        var maximumEntry=completed(bounded,0);
        var verified=new Entry(bounded,maximumEntry.result(),new Verification(Reconciliation.SAVED_COMPLETE,saved(maximumEntry),UUID.randomUUID(),102));
        assertTrue(encode(verified).length<=MAX_RECORD_BYTES);assertEquals(verified,decode(encode(verified)));
    }
    @Test void positiveVerificationCannotRelabelMissingTornOrNoEffectEvidence() {
        var e=completed(intent(harvest(1)),0);var o=saved(e);
        assertThrows(IllegalArgumentException.class,()->new Entry(e.intent(),e.result(),new Verification(Reconciliation.SAVED_COMPLETE,members(o,List.of()),UUID.randomUUID(),100)));
        assertThrows(IllegalArgumentException.class,()->new Verification(Reconciliation.UNPROVEN,o,UUID.randomUUID(),100));
        var noEffect=completed(intent(harvest(1)),1);
        assertThrows(IllegalArgumentException.class,()->new Entry(noEffect.intent(),noEffect.result(),new Verification(Reconciliation.SAVED_COMPLETE,saved(noEffect),UUID.randomUUID(),100)));
        assertArrayEquals(NativeHarvest.encode(e.intent().harvest()),NativeHarvest.encode(decode(encode(e)).intent().harvest()));
    }
}
