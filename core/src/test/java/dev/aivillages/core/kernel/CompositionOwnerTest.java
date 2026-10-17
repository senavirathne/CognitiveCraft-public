package dev.aivillages.core.kernel;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Prerequisites.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

final class CompositionOwnerTest {
    @TempDir Path directory;
    static final class Scene {
        final ResourceCompositionTest.Fixture f=new ResourceCompositionTest.Fixture(1);
        final JobLifecycleStoreTest.Memory disk=new JobLifecycleStoreTest.Memory(f.world);
        final JobLifecycleStore jobs=store(disk.state);
        final PrerequisiteAdmission admission=new PrerequisiteAdmission(jobs,Limits.defaults(),()->f.tick);
        Scene(){assertTrue(jobs.stageComposition(f.id,f.request,f.owner,f.ref,ResourceComposition.allowance(1000)).accepted());ack();}
        JobLifecycleStore store(Snapshot initial){return new JobLifecycleStore(initial,disk,JobLifecycleStore.privateJobs((a,c)->c.equals(f.owner)),c->{},PlanningGoals.catalog(),JobLifecycleStoreTest.environment(),f.clock,Settings.defaults(),false);}
        void ack(){jobs.tick();assertTrue(jobs.ready(),String.valueOf(jobs.lastFailure()));}
        Job root(){return jobs.query(f.id,f.owner);}
        Proposal propose(){var root=root();var need=new Need(root.id(),root.guard(),root.request(),ResourceCompositionTest.AXE,1,AllocationPolicy.MATERIAL_ONLY,f.oak);
            return new DeterministicPrerequisiteResolver(Limits.defaults(),()->f.tick).resolve(List.of(need),f.catalog,f::inspect,f::method,n->true,new Budgets.Ledger(ResourceComposition.allowance(1000),f.clock));}
        Proposal admit(long planks,long sticks){f.stock.put(ResourceCompositionTest.PLANK,planks);f.stock.put(ResourceCompositionTest.STICK,sticks);
            var p=propose();assertTrue(p.accepted(),p.reason()+" "+p.metrics());var result=admission.submit(p,f.owner,f.catalog,f::inspect,f::method,n->true);
            assertEquals(ResolutionStatus.RESOLVED,result.status(),result.toString());ack();return p;}
        Job child(){return jobs.query(root().children().getFirst(),f.owner);}
        Job producedSticks() {
            admit(5,0);var child=child();var ref=child.preparation().method();var attempt=UUID.randomUUID();
            assertTrue(jobs.assign(child.id(),child.guard(),attempt,f.actor,ref,List.of(ref),StickProduction.executionLimits(1000).total(),f.owner).accepted());ack();child=child();
            var driver=new StickTestSupport.Driver(4);driver.produce();var b=child.current().bound();
            var source=ResourceLeases.point(((ContainerValue)b.request().arguments().get("source")).value());var destination=((ContainerValue)b.request().arguments().get("destination")).value();
            var receipts=driver.receipts.stream().map(r->new ResourceDelivery.Receipt(r.receiptId(),f.owner,attempt,r.operationId(),r.batchId(),f.actor,source,destination,r.item(),r.stage(),r.quantity(),
                    new ResourceDelivery.BlockPosition(source.dimension(),source.minX(),source.minY(),source.minZ()),List.of(),r.transformation())).toList();
            var result=jobs.recordExecutionResult(child.id(),child.guard(),new Report(attempt,child.current().generation(),receipts.size(),Map.of(Budgets.Kind.COMMITTED_EFFECTS,(long)receipts.size()),List.of(),
                    new Execution(ExecutionStatus.SUCCEEDED,null,receipts.size(),new EvidenceRef(attempt.toString(),"transformation-delivery:1",f.owner.scope().domainId().toString())),receipts),f.owner);
            assertTrue(result.accepted(),result.toString());ack();assertEquals(State.SUCCEEDED,child().state());return child();
        }
        Allocation allocation(Job producer,long q){var a=producer.current();return new Allocation(UUID.randomUUID(),producer.id(),a.id(),a.resourceReceipts().getLast().receiptId(),root().id(),q,ResourceCompositionTest.STICK);}
        Supply stock(long count){return new Supply(ResourceCompositionTest.STICK,count==0?ObservationStatus.ABSENT:ObservationStatus.PRESENT,count,"actual-staging",f.ref,100,1,null);}
        Change allocate(Allocation allocation,Supply stock){return jobs.allocateMaterial(allocation,root().guard(),stock,100,f.owner);}
    }
    @Test void stageIsNonExecutableUntilAtomicPreparedGraphPublication(){var s=new Scene();assertFalse(s.jobs.materialReady(s.root()));assertEquals(State.WAITING,s.root().state());assertNull(s.root().preparation().method());s.admit(3,2);assertTrue(s.jobs.materialReady(s.root()));assertEquals(State.READY,s.root().state());assertTrue(s.root().children().isEmpty());}
    @Test void sharedPlankGraphPinsExactMethodsAndKeepsOriginalRoot(){var s=new Scene();var p=s.admit(0,0);assertEquals(3,s.root().children().size());assertEquals(4,s.jobs.snapshot().jobs().size());assertTrue(s.jobs.protectedRoots().artifacts().containsAll(p.nodes().stream().map(Node::method).toList()));assertTrue(s.jobs.snapshot().allocations().isEmpty());}
    @Test void actualStockAllocationUnlocksInputWithoutMakeCredit(){var s=new Scene();var producer=s.producedSticks();assertEquals(State.READY,s.root().state());assertFalse(s.jobs.materialReady(s.root()));assertEquals(0,s.root().fulfilled());var allocation=s.allocation(producer,2);assertTrue(s.allocate(allocation,s.stock(4)).accepted());s.ack();assertTrue(s.jobs.materialReady(s.root()));assertEquals(State.READY,s.root().state());assertEquals(0,s.root().fulfilled());assertEquals(1,((IntValue)s.jobs.bindWorker(s.root().id(),s.root().guard(),s.f.actor,s.f.owner).request().arguments().get("amount")).value());}
    @Test void consumedOutputReceiptDoesNotRecreateAvailableStock(){var s=new Scene();var p=s.producedSticks();var before=s.jobs.snapshot();assertEquals(Reason.STALE_OBSERVATION,s.allocate(s.allocation(p,2),s.stock(0)).reason());assertEquals(before,s.jobs.snapshot());}
    @Test void repeatGrantIsIdempotentAndChangedIdentityIsRejected(){var s=new Scene();var p=s.producedSticks();var a=s.allocation(p,2);assertTrue(s.allocate(a,s.stock(4)).accepted());s.ack();var before=s.jobs.snapshot();assertEquals(Code.DUPLICATE,s.allocate(a,s.stock(0)).code());assertEquals(Reason.REQUEST_INVALID,s.allocate(new Allocation(a.id(),a.producer(),a.attempt(),a.receipt(),a.demand(),1,a.item()),s.stock(4)).reason());assertEquals(before,s.jobs.snapshot());}
    @Test void oversizedOrSecondGrantCannotSpendSameIntentAgain(){var s=new Scene();var p=s.producedSticks();assertFalse(s.allocate(s.allocation(p,3),s.stock(4)).accepted());assertTrue(s.allocate(s.allocation(p,2),s.stock(4)).accepted());s.ack();assertFalse(s.allocate(s.allocation(p,1),s.stock(4)).accepted());}
    @Test void foreignPrincipalCannotInspectOrAllocatePrivateMaterial(){var s=new Scene();var p=s.producedSticks();var foreign=JobLifecycleStoreTest.owner(s.f.world);assertThrows(SecurityException.class,()->s.jobs.query(s.root().id(),foreign));assertEquals(Reason.AUTHORITY_DENIED,s.jobs.allocateMaterial(s.allocation(p,2),s.root().guard(),s.stock(4),100,foreign).reason());}
    @Test void legacyOutcomeAllocationNeverFulfillsMake(){var s=new Scene();var p=s.producedSticks();var a=s.allocation(p,1);assertEquals(Reason.REQUEST_INVALID,s.jobs.allocate(new Allocation(a.id(),a.producer(),a.attempt(),a.receipt(),a.demand(),1),s.root().guard(),s.f.owner).reason());}
    @Test void staleStockProofAndUnknownAreNotAvailability(){var s=new Scene();var p=s.producedSticks();var stock=s.stock(4);assertEquals(Reason.STALE_OBSERVATION,s.jobs.allocateMaterial(s.allocation(p,2),s.root().guard(),stock,111,s.f.owner).reason());assertEquals(Reason.STALE_OBSERVATION,s.allocate(s.allocation(p,2),new Supply(stock.item(),ObservationStatus.UNKNOWN,4,stock.identity(),stock.observation(),100,1,null)).reason());}
    @Test void producerUsageDebitsOriginalRootAndCancellationPreservesOutput(){var s=new Scene();var p=s.producedSticks();assertEquals(p.usage().get(Budgets.Kind.COMMITTED_EFFECTS),s.root().usage().get(Budgets.Kind.COMMITTED_EFFECTS));assertTrue(s.jobs.cancel(s.root().id(),s.root().guard(),s.f.owner).accepted());s.ack();assertEquals(State.CANCELLED,s.root().state());assertEquals(State.SUCCEEDED,s.child().state());assertEquals(4,s.child().fulfilled());}
    @Test void crashBeforeGraphAdmissionPreservesInactiveRoot(){var s=new Scene();var restart=s.store(s.disk.state);restart.tick();assertEquals(State.INTERRUPTED,restart.query(s.f.id,s.f.owner).state());assertTrue(restart.snapshot().jobs().getFirst().children().isEmpty());}
    @Test void crashAfterIntermediateDepositInterruptsConsumersWithoutReplayingProducer(){var s=new Scene();var p=s.producedSticks();var restart=s.store(s.disk.state);restart.tick();assertEquals(State.INTERRUPTED,restart.query(s.f.id,s.f.owner).state());assertEquals(State.SUCCEEDED,restart.query(p.id(),s.f.owner).state());assertEquals(4,restart.query(p.id(),s.f.owner).fulfilled());assertTrue(restart.snapshot().allocations().isEmpty());}
    @Test void schemaThreeRoundTripProtectsPreparedMethodAndMaterialIntents()throws Exception{var s=new Scene();s.admit(0,0);try(var journal=JobJournal.open(directory,s.f.world)){journal.replace(journal.snapshot(),new Snapshot(s.f.world,1,s.jobs.snapshot().jobs(),s.jobs.snapshot().allocations()));}try(var journal=JobJournal.open(directory,s.f.world)){assertEquals(s.jobs.snapshot().jobs(),journal.snapshot().jobs());assertEquals(s.jobs.snapshot().allocations(),journal.snapshot().allocations());assertFalse(journal.readOnly());}assertTrue(Files.readString(directory.resolve(JobJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl")).startsWith("{\"records\""));}
    @Test void materialAllocationCodecUsesSeparateBackwardCompatibleForm()throws Exception{var s=new Scene();var p=s.producedSticks();var a=s.allocation(p,2);assertTrue(s.allocate(a,s.stock(4)).accepted());s.ack();try(var journal=JobJournal.open(directory,s.f.world)){journal.replace(journal.snapshot(),new Snapshot(s.f.world,1,s.jobs.snapshot().jobs(),s.jobs.snapshot().allocations()));}try(var journal=JobJournal.open(directory,s.f.world)){assertEquals(s.jobs.snapshot().jobs(),journal.snapshot().jobs());assertEquals(s.jobs.snapshot().allocations(),journal.snapshot().allocations());assertTrue(journal.snapshot().allocations().getFirst().material());}}
    @ParameterizedTest @ValueSource(strings={"stock","catalog","method"})
    void changedAdmissionInputsPublishNoPartialExecutableGraph(String change){var s=new Scene();s.f.stock.put(ResourceCompositionTest.PLANK,5L);var p=s.propose();var catalog=s.f.catalog;if(change.equals("stock"))s.f.stock.put(ResourceCompositionTest.PLANK,4L);if(change.equals("catalog"))catalog=new Catalog(2,catalog.recipes(),catalog.productions(),Set.of(),true);if(change.equals("method"))s.f.methodReason=Reason.KNOWLEDGE_REQUIRED;var result=s.admission.submit(p,s.f.owner,catalog,s.f::inspect,s.f::method,n->true);assertNotEquals(ResolutionStatus.RESOLVED,result.status());if(result.publication().accepted())s.ack();assertTrue(s.root().children().isEmpty());assertNull(s.root().preparation().method());}
}
