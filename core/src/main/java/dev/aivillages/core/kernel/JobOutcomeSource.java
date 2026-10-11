package dev.aivillages.core.kernel;

import java.util.*;
import java.util.function.Supplier;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Accomplishments.*;

/** Trusted, off-thread owner adapter. No command, narrative or model can supply outcome facts. */
public final class JobOutcomeSource {
    private final Supplier<Jobs.Snapshot> jobs;
    private final Supplier<CitizenRegistry.Snapshot> citizens;
    public JobOutcomeSource(Supplier<Jobs.Snapshot> jobs,Supplier<CitizenRegistry.Snapshot> citizens){
        this.jobs=Objects.requireNonNull(jobs);this.citizens=Objects.requireNonNull(citizens);
    }
    static final class Work {
        final int maximum;int count;
        Work(int maximum){this.maximum=maximum;}
        void step(){if(count>=maximum)throw new Limited();count++;}
    }
    static final class Limited extends RuntimeException { }
    static final class Invalid extends RuntimeException { }
    static final class Stale extends RuntimeException { }
    record Verified(Jobs.Job job,Map<UUID,CitizenRegistry.Citizen> citizens) { }

    Verified read(OutcomeRef reference,Work work){
        var snapshot=jobs.get();var identities=citizens.get();
        if(snapshot==null||identities==null||!reference.world().equals(snapshot.worldId())||!reference.world().equals(identities.worldId()))throw new Invalid();
        Jobs.Job selected=null;
        for(var job:snapshot.jobs()){work.step();if(job.id().equals(reference.job()))selected=job;}
        if(selected==null)throw new Invalid();
        if(selected.revision()!=reference.revision())throw new Stale();
        if(!selected.request().request().capability().equals(CropDelivery.ID))throw new Invalid();
        var index=new HashMap<UUID,CitizenRegistry.Citizen>();
        for(var citizen:identities.citizens()){work.step();index.put(citizen.actor().citizenId(),citizen);}
        var ids=new HashSet<UUID>();
        for(var attempt:selected.attempts()){
            work.step();var citizen=index.get(attempt.worker().citizenId());
            if(citizen==null||!citizen.actor().equals(attempt.worker())||!citizen.owner().equals(selected.origin())
                    ||!attempt.bound().context().equals(selected.origin())||!attempt.bound().request().capability().equals(CropDelivery.ID))throw new Invalid();
            var source=((AreaValue)attempt.bound().request().arguments().get("source")).value();
            var target=((ContainerValue)attempt.bound().request().arguments().get("destination")).value();
            if(!source.equals(((AreaValue)selected.request().request().arguments().get("source")).value())
                    ||!target.equals(((ContainerValue)selected.request().request().arguments().get("destination")).value()))throw new Invalid();
            var executions=new HashMap<UUID,Jobs.ExecutionReference>();
            for(var execution:attempt.executions()){work.step();if(!execution.artifact().capability().equals(CropDelivery.ID))throw new Invalid();executions.put(execution.runId(),execution);}
            var batches=new HashMap<List<UUID>,long[]>();
            for(var receipt:attempt.receipts()){
                work.step();if(!ids.add(receipt.receiptId())||!executions.containsKey(receipt.runId())||!receipt.actor().equals(attempt.worker())
                        ||!receipt.source().equals(source)||!receipt.destination().equals(target))throw new Invalid();
                var amounts=batches.computeIfAbsent(List.of(receipt.runId(),receipt.batchId()),ignored->new long[3]);
                amounts[receipt.stage().ordinal()]=Math.addExact(amounts[receipt.stage().ordinal()],receipt.wheat());
            }
            long proven=0;for(var stages:batches.values()){work.step();proven=Math.addExact(proven,Math.min(stages[0],Math.min(stages[1],stages[2])));}
            long requested=((IntValue)attempt.bound().request().arguments().get("amount")).value();
            if(attempt.credited()>proven||attempt.credited()>requested||requested<1||requested>64)throw new Invalid();
            if(attempt.terminal()!=null&&attempt.terminal().status()==Outcomes.ExecutionStatus.SUCCEEDED
                    &&(attempt.uncertain()||attempt.executions().stream().noneMatch(e->completed(attempt,e,work))))throw new Invalid();
        }
        // Detect forged reuse of one action across jobs even at a misconfigured trusted test source.
        for(var other:snapshot.jobs())if(!other.id().equals(selected.id()))for(var attempt:other.attempts())for(var receipt:attempt.receipts()){
            work.step();if(ids.contains(receipt.receiptId()))throw new Invalid();
        }
        return new Verified(selected,Map.copyOf(index));
    }
    static boolean completed(Jobs.Attempt attempt,Jobs.ExecutionReference execution,Work work){
        for(int n=0;n<attempt.receipts().size()*2;n++)work.step();
        return CropDelivery.completed(attempt.bound(),execution.runId(),attempt.receipts());
    }
    boolean controls(ActorRef actor,TrustedContext caller){
        if(caller==null||actor==null)return false;var snapshot=citizens.get();
        return snapshot!=null&&snapshot.worldId().equals(caller.scope().worldId())&&snapshot.citizens().stream()
                .anyMatch(c->c.actor().equals(actor)&&c.owner().equals(caller));
    }
}
