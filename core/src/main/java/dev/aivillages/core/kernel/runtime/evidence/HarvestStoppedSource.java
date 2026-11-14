package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Jobs.*;
import dev.aivillages.core.kernel.Outcomes.*;
import java.util.*;

/** First-harvest accounting only. Does not execute, allocate allowance, resume, or credit delivery. */
public final class HarvestStoppedSource implements TrustedStoppedSource {
    public interface Evidence {
        HarvestEvidenceStore store();
        HarvestEvidenceStore.Code verify(HarvestEvidenceStore.Reconciled proof);
    }
    private final JobLifecycleStore jobs;private final Evidence evidence;
    public HarvestStoppedSource(JobLifecycleStore jobs,Evidence evidence){this.jobs=Objects.requireNonNull(jobs);this.evidence=Objects.requireNonNull(evidence);}
    @Override public StoppedObservation observe(StoppedContext original) {
        var store=evidence.store();var job=jobs.query(original.job(),original.origin());var attempt=original.original();
        if(store.fenced() || store.pending() || !job.guard().equals(original.guard()) || !Objects.equals(job.current(),attempt))return unknown("harvest evidence or original owner not acknowledged");
        var entries=store.snapshot().entries().stream().filter(e->e.intent().key().step().equals(original.job())
                && e.intent().key().attempt().equals(original.attempt()) && e.intent().key().generation()==original.generation()).toList();
        if(entries.size()!=1)return unknown("first-harvest envelope requires one original effect");
        var entry=entries.getFirst();var intent=entry.intent();var result=entry.result();var request=attempt.bound().request();
        boolean crop=request.capability().equals(CropDelivery.ID);
        if(job.parent()!=null || !job.children().isEmpty() || !intent.key().root().equals(job.id())
                || !intent.origin().equals(original.origin()) || !intent.key().world().equals(original.world()) || !intent.actor().equals(attempt.worker())
                || !(crop?intent.item().equals("minecraft:wheat"):LogAcquisition.is(request.capability()) && LogAcquisition.item(request.capability()).id().equals(intent.item()))
                || !new AreaValue(intent.area()).equals(request.arguments().get("source")) || !new ContainerValue(intent.destination()).equals(request.arguments().get("destination"))
                || attempt.executions().size()!=1 || !attempt.executions().getFirst().runId().equals(intent.run())
                || intent.key().ordinal()!=1 || intent.conditions().usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L)!=0
                || intent.conditions().usage().getOrDefault(Budgets.Kind.ATTEMPTED_EFFECTS,0L)!=0
                || attempt.effects()>1 || result==null || result.live()!=NativeHarvest.Live.COMPLETE
                || (crop?result.cropReceipt()==null || !attempt.resourceReceipts().isEmpty()
                    || !attempt.receipts().isEmpty() && !attempt.receipts().equals(List.of(result.cropReceipt()))
                    :result.receipt()==null || !attempt.receipts().isEmpty()
                    || !attempt.resourceReceipts().isEmpty() && !attempt.resourceReceipts().equals(List.of(result.receipt()))))return unknown("original first-harvest coverage incomplete or changed");
        if(job.cancellationOutcome()!=State.CANCELLED)return unknown("saved harvest awaits original cancellation; continuation unavailable");
        var proof=store.reconcile(intent.key());
        if(proof==null || proof.result()!=NativeHarvest.Reconciliation.SAVED_COMPLETE) {
            return proof!=null && proof.result()==NativeHarvest.Reconciliation.WAIT_WORLD
                    ?new StoppedObservation(StoppedKind.WAIT_WORLD,null,"original harvest participants unavailable"):unknown("original harvest saved participants unproven or torn");
        }
        if(evidence.verify(proof)!=HarvestEvidenceStore.Code.RESOLVED)return unknown("original harvest verification awaiting durable acknowledgement");
        // Cumulative metrics may already include later observation work; a receipt never resets them.
        var usage=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);usage.putAll(attempt.usage());
        result.usage().forEach((kind,count)->usage.merge(kind,count,Math::max));
        if(usage.entrySet().stream().anyMatch(e->e.getValue()>attempt.allowance().maximum(e.getKey())))return unknown("original harvest allowance exceeded");
        var report=new Report(original.attempt(),original.generation(),1,usage,crop?List.of(result.cropReceipt()):List.of(),new Execution(ExecutionStatus.CANCELLED,Reason.CANCELLED,1,null),crop?List.of():List.of(result.receipt()));
        return new StoppedObservation(StoppedKind.PARTIAL,report,"saved original harvest "+entry.token()+"; no delivery credit");
    }
    private static StoppedObservation unknown(String reason){return StoppedObservation.ambiguous(reason);}
}
