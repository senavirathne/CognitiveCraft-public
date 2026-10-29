package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Jobs.*;
import dev.aivillages.core.kernel.Outcomes.*;
import java.util.*;

/** Cancels the exact stopped first H/P owner after complete saved native lineage is acknowledged. */
public final class PickupStoppedSource implements TrustedStoppedSource {
    public interface Evidence {
        PickupEvidenceStore store();
        PickupEvidenceStore.Code verify(PickupEvidenceStore.Reconciled proof);
    }
    private final JobLifecycleStore jobs;private final Evidence evidence;
    public PickupStoppedSource(JobLifecycleStore jobs,Evidence evidence){this.jobs=Objects.requireNonNull(jobs);this.evidence=Objects.requireNonNull(evidence);}
    public StoppedObservation observe(StoppedContext original) {
        var store=evidence.store();var job=jobs.query(original.job(),original.origin());var attempt=original.original();
        if(store.fenced() || store.pending() || !job.guard().equals(original.guard()) || !Objects.equals(job.current(),attempt))return unknown("pickup owner not acknowledged");
        var entries=store.snapshot().entries().stream().filter(e->e.intent().key().step().equals(original.job())
                && e.intent().key().attempt().equals(original.attempt()) && e.intent().key().generation()==original.generation()).toList();
        if(entries.size()!=1)return unknown("original first pickup coverage incomplete");
        var entry=entries.getFirst();var intent=entry.intent();var h=intent.harvest();var r=entry.result();var request=attempt.bound().request();
        if(job.parent()!=null || !job.children().isEmpty() || !intent.key().root().equals(job.id()) || !intent.key().world().equals(original.world())
                || !h.intent().origin().equals(original.origin()) || !h.intent().actor().equals(attempt.worker())
                || !LogAcquisition.is(request.capability()) || !LogAcquisition.item(request.capability()).id().equals(h.intent().item())
                || !new AreaValue(h.intent().area()).equals(request.arguments().get("source")) || !new ContainerValue(h.intent().destination()).equals(request.arguments().get("destination"))
                || attempt.executions().size()!=1 || !attempt.executions().getFirst().runId().equals(h.intent().run())
                || attempt.effects()>2 || !attempt.receipts().isEmpty() || r==null || r.live()!=NativePickup.Live.COMPLETE || r.receipt()==null)
            return unknown("original pickup request or effect changed");
        var receipts=List.of(h.result().receipt(),r.receipt());
        if(attempt.resourceReceipts().size()>2 || !receipts.subList(0,attempt.resourceReceipts().size()).equals(attempt.resourceReceipts()))return unknown("original pickup receipt prefix changed");
        if(job.cancellationOutcome()!=State.CANCELLED)return unknown("original pickup awaits cancellation; no continuation authority");
        var proof=store.reconcile(intent.key());
        if(proof==null || proof.result()!=NativePickup.Reconciliation.SAVED_COMPLETE)
            return proof!=null && proof.result()==NativePickup.Reconciliation.WAIT_WORLD?new StoppedObservation(StoppedKind.WAIT_WORLD,null,"original pickup native cohort unavailable"):unknown("original pickup native cohort unproven");
        if(evidence.verify(proof)!=PickupEvidenceStore.Code.RESOLVED)return unknown("pickup verification awaiting durable acknowledgement");
        var usage=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);usage.putAll(attempt.usage());r.usage().forEach((kind,count)->usage.merge(kind,count,Math::max));
        if(usage.entrySet().stream().anyMatch(e->e.getValue()>attempt.allowance().maximum(e.getKey())))return unknown("original pickup allowance exceeded");
        return new StoppedObservation(StoppedKind.PARTIAL,new Report(original.attempt(),original.generation(),2,usage,List.of(),
                new Execution(ExecutionStatus.CANCELLED,Reason.CANCELLED,2,null),receipts),"saved original H/P; no delivery credit");
    }
    private static StoppedObservation unknown(String reason){return StoppedObservation.ambiguous(reason);}
}
