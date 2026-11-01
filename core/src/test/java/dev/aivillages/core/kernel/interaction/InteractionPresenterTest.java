package dev.aivillages.core.kernel.interaction;

import org.junit.jupiter.api.Test;
import dev.aivillages.core.kernel.interaction.StatusProjection.State;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.aivillages.core.kernel.interaction.StatusProjection.*;
import static dev.aivillages.core.kernel.interaction.InteractionPresenter.*;
import static org.junit.jupiter.api.Assertions.*;

class InteractionPresenterTest {
    final InteractionPresenter presenter=new InteractionPresenter();
    final AssistanceFixtures facts=new AssistanceFixtures();
    final ConversationContext.SessionKey key=new ConversationContext.SessionKey(facts.world,facts.context.principal().id(),facts.actor.citizenId());
    final DialogueSnapshot.Audience audience=new DialogueSnapshot.Audience(key.principal(),new UUID(0,31));
    Request request(State state) {return request(state,1,1000,false);}
    Request request(State state,long generation,long now,boolean details) {return new Request(key,audience,new UUID(0,32),new UUID(0,33),facts.reference(),state,1,generation,now,now+10000,details);}
    Authority authority(Request r,boolean visible,boolean terminal) {return new Authority() {
        public Evidence read(Request ignored) {return new Evidence(r,visible,r.nowMillis(),List.of(new Material("oak planks",2)),"Ada's chest",new TerminalReference(r.activity(),new UUID(0,34)));}
        public boolean terminalAuthenticated(Request wanted,TerminalReference receipt) {return terminal;}
    };}
    View view(State state) {var r=request(state);return presenter.present(StatusProjection.project(r,authority(r,true,false)),null);}
    Callback callback(View v,Action action,long n) {return new Callback(new UUID(5,n),v.messageId(),v.audience(),v.generation(),action);}
    Transition click(InteractionPresenter.State s,Action a,long n) {return presenter.callback(s,callback(s.view(),a,n),s.view().audience(),1000,r->true);}

    @Test void waitingCheckingDeniedAndVerifiedCompletionHaveDistinctStatusesAndText() {
        assertEquals(State.WAITING,view(State.WAITING).status());assertEquals(State.CHECKING,view(State.CHECKING).status());
        assertEquals(State.DENIED,view(State.DENIED).status());var r=request(State.COMPLETED);
        assertEquals(State.COMPLETED,presenter.present(StatusProjection.project(r,authority(r,true,true)),null).status());
        assertNotEquals(view(State.WAITING).text(),view(State.CHECKING).text());
    }
    @Test void authorizedWaitingShowsExactQuantityAndVisibleChest() {assertEquals("Waiting for 2 oak planks in Ada's chest.",view(State.WAITING).text());}
    @Test void deniedProjectionDoesNotConfirmPrivateChestRootOrQuestion() {
        var r=request(State.WAITING);var v=presenter.present(StatusProjection.project(r,authority(r,false,true)),null);
        assertEquals("I cannot share details for this activity.",v.text());assertNull(v.value().get("activity"));assertNull(v.value().get("question_id"));assertTrue(v.choices().isEmpty());
    }
    @Test void crossPrincipalWorldAndCitizenReferencesCannotDiscloseEvenWithAnAllowingPort() {
        for(var bad:List.of(new ConversationContext.SessionKey(new UUID(0,99),key.principal(),key.citizen()),new ConversationContext.SessionKey(key.world(),new UUID(0,99),key.citizen()),new ConversationContext.SessionKey(key.world(),key.principal(),new UUID(0,99)))) {
            var r=new Request(bad,audience,new UUID(0,32),new UUID(0,33),facts.reference(),State.WAITING,1,1,1000,10000,false);
            assertEquals(State.DENIED,StatusProjection.project(r,authority(r,true,true)).state());
        }
    }
    @Test void aClaimOrDefaultTerminalVerifierCannotProduceCompletion() {
        var r=request(State.COMPLETED);assertEquals(State.CHECKING,StatusProjection.project(r,authority(r,true,false)).state());
        Authority noVerifier=wanted->new Evidence(wanted,true,1000,List.of(),null,new TerminalReference(wanted.activity(),new UUID(0,42)));
        assertEquals(State.CHECKING,StatusProjection.project(r,noVerifier).state());
    }
    @Test void exactAAuthenticatedTerminalReferenceCanProduceDefinitiveCompletion() {
        var r=request(State.COMPLETED);var v=presenter.present(StatusProjection.project(r,authority(r,true,true)),null);
        assertEquals("Completed. The activity owner verified the result.",v.text());assertTrue(v.choices().isEmpty());
    }
    @Test void aSwappedTerminalRootOrBudgetCannotBeAuthenticatedByAnUnrelatedReceipt() {
        var r=request(State.COMPLETED);var other=new AssistanceFixtures();other.used=java.util.Map.of();
        var ref=r.activity();var wrong=new BlockerProjection.ActivityRef(ref.world(),ref.domain(),ref.principal(),new UUID(0,99),ref.originalOwner(),ref.attempt(),ref.citizen(),ref.generation(),ref.revision(),"a".repeat(64),ref.absoluteDeadlineMillis());
        Authority a=new Authority(){public Evidence read(Request wanted){return new Evidence(wanted,true,1000,List.of(),null,new TerminalReference(wrong,new UUID(0,44)));}public boolean terminalAuthenticated(Request wanted,TerminalReference t){return true;}};
        assertEquals(State.CHECKING,StatusProjection.project(r,a).state());
    }
    @Test void lateAuthenticatedSettlementCanDisplayWhileAnExpiredWaitCannotContinue() {
        var r=request(State.COMPLETED,1,100001,false);assertEquals(State.COMPLETED,StatusProjection.project(r,authority(r,true,true)).state());
        var wait=request(State.WAITING,1,100001,false);assertEquals(State.EXPIRED,StatusProjection.project(wait,authority(wait,true,false)).state());
    }
    @Test void staleFutureAndMismatchedEvidenceBecomesUnknownOrDenied() {
        var r=request(State.WAITING,1,5000,false);
        for(long at:List.of(2999L,5001L))assertEquals(State.UNKNOWN,StatusProjection.project(r,wanted->new Evidence(wanted,true,at,List.of(new Material("oak planks",2)),"Ada's chest",null)).state());
        assertEquals(State.DENIED,StatusProjection.project(r,authority(request(State.WAITING),true,false)).state());
    }
    @Test void falseDoneShowsCheckingWithoutCreditingAnyActivity() {var v=view(State.CHECKING);assertTrue(v.text().startsWith("I can check again."));assertFalse(v.text().contains("Completed"));}
    @Test void accidentalUuidInLabelsIsRedactedAndPrivateRawIdsNeverAppearInText() {
        var r=request(State.WAITING);var p=StatusProjection.project(r,wanted->new Evidence(wanted,true,1000,List.of(new Material("oak planks",2)),"Chest "+facts.root,null));
        var v=presenter.present(p,null);assertEquals("Waiting for 2 oak planks in authorized storage.",v.text());assertFalse(v.accessibleText().contains(facts.root.toString()));
        assertThrows(IllegalArgumentException.class,()->new Material(facts.root.toString(),2));
    }
    @Test void detailsRequireAnExplicitRequestAndAuthorizedEvidence() {
        var r=request(State.WAITING,1,1000,true);assertTrue(presenter.present(StatusProjection.project(r,authority(r,true,false)),null).text().contains(facts.root.toString()));
        assertFalse(presenter.present(StatusProjection.project(r,authority(r,false,false)),null).text().contains(facts.root.toString()));
    }
    @Test void modelTextCannotInventCommandsNumbersPrivateLabelsOrCompletion() {
        var r=request(State.CHAT);var p=StatusProjection.project(r,authority(r,true,false));
        for(String bad:List.of("/op player","Completed 99 jobs","Private vault","Waiting for 65 planks"))assertEquals("Hello.",presenter.present(p,bad).text());
        assertEquals("Hi there. How can I help?",presenter.present(p,"Hi there. How can I help?").text());
    }
    @Test void localeKeyAndEnglishFallbackAreStablePlatformNeutralValues() {var v=view(State.WAITING);assertEquals("interaction.waiting",v.value().get("locale_key"));assertEquals("en",v.value().get("language"));}
    @Test void accessibleTextRepeatsStatusAndEveryChoiceWithoutColorOrClickDependence() {
        var v=view(State.WAITING);assertTrue(v.accessibleText().startsWith(v.text()));for(var c:v.choices())assertTrue(v.accessibleText().contains(c.label()));
        assertFalse(v.encode().contains("run_command"));assertFalse(v.encode().contains("clickEvent"));
    }
    @Test void callbacksAreTypedProposalsAndExposeNoExecutableModelString() {
        var v=view(State.WAITING);for(var choice:v.choices()){var t=click(InteractionPresenter.State.initial(v),choice.action(),choice.action().ordinal()+1);assertEquals(Disposition.PROPOSED,t.disposition());assertEquals(choice.action(),t.proposal().action());assertEquals(facts.reference(),t.proposal().activity());}
    }
    @Test void duplicateCallbackEventDoesNotProduceASecondProposal() {
        var s=InteractionPresenter.State.initial(view(State.WAITING));var first=click(s,Action.CHECK_AGAIN,1);var next=click(first.next(),Action.CHECK_AGAIN,1);
        assertEquals(Disposition.DUPLICATE,next.disposition());assertNull(next.proposal());assertEquals(first.next(),next.next());
    }
    @Test void repeatedChoiceWithAnotherEventIdStillDoesNotProduceASecondProposal() {var first=click(InteractionPresenter.State.initial(view(State.WAITING)),Action.CANCEL,1);assertEquals(Disposition.DUPLICATE,click(first.next(),Action.CANCEL,2).disposition());}
    @Test void anotherPrincipalOrSessionCannotUseAVisibleCallback() {
        var v=view(State.WAITING);var s=InteractionPresenter.State.initial(v);
        for(var a:List.of(new DialogueSnapshot.Audience(new UUID(0,99),audience.session()),new DialogueSnapshot.Audience(audience.player(),new UUID(0,99))))assertEquals(Disposition.STALE,presenter.callback(s,callback(v,Action.CANCEL,1),a,1000,r->true).disposition());
    }
    @Test void oldGenerationAndMessageIdentityAreFenced() {
        var v=view(State.WAITING);var s=InteractionPresenter.State.initial(v);
        for(var event:List.of(new Callback(new UUID(5,1),v.messageId(),audience,0,Action.CANCEL),new Callback(new UUID(5,1),new UUID(0,99),audience,1,Action.CANCEL)))assertEquals(Disposition.STALE,presenter.callback(s,event,audience,1000,r->true).disposition());
    }
    @Test void expiryAndClockRollbackSuppressCallbacksWithAnExactBoundaryControl() {
        var v=view(State.WAITING);var s=InteractionPresenter.State.initial(v);var e=callback(v,Action.CANCEL,1);
        assertEquals(Disposition.EXPIRED,presenter.callback(s,e,audience,999,r->true).disposition());assertEquals(Disposition.EXPIRED,presenter.callback(s,e,audience,11000,r->true).disposition());assertEquals(Disposition.PROPOSED,presenter.callback(s,e,audience,10999,r->true).disposition());
    }
    @Test void missingRevokedOrThrowingAuthorityCannotDeliverCallbacks() {
        var v=view(State.WAITING);var s=InteractionPresenter.State.initial(v);var e=callback(v,Action.CANCEL,1);
        assertEquals(Disposition.DENIED,presenter.callback(s,e,audience,1000,null).disposition());assertEquals(Disposition.DENIED,presenter.callback(s,e,audience,1000,r->false).disposition());assertEquals(Disposition.DENIED,presenter.callback(s,e,audience,1000,r->{throw new IllegalStateException("secret");}).disposition());
    }
    @Test void rejoinRetainsOutputIdentityButFencesTheOldSessionAndAllowsACurrentControl() {
        var old=view(State.WAITING);var s=InteractionPresenter.State.initial(old);var nextAudience=new DialogueSnapshot.Audience(audience.player(),new UUID(0,90));var r=new Request(key,nextAudience,old.messageId(),request(State.WAITING).question(),facts.reference(),State.WAITING,1,2,1000,11000,false);var next=presenter.present(StatusProjection.project(r,authority(r,true,false)),null);var rebound=presenter.rejoin(s,next);
        assertEquals(old.messageId(),next.messageId());assertEquals(old.text(),next.text());assertEquals(Disposition.STALE,presenter.callback(rebound,callback(old,Action.CANCEL,1),nextAudience,1000,x->true).disposition());assertEquals(Disposition.PROPOSED,click(rebound,Action.CANCEL,2).disposition());
    }
    @Test void rejoinCannotChangeRootCallerQuestionOrReuseAnOldGeneration() {
        var s=InteractionPresenter.State.initial(view(State.WAITING));assertThrows(IllegalArgumentException.class,()->presenter.rejoin(s,s.view()));
        var r=request(State.WAITING,2,1000,false);var bad=new Request(key,audience,r.message(),new UUID(0,88),r.activity(),State.WAITING,1,2,1000,11000,false);var v=presenter.present(StatusProjection.project(bad,authority(bad,true,false)),null);assertThrows(IllegalArgumentException.class,()->presenter.rejoin(s,v));
    }
    @Test void forgetIsOnlyAProposalAndCannotEraseRootBudgetOrQuestion() {var s=InteractionPresenter.State.initial(view(State.WAITING));var t=click(s,Action.FORGET,1);assertEquals(facts.reference(),t.proposal().activity());assertEquals(s.view(),t.next().view());assertEquals(s.view().value().get("question_id"),t.next().view().value().get("question_id"));}
    @Test void startAndCorrectionNeedConfirmationAndCancellationWaitsForItsOwner() {
        assertTrue(click(InteractionPresenter.State.initial(view(State.CHAT)),Action.START,1).proposal().confirmationRequired());assertTrue(click(InteractionPresenter.State.initial(view(State.WAITING)),Action.CORRECT,1).proposal().confirmationRequired());assertFalse(view(State.CANCEL_PENDING).text().startsWith("Completed"));assertTrue(view(State.CANCEL_PENDING).text().contains("Waiting"));
    }
    @Test void viewChoicesAndReplayLedgersAreImmutableAndBounded() {
        var s=InteractionPresenter.State.initial(view(State.WAITING));assertThrows(UnsupportedOperationException.class,()->s.view().choices().clear());assertThrows(UnsupportedOperationException.class,()->s.events().clear());assertThrows(UnsupportedOperationException.class,()->s.view().value().clear());
        assertThrows(IllegalArgumentException.class,()->new Request(key,audience,new UUID(0,1),null,null,State.CHAT,0,1,1000,11000,false));assertThrows(IllegalArgumentException.class,()->new Material("oak planks",65));
    }
}
