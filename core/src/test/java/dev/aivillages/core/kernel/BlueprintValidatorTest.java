package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.Blueprint.*;
import static dev.aivillages.core.kernel.BlueprintValidator.*;

final class BlueprintValidatorTest {
    Result validate(Body body){return validate(body.canonical());}
    Result validate(String json){return BlueprintValidator.validate(json,BlueprintFixture.registry(),Limits.defaults(),()->false);}
    @Test void twoRoomFixtureHasIndependentlyCalculatedBillAndBoundedWork() {
        var fixture=BlueprintFixture.timber();assertEquals(331,fixture.cells().size());
        Result result=validate(fixture);assertEquals(Status.VALID,result.status());assertEquals(BlueprintFixture.bill(),result.materials());
        assertEquals(331,result.body().cells().size());assertEquals(7,result.body().palette().size());
        assertTrue(result.work()>331 && result.work()<=8192);assertTrue(result.inputBytes()<65536);
    }
    @Test void equivalentCellPalettePropertyAndDefaultOrdersDeduplicate() {
        Body original=BlueprintFixture.timber();var palette=new ArrayList<>(original.palette());Collections.reverse(palette);
        var cells=original.cells().stream().map(c->new Cell(c.x(),c.y(),c.z(),palette.size()-1-c.palette())).toList();
        var reversed=new ArrayList<>(cells);Collections.reverse(reversed);
        Body reordered=new Body(original.dimensions(),original.constraints(),palette,reversed);
        assertEquals(validate(original).body().ref(),validate(reordered).body().ref());
        Result explicit=validate(validate(original).body());assertTrue(explicit.valid());
        assertEquals(validate(original).body().ref(),explicit.body().ref());
    }
    @Test void geometryStateAndConstraintsChangeIdentity() {
        assertNotEquals(validate(BlueprintFixture.tiny()).body().ref(),validate(BlueprintFixture.changed()).body().ref());
        var body=BlueprintFixture.timber();var palette=new ArrayList<>(body.palette());
        for(int i=2;i<=5;i++){var properties=new TreeMap<>(palette.get(i).properties());properties.put("hinge","right");palette.set(i,new State("minecraft:oak_door",properties));}
        assertNotEquals(validate(body).body().ref(),validate(new Body(body.dimensions(),body.constraints(),palette,body.cells())).body().ref());
        var tiny=BlueprintFixture.tiny();
        assertNotEquals(validate(tiny).body().ref(),validate(new Body(tiny.dimensions(),new Constraints(true,true,2),tiny.palette(),tiny.cells())).body().ref());
    }
    @Test void unusedSupportedPaletteDoesNotChangeIdentityOrRequirements() {
        Body body=BlueprintFixture.tiny();
        var palette=new ArrayList<>(body.palette());palette.add(new State("minecraft:torch",Map.of()));
        Result a=validate(body),b=validate(new Body(body.dimensions(),body.constraints(),palette,body.cells()));
        assertEquals(a.body().ref(),b.body().ref());assertEquals(a.materials(),b.materials());assertEquals(a.requirements(),b.requirements());
    }
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void identicalAndDefaultEquivalentDuplicatesRejectRegardlessOfPaletteOrInputOrder(int representation) {
        var states=List.of(new State("minecraft:oak_slab",Map.of("type","bottom")),
                new State("minecraft:oak_slab",Map.of("type","bottom","waterlogged","false")));
        var cells=representation==0?List.of(new Cell(0,0,0,0),new Cell(0,0,0,0)):
                representation==1?List.of(new Cell(0,0,0,0),new Cell(0,0,0,1)):
                        List.of(new Cell(0,0,0,1),new Cell(0,0,0,0));
        var result=validate(new Body(new Dimensions(1,1,1),new Constraints(true,true,1),states,cells));
        assertEquals(Status.INVALID_DATA,result.status());assertEquals("DUPLICATE_CELL",result.reason());
        assertNull(result.body());assertTrue(result.materials().isEmpty());assertTrue(result.requirements().isEmpty());
        assertTrue(result.inputBytes()>0);assertTrue(result.work()>0 && result.work()<=Limits.defaults().work());
    }
    @Test void compactDirectionalFixtureHasIndependentBillAndFourTurnsRestoreCanonicalBody() {
        var result=validate(BlueprintFixture.compact());assertTrue(result.valid(),result.reason());
        assertEquals(7,result.body().cells().size());assertEquals(BlueprintFixture.compactBill(),result.materials());
        Body original=result.body(),rotated=original;String canonical=original.canonical();
        for(int turn=1;turn<=4;turn++) {
            rotated=BlueprintValidator.rotate(rotated,1);var assessment=validate(rotated);assertTrue(assessment.valid(),assessment.reason());
            assertEquals(BlueprintFixture.compactBill(),assessment.materials());
            assertEquals(turn%2==0?3:2,rotated.dimensions().x());assertEquals(turn%2==0?2:3,rotated.dimensions().z());
            String facing=List.of("north","east","south","west").get(turn%4);
            assertTrue(rotated.palette().stream().filter(s->s.block().equals("minecraft:oak_door"))
                    .allMatch(s->s.properties().get("facing").equals(facing) && s.properties().get("hinge").equals("left")));
            assertEquals(canonical,original.canonical());
        }
        assertEquals(original,rotated);assertEquals(original.ref(),rotated.ref());
    }
    @Test void conflictingDuplicateCellsReject() {
        var tiny=BlueprintFixture.tiny();var body=new Body(tiny.dimensions(),tiny.constraints(),
                List.of(tiny.palette().getFirst(),new State("minecraft:torch",Map.of())),List.of(tiny.cells().getFirst(),new Cell(0,0,0,1)));
        assertEquals("CONFLICTING_CELL",validate(body).reason());
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","9223372036854775807"})
    void nonpositiveOrOverflowingDimensionsReject(String x) {
        String json=BlueprintFixture.tiny().canonical().replace("\"x\":2","\"x\":"+x);
        assertEquals(Status.INVALID_DATA,validate(json).status());
    }
    @ParameterizedTest @ValueSource(ints={-1,2,Integer.MAX_VALUE})
    void negativeOutsideAndOverflowCellCoordinatesReject(int x) {
        var tiny=BlueprintFixture.tiny();assertEquals(Status.INVALID_DATA,validate(new Body(tiny.dimensions(),tiny.constraints(),
                tiny.palette(),List.of(new Cell(x,0,0,0)))).status());
    }
    @ParameterizedTest @ValueSource(strings={"program","permissions","nbt","scripts","jobs"})
    void executableOrAuthorityPayloadsReject(String field) {
        String json=BlueprintFixture.tiny().canonical();json=json.substring(0,json.length()-1)+",\""+field+"\":[]}";
        assertEquals(Status.INVALID_DATA,validate(json).status());
    }
    @ParameterizedTest @ValueSource(ints={-1,1,Integer.MAX_VALUE})
    void invalidPaletteReferencesRejectWithoutAnAcceptedBodyOrMaterialBill(int palette) {
        var fixture=BlueprintFixture.tiny();
        var result=validate(new Body(fixture.dimensions(),fixture.constraints(),fixture.palette(),List.of(new Cell(0,0,0,palette))));
        assertEquals(Status.INVALID_DATA,result.status());assertEquals("CELL_BOUNDS",result.reason());
        assertNull(result.body());assertTrue(result.materials().isEmpty());assertTrue(result.work()<=Limits.defaults().work());
    }
    @Test void unknownBlockUnknownStateAndUnsupportedPlacementAreDistinct() {
        String json=BlueprintFixture.tiny().canonical().replace("minecraft:oak_planks","minecraft:command_block");
        assertEquals(Status.UNSUPPORTED_BLOCK,validate(json).status());
        json=BlueprintFixture.timber().canonical().replace("\"north\"","\"sideways\"");
        assertEquals("UNKNOWN_STATE",validate(json).reason());
        json=BlueprintFixture.timber().canonical().replace("\"type\":\"bottom\"","\"type\":\"bottom\",\"waterlogged\":\"true\"");
        assertEquals("FLUID_PLACEMENT_UNSUPPORTED",validate(json).reason());
        var definition=new Definition("test:scripted","test:scripted",Kind.SINGLE,Map.of("code",Set.of("run")),Map.of("code","run"),"untrusted");
        var body=new Body(new Dimensions(1,1,1),new Constraints(false,false,0),List.of(new State("test:scripted",Map.of())),List.of(new Cell(0,0,0,0)));
        assertEquals(Status.UNSUPPORTED_BLOCK,BlueprintValidator.validate(body.canonical(),new Registry("test",Map.of(definition.block(),definition)),Limits.defaults(),()->false).status());
    }
    @Test void absentMismatchedOrUnsupportedDoorPairsReject() {
        Body original=BlueprintFixture.timber();var cells=new ArrayList<>(original.cells());cells.removeIf(c->c.x()==2 && c.y()==2 && c.z()==0);
        assertEquals("DOOR_PAIR",validate(new Body(original.dimensions(),original.constraints(),original.palette(),cells)).reason());
        var palette=new ArrayList<>(original.palette());palette.set(3,new State("minecraft:oak_door",Map.of("facing","west","half","upper","hinge","left")));
        assertEquals("DOOR_PAIR",validate(new Body(original.dimensions(),original.constraints(),palette,original.cells())).reason());
        cells=new ArrayList<>(original.cells());cells.removeIf(c->c.x()==2 && c.y()==0 && c.z()==0);
        assertEquals("DOOR_SUPPORT",validate(new Body(original.dimensions(),original.constraints(),original.palette(),cells)).reason());
    }
    @Test void doubleSlabUsesTwoItemsAndSingleTopSlabUsesOne() {
        var tiny=BlueprintFixture.tiny();
        for(String type:List.of("top","bottom","double")) {
            Result result=validate(new Body(tiny.dimensions(),tiny.constraints(),List.of(new State("minecraft:oak_slab",Map.of("type",type))),tiny.cells()));
            assertEquals(Map.of("minecraft:oak_slab",type.equals("double")?2L:1L),result.materials());
        }
    }
    @Test void groundPlaneRequirementCanSupportABottomDoorPairWithoutGrantingSiteApproval() {
        var states=List.of(new State("minecraft:oak_door",Map.of("half","lower")),
                new State("minecraft:oak_door",Map.of("half","upper")));
        var cells=List.of(new Cell(0,0,0,0),new Cell(0,1,0,1));
        var body=new Body(new Dimensions(1,2,1),new Constraints(true,true,0),states,cells);
        assertEquals(Map.of("minecraft:oak_door",1L),validate(body).materials());
        assertEquals("DOOR_SUPPORT",validate(new Body(body.dimensions(),new Constraints(false,true,0),states,cells)).reason());
    }
    @Test void allHardShapeLimitsAreFiniteAtMaximumAndRejectTheirNextValue()throws Exception {
        var palette=Collections.nCopies(64,new State("minecraft:oak_planks",Map.of()));
        var cells=new ArrayList<Cell>();for(int i=0;i<512;i++)cells.add(new Cell(i%64,i/64,63,63));
        var body=new Body(new Dimensions(64,64,64),new Constraints(true,true,4),palette,cells);
        String json=body.canonical();json=json+" ".repeat(65536-json.length());
        Result result=validate(json);assertTrue(result.valid());assertEquals(65536,result.inputBytes());
        assertEquals(Map.of("minecraft:oak_planks",512L),result.materials());assertTrue(result.work()<=8192);
        assertEquals(Status.QUOTA,validate(json+" ").status());
        assertEquals(Status.QUOTA,validate(BlueprintFixture.tiny().canonical().replace("\"x\":2","\"x\":65")).status());
        assertThrows(IllegalArgumentException.class,()->new Limits(-1,64,262144,64,512,8192));
        assertThrows(IllegalArgumentException.class,()->new Limits(65536,-1,262144,64,512,8192));
        assertThrows(IllegalArgumentException.class,()->new Limits(65536,64,-1,64,512,8192));
        assertThrows(IllegalArgumentException.class,()->new Limits(65536,64,262144,-1,512,8192));
        assertThrows(IllegalArgumentException.class,()->new Limits(65536,64,262144,64,-1,8192));
        assertThrows(IllegalArgumentException.class,()->new Limits(65536,64,262144,64,512,-1));
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3})
    void eachRotationPreservesBillBoundsAndDoorHandedness(int turns) {
        Body body=validate(BlueprintFixture.timber()).body();Body rotated=BlueprintValidator.rotate(body,turns);
        Result result=validate(rotated);assertTrue(result.valid());assertEquals(BlueprintFixture.bill(),result.materials());
        assertEquals(turns%2==0?11:9,rotated.dimensions().x());assertEquals(turns%2==0?9:11,rotated.dimensions().z());
        assertTrue(rotated.palette().stream().filter(s->s.block().equals("minecraft:oak_door")).allMatch(s->s.properties().get("hinge").equals("left")));
        String north=List.of("north","east","south","west").get(turns);
        assertTrue(rotated.palette().stream().anyMatch(s->s.block().equals("minecraft:oak_door") && s.properties().get("facing").equals(north)));
        Body four=body;for(int i=0;i<4;i++)four=BlueprintValidator.rotate(four,1);assertEquals(body,four);
    }
    @Test void axisRotatesAndSiteBindingCannotAlterCanonicalBodyOrGrantPermission() {
        var body=new Body(new Dimensions(2,1,1),new Constraints(false,false,0),
                List.of(new State("test:beam",Map.of("axis","x"))),List.of(new Cell(0,0,0,0)));
        var rotated=BlueprintValidator.rotate(body,1);assertEquals("z",rotated.palette().getFirst().properties().get("axis"));
        Body timber=validate(BlueprintFixture.timber()).body();Ref ref=timber.ref();String original=timber.canonical();
        var view=BlueprintValidator.bind(timber,new SiteBinding(ref,"minecraft:overworld",100,50,-20,1));
        assertEquals(ref,view.source());assertEquals(original,timber.canonical());assertEquals(9,view.geometry().dimensions().x());
        assertThrows(ArithmeticException.class,()->BlueprintValidator.bind(timber,new SiteBinding(ref,"minecraft:overworld",Integer.MAX_VALUE,0,0,0)));
    }
    @Test void byteDimensionVolumePaletteCellsAndWorkAllowancesAcceptExactAndRejectOneUnder() {
        String json=BlueprintFixture.tiny().canonical();Result sample=validate(json);Limits defaults=Limits.defaults();
        var exact=new Limits(json.length(),2,2,1,1,sample.work());
        assertTrue(BlueprintValidator.validate(json,BlueprintFixture.registry(),exact,()->false).valid());
        var under=List.of(new Limits(json.length()-1,2,2,1,1,sample.work()),new Limits(65536,1,2,1,1,8192),
                new Limits(65536,2,1,1,1,8192),new Limits(65536,2,2,0,1,8192),
                new Limits(65536,2,2,1,0,8192),new Limits(65536,2,2,1,1,sample.work()-1));
        for(Limits limit:under)assertEquals(Status.QUOTA,BlueprintValidator.validate(json,BlueprintFixture.registry(),limit,()->false).status());
        assertEquals(Status.QUOTA,BlueprintValidator.validate(json,BlueprintFixture.registry(),new Limits(0,0,0,0,0,0),()->false).status());
        assertThrows(IllegalArgumentException.class,()->new Limits(65537,64,262144,64,512,8192));
        assertThrows(IllegalArgumentException.class,()->new Limits(65536,65,262144,64,512,8192));
        assertThrows(IllegalArgumentException.class,()->new Limits(65536,64,262145,64,512,8192));
        assertThrows(IllegalArgumentException.class,()->new Limits(65536,64,262144,65,512,8192));
        assertThrows(IllegalArgumentException.class,()->new Limits(65536,64,262144,64,513,8192));
        assertThrows(IllegalArgumentException.class,()->new Limits(65536,64,262144,64,512,8193));
        assertEquals(defaults,Limits.defaults());
    }
    @Test void hugeInputPaletteAndTokenCountsRejectBoundedly() {
        assertEquals(Status.QUOTA,validate(" ".repeat(65537)).status());
        String json=BlueprintFixture.tiny().canonical();
        var root=new TreeMap<String,Object>();try{root.putAll(StrictJson.object(json));}catch(Exception e){throw new AssertionError(e);}
        root.put("palette",Collections.nCopies(65,Map.of("block","minecraft:oak_planks","properties",Map.of())));
        assertEquals("PALETTE",validate(StrictJson.canonical(root)).reason());
        root.put("palette",List.of(Map.of("block","minecraft:oak_planks","properties",Map.of())));
        root.put("cells",Collections.nCopies(513,Map.of("x",0,"y",0,"z",0,"palette",0)));
        assertEquals("CELLS",validate(StrictJson.canonical(root)).reason());
        assertEquals(Status.INVALID_DATA,validate(json.replace("\"schema\":1","\"schema\":1,\"schema\":1")).status());
    }
    @Test void cancellationBeforeAndDuringValidationStopsBoundedWork() {
        var registry=BlueprintFixture.registry();String json=BlueprintFixture.timber().canonical();
        assertEquals(Status.CANCELLED,BlueprintValidator.validate(json,registry,Limits.defaults(),()->true).status());
        var calls=new AtomicInteger();Result result=BlueprintValidator.validate(json,registry,Limits.defaults(),()->calls.incrementAndGet()>20);
        assertEquals(Status.CANCELLED,result.status());assertTrue(result.work()<=20);
    }
    @Test void unsupportedBodySchemaIsInactiveAndRegistrySnapshotsAreImmutable() {
        assertEquals(Status.INCOMPATIBLE,validate(BlueprintFixture.tiny().canonical().replace("\"schema\":1","\"schema\":2")).status());
        var map=new TreeMap<>(BlueprintFixture.registry().blocks());Registry registry=new Registry("test",map);map.clear();assertEquals(4,registry.blocks().size());
        assertThrows(UnsupportedOperationException.class,()->registry.blocks().clear());
    }
}
