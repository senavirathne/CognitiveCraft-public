package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.StrictJson;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic contract fixtures; these are never Minecraft effects or save witnesses. */
final class NativeDepositTest {
    static final UUID WORLD=UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final String LOG="minecraft:oak_log";
    static final TrustedContext OWNER=new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(WORLD,UUID.randomUUID()));
    static Participant actor(int count,long revision) {
        var slots=new ArrayList<>(Collections.nCopies(8,new Slot("minecraft:air",0)));slots.set(0,new Slot(count==0?"minecraft:air":LOG,count));
        return new Participant(Kind.ACTOR,"minecraft:overworld","20000000-0000-0000-0000-000000000001",UUID.fromString("30000000-0000-0000-0000-000000000001"),revision,slots);
    }
    static Participant chest(int count,long revision) {
        var slots=new ArrayList<>(Collections.nCopies(27,new Slot("minecraft:air",0)));slots.set(0,new Slot(count==0?"minecraft:air":LOG,count));
        return new Participant(Kind.CHEST,"minecraft:overworld","10,64,10",UUID.fromString("40000000-0000-0000-0000-000000000001"),revision,slots);
    }
    static Conditions conditions() {return new Conditions(UUID.randomUUID(),5,10_000,8,100,2500,1000,List.of(new Claim(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1)),Map.of(dev.aivillages.core.kernel.Budgets.Kind.COMMITTED_EFFECTS,2L));}
    static Intent intent() {return new Intent(new Key(WORLD,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1),OWNER,UUID.randomUUID(),UUID.randomUUID(),actor(1,7),chest(5,11),LOG,0,0,64,conditions());}
    static Entry completed(Intent intent) {return new Entry(intent,observed(intent,actor(0,8),chest(6,12),""));}
    static Marker marker(Entry entry,MarkerPhase phase) {return new Marker(entry.token(),entry.intent().digest(),phase,phase==MarkerPhase.RESULT?entry.result().digest():"");}

    @Test void exactOriginalIdentityAndClosedCanonicalRecordRoundTrip() {
        Entry entry=completed(intent());assertEquals(entry,decode(encode(entry)));
        assertEquals(entry.token(),decode(encode(entry)).intent().key().token());
        Marker marker=marker(entry,MarkerPhase.RESULT);assertEquals(marker,markerJson(markerJson(marker)));
    }
    @Test void liveMutationAndJournalDataNeverSupplySavedEvidence() {
        Entry entry=completed(intent());Marker marker=marker(entry,MarkerPhase.RESULT);
        assertEquals(Reconciliation.UNPROVEN,classify(entry,new Witness(entry.result().actor(),marker,false),new Witness(entry.result().chest(),marker,false)));
        assertEquals(Reconciliation.SAVED_COMPLETE,classify(entry,new Witness(entry.result().actor(),marker,true),new Witness(entry.result().chest(),marker,true)));
    }
    @Test void independentParticipantPhasesDetectTheActualTornCombination() {
        Entry entry=completed(intent());Marker before=marker(entry,MarkerPhase.PREPARED),after=marker(entry,MarkerPhase.RESULT);
        assertEquals(Reconciliation.TORN_PARTICIPANTS,classify(entry,new Witness(entry.result().actor(),after,true),new Witness(entry.intent().chest(),before,true)));
        assertEquals(Reconciliation.JOURNAL_AHEAD_OF_PARTICIPANTS,classify(entry,new Witness(entry.intent().actor(),before,true),new Witness(entry.intent().chest(),before,true)));
    }
    @Test void preparedNoEffectRequiresBothOriginalSavedParticipants() {
        Entry entry=new Entry(intent(),null);Marker marker=marker(entry,MarkerPhase.PREPARED);
        assertEquals(Reconciliation.PREPARED_NO_EFFECT,classify(entry,new Witness(entry.intent().actor(),marker,true),new Witness(entry.intent().chest(),marker,true)));
        assertEquals(Reconciliation.WAIT_WORLD,classify(entry,null,new Witness(entry.intent().chest(),marker,true)));
        assertEquals(Reconciliation.UNPROVEN,classify(entry,new Witness(entry.intent().actor(),null,true),new Witness(entry.intent().chest(),marker,true)));
    }
    @Test void equalCountsAfterAnotherMutationDoNotRecreateCausalEvidence() {
        Entry entry=completed(intent());Marker marker=marker(entry,MarkerPhase.RESULT);
        Participant original=entry.result().chest();Participant swapped=new Participant(original.kind(),original.dimension(),original.address(),original.incarnation(),original.revision()+1,original.slots());
        assertEquals(original.slots(),swapped.slots());
        assertEquals(Reconciliation.UNPROVEN,classify(entry,new Witness(entry.result().actor(),marker,true),new Witness(swapped,marker,true)));
    }
    @Test void sameCoordinateReplacementAndWrongEffectMarkersCannotSettle() {
        Entry entry=completed(intent());Marker marker=marker(entry,MarkerPhase.RESULT);Participant old=entry.result().chest();
        Participant replacement=new Participant(old.kind(),old.dimension(),old.address(),UUID.randomUUID(),old.revision(),old.slots());
        assertEquals(Reconciliation.UNPROVEN,classify(entry,new Witness(entry.result().actor(),marker,true),new Witness(replacement,marker,true)));
        Marker foreign=new Marker(intent().key().token(),marker.intent(),MarkerPhase.RESULT,marker.result());
        assertEquals(Reconciliation.UNPROVEN,classify(entry,new Witness(entry.result().actor(),foreign,true),new Witness(old,marker,true)));
    }
    @Test void completePartialObservationStaysVisibleWithoutInventingACompletedDeposit() {
        Intent intent=intent();Entry partial=new Entry(intent,observed(intent,actor(0,8),intent.chest(),"exception after actor removal"));
        assertEquals(Live.PARTIAL,partial.result().live());Marker marker=marker(partial,MarkerPhase.RESULT);
        assertEquals(Reconciliation.PARTIAL_UNRESOLVED,classify(partial,new Witness(partial.result().actor(),marker,true),new Witness(partial.result().chest(),marker,true)));
        assertThrows(IllegalArgumentException.class,()->new Entry(intent,new Result(intent.digest(),Live.COMPLETE,actor(0,8),intent.chest(),"")));
    }
    @Test void noEffectNeedsUnchangedParticipantsAndItsOwnSavedResult() {
        Intent intent=intent();Entry entry=new Entry(intent,observed(intent,intent.actor(),intent.chest(),"capacity changed before native call"));
        assertEquals(Live.NO_EFFECT,entry.result().live());Marker marker=marker(entry,MarkerPhase.RESULT);
        assertEquals(Reconciliation.SAVED_NO_EFFECT,classify(entry,new Witness(intent.actor(),marker,true),new Witness(intent.chest(),marker,true)));
    }
    @Test void boundedSchemaRejectsExtraFieldsWrongWorldNoncanonicalAndMalformedUtf8() throws Exception {
        Entry entry=completed(intent());byte[] raw=encode(entry);var row=StrictJson.object(new String(raw,StandardCharsets.UTF_8));row.put("trusted",true);
        assertThrows(IllegalArgumentException.class,()->decode(StrictJson.canonical(row).getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->decode((" "+new String(raw,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)));
        byte[] broken=raw.clone();broken[1]=(byte)0xc3;broken[2]=0x28;assertThrows(IllegalArgumentException.class,()->decode(broken));
        Intent i=entry.intent();Key wrong=new Key(UUID.randomUUID(),i.key().root(),i.key().step(),i.key().attempt(),1,1);
        assertThrows(IllegalArgumentException.class,()->new Intent(wrong,i.origin(),i.run(),i.operation(),i.actor(),i.chest(),LOG,0,0,64,i.conditions()));
        assertThrows(IllegalArgumentException.class,()->new Key(WORLD,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),0,1));
        assertThrows(IllegalArgumentException.class,()->new Key(WORLD,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,65));
    }
    @Test void savedVerificationPersistsBothActualWitnessesAndCannotPromoteTornData() {
        Entry original=completed(intent());Marker marker=marker(original,MarkerPhase.RESULT);
        var proof=new Verification(Reconciliation.SAVED_COMPLETE,original.result().actor(),original.result().chest(),marker,marker,UUID.randomUUID(),100);
        Entry verified=new Entry(original.intent(),original.result(),proof);assertEquals(verified,decode(encode(verified)));
        var torn=new Verification(Reconciliation.SAVED_COMPLETE,original.intent().actor(),original.intent().chest(),marker,marker,UUID.randomUUID(),100);
        assertThrows(IllegalArgumentException.class,()->new Entry(original.intent(),original.result(),torn));
    }
}
