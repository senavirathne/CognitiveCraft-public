package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Jobs.*;
import dev.aivillages.core.kernel.Outcomes.*;
import java.util.*;

/** Accounting only: original H/P/D chain and cancellation; no new worker, deadline or allowance. */
public final class JoinedDepositStoppedSource implements TrustedStoppedSource {
    public interface Evidence {
        HarvestEvidenceStore harvest();PickupEvidenceStore pickup();DepositEvidenceStore deposit();
        DepositEvidenceStore.Code verify(DepositEvidenceStore.Reconciled proof);
    }
    private final JobLifecycleStore jobs;private final Evidence evidence;
    public JoinedDepositStoppedSource(JobLifecycleStore jobs,Evidence evidence){this.jobs=Objects.requireNonNull(jobs);this.evidence=Objects.requireNonNull(evidence);}
    public StoppedObservation observe(StoppedContext original) {
        var store=evidence.deposit();var job=jobs.query(original.job(),original.origin());var attempt=original.original();
        if(store.fenced() || store.pending() || evidence.harvest().fenced() || evidence.harvest().pending() || evidence.pickup().fenced() || evidence.pickup().pending()
                || !job.guard().equals(original.guard()) || !Objects.equals(job.current(),attempt))return unknown("joined original evidence not acknowledged");
        var entries=store.snapshot().entries().stream().filter(e->e.intent().key().step().equals(original.job())
                && e.intent().key().attempt().equals(original.attempt()) && e.intent().key().generation()==original.generation()).toList();
        if(entries.size()!=1)return unknown("original first deposit coverage incomplete");
        var entry=entries.getFirst();var intent=entry.intent();var prefix=intent.pickupPrefix();var result=entry.result();
        if(prefix==null || result!=null && result.live()!=NativeDeposit.Live.COMPLETE && result.live()!=NativeDeposit.Live.NO_EFFECT)return unknown("joined native deposit result absent or uncertain");
        var pickup=prefix.pickup();var harvest=pickup.intent().harvest();var h=harvest.intent();var request=attempt.bound().request();
        if(evidence.pickup().snapshot().entries().stream().noneMatch(pickup::equals) || evidence.harvest().snapshot().entries().stream().noneMatch(harvest::equals))return unknown("original H/P journals missing or changed");
        long effects=result!=null && result.live()==NativeDeposit.Live.COMPLETE?3:2;
        if(job.parent()!=null || !job.children().isEmpty() || !intent.key().root().equals(job.id()) || !intent.key().world().equals(original.world())
                || !intent.origin().equals(original.origin()) || !h.actor().equals(attempt.worker()) || !LogAcquisition.is(request.capability())
                || !LogAcquisition.item(request.capability()).id().equals(intent.item()) || !new AreaValue(h.area()).equals(request.arguments().get("source"))
                || !new ContainerValue(h.destination()).equals(request.arguments().get("destination")) || attempt.executions().size()!=1
                || !attempt.executions().getFirst().runId().equals(intent.run()) || attempt.effects()>effects || !attempt.receipts().isEmpty())return unknown("original deposit request or owner changed");
        var receipts=new ArrayList<ResourceDelivery.Receipt>(intent.receiptPrefix().receipts());if(result!=null && result.receipt()!=null)receipts.add(result.receipt());
        if(attempt.resourceReceipts().size()>receipts.size() || !receipts.subList(0,attempt.resourceReceipts().size()).equals(attempt.resourceReceipts()))return unknown("original deposit receipt prefix changed");
        if(job.cancellationOutcome()!=State.CANCELLED)return unknown("saved deposit awaits original cancellation; no continuation authority");
        var proof=store.reconcile(intent.key());var expected=result==null?NativeDeposit.Reconciliation.PREPARED_NO_EFFECT:result.live()==NativeDeposit.Live.COMPLETE?NativeDeposit.Reconciliation.SAVED_COMPLETE:NativeDeposit.Reconciliation.SAVED_NO_EFFECT;
        if(proof==null || proof.result()!=expected)return proof!=null&&proof.result()==NativeDeposit.Reconciliation.WAIT_WORLD
                ?new StoppedObservation(StoppedKind.WAIT_WORLD,null,"joined native cohort unavailable"):unknown("joined native cohort unproven");
        if(evidence.verify(proof)!=DepositEvidenceStore.Code.RESOLVED)return unknown("joined verification awaiting durable acknowledgement");
        var usage=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);usage.putAll(attempt.usage());(result==null?intent.conditions().usage():result.usage()).forEach((kind,count)->usage.merge(kind,count,Math::max));
        if(usage.entrySet().stream().anyMatch(e->e.getValue()>attempt.allowance().maximum(e.getKey())))return unknown("original deposit allowance exceeded");
        return new StoppedObservation(StoppedKind.PARTIAL,new Report(original.attempt(),original.generation(),effects,usage,List.of(),
                new Execution(ExecutionStatus.CANCELLED,Reason.CANCELLED,effects,null),receipts),"saved original H/P/D; exact delivery credit only");
    }
    private static StoppedObservation unknown(String why){return StoppedObservation.ambiguous(why);}
}
