package dev.aivillages.core.kernel.runtime.persistence;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One offered storage operation, with no internal queue and no caller-thread I/O. */
public final class ArchiveService {
    @FunctionalInterface public interface Operation<T> { T run(ArchiveWriter writer)throws IOException; }
    private final ArchiveWriter writer;
    private final Executor background;
    private final AtomicReference<CompletableFuture<?>> pending=new AtomicReference<>();
    private final AtomicBoolean stopped=new AtomicBoolean();
    public ArchiveService(ArchiveWriter writer,Executor background) {
        this.writer=Objects.requireNonNull(writer);this.background=Objects.requireNonNull(background);
    }
    /** Capture is O(1). Empty means no operation was issued; it is ordinary backpressure. */
    public synchronized <T> Optional<CompletableFuture<T>> offer(Operation<T> operation) {
        Objects.requireNonNull(operation);var completion=new CompletableFuture<T>();Thread caller=Thread.currentThread();
        if(stopped.get() || writer.readOnly() || !pending.compareAndSet(null,completion))return Optional.empty();
        if(stopped.get()){pending.compareAndSet(completion,null);return Optional.empty();}
        try {
            background.execute(()->{
                try {
                    if(Thread.currentThread()==caller)throw new IllegalStateException("Background storage executor required");
                    completion.complete(operation.run(writer));
                }catch(Throwable failure){completion.completeExceptionally(failure);}
                finally{pending.compareAndSet(completion,null);}
            });
        }catch(RejectedExecutionException rejected){pending.compareAndSet(completion,null);return Optional.empty();}
        return Optional.of(completion);
    }
    public boolean pending(){return pending.get()!=null;}
    public synchronized void stopAdmissions(){stopped.set(true);}
    /** Drain is lifecycle coordination only, never a successful-publication acknowledgement. */
    public CompletableFuture<Void> drained() {
        var work=pending.get();return work==null?CompletableFuture.completedFuture(null):work.handle((value,error)->null);
    }
}
