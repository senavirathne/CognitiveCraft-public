package dev.aivillages.core.kernel;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.aivillages.core.kernel.Accomplishments.*;

/** One immutable job reference per turn; no historical tick scan, new scheduler or model port. */
public final class AccomplishmentService implements AutoCloseable {
    private final AccomplishmentLedger ledger;
    private final Thread gameThread=Thread.currentThread();
    private final AtomicBoolean closed=new AtomicBoolean();
    private CompletableFuture<Result> pending;
    private long revision=-1;
    private int cursor;
    private int backoff;
    private boolean retry;
    private Code last=Code.DUPLICATE;
    public AccomplishmentService(AccomplishmentLedger ledger){this.ledger=Objects.requireNonNull(ledger);}
    public void maintain(Jobs.Snapshot snapshot,Executor worker){
        if(Thread.currentThread()!=gameThread)throw new IllegalStateException("Ledger intake requires game thread");
        if(closed.get())return;
        if(pending!=null){if(!pending.isDone())return;try{last=pending.join().code();}catch(CompletionException failed){last=Code.UNAVAILABLE;}pending=null;
            retry|=last==Code.LIMIT||last==Code.STALE||last==Code.UNAVAILABLE;}
        if(snapshot.revision()!=revision){revision=snapshot.revision();cursor=0;backoff=0;retry=false;}
        if(cursor>=snapshot.jobs().size()){
            if(!retry)return;if(backoff++<20)return;cursor=0;backoff=0;retry=false;
        }
        var job=snapshot.jobs().get(cursor++);
        if(!job.request().request().capability().equals(CropDelivery.ID)||job.attempts().isEmpty())return;
        try{pending=CompletableFuture.supplyAsync(()->ledger.recordOutcomes(List.of(OutcomeRef.of(job)),closed::get),worker);}
        catch(RuntimeException refused){cursor--;last=Code.UNAVAILABLE;}
    }
    public Code last(){return last;}
    public boolean pending(){return pending!=null&&!pending.isDone();}
    @Override public void close(){closed.set(true);}
}
