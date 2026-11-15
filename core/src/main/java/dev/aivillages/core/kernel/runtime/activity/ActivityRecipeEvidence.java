package dev.aivillages.core.kernel.runtime.activity;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.RecipeChoice;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.ResourceDelivery.*;

/** Checks acknowledged native phase evidence against an immutable exact choice and its original attempt. */
public final class ActivityRecipeEvidence {
    private static final List<Stage> PHASES=List.of(Stage.WITHDRAW,Stage.CRAFT,Stage.DEPOSIT);
    private ActivityRecipeEvidence(){}

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
