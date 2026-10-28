package dev.aivillages.core.kernel.interaction.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Diagnostic CLI; this is synthetic codec coverage, never Minecraft or module qualification. */
public final class InteractionCodecProbe {
    private InteractionCodecProbe() { }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        InteractionCodec codec = new InteractionCodec(); int count = 0;
        Path contract = Path.of(args[0]);
        try (var paths = Files.list(contract.resolve("fixtures"))) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                Map<String, Object> fixture = (Map<String, Object>) InteractionCodec.parseJson(Files.readString(file));
                String message = InteractionCodec.canonical(fixture.get("message"));
                var disposition = codec.inspect(message, InteractionCodec.canonical(fixture.get("context")));
                if (!disposition.name().equals(fixture.get("expected")))
                    throw new AssertionError(fixture.get("id") + ": " + disposition + " expected " + fixture.get("expected"));
                System.out.print(InteractionCodec.canonical(Map.of("id", fixture.get("id"),
                        "expected", fixture.get("expected"), "actual", disposition.name(), "tier", "SYNTHETIC_CODEC")));
                count++;
            }
        }
        if (count != 57) throw new AssertionError("Frozen fixture count: " + count);
        // Additional parser negative controls exercise bytes which cannot appear in canonical fixtures.
        for (String bad : List.of("{\"x\":1,\"x\":2}", "1.0", "1e1", "01", "true false",
                "{\"x\":NaN}", "9007199254740992", "\"e\\u0301\"", "\"\\ud800\"", "[1,]", "//hi\n{}")) {
            try { InteractionCodec.parseJson(bad); throw new AssertionError("Accepted invalid JSON: " + bad); }
            catch (InteractionWire.DecodeException expected) { /* Observable strict-parser refusal. */ }
        }
    }
}
