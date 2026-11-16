package dev.aivillages.providers;

import dev.aivillages.core.kernel.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.aivillages.core.kernel.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

class PlankVariantGenerationTest {
    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    void bambooRequiresTheInstalledTwoItemBatch(int proposedYield) throws Exception {
        try (var fixture = new GenerationChecks.Fixture()) {
            var old = GenerationChecks.request(Generation.Role.INITIAL, "Bamboo plank production");
            var arguments = new HashMap<String, Value>(old.bound().request().arguments());
            arguments.put("source", arguments.get("destination"));
            arguments.put("amount", new IntValue(5));
            var capability = RecipeProduction.PlankMaterial.BAMBOO.capability(1);
            var bound = new ValidatedRequest(new CapabilityRequest(capability, arguments), old.bound().context(),
                    old.bound().observation());
            var signatures = RecipeProduction.operations(capability).stream()
                    .map(GatewayPrimitives.Operation::signature).toList();
            var request = new Generation.Request(old.id(), bound, RecipeProduction.of(capability).spec(), signatures,
                    List.of(), old.role(), old.context());
            var limits = new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS, 1L,
                    Budgets.Kind.INPUT_BYTES, 16384L, Budgets.Kind.OUTPUT_BYTES, 8192L), fixture.clock.millis() + 10000),
                    16384, 8192);
            var generated = fixture.adapter.generate(request, limits, new Budgets.Ledger(limits.total(), fixture.clock));
            String proposal = StrictJson.canonical(Map.of("schema", 1, "capability", capability.name(),
                    "capabilityVersion", 1, "dependencies", List.of(), "body", Map.of(
                    "preflight", "cognitivecraft:observe_plank_variant_inputs", "batchSize", proposedYield,
                    "cycle", List.of("cognitivecraft:move_to_input", "cognitivecraft:withdraw_plank_variant_input",
                            "cognitivecraft:craft_plank_variant", "cognitivecraft:move_to_destination",
                            "cognitivecraft:transfer_plank_variant"), "result", Map.of("param", "amount"))));
            fixture.backend.respond(0, proposal, 900L, 180L);
            var result = generated.result().toCompletableFuture().join();
            if (proposedYield == 4) {
                assertEquals(Generation.Outcome.MALFORMED, result.outcome());
                assertNull(result.candidateIr());
            } else {
                assertEquals(Generation.Outcome.CANDIDATE, result.outcome());
                assertTrue(result.candidateIr().contains("\"right\":{\"int\":2}"));
                var compiler = new SkillCompiler(PlanningGoals.catalog(), GatewayPrimitives.instance(), ref -> Optional.empty());
                var skill = assertInstanceOf(SkillCompiler.Success.class, compiler.compile(result.candidateIr())).skill();
                assertEquals(capability, skill.artifact().descriptor().ref().capability());
                assertEquals(6, skill.artifact().descriptor().primitives().size());
                assertTrue(skill.artifact().descriptor().dependencies().isEmpty());
            }
        }
    }
}
