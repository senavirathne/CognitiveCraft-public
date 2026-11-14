package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.production.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic serialization/proposal fixtures; no native outcome or trusted stock is asserted. */
final class RecipeChoiceTest {
    static final ContainerRef SOURCE=new ContainerRef("minecraft:overworld",1,2,3),DESTINATION=new ContainerRef("minecraft:overworld",4,2,3);
    static final RecipeChoice.ItemKey WOOD=new RecipeChoice.ItemKey("minecraft:birch_wood","{}"),PLANK=new RecipeChoice.ItemKey("minecraft:birch_planks","{}");
    static RecipeChoice choice(int demand) {
        int count=demand/4+(demand%4==0?0:1);var batches=new ArrayList<RecipeChoice.Batch>();
        for(int i=0;i<count;i++)batches.add(new RecipeChoice.Batch(List.of(WOOD,RecipeChoice.ItemKey.EMPTY,RecipeChoice.ItemKey.EMPTY,RecipeChoice.ItemKey.EMPTY),
                List.of(new RecipeChoice.Binding(0,SOURCE,26,count-i,WOOD))));
        return new RecipeChoice(new CapabilityId(BirchPlankProduction.ID.name(),2),BirchPlankProduction.RECIPE,"a".repeat(64),RecipeChoice.RUNTIME,0,2,2,batches,PLANK,4,demand,List.of());
    }
    static ValidatedRequest request(RecipeChoice c,long amount) {
        var actor=JobLifecycleStoreTest.actor();var owner=JobLifecycleStoreTest.owner(UUID.randomUUID());
        return new ValidatedRequest(new CapabilityRequest(c.capability(),Map.of("actor",new ActorValue(actor),"source",new ContainerValue(SOURCE),
                "destination",new ContainerValue(DESTINATION),"amount",new IntValue(amount))),owner,new ObservationRef(UUID.randomUUID(),0,actor.dimension()));
    }
    @Test void canonicalBytesRoundTripAndExactSelectedInputsSurviveEveryPureBoundary() {
        var c=choice(11);assertEquals(c,RecipeChoice.fromCanonical(c.canonical()));assertEquals(c.digest(),RecipeChoice.fromCanonical(c.canonical()).digest());
        assertEquals(Map.of(WOOD,3L),c.exactInputs());assertEquals(12,c.quantities().physicalOutput());assertEquals(1,c.quantities().surplus());
        assertEquals(List.of(4L,4L,3L),java.util.stream.IntStream.range(0,3).mapToObj(c::deliveryForBatch).toList());
        var plan=new DeterministicPrerequisiteResolver(Prerequisites.Limits.defaults(),()->0).describeChoice(UUID.randomUUID(),7,request(c,11),c);
        assertEquals(c.digest(),plan.choiceDigest());assertEquals(3,plan.stock().getFirst().units());assertEquals(WOOD,plan.stock().getFirst().item());
        assertTrue(plan.stock().getFirst().proposedKey().contains(WOOD.componentDigest()));
        assertFalse(RecipeProduction.is(c.capability()),"New choice was registered on the unchanged legacy route");
    }
    @Test void unknownFieldsWrongVersionsNoncanonicalBytesAndCountsRefuse() {
        var c=choice(3);assertThrows(IllegalArgumentException.class,()->RecipeChoice.fromCanonical(c.canonical().replace("\"schema\":1","\"schema\":1,\"receipt\":true")));
        assertThrows(IllegalArgumentException.class,()->RecipeChoice.fromCanonical(" "+c.canonical()));
        assertThrows(IllegalArgumentException.class,()->RecipeChoice.fromCanonical(c.canonical().replace("\"demand\":3","\"demand\":3.0")));
        assertThrows(IllegalArgumentException.class,()->RecipeChoice.fromCanonical(c.canonical().replace("\"version\":2","\"version\":1")));
        assertThrows(IllegalArgumentException.class,()->ResourceComposition.proposeChoice(UUID.randomUUID(),0,request(c,4),c));
        assertThrows(IllegalArgumentException.class,()->new RecipeChoice.ItemKey("minecraft:air","{\"x\":1}"));
    }
    @Test void immutableGridOrderComponentsAndSourceSlotsArePartOfTheChoice() {
        var c=choice(3);var grid=new ArrayList<>(c.batch(0).grid());var bindings=new ArrayList<>(c.batch(0).bindings());
        var batch=new RecipeChoice.Batch(grid,bindings);grid.set(0,RecipeChoice.ItemKey.EMPTY);bindings.clear();
        assertEquals(c.batch(0),batch);assertThrows(UnsupportedOperationException.class,()->batch.grid().clear());
        assertThrows(IllegalArgumentException.class,()->new RecipeChoice.Batch(List.of(RecipeChoice.ItemKey.EMPTY,WOOD),c.batch(0).bindings()));
        assertThrows(IllegalArgumentException.class,()->new RecipeChoice.ItemKey("minecraft:birch_wood","{\"b\":1,\"a\":2}"));
        var changed=new RecipeChoice.Binding(0,SOURCE,25,1,WOOD);
        var b=new RecipeChoice.Batch(c.batch(0).grid(),List.of(changed));
        var other=new RecipeChoice(c.capability(),c.recipe(),c.semanticFingerprint(),c.runtimeCompatibility(),0,2,2,List.of(b),c.result(),4,3,List.of());
        assertNotEquals(c.digest(),other.digest());
    }
    @Test void currentScopeAndSourceBindingsCannotBeSilentlySubstituted() {
        var c=choice(3);var r=request(c,3);var args=new HashMap<>(r.request().arguments());
        args.put("source",new ContainerValue(DESTINATION));var changed=new ValidatedRequest(new CapabilityRequest(c.capability(),args),r.context(),r.observation());
        assertThrows(IllegalArgumentException.class,()->ResourceComposition.proposeChoice(UUID.randomUUID(),0,changed,c));
        assertThrows(IllegalArgumentException.class,()->ResourceComposition.proposeChoice(UUID.randomUUID(),-1,r,c));
    }
    @Test void baselineRuntimeAdmissionExplicitlyRejectsTheUnwiredVersionTwoContract() {
        var c=choice(3);var request=request(c,3);
        var result=RequestValidator.validate(request.request(),request.context(),request.observation(),PlanningGoals.catalog(),JobLifecycleStoreTest.environment());
        var rejected=assertInstanceOf(RequestValidator.Rejected.class,result);
        assertEquals(Outcomes.Reason.REQUEST_INVALID,rejected.reason());assertEquals("$.capability",rejected.path());
    }
}
