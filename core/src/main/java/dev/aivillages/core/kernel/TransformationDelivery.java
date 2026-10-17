package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceDelivery.*;

/** Validates complete new-production lineage; old stock and withdrawal alone earn no output credit. */
public final class TransformationDelivery {
    private TransformationDelivery() { }
    public static boolean matches(ValidatedRequest bound,UUID run,Receipt r) {
        if(!RecipeProduction.is(bound.request().capability()) || r.transformation()==null)return false;
        var d=RecipeProduction.of(bound.request().capability());var a=bound.request().arguments();
        if(d.axe() && (!(a.get("table") instanceof FacilityValue table) || !table.value().equals(r.transformation().facility())))return false;
        return r.origin().equals(bound.context()) && r.runId().equals(run)
                && a.get("actor") instanceof ActorValue actor && actor.value().equals(r.actor())
                && a.get("source") instanceof ContainerValue source && ResourceLeases.point(source.value()).equals(r.source())
                && a.get("destination") instanceof ContainerValue destination && destination.value().equals(r.destination())
                && r.dropIds().isEmpty() && r.sourceBlock().inside(r.source())
                && r.transformation().recipe().key().equals(RecipeProduction.of(bound.request().capability()).recipe())
                && r.item().equals(r.stage()==Stage.WITHDRAW ? r.item().equals(ResourceDelivery.STICK) && d.axe() ? ResourceDelivery.STICK : d.input() : d.output());
    }
    public static long quantity(ValidatedRequest bound,UUID run,List<Receipt> receipts) {
        if(!RecipeProduction.is(bound.request().capability()) || receipts.size()>MAX_RECEIPTS)
            throw new IllegalArgumentException("Transformation receipt bounds");
        if(RecipeProduction.of(bound.request().capability()).axe())return axeQuantity(bound,run,receipts);
        int inputs=RecipeProduction.of(bound.request().capability()).inputPerBatch();
        int output=RecipeProduction.of(bound.request().capability()).outputPerBatch();
        Map<UUID,Receipt> seen=new HashMap<>(); Set<UUID> operations=new HashSet<>();
        Map<UUID,List<Receipt>> batches=new LinkedHashMap<>();
        for(var r:receipts) {
            if(!matches(bound,run,r))throw new IllegalArgumentException("Transformation binding");
            var old=seen.putIfAbsent(r.receiptId(),r);
            if(old!=null) {if(!old.equals(r))throw new IllegalArgumentException("Changed transformation receipt");continue;}
            if(!operations.add(r.operationId()))throw new IllegalArgumentException("Repeated physical operation");
            batches.computeIfAbsent(r.batchId(),ignored->new ArrayList<>()).add(r);
        }
        if(batches.size()>RecipeProduction.of(bound.request().capability()).maximumBatches())throw new IllegalArgumentException("Craft batch limit");
        long total=0;Receipt previous=null;int batchIndex=0;
        for(var rows:batches.values()) {
            if(previous!=null && (previous.quantity()!=output || rows.getFirst().stage()!=Stage.WITHDRAW
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
                        if(withdraw==null || craft!=null || deposit!=null || r.quantity()!=output
                                || t.consumedInputs()!=inputs || t.producedOutputs()!=output || t.retainedOutputs()!=output
                                || !same(withdraw,r) || !withdraw.transformation().custodyAfter().equals(t.custodyBefore()))
                            throw new IllegalArgumentException("Missing consumed-input lineage");
                        craft=r;
                    }
                    case DEPOSIT -> {
                        if(craft==null || deposit!=null || r.quantity()>output || t.consumedInputs()!=0
                                || t.producedOutputs()!=0 || t.retainedOutputs()!=output-r.quantity()
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
    private static long axeQuantity(ValidatedRequest bound,UUID run,List<Receipt> receipts) {
        Map<UUID,Receipt> ids=new HashMap<>();Set<UUID> operations=new HashSet<>();
        Map<UUID,List<Receipt>> batches=new LinkedHashMap<>();
        for(var r:receipts) {
            if(!matches(bound,run,r))throw new IllegalArgumentException("Axe receipt binding");
            var old=ids.putIfAbsent(r.receiptId(),r);
            if(old!=null) {if(!old.equals(r))throw new IllegalArgumentException("Changed receipt");continue;}
            if(!operations.add(r.operationId()))throw new IllegalArgumentException("Duplicate axe effect");
            batches.computeIfAbsent(r.batchId(),key->new java.util.ArrayList<>()).add(r);
        }
        if(batches.size()>WoodenAxeProduction.MAX_BATCHES)throw new IllegalArgumentException("Axe batch cap");
        Receipt previous=null;long total=0;int index=0;
        for(var rows:batches.values()) {
            Receipt planks=null,sticks=null,craft=null,deposit=null;
            for(var r:rows) {
                var t=r.transformation();
                if(r.stage()==Stage.WITHDRAW) {
                    if(craft!=null || deposit!=null || t.consumedInputs()!=0 || t.producedOutputs()!=0 || t.retainedOutputs()!=0)
                        throw new IllegalArgumentException("Invalid axe withdrawal");
                    if(r.item().equals(ResourceDelivery.OAK_PLANKS) && planks==null && sticks==null && r.quantity()==3)planks=r;
                    else if(r.item().equals(ResourceDelivery.STICK) && sticks==null && planks!=null && r.quantity()==2
                            && same(planks,r) && planks.transformation().custodyBefore().equals(t.custodyBefore())
                            && planks.transformation().custodyAfter().equals(t.custodyAfter()))sticks=r;
                    else throw new IllegalArgumentException("Missing paired atomic inputs");
                } else if(r.stage()==Stage.CRAFT) {
                    if(planks==null || sticks==null || craft!=null || deposit!=null || r.quantity()!=1
                            || t.consumedInputs()!=5 || t.producedOutputs()!=1 || t.retainedOutputs()!=1
                            || !same(sticks,r) || !sticks.transformation().custodyAfter().equals(t.custodyBefore()))
                        throw new IllegalArgumentException("Axe input consumption lineage");
                    craft=r;
                } else if(r.stage()==Stage.DEPOSIT) {
                    if(craft==null || deposit!=null || r.quantity()!=1 || t.consumedInputs()!=0 || t.producedOutputs()!=0
                            || t.retainedOutputs()!=0 || !same(craft,r) || !craft.transformation().custodyAfter().equals(t.custodyBefore()))
                        throw new IllegalArgumentException("Axe output custody lineage");
                    deposit=r;total=Math.addExact(total,1);
                } else throw new IllegalArgumentException("Not axe production");
            }
            if(planks==null || sticks==null || previous!=null && (!same(previous,planks)
                    || !previous.transformation().custodyAfter().equals(planks.transformation().custodyBefore())))
                throw new IllegalArgumentException("Axe invocation/custody changed");
            if(deposit==null && ++index<batches.size())throw new IllegalArgumentException("Unfinished axe batch");
            if(deposit!=null)index++;
            previous=deposit;
        }
        if(total>((IntValue)bound.request().arguments().get("amount")).value())throw new IllegalArgumentException("Axe over-credit");
        return total;
    }
    private static boolean same(Receipt a,Receipt b) {
        var x=a.transformation();var y=b.transformation();
        return Objects.equals(x.facility(),y.facility()) && x.facilityIdentity().equals(y.facilityIdentity()) && x.recipe().equals(y.recipe()) && x.sourceIdentity().equals(y.sourceIdentity())
                && x.destinationIdentity().equals(y.destinationIdentity());
    }
}
