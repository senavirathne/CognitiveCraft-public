package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SurvivalGateway.*;
import static dev.aivillages.core.kernel.WorldReferenceDiscovery.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import static org.junit.jupiter.api.Assertions.*;

class WorldReferenceDiscoveryTest {
    static final String DIM = "minecraft:overworld";
    static final Point ORIGIN = point(0,64,0);
    static final ActorRef ACTOR = new ActorRef(new UUID(0,1),new UUID(0,2),DIM);
    static Point point(int x,int y,int z) { return new Point(DIM,x,y,z); }
    static final class Fake implements Observation {
        final Map<Point,Boolean> crops = new HashMap<>();
        final Set<Point> unknown = new HashSet<>();
        final Map<ContainerRef,ContainerSample> boxes = new HashMap<>();
        final Map<ActorRef,ActorState> actors = new HashMap<>();
        int cells,entities,slots;
        public ActorState actor(ActorRef actor) {
            entities++; return actors.getOrDefault(actor,new ActorState(false,false,DIM,0,64,0,0));
        }
        public CropState crop(ActorRef actor,Point p) {
            cells++;
            return new CropState(unknown.contains(p) ? ObservationStatus.UNKNOWN
                    : crops.containsKey(p) ? ObservationStatus.PRESENT : ObservationStatus.ABSENT,
                    Boolean.TRUE.equals(crops.get(p)),p.toString());
        }
        public ContainerSample container(ActorRef actor,ContainerRef box) {
            cells++; var p = point(box.x(),box.y(),box.z());
            if (unknown.contains(p)) return new ContainerSample(ObservationStatus.UNKNOWN,0,0,"unknown");
            var sample = boxes.getOrDefault(box,new ContainerSample(ObservationStatus.ABSENT,0,0,"absent"));
            slots += sample.slotsInspected(); return sample;
        }
        void patch(int x,int z,int amount) { for (int i=0;i<amount;i++) crops.put(point(x+i,64,z),true); }
        void box(int x,int z,int capacity) {
            var box = new ContainerRef(DIM,x,64,z);
            boxes.put(box,new ContainerSample(ObservationStatus.PRESENT,capacity,27,"chest:"+x+":"+z));
        }
    }
    static <T> Result<T> finish(Kind kind,Point anchor,Discovery<T> discovery) {
        Registry registry = new Registry(); var criterion = new Criteria("test:criterion");
        registry.register(kind,criterion,ignored -> discovery);
        Attempt<T> attempt = registry.start(new Search(kind,Relation.NEAREST,anchor,criterion,
                new WorldReferenceResolver.Limits(WORK_PER_POLL,TOTAL_WORK,MAX_CANDIDATES,MAX_DIAGNOSTICS,100000)),0);
        Result<T> result; long last = 0; int polls = 0;
        do {
            result = attempt.poll(++polls);
            assertTrue(result.chargedWork()-last <= WORK_PER_POLL); last = result.chargedWork();
            assertTrue(polls < 1000,"bounded termination");
        } while (result.phase() == Phase.RESOLVING);
        assertTrue(result.chargedWork() <= TOTAL_WORK);
        return result;
    }
    static Result<WheatField> field(Fake world,int amount) { return finish(Kind.AREA,ORIGIN,wheat(ACTOR,amount,ORIGIN,world)); }

    @Test void coherentConnectedCropsQualifyWhileIsolatedDiagonalAndDisconnectedCropsDoNot() {
        var world = new Fake(); world.patch(2,0,2);
        assertEquals(Phase.RESOLVED,field(world,2).phase());
        world.crops.clear(); world.patch(2,0,1);
        assertEquals(Reason.RESOURCE_MISSING,field(world,1).reason());
        world.crops.put(point(3,64,1),true);
        assertEquals(Reason.RESOURCE_MISSING,field(world,2).reason());
        world.crops.clear(); world.patch(2,0,2); world.patch(6,0,2);
        assertEquals(Reason.RESOURCE_MISSING,field(world,3).reason());
    }
    @Test void immatureCellsConnectButCannotSupplyWheat() {
        var world = new Fake(); world.patch(2,0,3); world.crops.put(point(3,64,0),false);
        assertEquals(Phase.RESOLVED,field(world,2).phase());
        assertEquals(Reason.RESOURCE_MISSING,field(world,3).reason());
    }
    @Test void nearestInsufficientFieldDoesNotConcealFartherSufficientField() {
        var world = new Fake(); world.patch(1,0,2); world.crops.put(point(2,64,0),false); world.patch(6,0,4);
        var selected = field(world,4).value().area();
        assertEquals(new Cuboid(DIM,6,64,0,9,64,0),selected);
    }
    @Test void fieldDistanceUsesNearestMatureMemberAndStableCoordinates() {
        var world = new Fake(); world.patch(-5,0,2); world.patch(4,0,2);
        assertEquals(-5,field(world,2).value().area().minX());
        var reversed = new Fake(); world.crops.entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparingInt(Point::x).reversed())).forEach(e -> reversed.crops.put(e.getKey(),e.getValue()));
        assertEquals(field(world,2).value().area(),field(reversed,2).value().area());
    }
    @Test void cancelledStrategiesReleaseTheirScanAndCannotObserveAgain() {
        var world = new Fake(); world.patch(0,0,4); world.box(2,0,64);
        var field = wheat(ACTOR,4,ORIGIN,world);
        field.poll(WORK_PER_POLL); field.cancel(); int cells=world.cells;
        assertTrue(field.poll(WORK_PER_POLL).complete()); assertEquals(cells,world.cells);
        var boxes = containers(ACTOR,4,ORIGIN,world);
        boxes.poll(WORK_PER_POLL); boxes.cancel(); cells=world.cells;
        assertTrue(boxes.poll(WORK_PER_POLL).complete()); assertEquals(cells,world.cells);
    }
    @Test void discoveryCompletesTheDomainBeforeClaimingNearest() {
        var world = new Fake(); world.patch(12,-10,2); world.patch(0,0,2);
        var discovery = wheat(ACTOR,2,ORIGIN,world);
        var early = discovery.poll(WORK_PER_POLL);
        assertFalse(early.complete()); assertTrue(early.candidates().isEmpty());
        var selected = finish(Kind.AREA,ORIGIN,discovery);
        assertEquals(0,selected.value().area().minX()); assertEquals(SEARCH_CELLS,world.cells);
    }
    @Test void searchBoundaryAndUnknownNeighboursExcludeAnIncompleteRegion() {
        var world = new Fake(); world.patch(15,0,2);
        var boundary = field(world,2);
        assertEquals(Reason.RESOURCE_MISSING,boundary.reason());
        assertTrue(boundary.diagnostics().contains("excluded-boundary=1"));
        world.crops.clear(); world.patch(1,0,2); world.unknown.add(point(1,64,1));
        assertEquals(Reason.RESOURCE_MISSING,field(world,2).reason());
        assertEquals(SEARCH_CELLS*2,world.cells);
    }
    @Test void aBoundingCuboidCannotCaptureADisconnectedInteriorPatchOrUnknownCell() {
        var world = new Fake();
        for (int x=0;x<=4;x++) for (int z=0;z<=4;z++)
            if (x==0||x==4||z==0||z==4) world.crops.put(point(x,64,z),true);
        world.crops.put(point(2,64,2),true);
        assertEquals(Reason.RESOURCE_MISSING,field(world,4).reason());
        world.crops.remove(point(2,64,2)); world.unknown.add(point(2,64,2));
        assertEquals(Reason.RESOURCE_MISSING,field(world,4).reason());
    }
    @Test void aRegionAtTheLimitWorksAndAnOversizedRegionIsNotTruncated() {
        var world = new Fake();
        for (int x=-7;x<=8;x++) for (int z=-7;z<=8;z++) world.crops.put(point(x,64,z),true);
        var limit = field(world,64); assertEquals(Phase.RESOLVED,limit.phase());
        assertEquals(256,volume(limit.value().area()));
        world.crops.put(point(9,64,0),true);
        assertEquals(Reason.RESOURCE_MISSING,field(world,64).reason());
    }
    @Test void retainedWheatAndCandidateLimitsFailWithoutReturningAProvisionalField() {
        var world = new Fake();
        for (int y=61;y<=65;y+=2) for (int x=-15;x<=15;x++) for (int z=-15;z<=15;z++) world.crops.put(point(x,y,z),true);
        var result = field(world,4); assertEquals(Reason.BUDGET_EXHAUSTED,result.reason()); assertNull(result.value());
        assertTrue(world.cells <= SEARCH_CELLS);
        world.crops.clear();
        for (int x=-14;x<=13;x+=3) for (int z=-14;z<=14;z+=2) world.patch(x,z,2);
        result = field(world,1); assertEquals(Reason.BUDGET_EXHAUSTED,result.reason()); assertNull(result.value());
    }
    @Test void nearestFullySufficientContainerWinsAndSlotsAreAccounted() {
        var world = new Fake(); world.box(1,0,3); world.box(6,0,4);
        var selected = finish(Kind.CONTAINER,ORIGIN,containers(ACTOR,4,ORIGIN,world));
        assertEquals(new ContainerRef(DIM,6,64,0),selected.value().container());
        assertEquals(SEARCH_CELLS,world.cells); assertEquals(54,world.slots);
        assertTrue(selected.chargedWork() >= SEARCH_CELLS+world.slots);
    }
    @Test void unknownCloserContainerIsNotTreatedAsEmptyOrInspectedForStock() {
        var world = new Fake(); world.box(1,0,64); world.box(6,0,4); world.unknown.add(point(1,64,0));
        assertEquals(6,finish(Kind.CONTAINER,ORIGIN,containers(ACTOR,4,ORIGIN,world)).value().container().x());
        assertEquals(27,world.slots);
    }
    @Test void containerCandidateOverflowAndMissingCapacityTerminateBoundedly() {
        var world = new Fake(); world.box(1,0,3);
        assertEquals(Reason.FACILITY_MISSING,finish(Kind.CONTAINER,ORIGIN,containers(ACTOR,4,ORIGIN,world)).reason());
        for(int x=-10;x<=10;x++) for(int z=-10;z<=10;z++) world.box(x,z,64);
        var result = finish(Kind.CONTAINER,ORIGIN,containers(ACTOR,4,ORIGIN,world));
        assertEquals(Reason.BUDGET_EXHAUSTED,result.reason()); assertNull(result.value());
    }
    @Test void sourceRevalidationDetectsChangedMaturityMembershipAndNewBoundaryCropsIncrementally() {
        var world = new Fake(); world.patch(1,0,4); var selected = field(world,4).value();
        var validation = new FieldValidation(selected,ACTOR,world); ValidationStep step;
        int units=0; do { step=validation.poll(3); assertTrue(step.work()<=3); units+=step.work(); } while(!step.complete());
        assertNull(step.reason()); assertEquals(20,units);
        world.crops.put(point(0,64,0),true);
        assertEquals(Reason.STALE_OBSERVATION,new FieldValidation(selected,ACTOR,world).poll(256).reason());
        world.crops.remove(point(0,64,0)); world.crops.put(point(1,64,0),false);
        assertEquals(Reason.STALE_OBSERVATION,new FieldValidation(selected,ACTOR,world).poll(256).reason());
    }
    @Test void areaAnchorUsesOverflowSafeFloorCentreIncludingNegativeCoordinates() {
        assertEquals(point(-2,64,-2),areaAnchor(new Cuboid(DIM,-3,64,-3,0,64,0)));
        assertEquals(1,volume(new Cuboid(DIM,Integer.MAX_VALUE,64,0,Integer.MAX_VALUE,64,0)));
        assertThrows(IllegalArgumentException.class,() -> new LanguageRequests.AreaCoordinates(
                new LanguageRequests.Coordinates(-30000000,-30000000,-30000000),
                new LanguageRequests.Coordinates(30000000,30000000,30000000)));
    }
    @Test void capturedCitizenDomainExcludesDistantNamedUnavailableAndWrongDimensionRows() {
        var world = new Fake(); var rows = new ArrayList<CitizenRegistry.Address>();
        for(int i=1;i<=20;i++) {
            ActorRef actor=new ActorRef(new UUID(0,i),new UUID(1,i),DIM);
            world.actors.put(actor,new ActorState(true,true,DIM,i==20?1:20,64,0,0));
            rows.add(new CitizenRegistry.Address(actor,null,CitizenRegistry.Availability.LOADED));
        }
        var result=finish(Kind.CITIZEN,ORIGIN,citizens(rows,ORIGIN,true,world));
        assertEquals(new UUID(0,20),result.value().actor().citizenId()); assertEquals(20,world.entities);
        assertEquals(point(1,64,0),result.value().capturedPosition());
        rows.set(19,new CitizenRegistry.Address(rows.get(19).actor(),"Already Named",CitizenRegistry.Availability.LOADED));
        assertEquals(Reason.ACTOR_UNAVAILABLE,finish(Kind.CITIZEN,ORIGIN,citizens(rows,ORIGIN,true,world)).reason());
    }
}
