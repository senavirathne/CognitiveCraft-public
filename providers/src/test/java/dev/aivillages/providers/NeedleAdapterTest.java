package dev.aivillages.providers;

import dev.aivillages.core.kernel.LanguageRequests;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class NeedleAdapterTest {
    @TempDir Path root;
    static final String OUTPUT = "{\"success\":true,\"type\":\"call\",\"confidence\":0.95,\"function_calls\":[{\"name\":\"harvest_wheat\",\"arguments\":{\"citizen\":\"Ada\",\"amount\":4,\"source\":\"farm\",\"destination\":\"chest\"}}]}";
    static LanguageRequests.Input input() { return new LanguageRequests.Input("Ada harvest four wheat from 0,64,0 through 2,64,2 and deliver to the chest at 5,64,0", List.of("Ada")); }
    static LanguageRequests.Result decode(String text) { return NeedleWire.decode(text.getBytes(StandardCharsets.UTF_8), NeedleWire.prepare(input())); }
    @Test void actualEnvelopeDecodesTypedFieldsAndConfidence() {
        var r = decode(OUTPUT); assertEquals(LanguageRequests.Kind.EXTRACTED, r.kind());
        assertEquals(4L, r.extracted().amount()); assertEquals(.95, r.confidence());
    }
    @Test void authorityClaimsAreIgnoredAndNeverDecodedIntoRequest() {
        var r = decode(OUTPUT.replace("\"success\":true", "\"permissions\":\"all\",\"budget\":999999,\"success\":true"));
        assertEquals(LanguageRequests.Kind.EXTRACTED, r.kind()); assertEquals(4L, r.extracted().amount());
    }
    @Test void duplicateFieldsAndTrailingJsonAreInvalid() {
        assertEquals(LanguageRequests.Kind.INVALID, decode(OUTPUT.replace("\"amount\":4", "\"amount\":4,\"amount\":64")).kind());
        assertEquals(LanguageRequests.Kind.INVALID, decode(OUTPUT + "{}").kind());
    }
    @Test void fractionalAndOverflowingQuantitiesCannotCoerce() {
        assertEquals(LanguageRequests.Kind.INVALID, decode(OUTPUT.replace("\"amount\":4", "\"amount\":4.1")).kind());
        assertEquals(LanguageRequests.Kind.INVALID, decode(OUTPUT.replace("\"amount\":4", "\"amount\":9223372036854775808")).kind());
    }
    @Test void unknownToolsOrExecutableProgramsAreInvalid() {
        assertEquals(LanguageRequests.Kind.INVALID, decode(OUTPUT.replace("harvest_wheat", "run_command")).kind());
        assertEquals(LanguageRequests.Kind.INVALID, decode(OUTPUT.replace("\"amount\":4", "\"ir\":\"run /op\",\"amount\":4")).kind());
    }
    @Test void suppressedEmptyOrMultipleCallsOnlyClarify() {
        assertEquals(LanguageRequests.Kind.CLARIFICATION, decode("{\"success\":true,\"type\":\"call\",\"confidence\":0.1,\"function_calls\":[],\"suppressed_calls\":[{}]}").kind());
        assertEquals(LanguageRequests.Kind.CLARIFICATION, decode(OUTPUT.replace("\"confidence\":0.95", "\"confidence\":null")).kind());
    }
    @Test void nativeNegationAndUngroundedFlagsCannotExecute() {
        assertEquals(LanguageRequests.Kind.CLARIFICATION, decode(OUTPUT.replace("\"success\":true", "\"validation\":{\"negation\":true},\"success\":true")).kind());
        assertEquals(LanguageRequests.Kind.CLARIFICATION, decode(OUTPUT.replace("\"success\":true", "\"validation\":{\"ungrounded\":[\"harvest_wheat.amount\"]},\"success\":true")).kind());
    }
    @Test void invalidUtf8AndOutputWorkLimitsAreRejected() {
        assertEquals(LanguageRequests.Kind.INVALID, NeedleWire.decode(new byte[]{(byte)0xff}, NeedleWire.prepare(input())).kind());
        assertEquals(LanguageRequests.Kind.INVALID, decode(" ".repeat(8193)).kind());
        assertEquals(LanguageRequests.Kind.INVALID, decode("[".repeat(17) + "0" + "]".repeat(17)).kind());
    }
    @Test void exactOutputByteCapStillDecodes() {
        String padded = OUTPUT + " ".repeat(8192 - OUTPUT.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(LanguageRequests.Kind.EXTRACTED, decode(padded).kind());
    }
    @Test void scopedOpaqueIdsAreOmittedUnlessExplicitlySelectedAndRestoreWithoutAuthority() {
        String id = "01234567-89ab-cdef-0123-456789abcdef";
        var ordinary = NeedleWire.prepare(new LanguageRequests.Input(input().text(), List.of("Ada", id)));
        assertFalse(ordinary.tools().contains(id));
        var selected = NeedleWire.prepare(new LanguageRequests.Input(input().text().replace("Ada", id), List.of("Ada", id)));
        assertEquals(id, selected.explicitCitizenId()); assertTrue(selected.text().contains("selected"));
        assertEquals(id, NeedleWire.decode(OUTPUT.replace("Ada", "selected").getBytes(StandardCharsets.UTF_8), selected).extracted().citizen());
    }
    @Test void schemaIsBoundedAndContainsOnlyNluArguments() {
        String tools = NeedleWire.tools(input()); assertTrue(tools.contains("harvest_wheat"));
        assertFalse(tools.contains("permission")); assertFalse(tools.contains("budget")); assertFalse(tools.contains("principal"));
        assertTrue(tools.length() < 8192);
    }
    @Test void disposableReferencesKeepOnlyExplicitCoordinatesAndCannotFillMissingChest() {
        var prepared = NeedleWire.prepare(input());
        assertEquals("0,64,0", prepared.from()); assertEquals("2,64,2", prepared.through());
        assertEquals("5,64,0", prepared.destination()); assertTrue(prepared.text().contains("from farm"));
        var missing = NeedleWire.prepare(new LanguageRequests.Input("Ada harvest 4 wheat into this chest", List.of("Ada")));
        assertNull(missing.from()); assertNull(missing.through()); assertNull(missing.destination());
        assertEquals(dev.aivillages.core.kernel.WorldReferenceResolver.ReferenceState.EXPLICIT_UNSUPPORTED,
                missing.classification().destination().state());
        assertEquals(LanguageRequests.Kind.CLARIFICATION,
                NeedleWire.decode(OUTPUT.getBytes(StandardCharsets.UTF_8), missing).kind());
    }

    @Test void omittedWorldReferencesAreAbsentFromToolSchemaAndRemainNullInExtraction() {
        var prepared = NeedleWire.prepare(new LanguageRequests.Input("Ada harvest 4 wheat", List.of("Ada")));
        assertFalse(prepared.tools().contains("\"source\""));
        assertFalse(prepared.tools().contains("\"destination\""));
        String output = "{\"success\":true,\"type\":\"call\",\"confidence\":0.95,"
                + "\"function_calls\":[{\"name\":\"harvest_wheat\","
                + "\"arguments\":{\"citizen\":\"Ada\",\"amount\":4}}]}";
        var result = NeedleWire.decode(output.getBytes(StandardCharsets.UTF_8), prepared);
        assertEquals(LanguageRequests.Kind.EXTRACTED, result.kind());
        assertNull(result.extracted().from());
        assertNull(result.extracted().through());
        assertNull(result.extracted().destination());
    }

    @Test void explicitNearestUsesDisposableTokensWhilePreservingUnresolvedProvenance() {
        var prepared=NeedleWire.prepare(new LanguageRequests.Input(
                "Ada harvest 4 wheat from the nearest field and deliver it to the nearest container",List.of("Ada")));
        assertTrue(prepared.text().contains("from farm")); assertTrue(prepared.text().contains("to chest"));
        var result=NeedleWire.decode(OUTPUT.getBytes(StandardCharsets.UTF_8),prepared);
        assertEquals(LanguageRequests.Kind.EXTRACTED,result.kind());
        assertNull(result.extracted().from()); assertNull(result.extracted().through()); assertNull(result.extracted().destination());
        assertEquals(dev.aivillages.core.kernel.WorldReferenceResolver.ReferenceState.EXPLICIT_NEAREST,prepared.classification().source().state());
    }
    @Test void explicitNearestCitizenNeverDecodesIntoAChosenActor() {
        var prepared=NeedleWire.prepare(new LanguageRequests.Input("nearest villager, harvest 4 wheat",List.of("Ada")));
        String output="{\"success\":true,\"type\":\"call\",\"confidence\":0.95,\"function_calls\":[{\"name\":\"harvest_wheat\","
                +"\"arguments\":{\"citizen\":\"selected\",\"amount\":4}}]}";
        var result=NeedleWire.decode(output.getBytes(StandardCharsets.UTF_8),prepared);
        assertEquals(LanguageRequests.Kind.EXTRACTED,result.kind()); assertNull(result.extracted().citizen());
    }

    @Test void namingToolKeepsProposedNameSeparateFromImplicitCitizenTarget() {
        var prepared = NeedleWire.prepare(new LanguageRequests.Input("I name you Ada", List.of()));
        assertTrue(prepared.tools().contains("name_citizen"));
        String output = "{\"success\":true,\"type\":\"call\",\"confidence\":0.95,"
                + "\"function_calls\":[{\"name\":\"name_citizen\","
                + "\"arguments\":{\"name\":\"Ada\"}}]}";
        var result = NeedleWire.decode(output.getBytes(StandardCharsets.UTF_8), prepared);
        assertEquals(LanguageRequests.Action.NAME_CITIZEN, result.extracted().action());
        assertEquals("Ada", result.extracted().proposedName());
        assertNull(result.extracted().citizen());
    }
    @Test void explicitNamingCarriesCitizenSeparatelyFromNewName() {
        String id = "01234567-89ab-cdef-0123-456789abcdef";
        var prepared = NeedleWire.prepare(new LanguageRequests.Input(
                "name " + id + " as Ada", List.of(id)));
        assertFalse(prepared.tools().contains("harvest_wheat"));
        assertTrue(prepared.tools().contains("name_citizen"));
        String output = "{\"success\":true,\"type\":\"call\",\"confidence\":0.95,"
                + "\"function_calls\":[{\"name\":\"name_citizen\","
                + "\"arguments\":{\"citizen\":\"selected\",\"name\":\"Ada\"}}]}";
        var result = NeedleWire.decode(output.getBytes(StandardCharsets.UTF_8), prepared);
        assertEquals(LanguageRequests.Kind.EXTRACTED, result.kind());
        assertEquals(id, result.extracted().citizen());
        assertEquals("Ada", result.extracted().proposedName());
    }

    LocalNeedleAdapter fake(String script) throws Exception {
        Path binary = root.resolve("needle"); Files.writeString(binary, "#!/bin/sh\n" + script);
        binary.toFile().setExecutable(true);
        return new LocalNeedleAdapter(new LocalNeedleAdapter.Config(binary, root.resolve("model")), config -> { });
    }
    @Test void isolatedProcessUsesSameDecoder() throws Exception {
        try (var adapter = fake("printf '%s' '" + OUTPUT + "'\n")) {
            assertEquals(LanguageRequests.Kind.EXTRACTED, adapter.interpret(input()).result().toCompletableFuture().get(5, TimeUnit.SECONDS).kind());
        }
    }
    @Test void cancellationStopsLocalProcessAndDiscardsLateOutput() throws Exception {
        try (var adapter = fake("exec /bin/sleep 30\n")) {
            var call = adapter.interpret(input());
            long deadline = System.currentTimeMillis() + 2000;
            while (adapter.calls() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertEquals(1, adapter.calls()); call.cancel();
            while (adapter.busy() && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertFalse(adapter.busy(), "Cancelled process must actually stop before another call");
            assertEquals(LanguageRequests.Kind.UNAVAILABLE, call.result().toCompletableFuture().get(2, TimeUnit.SECONDS).kind());
        }
    }
    @Test void concurrentCallCannotQueueOrOverlapCompute() throws Exception {
        try (var adapter = fake("exec /bin/sleep 30\n")) {
            var first = adapter.interpret(input()); var next = adapter.interpret(input());
            assertEquals(LanguageRequests.Kind.UNAVAILABLE, next.result().toCompletableFuture().get(2, TimeUnit.SECONDS).kind()); first.cancel();
        }
    }
    @Test void oversizedProcessOutputIsRejectedAndTerminated() throws Exception {
        try (var adapter = fake("/usr/bin/head -c 9000 /dev/zero\n")) {
            assertEquals(LanguageRequests.Kind.INVALID, adapter.interpret(input()).result().toCompletableFuture().get(5, TimeUnit.SECONDS).kind());
        }
    }
    @Test void absentOrWrongAssetsAreExplicitlyUnavailable() throws Exception {
        try (var adapter = new LocalNeedleAdapter(new LocalNeedleAdapter.Config(root.resolve("absent"), root.resolve("absent-model")))) {
            assertEquals(LanguageRequests.Kind.UNAVAILABLE, adapter.interpret(input()).result().toCompletableFuture().get(5, TimeUnit.SECONDS).kind());
        }
    }
}
