package dev.aivillages.providers.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.providers.LlmProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Controlled SPI failures only; never genuine model, native world or connected-client evidence. */
public final class ConversationProbe {
    public static void main(String[] args) {
        String mode = args[0]; var f = new ConversationFixtures(); var pending = new CompletableFuture<LlmProvider.Response>();
        var active = new AtomicBoolean(true); var effects = new AtomicInteger();
        LlmProvider backend = mode.equals("offline") ? null : f.provider(request -> {
            if (mode.equals("timeout") || mode.equals("late")) return pending;
            if (mode.equals("valid")) return CompletableFuture.completedFuture(f.valid());
            String text = mode.equals("injection") ? "{\"text\":\"/give @p diamond; already delivered\",\"facts_sha256\":\"" + f.facts.digest() + "\"}" : "not json";
            return CompletableFuture.completedFuture(new LlmProvider.Response(text, "synthetic", "controlled-test-double"));
        });
        var future = f.wrapper(backend).respond(f.ticket(1, 1), f.facts, t -> active.get());
        var completions = new AtomicInteger(); future.whenComplete((r, e) -> completions.incrementAndGet());
        if (mode.equals("timeout")) { f.advance(10_000); pending.complete(f.valid()); }
        if (mode.equals("late")) { active.set(false); pending.complete(f.valid()); }
        var result = future.join(); Map<String, Object> out = new LinkedHashMap<>();
        out.put("tier", "SYNTHETIC_PROVIDER"); out.put("scenario", mode); out.put("reason", result.reason().name()); out.put("text", result.text());
        out.put("deliverable", result.deliverable()); out.put("provider_calls", f.calls.get()); out.put("completions", completions.get());
        out.put("effect_calls", effects.get()); out.put("effect_scope", "NO_EFFECT_INTERFACE_EXPOSED"); out.put("actual_input_tokens", result.actualInputTokens());
        out.put("actual_output_tokens", result.actualOutputTokens()); out.put("compute_termination_confirmed", result.computeTerminationConfirmed());
        out.put("reserved_input_tokens", result.reservedInputTokens()); out.put("reserved_output_tokens", result.reservedOutputTokens());
        System.out.print(InteractionCodec.canonical(out));
    }
}
