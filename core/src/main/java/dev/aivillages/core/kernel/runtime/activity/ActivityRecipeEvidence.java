package dev.aivillages.core.kernel.runtime.activity;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.RecipeChoice;
import dev.aivillages.core.kernel.production.RecipeChoicePlan;
import dev.aivillages.core.kernel.production.RecipeSources;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.ResourceDelivery.*;

/** Checks acknowledged native phase evidence against an immutable exact choice and its original attempt. */
public final class ActivityRecipeEvidence {
    private static final List<Stage> PHASES=List.of(Stage.WITHDRAW,Stage.CRAFT,Stage.DEPOSIT);
    private ActivityRecipeEvidence(){}

    /** Reconstructs the B invocation from the immutable A step. A calls native validation separately. */
    public static ValidatedRequest exactInvocation(Job root,ValidatedRequest invocation,ActivityPayload.Choice encoded,
                                                   ActivityPayload.Quantities quantities) {
        RecipeChoice choice=encoded.recipe();
        var operation=invocation.request();
        if(!operation.capability().equals(new CapabilityId(choice.capability().name(),1))
                || choice.demand()!=quantities.demand() || choice.resultCount()!=quantities.yield()
                || choice.batches().size()!=quantities.batches() || quantities.allocation()!=quantities.demand()
                || choice.remainders().stream().mapToLong(RecipeChoice.ItemAmount::count).sum()!=quantities.remainders())
            throw new IllegalArgumentException("Exact choice and authoritative quantity disagree");
        var request=new ValidatedRequest(new CapabilityRequest(choice.capability(),operation.arguments()),
                invocation.context(),invocation.observation());
        RecipeChoicePlan.propose(root.id(),root.revision(),request,choice,sources(request,choice));
        return request;
    }

    public static RecipeSources sources(ValidatedRequest invocation,RecipeChoice choice) {
        var explicit=((ContainerValue)invocation.request().arguments().get("source")).value();
        var sources=new LinkedHashSet<ContainerRef>();
        sources.add(explicit);
        choice.batches().forEach(b->b.bindings().forEach(binding->sources.add(binding.source())));
        return new RecipeSources(List.copyOf(sources));
    }

    public static boolean permittedInvocation(Job root,CapabilityRequest invocation,long credit) {
        var original=root.request().request();
        if(!ResourceDelivery.isDelivery(invocation.capability())
                || !new ActorValue(root.responsible()).equals(invocation.arguments().get("actor")))return false;
        if(credit>0) {
            var expected=new HashMap<>(original.arguments());
            expected.put("amount",new IntValue(credit));
            return new CapabilityRequest(original.capability(),expected).equals(invocation);
        }
        if(!(original.arguments().get("source") instanceof ContainerValue staging)
                || original.capability().equals(invocation.capability()))return false;
        if(CraftingTablePlacement.is(invocation.capability()))
            return staging.equals(invocation.arguments().get("source"))
                    && Objects.equals(original.arguments().get("table"),invocation.arguments().get("target"));
        return staging.value().equals(ResourceDelivery.destinationAddress(invocation))
                && (LogAcquisition.is(invocation.capability()) ? root.activity().production()!=null
                    && root.activity().production().acquisition()!=null
                    && new AreaValue(root.activity().production().acquisition()).equals(invocation.arguments().get("source"))
                    : staging.equals(invocation.arguments().get("source")));
    }

    public static Receipt receipt(ValidatedRequest bound,UUID id,UUID run,UUID operation,UUID batch,
                                  Stage stage,long quantity,ChoiceWitness witness) {
        var source=((ContainerValue)bound.request().arguments().get("source")).value();
        return new Receipt(id,bound.context(),run,operation,batch,
                ((ActorValue)bound.request().arguments().get("actor")).value(),ResourceLeases.point(source),
                ResourceDelivery.destinationAddress(bound.request()),RecipeProduction.of(bound.request().capability()).output(),
                stage,quantity,new BlockPosition(source.dimension(),source.x(),source.y(),source.z()),List.of(),null,null,witness);
    }

    public static boolean boundReceipt(ValidatedRequest bound,UUID run,Receipt receipt) {
        return receipt.choice()!=null && RecipeProduction.is(bound.request().capability()) && run.equals(receipt.runId())
                && receipt.equals(receipt(bound,receipt.receiptId(),run,receipt.operationId(),receipt.batchId(),
                        receipt.stage(),receipt.quantity(),receipt.choice()));
    }

    public static boolean matches(Job job,Attempt attempt,ActivityPayload.Step step,Receipt receipt) {
        var witness=receipt.choice();
        return witness!=null && step.exactChoice() && witness.root().equals(job.id()) && witness.step().equals(step.id())
                && witness.attempt().equals(attempt.id()) && witness.generation()==attempt.generation()
                && witness.choiceDigest().equals(step.choice().sha256())
                && attempt.id().equals(receipt.runId()) && attempt.executions().size()==1
                && attempt.executions().getFirst().runId().equals(receipt.runId())
                && boundReceipt(attempt.bound(),attempt.id(),receipt);
    }

    public static long quantity(Job job,Attempt attempt,ActivityPayload.Step step,List<Receipt> receipts) {
        if(receipts.isEmpty())return 0;
        RecipeChoice choice=step.choice().recipe();
        if(receipts.size()>choice.batches().size()*PHASES.size())throw new IllegalArgumentException("Exact-choice receipt bound");
        var operations=new HashSet<UUID>();
        var tokens=new HashSet<UUID>();
        var batchIds=new HashSet<UUID>();
        Receipt previous=null;
        long delivered=0;
        for(int index=0;index<receipts.size();index++) {
            Receipt receipt=receipts.get(index);
            int batch=index/PHASES.size();
            Stage phase=PHASES.get(index%PHASES.size());
            if(!matches(job,attempt,step,receipt) || receipt.choice().batch()!=batch || receipt.stage()!=phase
                    || !operations.add(receipt.operationId()) || !tokens.add(receipt.choice().journalToken())
                    || (phase==Stage.WITHDRAW ? !batchIds.add(receipt.batchId()) :
                            !receipt.batchId().equals(previous.batchId()))
                    || previous!=null && !previous.choice().custodyAfter().equals(receipt.choice().custodyBefore()))
                throw new IllegalArgumentException("Exact-choice phase lineage");
            long physical=switch(phase) {
                case WITHDRAW -> choice.batch(batch).bindings().size();
                case CRAFT -> choice.resultCount();
                case DEPOSIT -> choice.deliveryForBatch(batch);
                default -> throw new IllegalStateException("Exact-choice phase");
            };
            if(receipt.quantity()!=physical)throw new IllegalArgumentException("Exact-choice physical quantity");
            if(phase==Stage.DEPOSIT)delivered=Math.addExact(delivered,physical);
            previous=receipt;
        }
        return delivered;
    }

    public static boolean completed(Job job,Attempt a,ActivityPayload.Step step,List<CropDelivery.CropReceipt> crops,
                                    List<Receipt> resources) {
        if(!step.exactChoice())return a.executions().stream().anyMatch(r->ResourceDelivery.completed(a.bound(),r.runId(),crops,resources));
        return crops.isEmpty() && quantity(job,a,step,resources)==step.quantity();
    }
}
