package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Mandates.*;
import static org.junit.jupiter.api.Assertions.*;

class MandateRegistryTest {
    static final UUID WORLD = new UUID(0,1);
    static final String DIM = "minecraft:overworld";
    static final ActorRef ACTOR = actor(100);
    static final TrustedContext OWNER = context(10), COORDINATOR = context(11), CHILD = context(12), FOREIGN = context(13);
    static final Cuboid AREA = new Cuboid(DIM,0,63,0,5,65,2), SOURCE = new Cuboid(DIM,0,64,0,3,64,0);
    static final ContainerRef DEST = new ContainerRef(DIM,5,64,0);
    static final ArtifactRef ARTIFACT = new ArtifactRef(CropDelivery.ID,"a".repeat(64));
    static TrustedContext context(int n) { return new TrustedContext(new PrincipalRef(new UUID(0,n)),new ScopeRef(WORLD,new UUID(1,n))); }
    static ActorRef actor(int n) { return new ActorRef(new UUID(2,n),new UUID(3,n),DIM); }
    static Rights rights() { return new Rights(Set.of(ACTOR),EnumSet.allOf(Effect.class),Set.of("minecraft:wheat"),AREA,Set.of(DEST)); }
    static Budgets.Limits budget(long count,long deadline) {
        var values=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
        for(var kind:List.of(Budgets.Kind.CALLS,Budgets.Kind.INSTRUCTIONS,Budgets.Kind.OBSERVATIONS,
                Budgets.Kind.TRAVEL_BLOCKS,Budgets.Kind.ATTEMPTED_EFFECTS,Budgets.Kind.COMMITTED_EFFECTS,Budgets.Kind.ELAPSED_TICKS)) values.put(kind,count);
        return new Budgets.Limits(values,deadline);
    }
    static ValidatedRequest request(TrustedContext caller) {
        return new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(ACTOR),"source",new AreaValue(SOURCE),
                "destination",new ContainerValue(DEST),"amount",new IntValue(1))),caller,new ObservationRef(new UUID(4,1),1,DIM));
    }
    static class Memory implements MandateRegistry.Storage {
        Snapshot state=Snapshot.empty(WORLD), next;
        CompletableFuture<Snapshot> pending;
        boolean hold;
        public CompletionStage<Snapshot> replace(Snapshot expected,Snapshot proposed) {
            assertEquals(state,expected);next=proposed;
            if(hold){pending=new CompletableFuture<>();return pending;}
            state=proposed;return CompletableFuture.completedFuture(proposed);
        }
        void release() { state=next;pending.complete(state);hold=false; }
    }
    static class Scene implements MandateRegistry.References,MandateRegistry.Executions {
        final JobLifecycleStoreTest.TestClock clock=new JobLifecycleStoreTest.TestClock();
        final Memory memory=new Memory();
        final Map<UUID,MandateRegistry.Assignment> assignments=new HashMap<>();
        final Set<TrustedContext> unavailable=new HashSet<>();
        final List<Binding> stopped=new ArrayList<>();
        MandateRegistry registry;
        boolean ownerAllows=true,current=true;
        int ownerChecks;
        Scene(){registry=restore(memory.state);}
        MandateRegistry restore(Snapshot state) { return new MandateRegistry(state,memory,this,this,clock,Limits.defaults(),false); }
        public boolean authority(ActorRef actor,Effect effect,TrustedContext issuer){ownerChecks++;return ownerAllows&&issuer.equals(OWNER);}
        public boolean available(TrustedContext caller){return !unavailable.contains(caller);}
        public MandateRegistry.Assignment assignment(ValidatedRequest bound,RunCorrelation run){return assignments.get(run.runId());}
        public boolean current(Binding binding){var a=assignments.get(binding.run());return current&&a!=null&&a.job().equals(binding.job())&&a.generation()==binding.generation();}
        public void requestStop(Binding binding){stopped.add(binding);}
        Grant grant(UUID parent,TrustedContext issuer,TrustedContext grantee,Rights rights,Budgets.Limits budget,long expires) {
            UUID id=UUID.randomUUID();var result=registry.grant(id,parent,null,rights,expires,budget,grantee,issuer);
            assertEquals(Status.PENDING,result.status(),result.toString());registry.tick();assertTrue(registry.ready());return find(id);
        }
        Grant root(){return grant(null,OWNER,COORDINATOR,rights(),budget(10000,11000),11000);}
        Grant find(UUID id){return registry.snapshot().grants().stream().filter(g->g.id().equals(id)).findFirst().orElseThrow();}
        RunCorrelation run(TrustedContext caller,long allowance) {
            var run=new RunCorrelation(UUID.randomUUID(),ARTIFACT,null);
            assignments.put(run.runId(),new MandateRegistry.Assignment(UUID.randomUUID(),1,ARTIFACT,budget(allowance,10000)));
            return run;
        }
        void reserve(TrustedContext caller,RunCorrelation run){assertEquals(Status.PENDING,registry.prepare(request(caller),run).status());registry.tick();}
        boolean allows(TrustedContext caller,RunCorrelation run){return registry.allows(request(caller),run.runId(),Effect.HARVEST,new EffectTarget(DIM,1,64,0));}
    }
    @Test void validRootAndChildRequireDurableReservationAndCurrentEffectScope() {
        var s=new Scene();Grant root=s.root();s.grant(root.id(),COORDINATOR,CHILD,rights(),budget(1000,10000),10000);
        var run=s.run(CHILD,100);assertFalse(s.allows(CHILD,run));s.reserve(CHILD,run);assertTrue(s.allows(CHILD,run));
        assertEquals(100,s.find(root.id()).reserved().get(Budgets.Kind.COMMITTED_EFFECTS));
        assertFalse(s.registry.allows(request(CHILD),run.runId(),Effect.HARVEST,new EffectTarget(DIM,6,64,0)));
        assertFalse(s.registry.allows(request(FOREIGN),run.runId(),Effect.HARVEST,new EffectTarget(DIM,1,64,0)));
    }
    @Test void pendingGrantAndReservationNeverPermitAnEffectAndRepeatedPreparationDoesNotRecharge() {
        var s=new Scene();s.memory.hold=true;UUID id=UUID.randomUUID();
        assertEquals(Status.PENDING,s.registry.grant(id,null,null,rights(),11000,budget(1000,11000),COORDINATOR,OWNER).status());
        assertFalse(s.registry.controls(ACTOR,Effect.HARVEST,COORDINATOR));s.memory.release();s.registry.tick();
        var run=s.run(COORDINATOR,100);s.memory.hold=true;
        assertEquals(Status.PENDING,s.registry.prepare(request(COORDINATOR),run).status());
        for(int n=0;n<10;n++){assertEquals(Status.PENDING,s.registry.prepare(request(COORDINATOR),run).status());assertFalse(s.allows(COORDINATOR,run));}
        s.memory.release();s.registry.tick();assertTrue(s.allows(COORDINATOR,run));
        assertEquals(Status.DUPLICATE,s.registry.prepare(request(COORDINATOR),run).status());
        assertEquals(100,s.find(id).reserved().get(Budgets.Kind.COMMITTED_EFFECTS));
    }
    @ParameterizedTest @ValueSource(strings={"worker","resource","area","container","actions","duration","budget","cycle","self"})
    void subdelegationRejectsEveryWideningWithoutPartialGrant(String dimension) {
        var s=new Scene();Rights parentRights=new Rights(Set.of(ACTOR),Set.of(Effect.OBSERVE,Effect.HARVEST),Set.of("minecraft:wheat"),AREA,Set.of(DEST));
        Grant parent=s.grant(null,OWNER,COORDINATOR,parentRights,budget(1000,10000),10000);
        Rights proposed=parentRights;long expires=10000;var allowance=budget(1000,10000);TrustedContext grantee=CHILD;
        switch(dimension){
            case "worker" -> proposed=new Rights(Set.of(ACTOR,actor(101)),parentRights.actions(),parentRights.resources(),AREA,Set.of(DEST));
            case "resource" -> proposed=new Rights(Set.of(ACTOR),parentRights.actions(),Set.of("minecraft:wheat","minecraft:oak_log"),AREA,Set.of(DEST));
            case "area" -> proposed=new Rights(Set.of(ACTOR),parentRights.actions(),parentRights.resources(),new Cuboid(DIM,0,63,0,6,65,2),Set.of(DEST));
            case "container" -> proposed=new Rights(Set.of(ACTOR),parentRights.actions(),parentRights.resources(),AREA,Set.of(DEST,new ContainerRef(DIM,4,64,0)));
            case "actions" -> proposed=new Rights(Set.of(ACTOR),Set.of(Effect.OBSERVE,Effect.HARVEST,Effect.TRANSFER),parentRights.resources(),AREA,Set.of(DEST));
            case "duration" -> expires=10001;
            case "budget" -> allowance=budget(1001,10000);
            case "cycle" -> grantee=OWNER;
            case "self" -> grantee=COORDINATOR;
        }
        Snapshot before=s.registry.snapshot();var result=s.registry.grant(UUID.randomUUID(),parent.id(),null,proposed,expires,allowance,grantee,COORDINATOR);
        assertFalse(result.accepted());assertEquals(before,s.registry.snapshot());assertTrue(s.registry.ready());
    }
    @Test void revokeLinearizesBeforeAcknowledgementAndFencesCachedChildEvenWhenDirectRightsChange() {
        var s=new Scene();var parent=s.root();s.grant(parent.id(),COORDINATOR,CHILD,rights(),budget(1000,10000),10000);
        var run=s.run(CHILD,100);s.reserve(CHILD,run);assertTrue(s.allows(CHILD,run));s.memory.hold=true;
        assertEquals(Status.PENDING,s.registry.revoke(parent.id(),s.find(parent.id()).revision(),OWNER).status());
        var policy=new MandateAuthority((a,e,c)->true,s.registry);
        assertFalse(policy.currentlyAllows(request(CHILD),run.runId(),Effect.HARVEST,new EffectTarget(DIM,1,64,0)));
        s.registry.maintain(1);assertEquals(1,s.stopped.size());assertEquals(run.runId(),s.stopped.getFirst().run());
        s.memory.release();s.registry.tick();assertTrue(s.find(parent.id()).revoked());assertFalse(s.allows(CHILD,run));
        assertEquals(Status.DUPLICATE,s.registry.revoke(parent.id(),s.find(parent.id()).revision(),OWNER).status());
    }
    @Test void failedRevocationPublicationAndLateAcknowledgementFenceAllDelegatedAuthority() {
        var s=new Scene();var parent=s.root();var run=s.run(COORDINATOR,100);s.reserve(COORDINATOR,run);s.memory.hold=true;
        s.registry.revoke(parent.id(),s.find(parent.id()).revision(),OWNER);s.memory.pending.completeExceptionally(new IllegalStateException("disk"));s.registry.tick();
        assertFalse(s.registry.ready());assertFalse(s.allows(COORDINATOR,run));assertFalse(s.registry.rootsComplete());
    }
    @Test void acknowledgementTimeoutAndCloseCannotActivatePendingAuthority() {
        var s=new Scene();s.root();var run=s.run(COORDINATOR,100);s.memory.hold=true;s.registry.prepare(request(COORDINATOR),run);
        s.clock.now+=30000;s.registry.tick();s.memory.release();s.registry.tick();assertFalse(s.allows(COORDINATOR,run));assertFalse(s.registry.ready());
        var other=new Scene();other.root();var next=other.run(COORDINATOR,100);other.memory.hold=true;other.registry.prepare(request(COORDINATOR),next);
        other.registry.close();other.memory.release();other.registry.tick();assertFalse(other.allows(COORDINATOR,next));
    }
    @Test void exactExpiryCoordinatorLossAndCurrentIssuerAuthorityAreCheckedAtEveryUse() {
        var s=new Scene();s.root();var run=s.run(COORDINATOR,100);s.reserve(COORDINATOR,run);assertTrue(s.allows(COORDINATOR,run));
        s.unavailable.add(COORDINATOR);assertFalse(s.allows(COORDINATOR,run));s.registry.maintain(1);assertEquals(1,s.stopped.size());
        s.unavailable.clear();s.ownerAllows=false;assertFalse(s.allows(COORDINATOR,run));s.ownerAllows=true;s.clock.now=10000;assertFalse(s.allows(COORDINATOR,run));
    }
    @Test void restoredGrantsNeedIssuerConsentAndOldRunsStayInactiveAfterConsent() {
        var s=new Scene();var root=s.root();var run=s.run(COORDINATOR,100);s.reserve(COORDINATOR,run);long reserved=s.find(root.id()).reserved().get(Budgets.Kind.CALLS);
        s.registry=s.restore(s.memory.state);assertFalse(s.registry.controls(ACTOR,Effect.HARVEST,COORDINATOR));assertFalse(s.allows(COORDINATOR,run));
        assertEquals(Status.DENIED,s.registry.revalidate(root.id(),s.find(root.id()).revision(),COORDINATOR).status());
        assertEquals(Status.PENDING,s.registry.revalidate(root.id(),s.find(root.id()).revision(),OWNER).status());s.registry.tick();
        assertTrue(s.registry.controls(ACTOR,Effect.HARVEST,COORDINATOR));assertFalse(s.allows(COORDINATOR,run));
        assertEquals(Status.DENIED,s.registry.prepare(request(COORDINATOR),run).status());assertEquals(reserved,s.find(root.id()).reserved().get(Budgets.Kind.CALLS));
        var next=s.run(COORDINATOR,100);s.reserve(COORDINATOR,next);assertTrue(s.allows(COORDINATOR,next));assertEquals(2*reserved,s.find(root.id()).reserved().get(Budgets.Kind.CALLS));
    }
    @Test void durableRevokedGrantAndMissingOrCyclicParentCannotBeRevalidated() {
        var s=new Scene();var root=s.root();s.registry.revoke(root.id(),root.revision(),OWNER);s.registry.tick();s.registry=s.restore(s.memory.state);
        assertEquals(Status.DENIED,s.registry.revalidate(root.id(),s.find(root.id()).revision(),OWNER).status());
        var missing=new Grant(new UUID(5,1),1,OWNER,COORDINATOR,new UUID(5,2),null,rights(),1000,11000,budget(1000,11000),Map.of(),false,List.of());
        var unknown=s.restore(new Snapshot(WORLD,1,List.of(missing),List.of()));
        assertEquals(Status.DENIED,unknown.revalidate(missing.id(),1,OWNER).status());
        var cyclic=new Grant(missing.id(),1,OWNER,COORDINATOR,missing.id(),null,rights(),1000,11000,budget(1000,11000),Map.of(),false,List.of());
        var cycle=s.restore(new Snapshot(WORLD,1,List.of(cyclic),List.of()));assertEquals(Status.DENIED,cycle.revalidate(cyclic.id(),1,OWNER).status());
    }
    @Test void childConsentCannotPrecedeParentConsentAfterReload() {
        var s=new Scene();var root=s.root();var child=s.grant(root.id(),COORDINATOR,CHILD,rights(),budget(1000,10000),10000);s.registry=s.restore(s.memory.state);
        assertEquals(Status.DENIED,s.registry.revalidate(child.id(),child.revision(),COORDINATOR).status());
        s.registry.revalidate(root.id(),s.find(root.id()).revision(),OWNER);s.registry.tick();
        assertEquals(Status.PENDING,s.registry.revalidate(child.id(),child.revision(),COORDINATOR).status());s.registry.tick();assertTrue(s.registry.controls(ACTOR,Effect.HARVEST,CHILD));
    }
    @Test void reservedBudgetCannotBeResetBySiblingRetryOrFailure() {
        var s=new Scene();Grant root=s.grant(null,OWNER,COORDINATOR,rights(),budget(100,11000),11000);
        var first=s.run(COORDINATOR,100);s.reserve(COORDINATOR,first);s.current=false;
        var next=s.run(COORDINATOR,1);assertEquals(Status.DENIED,s.registry.prepare(request(COORDINATOR),next).status());assertEquals(100,s.find(root.id()).reserved().get(Budgets.Kind.CALLS));
    }
    @Test void wrongWorldPrivateQueriesAndForeignGrantorRevealNoPrivateEvidence() {
        var s=new Scene();var root=s.root();assertEquals(List.of(),s.registry.query(FOREIGN,0,8));
        var hidden=s.registry.evidence(root.id(),FOREIGN);assertNull(hidden.id());assertEquals(0,hidden.revision());assertTrue(hidden.chain().isEmpty());
        var wrong=new TrustedContext(OWNER.principal(),new ScopeRef(UUID.randomUUID(),OWNER.scope().domainId()));
        assertFalse(s.registry.grant(UUID.randomUUID(),null,null,rights(),11000,budget(100,11000),COORDINATOR,wrong).accepted());
        assertFalse(s.registry.grant(UUID.randomUUID(),null,null,rights(),11000,budget(100,11000),COORDINATOR,FOREIGN).accepted());
    }
    @Test void exactDepthCountQueryAndMaintenanceLimitsHoldWithoutRefill() {
        var s=new Scene();Grant parent=s.root();TrustedContext issuer=COORDINATOR;
        for(int n=0;n<3;n++){TrustedContext next=context(20+n);parent=s.grant(parent.id(),issuer,next,rights(),budget(1000,10000),10000);issuer=next;}
        assertEquals(Status.DENIED,s.registry.grant(UUID.randomUUID(),parent.id(),null,rights(),10000,budget(100,10000),context(99),issuer).status());
        for(int n=4;n<32;n++)s.grant(null,OWNER,COORDINATOR,rights(),budget(1000,11000),11000);
        assertEquals(Status.LIMIT_REACHED,s.registry.grant(UUID.randomUUID(),null,null,rights(),11000,budget(100,11000),COORDINATOR,OWNER).status());
        assertEquals(8,s.registry.query(OWNER,0,8).size());assertThrows(IllegalArgumentException.class,()->s.registry.query(OWNER,0,9));
        assertEquals(4,s.registry.maintain(1));assertEquals(0,s.registry.maintain(1));assertEquals(4,s.registry.maintain(2));
    }
    @Test void bindingCapacityDeadlineOverflowAndFiniteRightsAreEnforced() {
        var s=new Scene();s.root();for(int n=0;n<64;n++)s.reserve(COORDINATOR,s.run(COORDINATOR,1));
        assertEquals(Status.LIMIT_REACHED,s.registry.prepare(request(COORDINATOR),s.run(COORDINATOR,1)).status());
        assertThrows(IllegalArgumentException.class,()->new Rights(Set.of(),Set.of(Effect.OBSERVE),Set.of("minecraft:wheat"),AREA,Set.of(DEST)));
        var nine=new HashSet<ActorRef>();for(int n=0;n<9;n++)nine.add(actor(n));
        assertThrows(IllegalArgumentException.class,()->new Rights(nine,Set.of(Effect.OBSERVE),Set.of("minecraft:wheat"),AREA,Set.of(DEST)));
        var overflow=new Scene();overflow.clock.now=Long.MAX_VALUE-1;
        assertFalse(overflow.registry.grant(UUID.randomUUID(),null,null,rights(),Long.MAX_VALUE,budget(100,Long.MAX_VALUE),COORDINATOR,OWNER).accepted());
    }
    @Test void changedAssignmentGenerationAndChangedBindingsInvalidateCachedAuthorization() {
        var s=new Scene();s.root();var run=s.run(COORDINATOR,100);s.reserve(COORDINATOR,run);var old=s.assignments.get(run.runId());
        s.assignments.put(run.runId(),new MandateRegistry.Assignment(old.job(),2,old.artifact(),old.allowance()));assertFalse(s.allows(COORDINATOR,run));
        assertEquals(Status.DENIED,s.registry.prepare(request(COORDINATOR),run).status());
    }
    @Test void foreignArtifactAndInferenceAllowanceCannotBecomeDelegatedExecution() {
        var s=new Scene();s.root();var run=s.run(COORDINATOR,100);var owner=s.assignments.get(run.runId());
        var values=new EnumMap<Budgets.Kind,Long>(owner.allowance().maxima());values.put(Budgets.Kind.INPUT_BYTES,1L);
        s.assignments.put(run.runId(),new MandateRegistry.Assignment(owner.job(),1,ARTIFACT,new Budgets.Limits(values,10000)));
        assertEquals(Status.DENIED,s.registry.prepare(request(COORDINATOR),run).status());assertTrue(s.registry.snapshot().bindings().isEmpty());
        var other=new ArtifactRef(CropDelivery.ID,"b".repeat(64));assertEquals(Status.DENIED,s.registry.prepare(request(COORDINATOR),new RunCorrelation(run.runId(),other,null)).status());
    }
    @Test void exactWorkerResourceContainerHistoryAndTtlCapsRejectOneMoreWithoutMutation() {
        var actors=new HashSet<ActorRef>();for(int n=0;n<8;n++)actors.add(actor(n));
        var resources=Set.of("minecraft:wheat","minecraft:oak_log","minecraft:r2","minecraft:r3");
        var containers=Set.of(DEST,new ContainerRef(DIM,1,64,0),new ContainerRef(DIM,2,64,0),new ContainerRef(DIM,3,64,0));
        assertDoesNotThrow(()->new Rights(actors,EnumSet.allOf(Effect.class),resources,AREA,containers));
        var tooManyResources=new HashSet<>(resources);tooManyResources.add("minecraft:r4");
        assertThrows(IllegalArgumentException.class,()->new Rights(actors,EnumSet.allOf(Effect.class),tooManyResources,AREA,containers));
        var tooManyContainers=new HashSet<>(containers);tooManyContainers.add(new ContainerRef(DIM,4,64,0));
        assertThrows(IllegalArgumentException.class,()->new Rights(actors,EnumSet.allOf(Effect.class),resources,AREA,tooManyContainers));
        assertThrows(IllegalArgumentException.class,()->new Rights(actors,Set.of(),resources,AREA,containers));
        var s=new Scene();long expiry=s.clock.now+MAX_TTL_MILLIS;
        Grant root=s.grant(null,OWNER,COORDINATOR,rights(),budget(MAX_BUDGET,expiry),expiry);
        Snapshot before=s.registry.snapshot();assertEquals(Status.INVALID,s.registry.grant(UUID.randomUUID(),null,null,rights(),expiry+1,budget(100,expiry),COORDINATOR,OWNER).status());assertEquals(before,s.registry.snapshot());
        var history=new ArrayList<Event>();for(int n=1;n<=8;n++)history.add(new Event(n,EventKind.RESERVED,UUID.randomUUID(),COORDINATOR));
        assertDoesNotThrow(()->new Grant(root.id(),8,OWNER,COORDINATOR,null,null,rights(),root.issuedAt(),expiry,root.budget(),Map.of(),false,history));
        history.add(new Event(9,EventKind.RESERVED,UUID.randomUUID(),COORDINATOR));
        assertThrows(IllegalArgumentException.class,()->new Grant(root.id(),9,OWNER,COORDINATOR,null,null,rights(),root.issuedAt(),expiry,root.budget(),Map.of(),false,history));
    }
    @Test void acceptedPlankRecipeRequiresBothInputOutputResourcesAndNamedSourceContainer() {
        var s=new Scene();var input=new ContainerRef(DIM,1,64,0);
        var allowed=new Rights(Set.of(ACTOR),EnumSet.allOf(Effect.class),Set.of("minecraft:oak_log","minecraft:oak_planks"),AREA,Set.of(input,DEST));
        Grant grant=s.grant(null,OWNER,COORDINATOR,allowed,budget(1000,11000),11000);
        var ref=new ArtifactRef(OakPlankProduction.ID,"c".repeat(64));var run=new RunCorrelation(UUID.randomUUID(),ref,null);
        var request=new ValidatedRequest(new CapabilityRequest(OakPlankProduction.ID,Map.of("actor",new ActorValue(ACTOR),"source",new ContainerValue(input),"destination",new ContainerValue(DEST),"amount",new IntValue(4))),COORDINATOR,new ObservationRef(UUID.randomUUID(),1,DIM));
        s.assignments.put(run.runId(),new MandateRegistry.Assignment(UUID.randomUUID(),1,ref,budget(100,10000)));
        assertEquals(Status.PENDING,s.registry.prepare(request,run).status());s.registry.tick();
        assertTrue(s.registry.allows(request,run.runId(),Effect.WITHDRAW,new EffectTarget(DIM,1,64,0)));
        assertTrue(s.registry.allows(request,run.runId(),Effect.CRAFT,new EffectTarget(DIM,2,64,0)));
        var missingInput=new Rights(Set.of(ACTOR),EnumSet.allOf(Effect.class),Set.of("minecraft:oak_planks"),AREA,Set.of(input,DEST));assertFalse(missingInput.matches(request));
        var wrongContainer=new Rights(Set.of(ACTOR),EnumSet.allOf(Effect.class),allowed.resources(),AREA,Set.of(DEST));assertFalse(wrongContainer.matches(request));
        s.registry.revoke(grant.id(),s.find(grant.id()).revision(),OWNER);
        assertFalse(s.registry.allows(request,run.runId(),Effect.CRAFT,new EffectTarget(DIM,2,64,0)));
    }
}
