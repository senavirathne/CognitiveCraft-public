package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceDelivery.*;

/** Validates complete new-production lineage; old stock and withdrawal alone earn no output credit. */
public final class TransformationDelivery {
    private TransformationDelivery() { }
    public static boolean matches(ValidatedRequest bound,UUID run,Receipt r) {
        if(!RecipeProduction.is(bound.request().capability()) || r.transformation()==null)return false;
        var a=bound.request().arguments();
        return r.origin().equals(bound.context()) && r.runId().equals(run)
                && a.get("actor") instanceof ActorValue actor && actor.value().equals(r.actor())
                && a.get("source") instanceof ContainerValue source && ResourceLeases.point(source.value()).equals(r.source())
                && a.get("destination") instanceof ContainerValue destination && destination.value().equals(r.destination())
                && r.dropIds().isEmpty() && r.sourceBlock().inside(r.source())
                && r.transformation().recipe().key().equals(RecipeProduction.of(bound.request().capability()).recipe())
                && r.item().equals(r.stage()==Stage.WITHDRAW ? RecipeProduction.of(bound.request().capability()).input()
                        : RecipeProduction.of(bound.request().capability()).output());
    }
    public static long quantity(ValidatedRequest bound,UUID run,List<Receipt> receipts) {
        if(!RecipeProduction.is(bound.request().capability()) || receipts.size()>MAX_RECEIPTS)
            throw new IllegalArgumentException("Transformation receipt bounds");
        int inputs=RecipeProduction.of(bound.request().capability()).inputPerBatch();
        Map<UUID,Receipt> seen=new HashMap<>(); Set<UUID> operations=new HashSet<>();
        Map<UUID,List<Receipt>> batches=new LinkedHashMap<>();
        for(var r:receipts) {
            if(!matches(bound,run,r))throw new IllegalArgumentException("Transformation binding");
            var old=seen.putIfAbsent(r.receiptId(),r);
            if(old!=null) {if(!old.equals(r))throw new IllegalArgumentException("Changed transformation receipt");continue;}
            if(!operations.add(r.operationId()))throw new IllegalArgumentException("Repeated physical operation");
            batches.computeIfAbsent(r.batchId(),ignored->new ArrayList<>()).add(r);
        }
        if(batches.size()>OakPlankProduction.MAX_BATCHES)throw new IllegalArgumentException("Craft batch limit");
        long total=0;Receipt previous=null;int batchIndex=0;
        for(var rows:batches.values()) {
            if(previous!=null && (previous.quantity()!=4 || rows.getFirst().stage()!=Stage.WITHDRAW
                    || !same(previous,rows.getFirst())
                    || !previous.transformation().custodyAfter().equals(rows.getFirst().transformation().custodyBefore())))
                throw new IllegalArgumentException("Changed invocation or interrupted custody between batches");
            Receipt withdraw=null,craft=null,deposit=null;
            for(var r:rows) {
                var t=r.transformation();
                switch(r.stage()) {
                    case WITHDRAW -> {
                        if(withdraw!=null || craft!=null || deposit!=null || r.quantity()!=inputs
                                || t.consumedInputs()!=0 || t.producedOutputs()!=0 || t.retainedOutputs()!=0)
                            throw new IllegalArgumentException("Withdrawal is not production");
                        withdraw=r;
                    }
                    case CRAFT -> {
                        if(withdraw==null || craft!=null || deposit!=null || r.quantity()!=4
                                || t.consumedInputs()!=inputs || t.producedOutputs()!=4 || t.retainedOutputs()!=4
                                || !same(withdraw,r) || !withdraw.transformation().custodyAfter().equals(t.custodyBefore()))
                            throw new IllegalArgumentException("Missing consumed-input lineage");
                        craft=r;
                    }
                    case DEPOSIT -> {
                        if(craft==null || deposit!=null || r.quantity()>4 || t.consumedInputs()!=0
                                || t.producedOutputs()!=0 || t.retainedOutputs()!=4-r.quantity()
                                || !same(craft,r) || !craft.transformation().custodyAfter().equals(t.custodyBefore()))
                            throw new IllegalArgumentException("Missing new-output custody");
                        deposit=r;total=Math.addExact(total,r.quantity());
                    }
                    default -> throw new IllegalArgumentException("Harvest is not crafting");
                }
            }
            if(deposit==null && ++batchIndex<batches.size())
                throw new IllegalArgumentException("Incomplete batch followed by new production");
            if(deposit!=null)batchIndex++;
            previous=deposit;
        }
        if(total>((IntValue)bound.request().arguments().get("amount")).value())
            throw new IllegalArgumentException("Output over-credit");
        return total;
    }
    private static boolean same(Receipt a,Receipt b) {
        var x=a.transformation();var y=b.transformation();
        return x.recipe().equals(y.recipe()) && x.sourceIdentity().equals(y.sourceIdentity())
                && x.destinationIdentity().equals(y.destinationIdentity());
    }
}
