package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.OakLogTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceDeliveryTest {
    @Test void requiresAllPhysicalStagesAndRetainsReorderedDuplicateMeaning() {
        var bound=bound(4);var run=UUID.randomUUID();var chain=chain(bound,run,4);
        assertFalse(OakLogDelivery.completed(bound,run,chain.subList(0,2)));
        var reordered=new ArrayList<>(chain);Collections.reverse(reordered);reordered.addAll(chain);
        assertTrue(OakLogDelivery.completed(bound,run,reordered));
        assertEquals(4,ResourceDelivery.quantity(bound,run,reordered));
    }
    @Test void changedDuplicateOrReissuedOperationCannotMultiplyCredit() {
        var bound=bound(4);var run=UUID.randomUUID();var chain=new ArrayList<>(chain(bound,run,2));var receipt=chain.getFirst();
        chain.add(changed(receipt,receipt.receiptId(),receipt.origin(),receipt.item(),4));
        assertFalse(OakLogDelivery.completed(bound,run,chain));
        chain.removeLast();chain.add(changed(receipt,UUID.randomUUID(),receipt.origin(),receipt.item(),2));
        assertFalse(OakLogDelivery.completed(bound,run,chain));
    }
    @Test void crossPrincipalResourceRunAndBindingAreRejected() {
        var bound=bound(1);var run=UUID.randomUUID();var chain=new ArrayList<>(chain(bound,run,1));var r=chain.getLast();
        chain.set(2,changed(r,r.receiptId(),JobLifecycleStoreTest.owner(OWNER.scope().worldId()),r.item(),1));
        assertFalse(OakLogDelivery.completed(bound,run,chain));
        chain.set(2,changed(r,r.receiptId(),r.origin(),ResourceDelivery.WHEAT,1));
        assertFalse(OakLogDelivery.completed(bound,run,chain));
        assertFalse(OakLogDelivery.completed(bound,UUID.randomUUID(),chain(bound,run,1)));
    }
    @Test void overspentOrDepositOnlyClaimsNeverEstablishCustody() {
        var bound=bound(1);var run=UUID.randomUUID();var chain=new ArrayList<>(chain(bound,run,1));var r=chain.getLast();
        chain.set(2,changed(r,r.receiptId(),r.origin(),r.item(),2));
        assertFalse(OakLogDelivery.completed(bound,run,chain));
        assertFalse(OakLogDelivery.completed(bound,run,List.of(r)));
    }
    @Test void exactResourceAndQuantityEnvelopeAndRoundTripKeepWheatContract() {
        assertThrows(IllegalArgumentException.class,()->new ResourceDelivery.ItemIdentity("oak_log","default"));
        assertThrows(IllegalArgumentException.class,()->new ResourceDelivery.ItemIdentity("minecraft:oak_log","custom"));
        for(int quantity:List.of(0,9))assertInstanceOf(RequestValidator.Rejected.class,RequestValidator.validate(bound(quantity).request(),OWNER,
                bound(quantity).observation(),PlanningGoals.catalog(),JobLifecycleStoreTest.environment()));
        var request=bound(7).request();assertEquals(request,((RequestCodec.Valid)RequestCodec.decode(RequestCodec.encode(request))).request());
        assertEquals("ATTRIBUTABLE_CROP_DELIVERY",CropDelivery.SPEC.completion().name());
        assertEquals(64,CropDelivery.SPEC.parameters().get(1).maximum());
    }
    @Test void compilationAndFixtureEvaluationRemainSeparateFromAdmission() {
        var compiler=new SkillCompiler(PlanningGoals.catalog(),GatewayPrimitives.instance(),ref->Optional.empty());
        var compiled=assertInstanceOf(SkillCompiler.Success.class,compiler.compile(program())).skill();
        assertEquals(OakLogDelivery.ID,compiled.artifact().descriptor().ref().capability());
        assertEquals(List.of("actor","amount","source","destination"),compiled.artifact().descriptor().parameters().stream().map(Parameter::name).toList());
        assertTrue(compiled.artifact().descriptor().primitives().stream().anyMatch(p -> p.id().equals("cognitivecraft:harvest_next_oak_log")));
    }
    @Test void everyExistingWheatPrimitiveFingerprintMatchesOriginalBaselineGolden() throws Exception {
        try(var stream=ResourceDeliveryTest.class.getResourceAsStream("/rb001-wheat-primitives.json")) {
            assertNotNull(stream);
            var golden=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("fingerprints");
            assertEquals(11,golden.size());
            for(var entry:golden.entrySet())assertEquals(entry.getValue().getAsString(),
                    GatewayPrimitives.instance().find(entry.getKey(),1).orElseThrow().fingerprint(),entry.getKey());
        }
    }
}
