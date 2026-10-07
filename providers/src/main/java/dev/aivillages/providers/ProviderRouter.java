package dev.aivillages.providers;

import dev.aivillages.core.WorldData;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Bounded queue, one in-flight request per provider, priority failover, circuit and daily budgets. */
public final class ProviderRouter implements AutoCloseable {
    public record Entry(ProviderConfig config, LlmProvider provider) { }
    public record Answer<T>(T value, String provider, String model) { }
    private static final class Status {
        long nextAttempt; int failures; boolean inFlight; String lastError = ""; WorldData.Usage usage = new WorldData.Usage();
    }
    private final List<Entry> entries; private final Map<String, Status> statuses = new LinkedHashMap<>();
    private final ThreadPoolExecutor executor; private final Clock clock; private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> outstanding = ConcurrentHashMap.newKeySet();
    public ProviderRouter(List<Entry> entries, int concurrency, Map<String, WorldData.Usage> saved, Clock clock) {
        this.entries = List.copyOf(entries); this.clock = clock;
        executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), r -> { var t = new Thread(r, "ai-villages-provider"); t.setDaemon(true); return t; });
        for (var e : entries) {
            if (statuses.containsKey(e.provider().id())) throw new IllegalArgumentException("Duplicate provider id");
            var s = new Status(); if (saved.containsKey(e.provider().id())) s.usage = copy(saved.get(e.provider().id())); statuses.put(e.provider().id(), s);
        }
    }
    public <T> CompletableFuture<Answer<T>> submit(LlmProvider.Request request, Function<String, T> validate) {
        var result = new CompletableFuture<Answer<T>>();
        if (closed.get()) { result.completeExceptionally(new ProviderFailure("Router closed", 1)); return result; }
        outstanding.add(result); result.whenComplete((v, ex) -> outstanding.remove(result));
        try {
            executor.execute(() -> {
                for (var e : entries) {
                    if (result.isDone() || closed.get()) return;
                    if (!reserve(e, request.tokenReservation())) continue;
                    CompletableFuture<LlmProvider.Response> call = null;
                    try {
                        call = e.provider().generate(request); var activeCall = call;
                        result.whenComplete((v, ex) -> { if (result.isCancelled()) activeCall.cancel(true); });
                        var response = call.get(e.config().timeoutSeconds, TimeUnit.SECONDS); T value = validate.apply(response.text());
                        synchronized (this) { var s = statuses.get(e.provider().id()); s.failures = 0; s.lastError = ""; }
                        result.complete(new Answer<>(value, response.provider(), response.model())); return;
                    } catch (InterruptedException ex) { Thread.currentThread().interrupt(); result.cancel(true); return; }
                    catch (Exception ex) {
                        Throwable cause = ex instanceof ExecutionException ? ex.getCause() : ex;
                        synchronized (this) {
                            var s = statuses.get(e.provider().id()); s.failures++;
                            long delay = cause instanceof ProviderFailure p ? p.cooldownSeconds() : Math.min(300, 15L << Math.min(s.failures, 4));
                            s.nextAttempt = Math.max(s.nextAttempt, clock.millis() + delay * 1000);
                            s.lastError = cause instanceof ProviderFailure p ? p.getMessage() : "timeout_or_invalid_output";
                        }
                    } finally {
                        if (call != null && !call.isDone()) call.cancel(true);
                        synchronized (this) { statuses.get(e.provider().id()).inFlight = false; }
                    }
                }
                result.completeExceptionally(new ProviderFailure("No provider available within configured budget", 1));
            });
        } catch (RejectedExecutionException ex) { result.completeExceptionally(new ProviderFailure("Planner queue full", 1)); }
        return result;
    }
    private synchronized boolean reserve(Entry e, long tokens) {
        var s = statuses.get(e.provider().id()); var c = e.config(); String day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
        if (!day.equals(s.usage.utcDay)) { s.usage = new WorldData.Usage(); s.usage.utcDay = day; }
        if (!c.enabled || s.inFlight || clock.millis() < s.nextAttempt || s.usage.requests >= c.dailyRequests || tokens > c.dailyTokenBudget - s.usage.reservedTokens) return false;
        s.inFlight = true; s.usage.requests++; s.usage.reservedTokens += tokens; s.nextAttempt = clock.millis() + c.minimumIntervalSeconds * 1000L; return true;
    }
    public synchronized Map<String, WorldData.Usage> usageSnapshot() {
        var result = new LinkedHashMap<String, WorldData.Usage>(); statuses.forEach((id, s) -> result.put(id, copy(s.usage))); return result;
    }
    public synchronized List<String> status() {
        return statuses.entrySet().stream().map(e -> e.getKey() + ": requests=" + e.getValue().usage.requests + " reservedTokens=" + e.getValue().usage.reservedTokens
            + " cooldownSeconds=" + Math.max(0, (e.getValue().nextAttempt - clock.millis()) / 1000) + " lastError=" + e.getValue().lastError).toList();
    }
    private static WorldData.Usage copy(WorldData.Usage u) { var c = new WorldData.Usage(); c.utcDay = u.utcDay; c.requests = u.requests; c.reservedTokens = u.reservedTokens; return c; }
    @Override public void close() { closed.set(true); outstanding.forEach(f -> f.cancel(true)); executor.shutdownNow(); }
}
