package dev.aivillages.core.kernel.production.planning;

import java.util.*;
import org.junit.jupiter.api.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.RecipeChoice;
import dev.aivillages.core.kernel.runtime.activity.ActivityPlan;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;
import static dev.aivillages.core.kernel.production.planning.PlannerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic adversarial/projection fixtures. No trusted receipt or Minecraft outcome is manufactured. */
final class PlanningBoundaryTest {
    final StaticProductionPlanner planner=new StaticProductionPlanner();final PlanningCoordinator coordinator=new PlanningCoordinator();
    @Test void declaredNativeSegmentsFollowAcquisitionAndActualBatchGrouping() {
        var root=new PlannerFixtures().goal(Mode.MAKE_NEW,PLANK,3).logs(1);
        var plan=planner.analyse(root.snapshot()).plan();
        assertEquals(1,PlanningCoordinator.remainingSegments(root.snapshot(),plan,1));
        var stocked=new PlannerFixtures().logs(3);plan=planner.analyse(stocked.snapshot()).plan();
        assertEquals(7,plan.actions().size());
        assertEquals(6,PlanningCoordinator.remainingSegments(stocked.snapshot(),plan,1));
        plan=planner.analyse(stocked.snapshot(),1,5).plan();assertNotNull(plan);
        assertEquals(5,PlanningCoordinator.remainingSegments(stocked.snapshot(),plan,1));
        var shortage=new PlannerFixtures().logSource(3);plan=planner.analyse(shortage.snapshot()).plan();
        assertNotNull(plan);
        assertEquals(9,PlanningCoordinator.remainingSegments(shortage.snapshot(),plan,1));
        plan=planner.analyse(shortage.snapshot(),1,8).plan();assertNotNull(plan);
        assertEquals(8,PlanningCoordinator.remainingSegments(shortage.snapshot(),plan,1));
        assertTrue(plan.actions().subList(0,3).stream().allMatch(a->a.kind()==Kind.ACQUIRE));
        shortage.settle(plan,0,3,0);
        assertEquals(5,PlanningCoordinator.remainingSegments(shortage.snapshot(),plan,1));
        assertThrows(IllegalArgumentException.class,()->PlanningCoordinator.remainingSegments(root.snapshot(),
                planner.analyse(root.snapshot()).plan(),0));
    }
    @Test void pollingStampIgnoresTimeAndPublicationRevisionButDetectsSupplyAndBudgetChanges(){
        var f=new PlannerFixtures().goal(Mode.MAKE_NEW,PLANK,3);
        var initial=PlanningCoordinator.observationStamp(f.snapshot());
        f.now+=5000;f.revision++;
        assertEquals(initial,PlanningCoordinator.observationStamp(f.snapshot()));
        f.logs(1);var supplied=PlanningCoordinator.observationStamp(f.snapshot());assertNotEquals(initial,supplied);
        var used=new EnumMap<Counter,Long>(Counter.class);used.putAll(zeroCounts());used.put(Counter.TRAVEL,1L);
        f.budget=new Budget(f.budget.original(),used,f.budget.absoluteDeadlineMillis());
        assertNotEquals(supplied,PlanningCoordinator.observationStamp(f.snapshot()));
        var charged=PlanningCoordinator.observationStamp(f.snapshot());
        var recipe=f.recipes.removeFirst();
        f.recipes.add(new RecipeProfile(recipe.recipe(),recipe.capability(),recipe.cells(),recipe.result(),
                recipe.semanticFingerprint(),recipe.method(),new Cost(80,3,200),recipe.reloadEpoch()));
        assertNotEquals(charged,PlanningCoordinator.observationStamp(f.snapshot()));
    }
    @Test void suppliedStockWakesAStoppedDecisionWithoutRenewingOriginalMemoryOrAllowance(){
        var f=new PlannerFixtures().goal(Mode.MAKE_NEW,PLANK,3);
        var used=new EnumMap<Counter,Long>(Counter.class);used.putAll(zeroCounts());used.put(Counter.TRAVEL,5L);
        f.budget=new Budget(f.budget.original(),used,f.budget.absoluteDeadlineMillis());
        var memory=Memory.empty();
        for(int n=0;n<6;n++){var d=coordinator.next(f.snapshot(),null,memory);memory=d.memory();f.now+=2500;}
        assertEquals(Long.MAX_VALUE,memory.nextCheckMillis());assertEquals(4,memory.unchangedChecks());
        var stopped=memory;f.logs(1);
        var wanted=coordinator.next(f.snapshot(),null,stopped);assertNotNull(wanted.neededChoice());
        var built=PlanningChoiceBuilder.build(f.snapshot(),wanted.neededChoice());
        f.choices.add(new ChoiceOffer(new ValidatedRequest(built.invocation(),f.context,
                new ObservationRef(id("supply"),f.revision,DIM)),built.choice(),built.sources()));
        var resumed=coordinator.next(f.snapshot(),null,stopped);
        assertEquals(DecisionKind.PROPOSE,resumed.kind());
        assertEquals(stopped.originalBudgetDigest(),resumed.memory().originalBudgetDigest());
        assertEquals(stopped.originalDeadlineMillis(),resumed.memory().originalDeadlineMillis());
        assertEquals(5,resumed.memory().observedUsage().get(Counter.TRAVEL));
        assertEquals(0,resumed.memory().observedUsage().get(Counter.MODEL_CALLS));
    }
    @Test void movingBetweenInfeasibleRoutesDoesNotRenewAnActivityPlanningDecision() {
        var fixture=new PlannerFixtures().logSource(3);
        fixture.budget=budget(128,64,1800,0,0,256,151000);
        var source=fixture.acquisition.getFirst();
        var site=fixture.facility;
        var memory=Memory.empty();
        int persisted=0;
        String priorPoll="";
        for(int n=0;n<36;n++){
            long walking=18+n%3;
            gatheringQuotes(fixture,source,site,walking,10,16);
            var snapshot=fixture.snapshot();
            String poll=PlanningCoordinator.observationStamp(snapshot);
            assertNotEquals(priorPoll,poll);
            priorPoll=poll;
            var blocked=coordinator.nextActivity(snapshot,null,memory,1,8,true);
            assertNull(blocked.proposal());
            if(!blocked.memory().equals(memory))persisted++;
            memory=blocked.memory();
            fixture.now+=500;
        }
        assertEquals(5,persisted);
        assertEquals(4,memory.unchangedChecks());
        assertEquals(Long.MAX_VALUE,memory.nextCheckMillis());

        gatheringQuotes(fixture,source,site,13,7,13);
        var available=coordinator.nextActivity(fixture.snapshot(),null,memory,1,8,true);
        assertEquals(DecisionKind.PROPOSE,available.kind(),available.blocker());
        assertEquals(Kind.ACQUIRE,available.proposal().kind());
        assertEquals(1768,available.analysis().allowance().ticks());
        assertEquals(1800,fixture.budget.original().get(Counter.ELAPSED_TICKS));
        assertEquals(memory.originalDeadlineMillis(),available.memory().originalDeadlineMillis());
    }

    private static void gatheringQuotes(PlannerFixtures f,Acquisition source,Facility site,
                                        long acquiring,long processing,long placement) {
        f.acquisition.set(0,new Acquisition(source.key(),source.source(),source.result(),source.availableUnits(),
                source.availability(),source.permitted(),source.reachable(),source.method(),new Cost(acquiring,4,144+6*acquiring)));
        f.facility=new Facility(site.reference(),site.availability(),site.permitted(),site.reachable(),site.identity(),
                site.approvedSiteFree(),site.relevantRevision(),site.placement(),new Cost(placement,3,100+6*placement));
        f.recipes.replaceAll(r->new RecipeProfile(r.recipe(),r.capability(),r.cells(),r.result(),r.semanticFingerprint(),
                r.method(),new Cost(r.axe()?19:processing,4,r.axe()?214:100+6*processing),r.reloadEpoch()));
    }
    @Test void persistedPlanReusesOriginalMethodsAndRevalidatesActualIntermediateStock() throws Exception {
        var fixture=new PlannerFixtures().logs(3);
        var original=planner.analyse(fixture.snapshot()).plan();
        var packed=ActivityPlan.of(original);
        var bytes=StrictJson.canonical(packed.encode());
        var decoded=ActivityPlan.decode(StrictJson.object(bytes));
        assertEquals(packed,decoded);
        assertEquals(original,decoded.bind(fixture.snapshot()));
        var first=original.actions().getFirst();
        assertEquals(Kind.PRODUCE,first.kind());
        fixture.stock.clear();
        fixture.stock.add(fixture.stock("remaining-logs",0,LOG,2));
        fixture.stock.add(fixture.stock("observed-checkpoint-planks",1,PLANK,first.yield()));
        fixture.settle(original,0,1,0);
        assertEquals(Feasibility.FEASIBLE,planner.revalidate(fixture.snapshot(),decoded.bind(fixture.snapshot())).feasibility());
        fixture.stock.clear();
        assertNotEquals(Feasibility.FEASIBLE,planner.revalidate(fixture.snapshot(),decoded.bind(fixture.snapshot())).feasibility());
        fixture.destination=new ContainerRef(DIM,81,64,0);
        fixture.context=new TrustedContext(new PrincipalRef(id("changed-caller")),fixture.context.scope());
        assertThrows(IllegalArgumentException.class,()->decoded.bind(fixture.snapshot()));
    }
    @Test void persistedPlanPreservesNativeComponentsBeyondTheJobStringLimit() throws Exception {
        var fixture=new PlannerFixtures().logs(3);
        var profile=fixture.recipes.getLast();
        var components="{\"minecraft:custom_data\":{\"text\":\""+"a".repeat(1_500)+"\"}}";
        var axe=new RecipeChoice.ItemKey(AXE.id(),components);
        fixture.output=axe;
        fixture.recipes.set(fixture.recipes.size()-1,new RecipeProfile(profile.recipe(),profile.capability(),
                profile.cells(),axe,profile.semanticFingerprint(),profile.method(),profile.allowance()));
        var frame=fixture.snapshot();
        var original=planner.analyse(frame).plan();
        var packed=ActivityPlan.of(original);
        String bytes=StrictJson.canonical(packed.encode());
        assertTrue(bytes.contains("\"deflatedBody\""));
        var restored=ActivityPlan.decode(StrictJson.object(bytes));
        assertEquals(packed,restored);
        assertEquals(original,restored.bind(frame));
        assertEquals(packed.tokens(),restored.tokens());
    }
    @Test void anExactBirchChildStagesThreeAndKeepsTheFourthAwayFromParentAllocation() throws Exception {
        var fixture=new PlannerFixtures().existingTable();
        var birchLog=item("birch_log");
        fixture.recipes.add(fixture.profile(BirchPlankProduction.ID,List.of(List.of(birchLog)),BIRCH));
        fixture.stock.add(fixture.stock("birch-input",0,birchLog,1));
        fixture.stock.add(fixture.stock("ready-sticks",1,STICK,2));
        var wanted=coordinator.nextActivity(fixture.snapshot(),null,Memory.empty(),1,8,true);
        assertNotNull(wanted.neededChoice(),wanted.blocker());
        assertEquals(3,wanted.neededChoice().nativeAmount());
        assertEquals(1,wanted.analysis().residual().get(BIRCH));
        var plan=ActivityPlan.of(wanted.analysis().plan(),true);
        assertEquals(plan,ActivityPlan.decode(StrictJson.object(StrictJson.canonical(plan.encode()))));
        var choice=PlanningChoiceBuilder.build(fixture.snapshot(),wanted.neededChoice());
        fixture.choices.add(new ChoiceOffer(new ValidatedRequest(choice.invocation(),fixture.context,
                new ObservationRef(id("birch-child-observation"),fixture.revision,DIM)),choice.choice(),choice.sources()));
        var offered=coordinator.nextActivity(fixture.snapshot(),plan.bind(fixture.snapshot()),Memory.empty(),1,8,true);
        assertEquals(DecisionKind.PROPOSE,offered.kind(),offered.blocker());
        var quantities=offered.proposal().quantities();
        assertEquals(4,quantities.newPhysicalOutput());
        assertEquals(3,quantities.nativeDelivery());
        assertEquals(3,quantities.parentAllocationUpperBound());
        assertEquals(1,quantities.actorSurplus());
        assertEquals(0,quantities.goalAllocationUpperBound());
        fixture.choices.clear();
        fixture.stock.removeFirst();
        fixture.stock.add(fixture.stock("exact-child-delivery",2,BIRCH,3));
        fixture.stock.add(fixture.actorStock("child-surplus-in-actor",0,BIRCH,1));
        fixture.settle(wanted.analysis().plan(),0,1,0);
        assertEquals(Feasibility.FEASIBLE,planner.revalidate(fixture.snapshot(),plan.bind(fixture.snapshot()),true).feasibility());
        fixture.stock.removeIf(stock->stock.key().endsWith("exact-child-delivery"));
        assertEquals(Feasibility.UNKNOWN,planner.revalidate(fixture.snapshot(),plan.bind(fixture.snapshot()),true).feasibility());
    }
    @Test void exhaustedComputeBoundReturnsUnknownRatherThanAnInventedImpossibility(){var f=new PlannerFixtures().logs(3);var bounded=new StaticProductionPlanner(1).analyse(f.snapshot());assertEquals(1,bounded.expansions());assertEquals(Feasibility.UNKNOWN,bounded.feasibility());assertNull(bounded.plan());assertEquals(Feasibility.FEASIBLE,planner.analyse(f.snapshot()).feasibility());}
    @Test void onePhysicalSlotOrAcquisitionRegionCannotBeCountedTwice(){var f=new PlannerFixtures().logs(1);f.stock.add(f.stock("duplicate-physical-slot",0,LOG,1));assertThrows(IllegalArgumentException.class,f::snapshot);var trees=new PlannerFixtures().logSource(7);var a=trees.acquisition.getFirst();trees.acquisition.add(new Acquisition("fixture:overlapping-tree-query",a.source(),a.result(),7,a.availability(),true,true,a.method(),a.allowance()));assertThrows(IllegalArgumentException.class,trees::snapshot);}
    @Test void availableAndObservedCountsRemainDistinctInConcreteBindings(){var f=new PlannerFixtures().goal(Mode.MAKE_NEW,PLANK,3);f.stock.add(new Stock("fixture:reserved-neighbours",new Location(f.source,null,0),LOG,1,16,0,null,List.of(),Eligibility.NONE,Availability.PRESENT,true,true,0,0));var wanted=coordinator.next(f.snapshot(),null,Memory.empty());var prepared=f.withChoice(wanted);assertEquals(DecisionKind.PROPOSE,prepared.kind());assertEquals(16,prepared.proposal().nativeChoice().choice().batch(0).bindings().getFirst().expectedCount());assertEquals(1,prepared.proposal().bindings().getFirst().units());}
    @Test void exactCellAssignmentPreservesARestrictedLaterIngredient(){var f=new PlannerFixtures().goal(Mode.MAKE_NEW,STICK,1);f.stock.add(f.stock("oak-first",0,PLANK,1));f.stock.add(f.stock("birch-second",1,BIRCH,1));var p=f.recipes.get(1);f.recipes.set(1,new RecipeProfile(p.recipe(),p.capability(),List.of(List.of(PLANK,BIRCH),List.of(PLANK)),p.result(),p.semanticFingerprint(),p.method(),p.allowance()));var missing=coordinator.next(f.snapshot(),null,Memory.empty());var built=PlanningChoiceBuilder.build(f.snapshot(),missing.neededChoice());assertEquals(BIRCH,built.choice().batch(0).grid().get(0));assertEquals(PLANK,built.choice().batch(0).grid().get(2));assertEquals(DecisionKind.PROPOSE,f.withChoice(missing).kind());}
    @Test void equivalentReadOnlyViewOrderingKeepsTheSameProposalAndStepTokens(){var f=new PlannerFixtures().goal(Mode.MAKE_NEW,STICK,1);f.stock.add(f.stock("oak",0,PLANK,1));f.stock.add(f.stock("birch",1,BIRCH,1));var wanted=coordinator.next(f.snapshot(),null,Memory.empty());var first=f.withChoice(wanted);Collections.reverse(f.stock);Collections.reverse(f.recipes);var second=coordinator.next(f.snapshot(),first.analysis().plan(),Memory.empty());assertEquals(first.proposal().stepToken(),second.proposal().stepToken());assertEquals(first.proposal().proposalId(),second.proposal().proposalId());}
    static Map<String,Object> model(Analysis a){var rows=new ArrayList<Object>();for(int i=0;i<a.plan().actions().size();i++)rows.add(Map.of("id","fixture:node-"+i,"actionToken",a.plan().actions().get(i).token(i),"dependsOn",i==0?List.of():List.of("fixture:node-"+(i-1))));return new HashMap<>(Map.of("schema",1,"goalDigest",a.plan().goal().digest(),"steps",rows));}
    @Test void modelMayComposeOnlyTheCompleteCompilerCatalogueAndReturnedPlanStaysImmutable(){var f=new PlannerFixtures().logs(3);var a=planner.analyse(f.snapshot());var proposal=model(a);String before=a.plan().digest();assertSame(a.plan(),ModelPlanValidator.validate(StrictJson.canonical(proposal),f.snapshot(),a));assertEquals(before,a.plan().digest());assertThrows(UnsupportedOperationException.class,()->a.plan().actions().clear());assertEquals(a.plan().actions().size(),((List<?>)ModelPlanValidator.catalogue(a).get("actions")).size());}
    @TestFactory List<DynamicTest> modelCannotInventResultsReceiptsAuthorityBudgetsBindingsOrCompletedSteps(){var tests=new ArrayList<DynamicTest>();for(var field:List.of("receipt","receipts","completedSteps","result","authority","budget","deadline","destination","executor","confirmedCredit"))tests.add(DynamicTest.dynamicTest("reject forged model field "+field,()->{var f=new PlannerFixtures().logs(3);var a=planner.analyse(f.snapshot());var value=model(a);value.put(field,"fixture:forged");assertThrows(IllegalArgumentException.class,()->ModelPlanValidator.validate(StrictJson.canonical(value),f.snapshot(),a));}));return tests;}
    @Test void wrongGoalInventedOperationCycleAndOversizedGraphRefuse(){var f=new PlannerFixtures().logs(3);var a=planner.analyse(f.snapshot());var wrong=model(a);wrong.put("goalDigest","0".repeat(64));assertThrows(IllegalArgumentException.class,()->ModelPlanValidator.validate(StrictJson.canonical(wrong),f.snapshot(),a));
        var invented=model(a);invented.put("steps",List.of(Map.of("id","fixture:invented","actionToken","minecraft:teleport_and_spawn_axe","dependsOn",List.of())));assertThrows(IllegalArgumentException.class,()->ModelPlanValidator.validate(StrictJson.canonical(invented),f.snapshot(),a));
        var cycle=model(a);var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<a.plan().actions().size();i++)rows.add(Map.of("id","fixture:node-"+i,"actionToken",a.plan().actions().get(i).token(i),"dependsOn",List.of("fixture:node-"+((i+1)%a.plan().actions().size()))));cycle.put("steps",rows);assertThrows(IllegalArgumentException.class,()->ModelPlanValidator.validate(StrictJson.canonical(cycle),f.snapshot(),a));
        var oversized=model(a);oversized.put("steps",Collections.nCopies(33,Map.of("id","fixture:x","actionToken",a.plan().actions().getFirst().token(0),"dependsOn",List.of())));assertThrows(IllegalArgumentException.class,()->ModelPlanValidator.validate(StrictJson.canonical(oversized),f.snapshot(),a));assertThrows(IllegalArgumentException.class,()->ModelPlanValidator.validate(" ".repeat(32769),f.snapshot(),a));}
    @Test void unknownFeasibilityAndMissingMaterialDependencyCannotBeSmuggledThroughModelValidation(){var f=new PlannerFixtures().logs(3);var a=planner.analyse(f.snapshot());var missing=model(a);var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<a.plan().actions().size();i++)rows.add(Map.of("id","fixture:node-"+i,"actionToken",a.plan().actions().get(i).token(i),"dependsOn",List.of()));missing.put("steps",rows);assertThrows(IllegalArgumentException.class,()->ModelPlanValidator.validate(StrictJson.canonical(missing),f.snapshot(),a));f.complete=false;f.stock.clear();var unknown=planner.analyse(f.snapshot());assertEquals(Feasibility.UNKNOWN,unknown.feasibility());assertThrows(IllegalArgumentException.class,()->ModelPlanValidator.validate(StrictJson.canonical(model(a)),f.snapshot(),unknown));}
    @Test void typedSavedPlanCannotInventAMaterialResultOrEraseItsMethodPin(){var f=new PlannerFixtures().goal(Mode.ACQUIRE_NEW,LOG,1).logSource(1);var plan=planner.analyse(f.snapshot()).plan();var old=plan.actions().getFirst();var fake=new Action(old.kind(),old.recipe(),old.capability(),old.inputs(),AXE,1,1,true,old.allowance(),old.sourceKey());var changed=new ValidatedPlan(plan.world(),plan.root(),plan.owner(),plan.context(),plan.goal(),plan.budgetInitialDigest(),plan.originalDeadlineMillis(),List.of(fake),plan.methodPins());assertEquals(Feasibility.UNKNOWN,planner.revalidate(f.snapshot(),changed).feasibility());
        var missing=new ValidatedPlan(plan.world(),plan.root(),plan.owner(),plan.context(),plan.goal(),plan.budgetInitialDigest(),plan.originalDeadlineMillis(),plan.actions(),Map.of());assertEquals(Feasibility.UNKNOWN,planner.revalidate(f.snapshot(),missing).feasibility());}
    @Test void returnOfKnownActorCustodyUsesFinitePolicyAndCreatesNoGoalCredit(){var f=new PlannerFixtures().goal(Mode.MAKE_NEW,STICK,1);f.returns=true;f.returnLimit=1;f.stock.add(f.actorStock("held-planks",0,PLANK,2));var result=coordinator.next(f.snapshot(),null,Memory.empty());assertEquals(DecisionKind.PROPOSE,result.kind(),result.blocker());assertEquals(Kind.RETURN_CUSTODY,result.proposal().kind());assertEquals(2,result.proposal().quantities().nativeDelivery());assertEquals(0,result.proposal().quantities().goalAllocationUpperBound());assertEquals(0,result.proposal().quantities().newPhysicalOutput());assertEquals(f.source,result.proposal().destination());assertNull(result.proposal().requestedInvocation(),"A must use the explicit transfer adapter, never replay a producer from its start");f.returnsUsed=1;assertEquals(Feasibility.INFEASIBLE,planner.analyse(f.snapshot()).feasibility());assertNull(coordinator.next(f.snapshot(),null,Memory.empty()).proposal());}
    @Test void actorCustodyWithoutReturnPolicyCannotBeConsumedAsContainerInput(){var f=new PlannerFixtures().goal(Mode.MAKE_NEW,STICK,1);f.stock.add(f.actorStock("held",0,PLANK,2));assertEquals(Feasibility.INFEASIBLE,planner.analyse(f.snapshot()).feasibility());assertNull(coordinator.next(f.snapshot(),null,Memory.empty()).proposal());}
    @Test void explicitOriginalHeldSticksBindPhysicalSlotsWithoutCreatingContainerClaims(){
        var fixture=new PlannerFixtures().goal(Mode.MAKE_NEW,AXE,2).existingTable();
        fixture.stock.add(fixture.stock("planks",1,PLANK,6));
        fixture.stock.add(fixture.actorStock("sticks",0,STICK,4));
        assertEquals(Feasibility.INFEASIBLE,planner.analyse(fixture.snapshot()).feasibility());
        fixture.useHeldSticks=true;
        var missing=coordinator.next(fixture.snapshot(),null,Memory.empty());
        assertNotNull(missing.neededChoice());
        var next=fixture.withChoice(missing);
        assertNotNull(next.proposal(),next.blocker());
        var offer=next.proposal().nativeChoice();var choice=offer.choice();
        assertEquals(fixture.actor,offer.sources().heldSticks());
        assertEquals(List.of(4,3,2,1),choice.batches().stream().flatMap(batch->batch.bindings().stream())
                .filter(b->fixture.actor.equals(b.heldBy()) && b.sourceSlot()==0).map(RecipeChoice.Binding::expectedCount).toList());
        assertEquals(List.of(3,3),choice.batches().stream().map(batch->batch.withdrawals().size()).toList());
        var plan=ResourceComposition.proposeChoice(fixture.root,fixture.revision,offer.request(),choice,offer.sources());
        assertEquals(1,plan.stock().size());assertEquals(6,plan.stock().getFirst().units());
        assertThrows(IllegalArgumentException.class,()->ResourceComposition.proposeChoice(fixture.root,fixture.revision,offer.request(),choice));
    }
    @Test void memoryCannotMoveBetweenRootsGoalsPrincipalsOrRenewBudget(){var f=new PlannerFixtures().goal(Mode.ACQUIRE_NEW,LOG,1).logSource(1);var first=coordinator.next(f.snapshot(),null,Memory.empty());f.root=id("different-root");assertEquals("DECISION_MEMORY_SCOPE_MISMATCH",coordinator.next(f.snapshot(),null,first.memory()).blocker());f.root=first.proposal().root();f.demand=2;assertEquals("ORIGINAL_GOAL_CHANGED",coordinator.next(f.snapshot(),null,first.memory()).blocker());f.demand=1;f.context=new TrustedContext(new PrincipalRef(id("other-principal")),f.context.scope());assertEquals("DECISION_MEMORY_SCOPE_MISMATCH",coordinator.next(f.snapshot(),null,first.memory()).blocker());}
    @Test void scopeMismatchAndFutureOrForeignSettlementEvidenceRefuse(){var f=new PlannerFixtures().goal(Mode.ACQUIRE_NEW,LOG,1).logSource(1);var a=planner.analyse(f.snapshot());f.context=new TrustedContext(new PrincipalRef(id("foreign-principal")),f.context.scope());assertEquals(Feasibility.INFEASIBLE,planner.revalidate(f.snapshot(),a.plan()).feasibility());f.progress.add(new SettledProgress(id("other-root"),"fixture:action","fixture:step",id("attempt"),"fixture:effect",List.of("fixture:receipt"),List.of("fixture:settlement"),SettlementKind.ACCEPTED_EFFECT,1,0,0));assertThrows(IllegalArgumentException.class,f::snapshot);}
    @Test void privateCountsUnsupportedVersionsAndOverflowRefuse(){var f=new PlannerFixtures();assertThrows(IllegalArgumentException.class,()->new Stock("fixture:private",new Location(f.source,null,0),LOG,1,1,0,null,List.of(),Eligibility.NONE,Availability.PRESENT,false,true,0,0));assertThrows(IllegalArgumentException.class,()->new MethodPin(OakPlankProduction.ID,"0".repeat(64),"0".repeat(64),true,2,1));assertThrows(ArithmeticException.class,()->new Cost(Long.MAX_VALUE,1,1).times(2));assertThrows(ArithmeticException.class,()->new Quantities(1,1,Long.MAX_VALUE,1,0,0,0,0,0));
        var p=f.recipes.getFirst();assertThrows(IllegalArgumentException.class,()->new ChoiceRequest(p.capability(),3,1,Map.of(LOG,-1L),f.source,p.semanticFingerprint(),List.of(f.source)));}
    @Test void checkedAvailableUnitsPreventConsumptionOfAnotherAllocation(){var f=new PlannerFixtures().goal(Mode.MAKE_NEW,PLANK,7);f.stock.add(new Stock("fixture:partly-reserved",new Location(f.source,null,0),LOG,1,16,0,null,List.of(),Eligibility.NONE,Availability.PRESENT,true,true,0,0));assertEquals(Feasibility.INFEASIBLE,planner.analyse(f.snapshot()).feasibility());assertNull(coordinator.next(f.snapshot(),null,Memory.empty()).proposal());}
}
