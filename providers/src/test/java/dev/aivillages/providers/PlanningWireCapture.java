package dev.aivillages.providers;

import dev.aivillages.core.kernel.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Test-only transparent capture: rejected bytes are evidence, never candidate IR or repair input. */
final class PlanningWireCapture implements LocalGenerationAdapter.Backend, AutoCloseable {
    private final LocalGenerationAdapter.Backend backend;
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2));
    private final ExecutorService handoff = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4));
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
    private final LocalGenerationAdapter adapter;
    private byte[] request = new byte[0];
    private ByteArrayOutputStream response = new ByteArrayOutputStream();
    private int status;
    private boolean overflow;

    PlanningWireCapture(String model, Clock clock) {
        var endpoint = URI.create("http://127.0.0.1:11434/api/chat");
        backend = new OllamaHttpBackend(endpoint, model);
        timer.setRemoveOnCancelPolicy(true);
        adapter = new LocalGenerationAdapter(new LocalGenerationAdapter.Config(endpoint, model, true, 768),
                this, clock, worker, handoff, timer);
    }

    LocalGenerationAdapter adapter() { return adapter; }

    @Override public LocalGenerationAdapter.Transfer start(byte[] body, long remainingMillis,
            int maxWireBytes, LocalGenerationAdapter.Sink sink) {
        synchronized (this) {
            request = body.clone(); response = new ByteArrayOutputStream(); status = 0; overflow = false;
        }
        return backend.start(body, remainingMillis, maxWireBytes, new LocalGenerationAdapter.Sink() {
            public void chunk(byte[] bytes) {
                synchronized (PlanningWireCapture.this) {
                    if (bytes != null && bytes.length <= maxWireBytes - response.size()) response.writeBytes(bytes);
                    else overflow = true;
                }
                sink.chunk(bytes);
            }
            public void complete(int code) {
                synchronized (PlanningWireCapture.this) { status = code; }
                sink.complete(code);
            }
            public void error() { sink.error(); }
            public void stopped() { sink.stopped(); }
            public void modelDigest(String digest) { sink.modelDigest(digest); }
        });
    }

    synchronized Map<String,Object> evidence(boolean material) throws Exception {
        var bytes = response.toByteArray();
        var row = new LinkedHashMap<String,Object>();
        row.put("httpStatus", status); row.put("overflow", overflow);
        row.put("request", new String(request, StandardCharsets.UTF_8));
        row.put("requestSha256", sha(request)); row.put("wireBytes", bytes.length);
        row.put("wireSha256", sha(bytes)); row.put("response", new String(bytes, StandardCharsets.UTF_8));
        try {
            var envelope = StrictJson.transportObject((String) row.get("response"));
            row.put("done", Boolean.TRUE.equals(envelope.get("done")));
            row.put("doneReason", envelope.getOrDefault("done_reason", "absent"));
            if (envelope.get("message") instanceof Map<?,?> message && message.get("content") instanceof String content) {
                row.put("rawCandidate", content); row.put("rawCandidateBytes", content.getBytes(StandardCharsets.UTF_8).length);
                row.put("rawCandidateSha256", sha(content.getBytes(StandardCharsets.UTF_8)));
                try {
                    if (material) MaterialPlan.decode(content); else Plan.decode(content, 8192);
                    row.put("decodeCode", "NONE"); row.put("decodePath", "");
                } catch (StrictJson.Invalid invalid) {
                    row.put("decodeCode", invalid.code()); row.put("decodePath", invalid.path());
                }
            }
        } catch (StrictJson.Invalid invalid) {
            row.put("envelopeCode", invalid.code()); row.put("envelopePath", invalid.path());
        }
        return row;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Override public void close() {
        adapter.close(); worker.shutdownNow(); handoff.shutdownNow(); timer.shutdownNow();
    }
}
