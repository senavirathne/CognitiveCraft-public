package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.production.RecipeChoice;
import org.junit.jupiter.api.Test;
import java.util.*;

import static dev.aivillages.core.kernel.interaction.AssistanceFixtures.*;
import static dev.aivillages.core.kernel.interaction.BlockerProjection.*;
import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockerProjectionTest {
    @Test void missingPrimitiveUnknownInventoryDeniedSourceAndFullDestinationHaveDistinctCodes() {
        var primitive = new AssistanceFixtures(); primitive.kinds.remove(Kind.PRODUCE);
        assertEquals(Code.UNSUPPORTED_EFFECT, primitive.assess().blocker().code());
        var unknown = new AssistanceFixtures();
        unknown.extraStock.add(new Stock("public:unknown-inventory", new Location(unknown.source, null, 2), PLANK,
                0, 0, 0, null, List.of(), Eligibility.NONE, Availability.UNKNOWN, true, true, 2, 5));
        assertEquals(Code.UNKNOWN_WORLD_STATE, unknown.assess().blocker().code());
        var denied = new AssistanceFixtures(); denied.rows.add(new Row(id(90), true, false, true, Code.UNAUTHORIZED));
        assertEquals(Code.UNAUTHORIZED, denied.assess().blocker().code());
        var full = new AssistanceFixtures(); full.rows.add(full.row(91, Code.DESTINATION_FULL));
        assertEquals(Code.DESTINATION_FULL, full.assess().blocker().code());
        for (var f : List.of(primitive, unknown, denied, full)) {
            assertNull(f.assess().request()); assertEquals(List.of(), f.assess().blocker().wire().body().get("requirements"));
        }
    }

    @Test void privacyThenUnknownThenUnsupportedThenCapacityThenShortageIsStable() {
        var f = new AssistanceFixtures();
        List<Row> rows = List.of(f.row(20, Code.DESTINATION_FULL), f.row(21, Code.UNSUPPORTED_EFFECT), f.row(22, Code.UNKNOWN_WORLD_STATE),
                new Row(id(23), true, false, true, Code.UNAUTHORIZED));
        for (int i = 0; i < rows.size(); i++) {
            f.rows = new ArrayList<>(rows.subList(0, rows.size() - i));
            assertEquals(List.of(Code.UNAUTHORIZED, Code.UNKNOWN_WORLD_STATE, Code.UNSUPPORTED_EFFECT, Code.DESTINATION_FULL).get(i), f.assess().blocker().code());
            Collections.reverse(f.rows);
            assertEquals(List.of(Code.UNAUTHORIZED, Code.UNKNOWN_WORLD_STATE, Code.UNSUPPORTED_EFFECT, Code.DESTINATION_FULL).get(i), f.assess().blocker().code());
        }
        f.rows.clear(); assertEquals(Code.INSUFFICIENT_MATERIAL, f.assess().blocker().code());
    }

    @Test void privateUnrelatedStockAndRowsCannotChangeThePublicRequestOrFingerprint() {
        var f = new AssistanceFixtures(); String expected = new dev.aivillages.core.kernel.interaction.contract.InteractionCodec().encode(f.assess().request());
        f.rows.add(new Row(id(30), false, false, false, Code.UNAUTHORIZED));
        f.extraStock.add(new Stock("secret:Hidden Pantry 913", new Location(new dev.aivillages.core.kernel.Contracts.ContainerRef(DIM, 90, 64, 90), null, 3),
                new RecipeChoice.ItemKey("minecraft:diamond", "{}"), 0, 0, 0, null, List.of(), Eligibility.NONE, Availability.UNKNOWN, false, false, 0, 5));
        String actual = new dev.aivillages.core.kernel.interaction.contract.InteractionCodec().encode(f.assess().request());
        assertEquals(expected, actual); assertFalse(actual.contains("diamond")); assertFalse(actual.contains("Hidden Pantry")); assertFalse(actual.contains("\"missing\":913"));
    }

    @Test void redactedDenialContainsNoRootStorageItemOrQuantity() {
        var f = new AssistanceFixtures(); f.rows.add(new Row(id(44), true, false, true, Code.UNAUTHORIZED));
        var result = f.assess(); var body = result.blocker().wire().body();
        assertEquals("REDACTED", body.get("visibility")); assertNull(body.get("activity")); assertNull(body.get("storage_id"));
        assertEquals(List.of(), body.get("requirements")); assertFalse(result.blocker().json().contains("oak_planks"));
    }

    @Test void staleTickRevisionAndUnavailableFacilityNeverBecomeMaterialRequests() {
        var f = new AssistanceFixtures(); var q = new AssistanceFeasibility.Question(id(11), 4, f.now, 103, 1000);
        var stale = new AssistanceFeasibility().assess(f.reference(), ref -> Optional.of(f.evidence()), f.view(), f.target(), q);
        assertEquals(Code.STALE_REFERENCE, stale.blocker().code()); assertNull(stale.request());
        f.tableKnown = false; assertEquals(Code.UNKNOWN_WORLD_STATE, f.assess().blocker().code()); assertNull(f.assess().request());
    }

    @Test void rawRuntimeShortageCannotBypassPlannerProof() {
        var f = new AssistanceFixtures(); f.rows.add(f.row(99, Code.INSUFFICIENT_MATERIAL));
        assertEquals(Code.UNKNOWN_WORLD_STATE, f.assess().blocker().code()); assertNull(f.assess().request());
    }

    @Test void insufficientDepositCapacityBlocksEvenWhenMaterialWouldOtherwiseHelp() {
        var f = new AssistanceFixtures();
        var target = new AssistanceFeasibility.Target(f.world, f.context.principal().id(), f.storage, f.source, true, true, true, 1, 2);
        var result = new AssistanceFeasibility().assess(f.reference(), ref -> Optional.of(f.evidence()), f.view(), target, f.question());
        assertEquals(Code.DESTINATION_FULL, result.blocker().code()); assertNull(result.request());
    }

    @Test void duplicateRowsAndUnknownCatalogueAreRejectedWithoutFalseCounts() {
        var f = new AssistanceFixtures(); var row = f.row(10, Code.TARGET_UNAVAILABLE);
        assertThrows(IllegalArgumentException.class, () -> new Evidence(f.reference(), id(9), 100, 5, true, true, true, List.of(row, row)));
        f.catalogue = false; var result = f.assess(); assertEquals(Code.NO_SUPPORTED_PRODUCTION_PATH, result.blocker().code());
        assertNull(result.request());
    }
}
