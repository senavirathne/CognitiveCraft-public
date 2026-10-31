package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.interaction.DialogueSnapshot.*;

class DialogueSnapshotTest {
    @Test void duplicateReplyNeverEmitsASecondRevalidationSignal() {
        var f=new DialogueFixtures(); f.ready(); f.send("CHECK_AGAIN"); var state=f.state;
        var t=f.dialogue.reply(state,f.actor,f.lastReply,f.root,f.lastReplyGeneration,f.lastIntent,f.now);
        assertSame(state,t.snapshot()); assertTrue(t.proposals().isEmpty()); assertEquals(1,state.replyRounds());
        assertTrue(f.send("CHECK_AGAIN").proposals().isEmpty()); assertEquals(1,f.state.replyRounds());
    }
    @Test void oldResponseGenerationAndOldQuestionRevisionCannotScheduleObservation() {
        var f=new DialogueFixtures(); f.ready(); var previous=f.state; var intent=f.intent(previous,"CHECK_AGAIN");
        f.send("HELP");
        assertTrue(f.dialogue.reply(f.state,f.actor,f.nextId(),f.root,previous.responseGeneration(),intent,f.now).proposals().isEmpty());
        f.send("CHECK_AGAIN"); f.observe(1,5); f.now=f.state.nextCheckMillis();
        assertTrue(f.dialogue.reply(f.state,f.actor,f.nextId(),f.root,f.state.responseGeneration(),intent,f.now).proposals().isEmpty());
    }
    @Test void crossPlayerSessionPrincipalAndWorldRepliesAreRefusedAlongsideAValidControl() {
        var f=new DialogueFixtures(); f.ready(); var a=f.actor;
        var impostors=List.of(new AssistanceDialogue.Actor(a.world(),a.domain(),a.principal(),a.citizen(),f.nextId(),a.session()),
                new AssistanceDialogue.Actor(a.world(),a.domain(),a.principal(),a.citizen(),a.player(),f.nextId()),
                new AssistanceDialogue.Actor(a.world(),a.domain(),f.nextId(),a.citizen(),a.player(),a.session()),
                new AssistanceDialogue.Actor(f.nextId(),a.domain(),a.principal(),a.citizen(),a.player(),a.session()));
        for(var actor:impostors) assertTrue(f.dialogue.reply(f.state,actor,f.nextId(),f.root,f.state.responseGeneration(),f.intent(f.state,"CHECK_AGAIN"),f.now).proposals().isEmpty());
        assertEquals(0,f.state.replyRounds()); assertEquals(1,f.send("CHECK_AGAIN").proposals().size());
    }
    @Test void forgedActivityCannotInvalidateTheLegitimateQuestionOrResetItsBudget() {
        var f=new DialogueFixtures(); f.ready(); var a=f.root;
        var forged=new BlockerProjection.ActivityRef(a.world(),a.domain(),a.principal(),f.nextId(),a.originalOwner(),a.attempt(),a.citizen(),a.generation(),a.revision(),"f".repeat(64),a.absoluteDeadlineMillis());
        var t=f.dialogue.reply(f.state,f.actor,f.nextId(),forged,f.state.responseGeneration(),f.intent(f.state,"CHECK_AGAIN"),f.now);
        assertSame(f.state,t.snapshot()); assertTrue(t.proposals().isEmpty()); assertEquals(a,f.state.activity());
    }
    @Test void expiryAtTheExactDeadlineSuppressesAllFurtherRepliesAndModelCallbacks() {
        var f=new DialogueFixtures(); f.ready(); f.send("HELP"); var message=f.state.pendingPrompt(); long generation=f.state.responseGeneration(); f.now=f.state.expiresMillis();
        var t=f.send("CHECK_AGAIN"); assertEquals(Phase.EXPIRED,f.state.phase()); assertTrue(t.proposals().isEmpty());
        assertTrue(f.dialogue.modelReady(f.state,f.actor,message,generation,f.now).proposals().isEmpty());
    }
    @Test void duplicateAndLateModelResultsCanOnlyProposeOneDelivery() {
        var f=new DialogueFixtures(); f.begin(); var message=f.state.pendingPrompt(); long generation=f.state.responseGeneration();
        assertEquals(1,f.model().proposals().size()); assertTrue(f.model().proposals().isEmpty()); f.delivered();
        assertEquals(List.of(message),f.state.deliveredMessageIds());
        assertTrue(f.dialogue.modelReady(f.state,f.actor,message,generation,f.now).proposals().isEmpty());
        f.send("HELP"); assertTrue(f.dialogue.modelReady(f.state,f.actor,message,generation,f.now).proposals().isEmpty());
        assertEquals(1,f.model().proposals().size());
    }
    @Test void duplicateDeliveryReceiptDoesNotAppendAnotherDeliveredMessage() {
        var f=new DialogueFixtures(); f.begin(); var message=f.state.pendingPrompt(); long generation=f.state.responseGeneration(); f.model(); f.delivered();
        var t=f.dialogue.delivered(f.state,f.actor,message,generation,f.now);
        assertSame(f.state,t.snapshot()); assertEquals(1,t.snapshot().deliveredMessageIds().size()); assertTrue(t.proposals().isEmpty());
    }
    @Test void snapshotRoundTripPreservesEveryOriginalCorrelationButAlwaysLocksRestore() {
        var f=new DialogueFixtures(); f.ready(); f.send("CHECK_AGAIN");
        var restored=DialogueSnapshot.restore(f.state.encode());
        assertEquals(f.state.encode(),restored.encode()); assertEquals(f.state.digest(),restored.digest()); assertEquals(f.root,restored.activity());
        assertEquals(f.state.pendingObservation(),restored.pendingObservation()); assertTrue(restored.needsOwnerRevalidation());
    }
    @Test void restoredDialogueCannotReplayPromptOrAcceptRepliesUntilNativeRevalidation() {
        var f=new DialogueFixtures(); f.ready(); f.send("HELP"); f.state=DialogueSnapshot.restore(f.state.encode());
        assertTrue(f.model().proposals().isEmpty()); assertTrue(f.send("CHECK_AGAIN").proposals().isEmpty()); assertTrue(f.begin().proposals().isEmpty());
        assertTrue(f.state.needsOwnerRevalidation());
    }
    @Test void exactNativeRestoreReceiptFencesOldCallbacksAndEmitsNoPromptOrResume() {
        var f=new DialogueFixtures(); f.ready(); f.send("HELP"); var oldMessage=f.state.pendingPrompt(); long oldGeneration=f.state.responseGeneration();
        f.state=DialogueSnapshot.restore(f.state.encode()); var original=f.state;
        var t=f.set(f.dialogue.revalidateRestored(original,f.actor,f.root,original.questionId(),original.dialogueRevision(),original.digest(),true,f.now));
        assertFalse(f.state.needsOwnerRevalidation()); assertTrue(t.proposals().isEmpty()); assertNull(f.state.pendingPrompt());
        assertEquals(original.replyRounds(),f.state.replyRounds()); assertEquals(original.deliveredMessageIds(),f.state.deliveredMessageIds());
        assertTrue(f.state.responseGeneration()>oldGeneration); assertEquals(original.dialogueRevision()+1,f.state.dialogueRevision());
        assertTrue(f.dialogue.modelReady(f.state,f.actor,oldMessage,oldGeneration,f.now).proposals().isEmpty());
    }
    @Test void forgedRestoreDigestOrUnavailableNativeAdapterCannotUnlockTheSnapshot() {
        var f=new DialogueFixtures(); f.ready(); f.state=DialogueSnapshot.restore(f.state.encode()); var s=f.state;
        assertSame(s,f.dialogue.revalidateRestored(s,f.actor,f.root,s.questionId(),s.dialogueRevision(),"0".repeat(64),true,f.now).snapshot());
        f.permitted=false;
        assertSame(s,f.dialogue.revalidateRestored(s,f.actor,f.root,s.questionId(),s.dialogueRevision(),s.digest(),true,f.now).snapshot());
    }
    @Test void restoredClosedQuestionStaysTerminalAndChangedOwnerClosesTheOldIdentity() {
        var f=new DialogueFixtures(); f.ready(); f.state=DialogueSnapshot.restore(f.state.encode()); var s=f.state; var a=f.root;
        var current=new BlockerProjection.ActivityRef(a.world(),a.domain(),a.principal(),f.nextId(),a.originalOwner(),a.attempt(),a.citizen(),a.generation()+1,a.revision(),a.originalBudgetDigest(),a.absoluteDeadlineMillis());
        f.set(f.dialogue.revalidateRestored(s,f.actor,current,s.questionId(),s.dialogueRevision(),s.digest(),true,f.now));
        assertEquals(Phase.CLOSED,f.state.phase()); assertEquals(a,f.state.activity()); assertTrue(f.send("HELP").proposals().isEmpty());
    }
    @Test void unknownSnapshotFieldsInconsistentLedgersAndForgedTrustedFlagsAreRejected() {
        var f=new DialogueFixtures(); f.ready();
        for(var field:List.of("trusted","job_id","credit","needs_owner_revalidation")) {
            var m=new LinkedHashMap<>(DialogueSnapshot.object(InteractionCodec.parseJson(f.state.encode()))); m.put(field,true);
            assertThrows(IllegalArgumentException.class,()->DialogueSnapshot.restore(InteractionCodec.canonical(m)));
        }
        var m=new LinkedHashMap<>(DialogueSnapshot.object(InteractionCodec.parseJson(f.state.encode()))); m.put("reply_rounds",9L);
        assertThrows(IllegalArgumentException.class,()->DialogueSnapshot.restore(InteractionCodec.canonical(m)));
        m.put("reply_rounds",0L); m.put("delivered",List.of(f.nextId().toString(),f.nextId().toString())); m.put("phase","REOBSERVE_REQUESTED");
        assertThrows(IllegalArgumentException.class,()->DialogueSnapshot.restore(InteractionCodec.canonical(m)));
    }
    @Test void duplicateSnapshotLedgerIdsAndExcessiveInputAreRejected() {
        var f=new DialogueFixtures(); f.ready(); var message=f.nextId().toString();
        var m=new LinkedHashMap<>(DialogueSnapshot.object(InteractionCodec.parseJson(f.state.encode()))); m.put("delivered",List.of(message,message));
        assertThrows(IllegalArgumentException.class,()->DialogueSnapshot.restore(InteractionCodec.canonical(m)));
        assertThrows(IllegalArgumentException.class,()->DialogueSnapshot.restore(" ".repeat(MAX_BYTES+1)));
    }
    @Test void clockRollbackCannotAdmitAReplyOrUnlockBackoff() {
        var f=new DialogueFixtures(); f.ready(); f.send("CHECK_AGAIN"); f.observe(1,5); f.now=999;
        var t=f.send("HELP"); assertTrue(t.proposals().isEmpty()); assertEquals("CLOCK_ROLLBACK",t.reason());
    }
    @Test void staleObservationCorrelationAndMismatchedEvidenceCannotScheduleAnotherResume() {
        var f=new DialogueFixtures(); f.ready(); f.send("CHECK_AGAIN"); var s=f.state;
        var feasible=new AssistanceFeasibility.Result(true,null,null,0);
        assertSame(s,f.dialogue.observation(s,f.actor,f.nextId(),f.root,s.questionId(),s.dialogueRevision(),f.nextId(),6,feasible,f.now).snapshot());
        assertSame(s,f.dialogue.observation(s,f.actor,s.pendingObservation(),f.root,s.questionId(),s.dialogueRevision()-1,f.nextId(),6,feasible,f.now).snapshot());
        var material=f.planning.assess();
        assertSame(s,f.dialogue.observation(s,f.actor,s.pendingObservation(),f.root,s.questionId(),s.dialogueRevision(),f.nextId(),5,material,f.now).snapshot());
    }
}
