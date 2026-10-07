package dev.aivillages.providers;

import dev.aivillages.core.kernel.Budgets;
import dev.aivillages.core.kernel.Generation;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static dev.aivillages.core.kernel.Budgets.Kind;

/** Explicit, separately invoked real-model smoke. Never part of deterministic JUnit. */
public final class GenerationLiveSmoke {
    private GenerationLiveSmoke() { }

    public static void main(String[] args) throws Exception {
        String model = System.getenv("COGNITIVECRAFT_OLLAMA_MODEL");
        if (model == null || model.isBlank()) throw new IllegalStateException("Explicit installed model tag required");
        var config = new LocalGenerationAdapter.Config(URI.create("http://127.0.0.1:11434/api/chat"),
                model, true, 1_024);
        var clock = Clock.systemUTC();
        try (var adapter = new LocalGenerationAdapter(config)) {
            for (String phase : new String[] { "cold", "warm" }) {
                var limits = new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Kind.CALLS, 2L,
                        Kind.REPAIRS, 0L, Kind.INPUT_BYTES, 32_768L, Kind.OUTPUT_BYTES, 16_384L),
                        clock.millis() + 180_000), 16_384, 8_192);
                var ledger = new Budgets.Ledger(limits.total(), clock);
                long start = System.nanoTime();
                Generation.Result result = null;
                int attempts = 0;
                while (attempts < 2) {
                    attempts++;
                    var request = GenerationChecks.request(Generation.Role.INITIAL,
                            "Propose a parameterized crop delivery Skill IR procedure using only the listed "
                            + "registered primitives. Use the declared capability, parameters and dependencies. "
                            + "Return one JSON object with schema, capability, capabilityVersion, "
                            + "dependencies and body. The runtime will validate all world effects.");
                    result = adapter.generate(request, limits, ledger).result()
                            .toCompletableFuture().get(185, TimeUnit.SECONDS);
                    if (result.outcome() == Generation.Outcome.CANDIDATE) break;
                    System.out.println(phase + " attempt=" + attempts + " outcome=" + result.outcome()
                            + " reason=" + result.reason() + " compute=" + result.compute());
                    if (result.outcome() != Generation.Outcome.MALFORMED) break;
                }
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                if (result.outcome() != Generation.Outcome.CANDIDATE)
                    throw new AssertionError(phase + " outcome=" + result.outcome()
                            + " reason=" + result.reason() + " compute=" + result.compute());
                if (result.descriptor().digest() == null) throw new AssertionError("Missing local model digest");
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(result.candidateIr().getBytes(StandardCharsets.UTF_8)));
                System.out.println(phase + " attempts=" + attempts + " model=" + model
                        + " digest=" + result.descriptor().digest()
                        + " latencyMs=" + elapsed + " candidateSha256=" + hash
                        + " inputBytes=" + result.usage().inputBytes()
                        + " outputBytes=" + result.usage().outputBytes()
                        + " promptTokens=" + result.usage().promptTokens()
                        + " outputTokens=" + result.usage().outputTokens()
                        + " precision=" + result.usage().tokenPrecision());
            }
        }
    }
}
