package dev.aivillages.fabric;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.*;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.GenerationPort;
import dev.aivillages.providers.*;

/** Test-only bounded wire evidence around the unchanged actual Ollama transport and production adapter. */
final class RecordedAxeGeneration implements GenerationPort,AutoCloseable {
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->daemon(r,"rb004-model-wire"));
    private final ExecutorService handoff=Executors.newSingleThreadExecutor(r->daemon(r,"rb004-model-result"));
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"rb004-model-deadline"));
    private final LocalGenerationAdapter adapter;
    RecordedAxeGeneration(Path root) {
        var config=new LocalGenerationAdapter.Config(URI.create("http://127.0.0.1:11434/api/chat"),System.getenv("COGNITIVECRAFT_OLLAMA_MODEL"),true,3072);
        var real=new OllamaHttpBackend(config.endpoint(),config.model());var sequence=new AtomicInteger();
        LocalGenerationAdapter.Backend capture=(body,remaining,max,sink)->{
            int attempt=sequence.incrementAndGet();if(attempt>8)throw new IllegalStateException("Bounded diagnostic attempts");
            save(root,"request-"+attempt+".json",body);var bytes=new ByteArrayOutputStream();
            return real.start(body,remaining,max,new LocalGenerationAdapter.Sink() {
                public void chunk(byte[] chunk) {int n=Math.min(chunk.length,Math.max(0,max-bytes.size()));bytes.write(chunk,0,n);sink.chunk(chunk);}
                public void complete(int status) {save(root,"response-"+attempt+".json",bytes.toByteArray());save(root,"http-"+attempt+".txt",Integer.toString(status).getBytes(java.nio.charset.StandardCharsets.UTF_8));sink.complete(status);}
                public void error() {save(root,"transport-error-"+attempt+".json",bytes.toByteArray());sink.error();}
                public void stopped() {sink.stopped();}
                public void modelDigest(String digest) {sink.modelDigest(digest);}
            });
        };
        adapter=new LocalGenerationAdapter(config,capture,Clock.systemUTC(),worker,handoff,timer);
    }
    private static Thread daemon(Runnable r,String name) {var t=new Thread(r,name);t.setDaemon(true);return t;}
    private static void save(Path root,String name,byte[] bytes) {
        // Backend.start and HTTP callbacks run off the game thread. Capture is bounded evidence, never a proposal substitution.
        try {Files.createDirectories(root);Files.write(root.resolve(name),bytes);}catch(java.io.IOException error){throw new IllegalStateException("Unable to retain actual generation wire",error);}
    }
    public Generation.Handle generate(Generation.Request request,Budgets.InferenceLimits limits,Budgets.Ledger usage) {return adapter.generate(request,limits,usage);}
    public Generation.Descriptor descriptor() {return adapter.descriptor();}
    public Generation.Status status() {return adapter.status();}
    public void close() {adapter.close();worker.shutdown();handoff.shutdown();timer.shutdown();}
}
