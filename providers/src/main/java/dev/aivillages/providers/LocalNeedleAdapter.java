package dev.aivillages.providers;

import dev.aivillages.core.kernel.LanguageRequests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** One stateless local process per turn; no HTTP, downloads, generation or tool execution. */
public final class LocalNeedleAdapter implements LanguageRequests.Port, AutoCloseable {
    public static final String REVISION = "c7c415a3d1b3d929014bc6e866d51ebb971f7089";
    public static final String MODEL_SHA256 = "c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38";
    public static final String RUNNER_SHA256 = "b197ceaef3b300a0b14c3a4fde92305527e43f9256c53d2a53d2a2fe8fe69678";
    public record Config(Path runner, Path model) {
        public Config { runner = runner.toAbsolutePath().normalize(); model = model.toAbsolutePath().normalize(); }
    }
    @FunctionalInterface interface Assets { void verify(Config config) throws Exception; }
    private final Config config;
    private final Assets assets;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "cognitivecraft-needle"));
    private final ScheduledExecutorService timer = newTimer();
    private static ScheduledExecutorService newTimer() {
        var timer = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "cognitivecraft-needle-deadline"));
        timer.setRemoveOnCancelPolicy(true); return timer;
    }
    private final AtomicReference<Call> active = new AtomicReference<>();
    private volatile boolean closed;
    private final AtomicLong calls = new AtomicLong();
    public long calls() { return calls.get(); }
    public boolean busy() { return active.get() != null; }
    public enum Compute { IDLE, STARTING, RUNNING, CANCELLING, STOP_UNCONFIRMED }
    public record Status(boolean closed, Compute compute, long calls, int queued) { }
    public Status status() {
        Call call = active.get();
        Compute compute = call == null ? Compute.IDLE : call.stopUnconfirmed ? Compute.STOP_UNCONFIRMED
                : call.cancelled.get() ? Compute.CANCELLING : call.processRef.get() == null ? Compute.STARTING : Compute.RUNNING;
        return new Status(closed, compute, calls(), 0);
    }
    public LocalNeedleAdapter(Config config) { this(config, LocalNeedleAdapter::verifyAssets); }
    /** Engine-free process fixtures exercise the same cancellation and byte handling. */
    LocalNeedleAdapter(Config config, Assets assets) { this.config = config; this.assets = assets; }
    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }
    @Override public LanguageRequests.Handle interpret(LanguageRequests.Input input) {
        Call call = new Call(input);
        if (closed || !active.compareAndSet(null, call)) {
            call.result.complete(LanguageRequests.Result.unavailable()); return call;
        }
        try {
            call.deadline = timer.schedule(call::cancel, LanguageRequests.DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
            worker.execute(call::run);
        } catch (RejectedExecutionException rejected) {
            active.compareAndSet(call, null); call.cancel();
        }
        return call;
    }
    private final class Call implements LanguageRequests.Handle {
        final LanguageRequests.Input input;
        final CompletableFuture<LanguageRequests.Result> result = new CompletableFuture<>();
        volatile ScheduledFuture<?> deadline;
        private final AtomicReference<Process> processRef = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile boolean stopUnconfirmed;
        Call(LanguageRequests.Input input) { this.input = input; }
        @Override public CompletionStage<LanguageRequests.Result> result() { return result; }
        @Override public void cancel() {
            cancelled.set(true);
            Process process = processRef.get();
            if (process != null) process.destroyForcibly();
            result.complete(LanguageRequests.Result.unavailable());
        }
        void run() {
            Path tools = null;
            Process process = null;
            try {
                assets.verify(config); // All asset I/O and hashing stays off the game thread.
                tools = Files.createTempFile("cognitivecraft-needle-", ".json");
                var prepared = NeedleWire.prepare(input);
                Files.writeString(tools, prepared.tools(), StandardCharsets.UTF_8);
                {
                    if (cancelled.get() || closed) return;
                    ProcessBuilder builder = new ProcessBuilder(config.runner().toString(), "--model", config.model().toString(),
                            "--tools", tools.toString(), "--prompt", prepared.text(), "--max", "384", "--threads", "1",
                            "--fail-input-overflow");
                    builder.environment().clear();
                    builder.environment().put("NEEDLE_TELEMETRY", "0");
                    builder.environment().put("DO_NOT_TRACK", "1");
                    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                    process = builder.start(); processRef.set(process); calls.incrementAndGet();
                    if (cancelled.get() || closed) { process.destroyForcibly(); return; }
                    process.getOutputStream().close();
                }
                byte[] bytes = process.getInputStream().readNBytes(LanguageRequests.MAX_OUTPUT_BYTES + 1);
                if (bytes.length > LanguageRequests.MAX_OUTPUT_BYTES) {
                    result.complete(LanguageRequests.Result.invalid()); process.destroyForcibly();
                } else if (process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    if (!cancelled.get()) result.complete(NeedleWire.decode(bytes, prepared));
                } else result.complete(LanguageRequests.Result.unavailable());
            } catch (Exception failure) { result.complete(LanguageRequests.Result.unavailable()); }
            finally {
                boolean stopped = true;
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    try { stopped = process.waitFor(1, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); stopped = false; }
                }
                if (deadline != null) deadline.cancel(false);
                if (tools != null) try { Files.deleteIfExists(tools); } catch (Exception ignored) { }
                result.complete(LanguageRequests.Result.unavailable());
                // An unconfirmed process stop fences this adapter instead of overlapping compute.
                stopUnconfirmed = !stopped;
                if (stopped) active.compareAndSet(this, null);
            }
        }
    }
    private static void verifyAssets(Config config) throws Exception {
        verify(config.runner(), 2_000_000, RUNNER_SHA256);
        verify(config.model(), 40_000_000, MODEL_SHA256);
        if (!Files.isExecutable(config.runner())) throw new IllegalArgumentException("Needle runner is not executable");
    }
    private static void verify(Path path, int limit, String expected) throws Exception {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(limit + 1); }
        if (bytes.length > limit || !expected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
            throw new IllegalArgumentException("Needle asset mismatch");
    }
    @Override public void close() {
        closed = true; Call call = active.get(); if (call != null) call.cancel();
        timer.shutdownNow(); worker.shutdown();
    }
}
