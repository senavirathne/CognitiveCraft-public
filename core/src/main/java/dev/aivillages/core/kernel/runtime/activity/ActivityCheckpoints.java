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
    private final boolean verifyClosedLogJobs;
    private final Predicate<Entry> savedSuccessor;
    private final Map<Key,Entry> settledHere=new HashMap<>();
    private enum ClosedLog { NONE, ADMISSION, CHILD }
    private final Thread thread=Thread.currentThread();private int cursor;
    public ActivityCheckpoints(JobLifecycleStore jobs,DepositEvidenceStore deposits,Predicate<Entry> currentProcessAcknowledgement) {
        this(jobs, deposits, currentProcessAcknowledgement, false);
    }
    public ActivityCheckpoints(JobLifecycleStore jobs, DepositEvidenceStore deposits,
                               Predicate<Entry> currentProcessAcknowledgement, boolean verifyClosedLogJobs) {
        this(jobs, deposits, currentProcessAcknowledgement, verifyClosedLogJobs, entry -> false);
    }
    public ActivityCheckpoints(JobLifecycleStore jobs, DepositEvidenceStore deposits,
                               Predicate<Entry> currentProcessAcknowledgement, boolean verifyClosedLogJobs,
                               Predicate<Entry> savedSuccessor) {
        this.jobs=Objects.requireNonNull(jobs);this.deposits=Objects.requireNonNull(deposits);
        this.currentProcessAcknowledgement=Objects.requireNonNull(currentProcessAcknowledgement);
        this.verifyClosedLogJobs = verifyClosedLogJobs;
        this.savedSuccessor = Objects.requireNonNull(savedSuccessor);
        deposits.attachLiveSettlements(this::settledHere);
    }
    private void check(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Checkpoint owner thread");}
    private boolean settledHere(Entry entry) {
        check();Entry original=settledHere.get(entry.intent().key());
        return original!=null && original.intent().equals(entry.intent()) && Objects.equals(original.result(),entry.result());
    }
    /** At most four original entries; one existing native-writer publication in a slice. */
    public void step() {
        check();if(deposits.fenced()||deposits.pending()||!jobs.ready())return;
        var entries=deposits.snapshot().entries();
        for(int n=0;n<Math.min(4,entries.size());n++) {
            cursor%=entries.size();Entry entry=entries.get(cursor++);
            if(entry.verification()!=null || settledHere(entry))continue;
            boolean activityCheckpoint = matches(entry);
            ClosedLog closed = !activityCheckpoint && verifyClosedLogJobs ? closedLog(entry) : ClosedLog.NONE;
            if (!activityCheckpoint && closed==ClosedLog.NONE) continue;
            if(currentProcessAcknowledgement.test(entry)) {
                if((activityCheckpoint || closed==ClosedLog.CHILD) && settledHere.size()<MAX_ENTRIES)
                    settledHere.put(entry.intent().key(),entry);
                continue;
            }
            // There is no current-process acknowledgement after restart. Counts alone cannot close it.
            var proof=deposits.reconcile(entry.intent().key());
            if(proof!=null && proof.result()==Reconciliation.SAVED_COMPLETE) {
                deposits.verify(proof);return;
            }
            if (activityCheckpoint && verifyClosedLogJobs && settledHere.size() < MAX_ENTRIES && savedSuccessor.test(entry))
                settledHere.put(entry.intent().key(), entry);
        }
    }
    /** A completed legacy child can release the same-process exclusion after its exact delivery. */
    private ClosedLog closedLog(Entry entry) {
        try {
            var intent = entry.intent();
            var key = intent.key();
            if (key.ordinal() != 3 || intent.pickupPrefix() == null
                    || entry.result() == null || entry.result().live() != Live.COMPLETE) return ClosedLog.NONE;
            var job = jobs.query(key.step(), intent.origin());
            if (job.activity() != null || job.state() != State.SUCCEEDED
                    || !job.origin().equals(intent.origin()) || job.generation() != key.generation()
                    || !LogAcquisition.is(job.request().request().capability())
                    || !(job.request().request().arguments().get("amount") instanceof Contracts.IntValue amount)
                    || amount.value() != 1) return ClosedLog.NONE;
            var attempt = job.current();
            if (attempt == null || !attempt.id().equals(key.attempt()) || attempt.open() || attempt.uncertain()
                    || attempt.terminal().status() != Outcomes.ExecutionStatus.SUCCEEDED
                    || Math.min(job.allowance().deadlineEpochMillis(),attempt.allowance().deadlineEpochMillis())
                        != intent.conditions().deadlineEpochMillis()
                    || attempt.effects() != 3 || attempt.credited() != 1) return ClosedLog.NONE;
            var pickup = intent.pickupPrefix().pickup();
            var harvest = pickup.intent().harvest();
            if (!attempt.resourceReceipts().equals(List.of(harvest.result().receipt(), pickup.result().receipt(), entry.result().receipt()))
                    || attempt.executions().stream().noneMatch(execution -> execution.runId().equals(intent.run())
                    && ResourceDelivery.completed(attempt.bound(), execution.runId(), attempt.receipts(), attempt.resourceReceipts())))
                return ClosedLog.NONE;
            var ancestor=job;
            for (int depth=0; ancestor.parent()!=null; depth++) {
                if (depth==8) return ClosedLog.NONE;
                var parent=jobs.query(ancestor.parent(),intent.origin());
                if (!parent.origin().equals(intent.origin()) || !parent.children().contains(ancestor.id())) return ClosedLog.NONE;
                ancestor=parent;
            }
            if (!ancestor.id().equals(key.root())) return ClosedLog.NONE;
            return job.parent()==null ? ClosedLog.ADMISSION : ClosedLog.CHILD;
        } catch (SecurityException | IllegalArgumentException | NullPointerException unavailable) {
            return ClosedLog.NONE;
        }
    }
    private boolean matches(Entry entry) {
        try {
            if(entry.result()==null || entry.result().live()!=Live.COMPLETE || entry.intent().pickupPrefix()==null)return false;
            var k=entry.intent().key();Job job=jobs.query(k.root(),entry.intent().origin());
            if(job.activity()==null || !k.root().equals(k.step()) || !job.origin().equals(entry.intent().origin()))return false;
            var checkpoint=job.activity().checkpoints().stream().filter(c->c.attempt().equals(k.attempt())&&c.generation()==k.generation()).findFirst();
            if(checkpoint.isEmpty() || checkpoint.get().quantity()!=1)return false;
            var attempt=job.attempts().stream().filter(a->a.id().equals(k.attempt())&&a.generation()==k.generation()).findFirst().orElseThrow();
            if(!LogAcquisition.is(attempt.bound().request().capability()))return false;
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
        if(job.activity().checkpoints().isEmpty())return entries.stream().noneMatch(e->
                e.intent().actor().address().equals(job.responsible().entityId().toString())
                && !e.intent().key().root().equals(job.id())
                && (job.activity().production()==null || !deposits.settled(e)));
        for(var c:job.activity().checkpoints()) {
            if(job.activity().production()!=null) {
                var step=job.activity().forAttempt(c.attempt());
                if(step==null || step.invocation()==null || !LogAcquisition.is(step.invocation().capability()))continue;
            }
            var found=entries.stream().filter(e->e.intent().key().root().equals(job.id())&&e.intent().key().attempt().equals(c.attempt())
                    &&e.intent().key().generation()==c.generation()&&matches(e)).toList();
            if(found.size()!=1 || !deposits.settled(found.getFirst()))return false;
        }
        return true;
    }
}
