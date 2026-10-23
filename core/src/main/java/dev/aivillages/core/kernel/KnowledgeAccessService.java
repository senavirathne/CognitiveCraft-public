package dev.aivillages.core.kernel;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.aivillages.core.kernel.KnowledgeAccess.*;

/** Bounded off-thread learning reconciliation; Jobs/dispatcher remain the only work scheduler. */
public final class KnowledgeAccessService implements AutoCloseable {
    private final KnowledgeAccessStore store;
    private final Thread gameThread=Thread.currentThread();
    private final AtomicBoolean closed=new AtomicBoolean();
    private CompletableFuture<Result> pending;
    private int cursor,backoff;
    private boolean retry;
    private long revision=-1,accessRevision=-1;
    private Code last=Code.DUPLICATE;
    public KnowledgeAccessService(KnowledgeAccessStore store){this.store=Objects.requireNonNull(store);}
    public void maintain(Jobs.Snapshot jobs,Executor worker){
        if(Thread.currentThread()!=gameThread)throw new IllegalStateException("Learning intake requires game thread");
        if(closed.get())return;
        store.checkSupervision();
        if(pending!=null){if(!pending.isDone())return;
            try{last=pending.join().code();}catch(CompletionException failure){last=Code.UNAVAILABLE;}pending=null;
            retry|=last==Code.PENDING||last==Code.LIMIT||last==Code.UNAVAILABLE;
        }
        if(jobs.revision()!=revision||store.snapshot().revision()!=accessRevision){revision=jobs.revision();accessRevision=store.snapshot().revision();cursor=0;backoff=0;retry=false;}
        if(cursor>=jobs.jobs().size()){
            if(!retry)return;if(backoff++<20)return;cursor=0;backoff=0;retry=false;
        }
        var job=jobs.jobs().get(cursor++);
        var practice=store.snapshot().practices().stream().filter(p->p.job().equals(job.id())&&p.stage()==Stage.PRACTICING).findFirst().orElse(null);
        if(practice==null&&(job.state()!=Jobs.State.SUCCEEDED||!ResourceDelivery.isDelivery(job.request().request().capability())))return;
        try{pending=CompletableFuture.supplyAsync(()->practice!=null
                ?store.recordLearningOutcome(job.id(),practice.learner().owner(),closed::get)
                :store.recordAcquisition(job.id(),closed::get),worker);
        }catch(RuntimeException refused){cursor--;last=Code.UNAVAILABLE;}
    }
    public Code last(){return last;}
    public boolean pending(){return pending!=null&&!pending.isDone();}
    @Override public void close(){closed.set(true);}
}
