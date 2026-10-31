package dev.aivillages.core.kernel.interaction;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.interaction.DialogueSnapshot.*;
import static dev.aivillages.core.kernel.interaction.DialogueTransition.*;

class AssistanceDialogueTest {
    @Test void doneOnlyRequestsReobservationAndUnchangedChestKeepsOriginalRootPending() {
        var f=new DialogueFixtures(); f.ready(); var original=f.state.activity();
        var t=f.send("CHECK_AGAIN"); assertEquals(Phase.REOBSERVE_REQUESTED,t.snapshot().phase());
        var proposal=assertInstanceOf(Reobserve.class,t.proposals().getFirst()); assertEquals("REVALIDATE_ONLY",proposal.signal().body().get("request_kind"));
        assertEquals(original.value(),proposal.signal().body().get("activity"));
        f.observe(1,5); assertEquals(Phase.AWAITING_REPLY,f.state.phase()); assertEquals(original,f.state.activity());
        assertEquals(2,f.missing()); assertEquals(1,f.state.identicalChecks()); assertEquals(3000,f.state.nextCheckMillis());
        assertEquals(OwnerAction.NONE,f.state.pendingOwnerAction());
    }
    @Test void wrongItemKeepsAccurateRemainderAndCannotEarnContinuation() {
        var f=new DialogueFixtures(); f.ready(); f.send("CHECK_AGAIN");
        f.planning.extraStock.add(f.planning.stock("wrong",7,new dev.aivillages.core.kernel.production.RecipeChoice.ItemKey("minecraft:dirt","{}"),27));
        f.observe(1,6); assertEquals(2,f.missing()); assertEquals(Phase.AWAITING_REPLY,f.state.phase()); assertEquals(1,f.state.identicalChecks());
    }
    @Test void partialNativeSupplyUpdatesOnlyTheRemainderOfTheSameQuestion() {
        var f=new DialogueFixtures(); f.ready(); var question=f.state.questionId(); long revision=f.state.dialogueRevision();
        f.send("CHECK_AGAIN"); f.observe(2,6);
        assertEquals(1,f.missing()); assertEquals(question,f.state.questionId()); assertEquals(revision+1,f.state.dialogueRevision());
        assertEquals(f.root,f.state.activity()); assertEquals(0,f.state.identicalChecks());
    }
    @Test void sufficientNativeSupplyStillWaitsForAWithoutASecondResumeSignal() {
        var f=new DialogueFixtures(); f.ready(); var signal=f.send("CHECK_AGAIN"); var request=f.state.pendingObservation();
        assertEquals(1,signal.proposals().size()); var observed=f.observe(3,6);
        assertEquals(Phase.WAITING_OWNER_DECISION,f.state.phase()); assertEquals(OwnerAction.REVALIDATE,f.state.pendingOwnerAction());
        assertEquals(request,f.state.pendingOwnerRequest()); assertTrue(observed.proposals().isEmpty());
        assertTrue(f.send("CHECK_AGAIN").proposals().isEmpty()); assertEquals(f.root,f.state.activity());
    }
    @Test void repeatedUnchangedChecksUseTwoFourEightSecondsThenWaitForNativeChange() {
        var f=new DialogueFixtures(); f.ready();
        for(int i=0;i<3;i++) {
            long started=f.now; f.send("CHECK_AGAIN"); f.observe(1,5);
            assertEquals(started+(2000L<<i),f.state.nextCheckMillis()); assertEquals(i+1,f.state.identicalChecks());
            f.now=f.state.nextCheckMillis();
        }
        assertTrue(f.state.waitingRelevantChange()); assertTrue(f.send("CHECK_AGAIN").proposals().isEmpty());
        var wrong=f.dialogue.relevantChange(f.state,f.actor,f.root,5,f.now); assertTrue(wrong.proposals().isEmpty()); assertTrue(wrong.snapshot().waitingRelevantChange());
        f.set(f.dialogue.relevantChange(f.state,f.actor,f.root,6,f.now)); assertFalse(f.state.waitingRelevantChange());
        assertEquals(Phase.REOBSERVE_REQUESTED,f.send("CHECK_AGAIN").snapshot().phase());
    }
    @Test void earlyCheckDoesNotConsumeAnotherReplyOrScheduleAnotherObservation() {
        var f=new DialogueFixtures(); f.ready(); f.send("CHECK_AGAIN"); f.observe(1,5);
        var before=f.state; assertTrue(f.send("CHECK_AGAIN").proposals().isEmpty()); assertEquals(before.replyRounds(),f.state.replyRounds());
        f.now=2999; assertTrue(f.send("CHECK_AGAIN").proposals().isEmpty()); f.now=3000;
        assertInstanceOf(Reobserve.class,f.send("CHECK_AGAIN").proposals().getFirst());
    }
    @Test void backoffAndQuestionNeverOutliveTheOriginalDeadline() {
        var f=new DialogueFixtures(); f.ready(); f.now=99999; f.send("CHECK_AGAIN"); f.observe(1,5);
        assertEquals(f.root.absoluteDeadlineMillis(),f.state.nextCheckMillis()); assertEquals(100000,f.state.expiresMillis());
        f.now=100000; assertEquals(Phase.EXPIRED,f.send("CHECK_AGAIN").snapshot().phase());
    }
    @Test void eightReplyRoundsAreFiniteAndNeverResetRootBudgetOrDeadline() {
        var f=new DialogueFixtures(); f.ready();
        for(int i=0;i<8;i++) f.send("HELP");
        assertEquals(8,f.state.replyRounds()); assertEquals(f.root,f.state.activity());
        var t=f.send("HELP"); assertEquals(Phase.CLOSED,t.snapshot().phase()); assertEquals(8,f.state.replyRounds()); assertTrue(t.proposals().isEmpty());
    }
    @Test void cancelIsOnlyAProposalUntilTheExactOwnerAcknowledgesIt() {
        var f=new DialogueFixtures(); f.ready(); var t=f.send("CANCEL");
        var p=assertInstanceOf(OwnerDecision.class,t.proposals().getFirst()); assertEquals(OwnerAction.CANCEL,p.action());
        assertEquals(Phase.WAITING_OWNER_DECISION,f.state.phase()); assertEquals(f.root,p.activity());
        assertTrue(f.dialogue.ownerDecision(f.state,f.actor,f.nextId(),f.root,true,f.now).proposals().isEmpty());
        assertEquals(Phase.WAITING_OWNER_DECISION,f.state.phase());
        f.set(f.dialogue.ownerDecision(f.state,f.actor,p.request(),f.root,true,f.now)); assertEquals(Phase.CANCELLED,f.state.phase());
    }
    @Test void nativeOwnerRefusalReturnsToConversationWithoutRepeatingTheProposal() {
        var f=new DialogueFixtures(); f.ready(); f.send("CANCEL"); var request=f.state.pendingOwnerRequest();
        var t=f.set(f.dialogue.ownerDecision(f.state,f.actor,request,f.root,false,f.now));
        assertEquals(Phase.AWAITING_REPLY,f.state.phase()); assertEquals(OwnerAction.NONE,f.state.pendingOwnerAction());
        assertInstanceOf(PreparePrompt.class,t.proposals().getFirst()); assertEquals(f.root,f.state.activity());
    }
    @Test void correctionRequiresExplicitSecondConfirmationThenOwnerApproval() {
        var f=new DialogueFixtures(); f.ready(); var first=f.send("CORRECT_GOAL");
        assertEquals(Phase.CLARIFICATION,f.state.phase()); assertInstanceOf(PreparePrompt.class,first.proposals().getFirst());
        assertEquals(OwnerAction.NONE,f.state.pendingOwnerAction()); var token=f.state.confirmationId();
        assertTrue(f.dialogue.confirmCorrection(f.state,f.actor,f.nextId(),f.nextId(),f.state.responseGeneration(),true,f.now).proposals().isEmpty());
        var t=f.set(f.dialogue.confirmCorrection(f.state,f.actor,f.nextId(),token,f.state.responseGeneration(),true,f.now));
        assertEquals(OwnerAction.AMEND,assertInstanceOf(OwnerDecision.class,t.proposals().getFirst()).action());
        assertEquals(Phase.WAITING_OWNER_DECISION,f.state.phase()); assertEquals(f.root,f.state.activity());
        f.set(f.dialogue.ownerDecision(f.state,f.actor,f.state.pendingOwnerRequest(),f.root,true,f.now));
        assertEquals(Phase.CLOSED,f.state.phase()); assertEquals(f.root,f.state.activity());
    }
    @Test void decliningCorrectionKeepsTheOriginalGoalAndEmitsNoAmendment() {
        var f=new DialogueFixtures(); f.ready(); f.send("CORRECT_GOAL");
        var t=f.set(f.dialogue.confirmCorrection(f.state,f.actor,f.nextId(),f.state.confirmationId(),f.state.responseGeneration(),false,f.now));
        assertEquals(Phase.AWAITING_REPLY,f.state.phase()); assertEquals(f.root,f.state.activity());
        assertTrue(t.proposals().stream().noneMatch(OwnerDecision.class::isInstance));
    }
    @Test void nullChosenTargetCannotTurnAReplyIntoAnAuthorizedEndpoint() {
        var f=new DialogueFixtures(); f.ready(); var t=f.send("CHOOSE_TARGET");
        assertEquals(Phase.AWAITING_REPLY,t.snapshot().phase()); assertTrue(t.proposals().isEmpty()); assertEquals(0,f.state.replyRounds());
    }
    @Test void chosenTargetIsAnUntrustedOwnerProposalAndNeverReplacesTheOriginalRoot() {
        var f=new DialogueFixtures(); f.ready();
        var selection=Map.<String,Object>of("kind","STORAGE","selector",Map.of("kind","NAMED","label","Public Supplies","container_type","CHEST"));
        var t=f.send("CHOOSE_TARGET",selection); var p=assertInstanceOf(OwnerDecision.class,t.proposals().getFirst());
        assertEquals(OwnerAction.CHOOSE_TARGET,p.action()); assertEquals(selection,p.untrustedCandidate().get("selection"));
        assertEquals(f.root,f.state.activity()); assertEquals(Phase.WAITING_OWNER_DECISION,f.state.phase());
    }
    @Test void helpDuringPendingObservationFencesOldPhrasingButDoesNotRequestAnotherRead() {
        var f=new DialogueFixtures(); f.ready(); f.send("CHECK_AGAIN"); var request=f.state.pendingObservation();
        var t=f.send("HELP"); assertInstanceOf(PreparePrompt.class,t.proposals().getFirst());
        assertEquals(request,f.state.pendingObservation()); assertEquals(Phase.REOBSERVE_REQUESTED,f.state.phase());
        f.observe(1,5); assertEquals(Phase.AWAITING_REPLY,f.state.phase());
    }
    @Test void cancellationFencesAnAlreadyPendingNativeObservation() {
        var f=new DialogueFixtures(); f.ready(); f.send("CHECK_AGAIN"); var old=f.state.pendingObservation();
        f.send("CANCEL"); var state=f.state;
        var t=f.dialogue.observation(state,f.actor,old,f.root,state.questionId(),state.dialogueRevision(),f.nextId(),6,new AssistanceFeasibility.Result(true,null,null,0),f.now);
        assertSame(state,t.snapshot()); assertTrue(t.proposals().isEmpty());
    }
    @Test void unavailableNativeAuthorityRefusesRepliesAndModelDelivery() {
        var f=new DialogueFixtures(); f.ready(); f.permitted=false;
        assertTrue(f.send("CHECK_AGAIN").proposals().isEmpty()); assertEquals(0,f.state.replyRounds());
        f.permitted=true; f.send("HELP"); f.permitted=false; assertTrue(f.model().proposals().isEmpty());
    }
    @Test void redactedOrStaleNativeObservationClosesOldQuestionWithoutReexposingItsFacts() {
        for(var code:java.util.List.of(BlockerProjection.Code.UNAUTHORIZED,BlockerProjection.Code.STALE_REFERENCE)) {
            var f=new DialogueFixtures(); f.ready(); f.send("CHECK_AGAIN");
            var blocked=new AssistanceFeasibility.Result(false,BlockerProjection.failure(f.planning.evidence(),code,"A_RUNTIME"),null,0);
            var t=f.set(f.dialogue.observation(f.state,f.actor,f.state.pendingObservation(),f.root,f.state.questionId(),f.state.dialogueRevision(),AssistanceFixtures.id(10),5,blocked,f.now));
            assertEquals(Phase.CLOSED,f.state.phase()); assertTrue(t.proposals().isEmpty()); assertNull(f.state.pendingPrompt());
        }
    }
    @Test void nativeRootOrOwnerChangeClosesOldQuestionWithoutRebasingIt() {
        var f=new DialogueFixtures(); f.ready(); var a=f.root;
        var replacement=new BlockerProjection.ActivityRef(a.world(),a.domain(),a.principal(),f.nextId(),a.originalOwner(),a.attempt(),a.citizen(),a.generation()+1,a.revision(),a.originalBudgetDigest(),a.absoluteDeadlineMillis());
        var t=f.set(f.dialogue.ownerChanged(f.state,f.actor,replacement,f.now));
        assertEquals(Phase.CLOSED,f.state.phase()); assertEquals(a,f.state.activity()); assertTrue(t.proposals().isEmpty());
    }
}
