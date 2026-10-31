package dev.aivillages.providers.interaction;

import dev.aivillages.core.kernel.interaction.ResponseFacts;
import dev.aivillages.core.kernel.interaction.ResponseTemplates;
import dev.aivillages.core.kernel.interaction.ResponseValidator;
import dev.aivillages.providers.LlmProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

final class ConversationFixtures {
    final AtomicLong now = new AtomicLong();
    final List<LlmProvider.Request> requests = new ArrayList<>();
    final List<Alarm> alarms = new ArrayList<>();
    final AtomicInteger calls = new AtomicInteger();
    final ResponseFacts facts = ResponseFacts.smallTalk("en");
    final ConversationProvider.TokenBudget tokens = new ConversationProvider.TokenBudget("a".repeat(64), "b".repeat(64), r -> 180);
    final UUID world = UUID.randomUUID(), principal = UUID.randomUUID(), citizen = UUID.randomUUID(), session = UUID.randomUUID();
    record Alarm(long at, Runnable action, boolean[] cancelled) { }
    ConversationProvider.Ticket ticket(long generation, long sequence) { return new ConversationProvider.Ticket(world, principal, citizen, session, generation, sequence, UUID.randomUUID()); }
    ConversationProvider.Scheduler scheduler() {
        return (delay, action) -> { boolean[] cancelled = {false}; alarms.add(new Alarm(now.get() + delay, action, cancelled)); return () -> cancelled[0] = true; };
    }
    void advance(long time) { now.set(time); for (var alarm : List.copyOf(alarms)) if (!alarm.cancelled()[0] && time >= alarm.at()) { alarm.cancelled()[0] = true; alarm.action().run(); } }
    LlmProvider.Response valid() { return new LlmProvider.Response(new ResponseValidator().envelope(facts, ResponseTemplates.choices(facts).get(1)), "synthetic", "controlled-test-double"); }
    LlmProvider provider(Function<LlmProvider.Request, CompletableFuture<LlmProvider.Response>> action) {
        return new LlmProvider() {
            public String id() { return "synthetic-test-double"; }
            public CompletableFuture<Response> generate(Request request) { requests.add(request); calls.incrementAndGet(); return action.apply(request); }
        };
    }
    ConversationProvider wrapper(LlmProvider provider) { return new ConversationProvider(provider, tokens, now::get, scheduler(), Runnable::run); }
}
