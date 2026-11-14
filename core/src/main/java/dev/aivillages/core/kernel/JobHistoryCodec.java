package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.aivillages.core.kernel.Jobs.*;

/** Separate archive envelope; the original Job/request/receipt codecs keep their meanings. */
final class JobHistoryCodec {
    private JobHistoryCodec() { }
    enum Custody { NO_EFFECT, BALANCED_DELIVERY, CLOSED_COMPOSITION, CLOSED_ALLOCATIONS }
    static Custody custody(Job job,Snapshot component) {
        Custody disposition=custody(job);
        if(disposition!=null)return disposition;
        if(job.state()==State.SUCCEEDED && job.attempts().isEmpty() && job.preparation()==null
                && !job.request().request().capability().equals(PlanningGoals.ID)
                && !ResourceDelivery.resource(job.request().request().capability())
                && component.allocations().stream().anyMatch(a->a.demand().equals(job.id()) && !a.material()))
            return Custody.CLOSED_ALLOCATIONS;
        return null;
    }

    static Custody custody(Job job) {
        if(!job.state().terminal() || job.attempts().stream().anyMatch(Attempt::open)
                || BlueprintMaterials.valid(job.request().request())
                // The baseline plan owner still reads its complete live/previous linkage from the hot snapshot.
                || job.request().request().capability().equals(PlanningGoals.ID))return null;
        if(job.attempts().isEmpty()) {
            if(!job.children().isEmpty() || !job.dependencies().isEmpty())return Custody.CLOSED_COMPOSITION;
            return job.state()==State.SUCCEEDED?null:Custody.NO_EFFECT;
        }
        boolean delivered=false;
        for(Attempt attempt:job.attempts()) {
            if(attempt.terminal()==null)return null;
            if(attempt.effects()==0 && attempt.credited()==0 && attempt.receiptCount()==0)continue;
            if(attempt.terminal().status()!=Outcomes.ExecutionStatus.SUCCEEDED
                    || !LogAcquisition.is(attempt.bound().request().capability())
                    && !attempt.bound().request().capability().equals(CropDelivery.ID))return null;
            // Every tracked batch must have left actor/drop custody for the original destination.
            // This is historical delivery provenance, never a claim about current stock or native saves.
            var batches=new HashMap<List<UUID>,long[]>();
            for(var receipt:attempt.receipts()) {
                long[] stages=batches.computeIfAbsent(List.of(receipt.runId(),receipt.batchId()),ignored->new long[3]);
                stages[receipt.stage().ordinal()]=Math.addExact(stages[receipt.stage().ordinal()],receipt.wheat());
            }
            for(var receipt:attempt.resourceReceipts()) {
                if(receipt.stage().ordinal()>2 || receipt.transformation()!=null || receipt.placement()!=null)return null;
                long[] stages=batches.computeIfAbsent(List.of(receipt.runId(),receipt.batchId()),ignored->new long[3]);
                stages[receipt.stage().ordinal()]=Math.addExact(stages[receipt.stage().ordinal()],receipt.quantity());
            }
            long deposited=0;
            for(long[] stages:batches.values()) {
                if(stages[0]!=stages[1] || stages[1]!=stages[2])return null;
                deposited=Math.addExact(deposited,stages[2]);
            }
            if(deposited==0 || deposited!=attempt.credited()
                    || attempt.executions().stream().noneMatch(e->ResourceDelivery.completed(attempt.bound(),e.runId(),
                            attempt.receipts(),attempt.resourceReceipts())))return null;
            delivered=true;
        }
        return delivered?Custody.BALANCED_DELIVERY:job.state()==State.SUCCEEDED?null:Custody.NO_EFFECT;
    }
    static byte[] encode(Snapshot component) {
        JobLifecycleStore.validateSnapshot(component,Settings.defaults());
        var custody=new ArrayList<Object>();var jobs=new ArrayList<Object>();var allocations=new ArrayList<Object>();
        for(Job job:component.jobs()) {
            Custody disposition=custody(job,component);
            if(disposition==null)throw new IllegalArgumentException("Unsettled or unsupported residual custody");
            custody.add(Map.of("job",job.id().toString(),"disposition",disposition.name()));
            jobs.add(JobCodec.jobValue(job));
        }
        component.allocations().stream().sorted(Comparator.comparing(a->a.id().toString()))
                .forEach(a->allocations.add(JobCodec.allocationValue(a)));
        return StrictJson.canonical(Map.of("schema",1L,"world",component.worldId().toString(),
                "jobs",jobs,"allocations",allocations,"custody",custody)).getBytes(StandardCharsets.UTF_8);
    }
    static UUID id(byte[] payload) { return UUID.nameUUIDFromBytes(payload); }
    static Snapshot decode(byte[] payload,UUID world)throws StrictJson.Invalid {
        Map<String,Object> envelope=StrictJson.object(new String(payload,StandardCharsets.UTF_8));
        if(!envelope.keySet().equals(Set.of("schema","world","jobs","allocations","custody"))
                || !Long.valueOf(1).equals(envelope.get("schema")) || !world.toString().equals(envelope.get("world"))
                || !(envelope.get("jobs") instanceof List<?> rawJobs) || rawJobs.isEmpty() || rawJobs.size()>64
                || !(envelope.get("allocations") instanceof List<?> rawAllocations) || rawAllocations.size()>128)
            throw new IllegalArgumentException("Closed Job archive schema/world/bounds");
        var jobs=new ArrayList<Job>();var allocations=new ArrayList<Allocation>();
        for(Object row:rawJobs)jobs.add(JobCodec.job(object(row)));
        for(Object row:rawAllocations)allocations.add(JobCodec.allocation(object(row)));
        Snapshot component=new Snapshot(world,0,jobs,allocations);
        if(!Arrays.equals(payload,encode(component)))throw new IllegalArgumentException("Noncanonical Job history or custody disposition");
        return component;
    }
    private static Map<String,Object> object(Object value)throws StrictJson.Invalid {
        if(!(value instanceof Map<?,?> row) || row.keySet().stream().anyMatch(key->!(key instanceof String)))
            throw new StrictJson.Invalid("$","OBJECT_REQUIRED");
        @SuppressWarnings("unchecked") var typed=(Map<String,Object>)row;
        return typed;
    }
}
