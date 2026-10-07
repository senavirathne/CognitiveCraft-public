package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import static org.junit.jupiter.api.Assertions.*;

class ResourceLeaseServiceTest {
    static final UUID WORLD=UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final String DIM="minecraft:overworld";
    static final class FakeClock extends Clock {
        long millis=1000;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(millis);}
    }
    static final class Fixture {
        final UUID world;
        final TrustedContext a,b;
        final ActorRef actorA=new ActorRef(UUID.randomUUID(),UUID.randomUUID(),DIM);
        final ActorRef actorB=new ActorRef(UUID.randomUUID(),UUID.randomUUID(),DIM);
        final Owner alice=new Owner(UUID.randomUUID(),0),bob=new Owner(UUID.randomUUID(),0);
        final FakeClock clock=new FakeClock();
        final Map<UUID,OwnerFacts> owners=new HashMap<>();
        final Set<UUID> denied=new HashSet<>();
        final Map<Resource,Long> quantities=new HashMap<>();
        final Map<Resource,String> identities=new HashMap<>();
        final Map<Resource,ObservationStatus> statuses=new HashMap<>();
        final Set<TrustedContext> shared=new HashSet<>();
        final Settings settings;
        final Resource stock;
        long tick,stale=-1;
        Snapshot disk,held;
        boolean holding;
        CompletableFuture<Snapshot> write;
        ResourceLeaseService service;
        Fixture(){this(WORLD,Settings.fixture());}
        Fixture(UUID world,Settings settings){
            this.world=world;this.settings=settings;
            a=new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(world,UUID.randomUUID()));
            b=new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(world,UUID.randomUUID()));
            owners.put(alice.job(),new OwnerFacts(a,actorA,UUID.randomUUID(),true,null));
            owners.put(bob.job(),new OwnerFacts(b,actorB,UUID.randomUUID(),true,null));
            stock=Resource.stock(world,new ContainerRef(DIM,1,2,3));disk=Snapshot.empty(world);
            service=make(disk,false);ack();
        }
        ResourceLeaseService make(Snapshot initial,boolean readOnly){
            return new ResourceLeaseService(initial,(expected,next)->{
                assertEquals(disk,expected);
                if(holding){held=next;write=new CompletableFuture<>();return write;}
                disk=next;return CompletableFuture.completedFuture(next);
            },this::owner,this::observe,settings,()->tick,clock,readOnly);
        }
        OwnerFacts owner(Owner owner,TrustedContext caller){
            OwnerFacts fact=owners.get(owner.job());
            if(fact==null || owner.generation()!=0 || denied.contains(owner.job())
                    || !fact.origin().equals(caller)&&!shared.contains(caller))return null;
            return fact;
        }
        Observation observe(Resource r,OwnerFacts owner){
            long quantity=quantities.getOrDefault(r,r.kind()==Kind.STOCK?5L:1L);
            ObservationStatus status=statuses.getOrDefault(r,quantity==0?ObservationStatus.ABSENT:ObservationStatus.PRESENT);
            return new Observation(r,status,quantity,identities.getOrDefault(r,"target"),
                    stale<0?tick:stale,1,null);
        }
        void ack(){service.tick();assertTrue(service.ready(),String.valueOf(service.failure()));}
        Result acquire(Owner owner,TrustedContext caller,List<Demand> values,long duration){
            Result result=service.acquire(owner,values,duration,caller);
            if(result.code()==Code.PENDING&&result.group()!=null){ack();return service.group(result.group(),owner,caller);}
            return result;
        }
        Result alice(Resource r,long n){return acquire(alice,a,List.of(new Demand(r,n)),100);}
        Result bob(Resource r,long n){return acquire(bob,b,List.of(new Demand(r,n)),100);}
        Resource facility(int x){return Resource.facility(world,new ContainerRef(DIM,x,1,0));}
        Ref one(Result result){assertTrue(result.usable(),result.toString());return result.leases().getFirst();}
        void control(Owner owner,boolean live,Reason reason){
            OwnerFacts old=owners.get(owner.job());owners.put(owner.job(),new OwnerFacts(old.origin(),old.worker(),old.run(),live,reason));
        }
        void complete(){disk=held;write.complete(held);holding=false;ack();}
    }
    @Test void fiveStockCannotGrantThreePlusThreeAcrossPrivateScopes(){
        var f=new Fixture();Ref first=f.one(f.alice(f.stock,3));Result second=f.bob(f.stock,3);
        assertEquals(Code.CONFLICT,second.code());assertEquals(2,second.available());
        assertNull(second.group());assertTrue(second.leases().isEmpty());assertTrue(f.service.validate(first,f.a).usable());
        assertEquals(1,f.service.snapshot().leases().size());
    }
    @Test void compatibleThreePlusTwoClaimsConserveFive(){
        var f=new Fixture();f.one(f.alice(f.stock,3));f.one(f.bob(f.stock,2));
        assertEquals(5,f.service.snapshot().leases().stream().mapToLong(Lease::remaining).sum());
        assertEquals(0,f.service.inspect(f.alice,f.stock,f.a).available());
    }
    @Test void pendingGrantReservesButDoesNotAuthorizeOrAcknowledgeAnother(){
        var f=new Fixture();f.holding=true;
        Result first=f.service.acquire(f.alice,List.of(new Demand(f.stock,3)),100,f.a);
        assertEquals(Code.PENDING,first.code());assertFalse(f.service.validate(first.leases().getFirst(),f.a).usable());
        Result second=f.service.acquire(f.bob,List.of(new Demand(f.stock,3)),100,f.b);
        assertEquals(Code.PENDING,second.code());assertTrue(second.leases().isEmpty());
        assertTrue(f.service.protectedRoots().jobs().contains(f.alice.job()));
        f.complete();assertTrue(f.service.group(first.group(),f.alice,f.a).usable());
        assertEquals(Code.CONFLICT,f.bob(f.stock,3).code());
    }
    @Test void physicalKeysDoNotContainScopeAndDistinctDimensionsDoNotAlias(){
        var f=new Fixture();f.one(f.alice(f.stock,3));
        Resource same=Resource.stock(WORLD,new ContainerRef(DIM,1,2,3));assertEquals(f.stock,same);
        assertEquals(Code.CONFLICT,f.bob(same,3).code());
        Resource nether=Resource.stock(WORLD,new ContainerRef("minecraft:the_nether",1,2,3));
        assertNotEquals(same,nether);f.one(f.bob(nether,3));
    }
    @Test void separateWorldsRemainDistinctAndForeignWorldKeysAreRejected(){
        var a=new Fixture();var b=new Fixture(UUID.randomUUID(),Settings.fixture());
        a.one(a.alice(a.stock,3));b.one(b.alice(b.stock,3));assertNotEquals(a.stock,b.stock);
        assertEquals(Reason.REQUEST_INVALID,a.bob(b.stock,1).reason());
    }
    @ParameterizedTest @EnumSource(value=Kind.class,names={"EQUIPMENT","FACILITY","SPACE"})
    void exclusiveClassesHaveOneHolder(Kind kind){
        var f=new Fixture();Resource resource=switch(kind){
            case EQUIPMENT -> new Resource(WORLD,kind,ResourceLeases.point(new ContainerRef(DIM,3,1,2)),"minecraft:iron_hoe",0);
            case FACILITY -> f.facility(3);
            case SPACE -> Resource.space(WORLD,new Cuboid(DIM,0,1,0,2,1,2));
            default -> throw new AssertionError();
        };
        Ref held=f.one(f.alice(resource,1));assertEquals(Code.CONFLICT,f.bob(resource,1).code());
        assertTrue(f.service.validate(held,f.a).usable());
    }
    @Test void equipmentSlotsAreSeparateButTheSameSlotConflicts(){
        var f=new Fixture();Cuboid point=ResourceLeases.point(new ContainerRef(DIM,4,1,1));
        Resource first=new Resource(WORLD,Kind.EQUIPMENT,point,"minecraft:iron_hoe",0);
        Resource other=new Resource(WORLD,Kind.EQUIPMENT,point,"minecraft:iron_hoe",1);
        f.one(f.alice(first,1));f.one(f.bob(other,1));assertEquals(Code.CONFLICT,f.bob(first,1).code());
    }
    @Test void closedVoxelBoundaryOverlapsButAdjacentCellsCoexist(){
        var f=new Fixture();
        Resource a=Resource.space(WORLD,ResourceLeases.region(DIM,2,1,0,0,1,0));
        f.one(f.alice(a,1));
        assertEquals(Code.CONFLICT,f.bob(Resource.space(WORLD,new Cuboid(DIM,2,1,0,3,1,0)),1).code());
        f.one(f.bob(Resource.space(WORLD,new Cuboid(DIM,3,1,0,5,1,0)),1));
        assertEquals(a,Resource.space(WORLD,new Cuboid(DIM,0,1,0,2,1,0)));
    }
    @Test void overlappingNonidenticalCropPoolsCannotHideDoubleAllocation(){
        var f=new Fixture();
        f.one(f.alice(Resource.crops(WORLD,new Cuboid(DIM,0,1,0,2,1,0)),3));
        assertEquals(Code.CONFLICT,f.bob(Resource.crops(WORLD,new Cuboid(DIM,2,1,0,4,1,0)),3).code());
    }
    @Test void fourMemberGroupWithOneConflictGrantsNothing(){
        var f=new Fixture();f.one(f.alice(f.facility(4),1));Snapshot before=f.service.snapshot();
        Result result=f.acquire(f.bob,f.b,List.of(new Demand(f.facility(1),1),new Demand(f.facility(2),1),
                new Demand(f.facility(3),1),new Demand(f.facility(4),1)),100);
        assertEquals(Code.CONFLICT,result.code());assertEquals(before,f.service.snapshot());assertNull(result.group());
    }
    @Test void fourMembersAreAcceptedAndFifthIsRejectedAtomically(){
        var f=new Fixture();List<Demand> demands=new ArrayList<>();
        for(int n=0;n<4;n++)demands.add(new Demand(f.facility(n),1));
        assertEquals(4,f.acquire(f.alice,f.a,demands,100).leases().size());
        demands.add(new Demand(f.facility(5),1));Snapshot before=f.service.snapshot();
        assertEquals(Reason.REQUEST_INVALID,f.acquire(f.bob,f.b,demands,100).reason());assertEquals(before,f.service.snapshot());
    }
    @Test void duplicateStockIsSummedAndExclusiveDuplicatesCollapse(){
        var f=new Fixture();
        Result grant=f.acquire(f.alice,f.a,List.of(new Demand(f.stock,2),new Demand(f.stock,2)),100);
        assertEquals(1,grant.leases().size());assertEquals(4,f.service.snapshot().leases().getFirst().remaining());
        assertEquals(Code.CONFLICT,f.bob(f.stock,2).code());
        Result exclusive=f.acquire(f.alice,f.a,List.of(new Demand(f.facility(8),1),new Demand(f.facility(8),1)),100);
        assertEquals(1,exclusive.leases().size());
    }
    @Test void duplicateQuantityInflationAndOverlappingGroupKeysCommitNothing(){
        var f=new Fixture();Snapshot before=f.service.snapshot();
        assertEquals(Code.CONFLICT,f.acquire(f.alice,f.a,List.of(new Demand(f.stock,3),new Demand(f.stock,3)),100).code());
        assertEquals(Reason.REQUEST_INVALID,f.acquire(f.alice,f.a,List.of(new Demand(f.stock,4096),new Demand(f.stock,4096)),100).reason());
        Resource a=Resource.space(WORLD,new Cuboid(DIM,0,1,0,2,1,0));
        Resource b=Resource.space(WORLD,new Cuboid(DIM,2,1,0,4,1,0));
        assertEquals(Reason.REQUEST_INVALID,f.acquire(f.alice,f.a,List.of(new Demand(a,1),new Demand(b,1)),100).reason());
        assertEquals(before,f.service.snapshot());assertTrue(f.service.ready());
    }
    @ParameterizedTest @ValueSource(longs={-1,0,101,Long.MAX_VALUE})
    void invalidDurationsHaveNoPublication(long duration){
        var f=new Fixture();Snapshot before=f.service.snapshot();
        assertEquals(Reason.REQUEST_INVALID,f.acquire(f.alice,f.a,List.of(new Demand(f.stock,1)),duration).reason());
        assertEquals(before,f.service.snapshot());
    }
    @ParameterizedTest @ValueSource(longs={99,100,101})
    void expiryIsExclusiveAtOneHundred(long tick){
        var f=new Fixture();Ref ref=f.one(f.alice(f.stock,3));f.tick=tick;
        Result answer=f.service.validate(ref,f.a);assertEquals(tick<100,answer.usable());
        if(tick>=100)assertEquals(Code.EXPIRED,answer.code());
    }
    @Test void renewalIntervalAndGenerationFencePreventOldCallbacks(){
        var f=new Fixture();Result grant=f.alice(f.stock,3);Ref old=f.one(grant);f.tick=9;
        assertEquals(Reason.BUDGET_EXHAUSTED,f.service.renew(grant.leases(),100,f.a).reason());
        f.tick=10;Result renew=f.service.renew(grant.leases(),100,f.a);assertEquals(Code.PENDING,renew.code());f.ack();
        Result current=f.service.group(grant.group(),f.alice,f.a);
        assertTrue(current.leases().getFirst().generation()>old.generation());
        assertEquals(Code.STALE,f.service.validate(old,f.a).code());assertEquals(Code.STALE,f.service.release(List.of(old),f.a).code());
        assertTrue(f.service.validate(f.one(current),f.a).usable());
    }
    @Test void eightRenewalsAreFiniteAndNinthDoesNotPublish(){
        var f=new Fixture();Result grant=f.alice(f.stock,3);
        for(int n=1;n<=8;n++){f.tick=n*10;Result next=f.service.renew(grant.leases(),100,f.a);
            assertEquals(Code.PENDING,next.code());f.ack();grant=f.service.group(grant.group(),f.alice,f.a);}
        Snapshot before=f.service.snapshot();f.tick=90;
        assertEquals(Reason.BUDGET_EXHAUSTED,f.service.renew(grant.leases(),100,f.a).reason());assertEquals(before,f.service.snapshot());
    }
    @Test void expiredRenewalCannotReviveAClaim(){
        var f=new Fixture();Result grant=f.alice(f.stock,3);f.tick=100;
        assertFalse(f.service.renew(grant.leases(),100,f.a).usable());assertFalse(f.service.validate(f.one(grant),f.a).usable());
    }
    @Test void releaseReacquireAndDuplicateReleasePreserveTheNewGrant(){
        var f=new Fixture();Result first=f.alice(f.stock,3);Ref old=f.one(first);
        assertEquals(Code.PENDING,f.service.release(first.leases(),f.a).code());f.ack();
        Ref next=f.one(f.alice(f.stock,3));Snapshot before=f.service.snapshot();
        assertEquals(Code.RELEASED,f.service.release(List.of(old),f.a).code());assertEquals(before,f.service.snapshot());
        assertTrue(f.service.validate(next,f.a).usable());assertNotEquals(old.id(),next.id());
    }
    @Test void incompleteGroupReleaseAndGuessedReferencesHaveNoEffect(){
        var f=new Fixture();Result grant=f.acquire(f.alice,f.a,List.of(new Demand(f.facility(1),1),new Demand(f.facility(2),1)),100);
        Snapshot before=f.service.snapshot();
        assertEquals(Reason.REQUEST_INVALID,f.service.release(List.of(grant.leases().getFirst()),f.a).reason());
        assertEquals(Reason.AUTHORITY_DENIED,f.service.validate(grant.leases().getFirst(),f.b).reason());assertEquals(before,f.service.snapshot());
        assertFalse(f.service.validate(new Ref(UUID.randomUUID(),UUID.randomUUID(),1),f.b).usable());
    }
    @Test void sharingPreservesOriginalScopeAndRevocationDeniesFurtherUse(){
        var f=new Fixture();f.shared.add(f.b);Result grant=f.acquire(f.alice,f.b,List.of(new Demand(f.stock,3)),100);
        Ref ref=f.one(grant);assertEquals(f.a,f.service.snapshot().leases().getFirst().origin());
        f.shared.clear();assertEquals(Reason.AUTHORITY_DENIED,f.service.validate(ref,f.b).reason());
        assertTrue(f.service.validate(ref,f.a).usable());
    }
    @Test void unknownAndStaleObservationsNeverProveAbsentStock(){
        var f=new Fixture();f.statuses.put(f.stock,ObservationStatus.UNKNOWN);
        assertEquals(Reason.TARGET_UNAVAILABLE,f.alice(f.stock,3).reason());
        f.statuses.clear();f.tick=10;f.stale=9;assertEquals(Reason.STALE_OBSERVATION,f.alice(f.stock,3).reason());
        assertTrue(f.service.snapshot().leases().isEmpty());
    }
    @Test void containerLossOrReplacementRevokesTheWholeGroup(){
        var f=new Fixture();Result grant=f.acquire(f.alice,f.a,List.of(new Demand(f.stock,3),new Demand(f.facility(3),1)),100);
        f.quantities.put(f.stock,2L);
        assertEquals(Reason.RESOURCE_MISSING,f.service.validate(grant.leases().getFirst(),f.a).reason());f.ack();
        assertTrue(f.service.snapshot().leases().stream().allMatch(l->l.state()==State.REVOKED));
        f.quantities.put(f.stock,5L);Ref replacement=f.one(f.alice(f.stock,3));
        f.identities.put(f.stock,"new-container");
        assertEquals(Reason.TARGET_INVALID,f.service.validate(replacement,f.a).reason());f.ack();
    }
    @ParameterizedTest @EnumSource(value=Reason.class,names={"CANCELLED","ACTOR_UNAVAILABLE","AUTHORITY_DENIED"})
    void cancellationAndOwnerLossReleaseClaimsThroughTheOwner(Reason reason){
        var f=new Fixture();f.one(f.alice(f.stock,3));f.control(f.alice,false,reason);
        assertFalse(f.service.validate(f.service.snapshot().leases().getFirst().ref(),f.a).usable());
        f.service.tick();f.ack();assertEquals(State.REVOKED,f.service.snapshot().leases().getFirst().state());
        f.one(f.bob(f.stock,3));
    }
    @Test void revokedControlCannotRenewAndCleanupIsBounded(){
        var f=new Fixture();Result grant=f.alice(f.stock,3);f.denied.add(f.alice.job());f.tick=10;
        assertFalse(f.service.renew(grant.leases(),100,f.a).usable());
        f.service.tick();assertTrue(f.service.lastReconciled()<=8);f.ack();
        assertEquals(State.REVOKED,f.service.snapshot().leases().getFirst().state());
    }
    @Test void restartUsesNewEpochDespiteTickResetAndWallClockAdvance(){
        var f=new Fixture();Ref old=f.one(f.alice(f.stock,3));Snapshot saved=f.disk;
        f.tick=0;f.clock.millis+=1_000_000;f.service=f.make(saved,false);assertFalse(f.service.ready());f.ack();
        assertEquals(State.INTERRUPTED,f.service.snapshot().leases().getFirst().state());
        assertEquals(Code.STALE,f.service.validate(old,f.a).code());assertNotEquals(saved.epoch(),f.service.snapshot().epoch());
        f.one(f.bob(f.stock,3));
    }
    @Test void backwardsTickClockFencesExistingAndPendingClaims(){
        var f=new Fixture();f.tick=20;Ref ref=f.one(f.alice(f.stock,3));f.tick=19;
        assertFalse(f.service.validate(ref,f.a).usable());assertEquals(Reason.INTERRUPTED,f.service.failure());
        assertFalse(f.service.protectedRoots().complete());assertFalse(f.service.ready());
    }
    @Test void failedAndTimedOutAcknowledgementsRetainProtectedPendingRoots(){
        var f=new Fixture();f.holding=true;f.service.acquire(f.alice,List.of(new Demand(f.stock,3)),100,f.a);
        f.write.completeExceptionally(new IllegalStateException("uncertain publication"));f.service.tick();
        assertEquals(Reason.STORAGE_UNAVAILABLE,f.service.failure());
        assertTrue(f.service.protectedRoots().jobs().contains(f.alice.job()));assertFalse(f.service.protectedRoots().complete());
        assertFalse(f.bob(f.stock,3).usable());
        var slow=new Fixture();slow.holding=true;slow.service.acquire(slow.alice,List.of(new Demand(slow.stock,3)),100,slow.a);
        slow.clock.millis+=30001;slow.service.tick();assertEquals(Reason.STORAGE_UNAVAILABLE,slow.service.failure());
    }
    @Test void exactAcknowledgementDeadlineAndWrongSnapshotAreChecked(){
        var f=new Fixture();f.holding=true;Result grant=f.service.acquire(f.alice,List.of(new Demand(f.stock,3)),100,f.a);
        f.clock.millis+=30000;f.complete();assertTrue(f.service.group(grant.group(),f.alice,f.a).usable());
        var bad=new Fixture();bad.holding=true;bad.service.acquire(bad.alice,List.of(new Demand(bad.stock,3)),100,bad.a);
        bad.write.complete(bad.disk);bad.service.tick();assertEquals(Reason.STORAGE_UNAVAILABLE,bad.service.failure());
    }
    @Test void eightPerJobAndActiveAndRetainedCapsRejectNPlusOne(){
        var f=new Fixture();
        for(int n=0;n<8;n++)f.one(f.alice(f.facility(n),1));
        assertEquals(Reason.BUDGET_EXHAUSTED,f.alice(f.facility(9),1).reason());
        Settings small=new Settings(1,2,8,4,100,10,8,8,256,16,0,30000);
        var s=new Fixture(WORLD,small);Result first=s.alice(s.stock,1);
        assertEquals(Reason.BUDGET_EXHAUSTED,s.bob(s.facility(2),1).reason());
        s.service.release(first.leases(),s.a);s.ack();Result second=s.alice(s.stock,1);
        s.service.release(second.leases(),s.a);s.ack();
        assertEquals(Reason.STORAGE_LIMIT_REACHED,s.alice(s.stock,1).reason());assertEquals(2,s.service.snapshot().leases().size());
    }
    @Test void spatialNumericAndDurationOverflowAreBounded(){
        var f=new Fixture();Resource wide=Resource.space(WORLD,new Cuboid(DIM,0,1,0,16,1,0));
        assertEquals(Reason.REQUEST_INVALID,f.alice(wide,1).reason());
        assertThrows(IllegalArgumentException.class,()->new Demand(f.stock,Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->new Resource(WORLD,Kind.STOCK,
                ResourceLeases.point(new ContainerRef(DIM,1,1,1)),"minecraft:diamond",-1));
        f.tick=Long.MAX_VALUE;assertEquals(Reason.REQUEST_INVALID,f.alice(f.stock,1).reason());
        assertTrue(f.service.snapshot().leases().isEmpty());
    }
    @Test void consumptionNeedsCurrentRunActorSourceAndStableAttribution(){
        var f=new Fixture();Resource source=Resource.crops(WORLD,new Cuboid(DIM,0,1,0,2,1,0));
        Ref ref=f.one(f.alice(source,3));OwnerFacts actor=f.owners.get(f.alice.job());
        var receipt=new CropDelivery.CropReceipt(UUID.randomUUID(),actor.run(),UUID.randomUUID(),actor.worker(),
                source.area(),new ContainerRef(DIM,1,1,1),CropDelivery.Stage.HARVEST,1);
        var foreign=new CropDelivery.CropReceipt(UUID.randomUUID(),UUID.randomUUID(),receipt.batchId(),actor.worker(),
                source.area(),receipt.destination(),CropDelivery.Stage.HARVEST,1);
        assertEquals(Reason.REQUEST_INVALID,f.service.consumed(ref,foreign,f.a).reason());
        f.quantities.put(source,4L);assertEquals(Code.PENDING,f.service.consumed(ref,receipt,f.a).code());f.ack();
        assertEquals(2,f.service.snapshot().leases().getFirst().remaining());
        Snapshot before=f.service.snapshot();assertTrue(f.service.consumed(ref,receipt,f.a).usable());
        assertEquals(before,f.service.snapshot());assertTrue(f.service.validate(ref,f.a).usable());
        assertEquals(2,f.service.inspect(f.bob,source,f.b).available());
    }
    static Stream<Arguments> invalidSettings(){
        Settings d=Settings.fixture();List<Arguments> rows=new ArrayList<>();
        long[] good={d.active(),d.retained(),d.perJob(),d.group(),d.duration(),d.renewalInterval(),d.renewals(),
                d.reconcile(),d.cells(),d.axis(),d.observationAge(),d.acknowledgementMillis()};
        long[] above={65,129,9,5,1201,101,9,9,257,257,11,30001};
        for(int n=0;n<good.length;n++) {
            long[] zero=good.clone();zero[n]=n==10?-1:0;rows.add(Arguments.of((Object)zero));
            long[] large=good.clone();large[n]=above[n];rows.add(Arguments.of((Object)large));
        }return rows.stream();
    }
    @ParameterizedTest @MethodSource("invalidSettings") void configurationHasNoUnlimitedValues(long[] v){
        assertThrows(IllegalArgumentException.class,()->new Settings((int)v[0],(int)v[1],(int)v[2],(int)v[3],
                v[4],v[5],(int)v[6],(int)v[7],(int)v[8],(int)v[9],v[10],v[11]));
    }
    @Test void serviceOperationsHaveGameThreadAffinity(){
        var f=new Fixture();
        assertThrows(CompletionException.class,()->CompletableFuture.runAsync(()->f.service.inspect(f.alice,f.stock,f.a)).join());
    }
}
