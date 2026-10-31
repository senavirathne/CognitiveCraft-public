package dev.aivillages.core.kernel.interaction;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.aivillages.core.kernel.interaction.ReferenceClarification.*;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceClarificationTest {
    private final ReferenceClarification rule=new ReferenceClarification();
    private final Query query=new Query(IntentParserTest.ADA,new UUID(0,21),Operation.WITHDRAW,100);
    private final VisibleCandidate chest=new VisibleCandidate(new UUID(0,22),3,"Pantry","CHEST");
    private Observation observation(Status s,List<VisibleCandidate> rows,boolean more,boolean def) { return new Observation(query,s,rows,more,def,102); }
    private Result check(String name,Resolver resolver) { return rule.check(SelectorParserTest.parsed(name),query,resolver); }
    private Clarify clarification(String name,Resolver resolver) { return assertInstanceOf(Clarify.class,check(name,resolver)); }
    @Test void explicitMissingNameNeverFallsBackToNearestOrDefault() {
        AtomicInteger calls=new AtomicInteger();
        var result=clarification("chest \"Missing\"",(q,s)->{calls.incrementAndGet();assertEquals("NAMED",((Map<?,?>)s.get("selector")).get("kind"));return observation(Status.MATCHES,List.of(),false,true);});
        assertEquals(Reason.UNAVAILABLE,result.reason());assertEquals(1,calls.get());
    }
    @Test void twoVisibleCandidatesClarifyInsteadOfChoosingFirst() {
        var other=new VisibleCandidate(new UUID(0,23),1,"PANTRY","CHEST");
        var r=clarification("chest \"Pantry\"",(q,s)->observation(Status.MATCHES,List.of(chest,other),false,false));
        assertEquals(Reason.AMBIGUOUS,r.reason());assertEquals(2,r.choices().size());
    }
    @Test void unsupportedAndDeniedReadsDiscloseNoChoicesOrDefault() {
        for(var status:List.of(Status.DENIED,Status.UNKNOWN,Status.UNSUPPORTED)) {
            var r=clarification("chest \"Pantry\"",(q,s)->observation(status,List.of(chest),false,true));
            assertTrue(r.choices().isEmpty());assertEquals(status==Status.UNSUPPORTED?Reason.UNSUPPORTED:Reason.UNAVAILABLE,r.reason());
        }
    }
    @Test void conflictingNameAndContainerTypeClarifyWithoutChangingIdentity() {
        var r=clarification("barrel \"Pantry\"",(q,s)->observation(Status.MATCHES,List.of(chest),false,false));
        assertEquals(Reason.TYPE_CONFLICT,r.reason());
        assertInstanceOf(CandidateReference.class,check("chest \"Pantry\"",(q,s)->observation(Status.MATCHES,List.of(chest),false,false)));
    }
    @Test void namedWarehouseAliasStillRequiresSupportedRegisteredStorage() {
        assertInstanceOf(CandidateReference.class,check("warehouse \"Pantry\"",(q,s)->observation(Status.MATCHES,List.of(chest),false,false)));
        assertEquals(Reason.UNAVAILABLE,clarification("warehouse \"Castle\"",(q,s)->observation(Status.MATCHES,List.of(),false,false)).reason());
    }
    @Test void omittedSourceRequiresConfirmedConfiguredDefaultInThisQuery() {
        assertEquals(Reason.DEFAULT_UNCONFIRMED,assertInstanceOf(Clarify.class,rule.omittedSource(query,"CHEST",(q,s)->observation(Status.MATCHES,List.of(chest),false,false))).reason());
        var r=assertInstanceOf(CandidateReference.class,rule.omittedSource(query,"CHEST",(q,s)->{assertEquals("DEFAULT",((Map<?,?>)s.get("selector")).get("kind"));return observation(Status.MATCHES,List.of(chest),false,true);}));
        assertEquals(chest.identity(),r.identity());assertEquals(query,r.query());
    }
    @Test void staleExpiredOrCrossPrincipalObservationsCannotBind() {
        var other=new Query(new ConversationContext.SessionKey(query.session().world(),new UUID(0,90),query.session().citizen()),query.request(),query.operation(),100);
        for(var o:List.of(new Observation(other,Status.MATCHES,List.of(chest),false,false,102),new Observation(query,Status.MATCHES,List.of(chest),false,false,99),new Observation(query,Status.MATCHES,List.of(chest),false,false,103))) assertEquals(Reason.STALE,clarification("chest \"Pantry\"",(q,s)->o).reason());
    }
    @Test void currentDepositOnlyUseIsIndependentOfWithdrawOrInspectPermission() {
        var deposit=new Query(query.session(),query.request(),Operation.DEPOSIT,100);
        var r=rule.check(SelectorParserTest.parsed("chest \"Pantry\""),deposit,(q,s)->{assertEquals(Operation.DEPOSIT,q.operation());return new Observation(q,Status.MATCHES,List.of(chest),false,false,100);});
        assertInstanceOf(CandidateReference.class,r);
        assertEquals(Reason.UNAVAILABLE,clarification("chest \"Pantry\"",(q,s)->observation(Status.DENIED,List.of(),false,false)).reason());
    }
    @Test void renameKeepsStableIdentityWhileReplacementPreservesNewGeneration() {
        var renamed=new VisibleCandidate(chest.identity(),chest.generation(),"Meals","CHEST");
        var r=assertInstanceOf(CandidateReference.class,check("chest \"Meals\"",(q,s)->observation(Status.MATCHES,List.of(renamed),false,false)));
        assertEquals(chest.identity(),r.identity());assertEquals(3,r.generation());
        var replaced=new VisibleCandidate(new UUID(0,99),4,"Meals","CHEST");
        var next=assertInstanceOf(CandidateReference.class,check("chest \"Meals\"",(q,s)->observation(Status.MATCHES,List.of(replaced),false,false)));
        assertNotEquals(r.identity(),next.identity());assertEquals(4,next.generation());
        assertEquals(Reason.UNAVAILABLE,clarification("chest \"Pantry\"",(q,s)->observation(Status.MATCHES,List.of(renamed),false,false)).reason());
    }
    @Test void casefoldMatchesMetadataButCannotDisambiguateDuplicateLabels() {
        var c=new VisibleCandidate(chest.identity(),3,"STRASSE","CHEST");
        assertInstanceOf(CandidateReference.class,check("chest \"Straße\"",(q,s)->observation(Status.MATCHES,List.of(c),false,false)));
        assertEquals(Reason.AMBIGUOUS,clarification("chest \"Straße\"",(q,s)->observation(Status.MATCHES,List.of(c),true,false)).reason());
    }
    @Test void citizenSelectorsRequireCitizenMetadataAndNeverBecomeStorage() {
        var bob=new VisibleCandidate(new UUID(0,8),1,"Bob","CITIZEN");
        assertInstanceOf(CandidateReference.class,check("citizen Bob",(q,s)->observation(Status.MATCHES,List.of(bob),false,false)));
        assertEquals(Reason.TYPE_CONFLICT,clarification("citizen Bob",(q,s)->observation(Status.MATCHES,List.of(chest),false,false)).reason());
    }
    @Test void unavailableResolverAndExceptionsFailClosedWithoutLeakingMessages() {
        assertEquals(new Clarify(Reason.UNAVAILABLE,List.of()),check("chest \"Pantry\"",null));
        assertEquals(new Clarify(Reason.UNAVAILABLE,List.of()),check("chest \"Pantry\"",(q,s)->{throw new IllegalStateException("private secret");}));
    }
    @Test void observationAndClarificationChoicesAreImmutableAndBounded() {
        assertThrows(IllegalArgumentException.class,()->observation(Status.MATCHES,List.of(chest,chest,chest),true,false));
        var r=clarification("chest \"Pantry\"",(q,s)->observation(Status.MATCHES,List.of(chest,chest),true,false));
        assertThrows(UnsupportedOperationException.class,()->r.choices().clear());
    }
}
