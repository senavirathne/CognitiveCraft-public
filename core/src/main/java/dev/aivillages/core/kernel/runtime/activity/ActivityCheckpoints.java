package dev.aivillages.core.kernel.runtime.activity;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.runtime.evidence.*;
import java.util.*;
import java.util.function.Predicate;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;

/** Disposable original-checkpoint client. Live acknowledgements never become saved verifications.
 * Restart discards every live grant; the original native journal must then verify its saved cohort. */
public final class ActivityCheckpoints {
    private final JobLifecycleStore jobs;
    private final DepositEvidenceStore deposits;
    private final Predicate<Entry> currentProcessAcknowledgement;
    private final Map<String,Entry> live=new HashMap<>();
    private final Thread thread=Thread.currentThread();private int cursor;
    public ActivityCheckpoints(JobLifecycleStore jobs,DepositEvidenceStore deposits,Predicate<Entry> currentProcessAcknowledgement) {
        this.jobs=Objects.requireNonNull(jobs);this.deposits=Objects.requireNonNull(deposits);
        this.currentProcessAcknowledgement=Objects.requireNonNull(currentProcessAcknowledgement);
        deposits.attachLiveSettlements(this::liveSettled);
    }
    private void check(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Checkpoint owner thread");}
    private boolean liveSettled(Entry entry) {
        check();Entry original=live.get(entry.token());
        return original!=null && original.intent().equals(entry.intent()) && Objects.equals(original.result(),entry.result());
    }
    /** At most four original entries; one existing native-writer publication in a slice. */
    public void step() {
        check();if(deposits.fenced()||deposits.pending()||!jobs.ready())return;
        var entries=deposits.snapshot().entries();
        for(int n=0;n<Math.min(4,entries.size());n++) {
            cursor%=entries.size();Entry entry=entries.get(cursor++);
            if(entry.verification()!=null || liveSettled(entry) || !matches(entry))continue;
            if(currentProcessAcknowledgement.test(entry)) {
                if(live.size()<MAX_ENTRIES)live.put(entry.token(),entry);
                continue;
            }
            // There is no current-process acknowledgement after restart. Counts alone cannot close it.
            var proof=deposits.reconcile(entry.intent().key());
            if(proof!=null && proof.result()==Reconciliation.SAVED_COMPLETE) {
                deposits.verify(proof);return;
            }
        }
    }
    private boolean matches(Entry entry) {
        try {
            if(entry.result()==null || entry.result().live()!=Live.COMPLETE || entry.intent().pickupPrefix()==null)return false;
            var k=entry.intent().key();Job job=jobs.query(k.root(),entry.intent().origin());
            if(job.activity()==null || !k.root().equals(k.step()) || !job.origin().equals(entry.intent().origin())
                    || !LogAcquisition.is(job.request().request().capability()))return false;
            var checkpoint=job.activity().checkpoints().stream().filter(c->c.attempt().equals(k.attempt())&&c.generation()==k.generation()).findFirst();
            if(checkpoint.isEmpty() || checkpoint.get().quantity()!=1)return false;
            var attempt=job.attempts().stream().filter(a->a.id().equals(k.attempt())&&a.generation()==k.generation()).findFirst().orElseThrow();
            var pickup=entry.intent().pickupPrefix().pickup();var harvest=pickup.intent().harvest();
            var receipts=List.of(harvest.result().receipt(),pickup.result().receipt(),entry.result().receipt());
            return !attempt.open() && attempt.terminal()!=null && attempt.terminal().status()==Outcomes.ExecutionStatus.SUCCEEDED
                    && attempt.credited()==1 && attempt.effects()==3 && attempt.resourceReceipts().equals(receipts)
                    && checkpoint.get().receipts().equals(receipts.stream().map(ResourceDelivery.Receipt::receiptId).toList())
                    && attempt.executions().stream().anyMatch(e->e.runId().equals(entry.intent().run())
                        && ResourceDelivery.completed(attempt.bound(),e.runId(),attempt.receipts(),attempt.resourceReceipts()));
        }catch(SecurityException|IllegalArgumentException|NoSuchElementException invalid){return false;}
    }
    /** Read-only host readiness. No caller can turn this value into effect authority. */
    public boolean ready(Job job) {
        check();if(deposits.fenced()||deposits.pending())return false;
        if(job.activity()==null)return true;
        var entries=deposits.snapshot().entries();
        // The qualified native predecessor is within this original root, never an unrelated old activity.
        if(job.activity().checkpoints().isEmpty())return entries.stream().noneMatch(e->e.intent().actor().address().equals(job.responsible().entityId().toString())&&!e.intent().key().root().equals(job.id()));
        for(var c:job.activity().checkpoints()) {
            var found=entries.stream().filter(e->e.intent().key().root().equals(job.id())&&e.intent().key().attempt().equals(c.attempt())
                    &&e.intent().key().generation()==c.generation()&&matches(e)).toList();
            if(found.size()!=1 || !deposits.settled(found.getFirst()))return false;
        }
        return true;
    }
}
