package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.ResourceDelivery;
import java.util.*;

/** Exact original gateway data retained before a later effect; no saved-world or execution authority. */
public record ResourceReceiptPrefix(List<ResourceDelivery.Receipt> receipts) {
    public ResourceReceiptPrefix {
        receipts=List.copyOf(receipts);
        if(receipts.isEmpty() || receipts.size()>64)throw new IllegalArgumentException("Resource receipt prefix quota");
        var first=receipts.getFirst();
        var ids=new HashSet<UUID>();var operations=new HashSet<UUID>();var drops=new HashMap<UUID,UUID>();
        var batches=new HashMap<UUID,ResourceDelivery.Receipt>();var quantities=new HashMap<UUID,long[]>();
        for(var receipt:receipts) {
            ResourceReceiptPrefixCodec.encode(receipt);
            if(!receipt.origin().equals(first.origin()) || !receipt.runId().equals(first.runId())
                    || !receipt.actor().equals(first.actor()) || !receipt.source().equals(first.source())
                    || !receipt.destination().equals(first.destination()) || !receipt.item().equals(first.item())
                    || !ids.add(receipt.receiptId()) || !operations.add(receipt.operationId()))
                throw new IllegalArgumentException("Changed or repeated original resource prefix binding");
            var batch=batches.get(receipt.batchId());
            if(receipt.stage()==ResourceDelivery.Stage.HARVEST) {
                if(batch!=null)throw new IllegalArgumentException("Repeated resource harvest");
                batches.put(receipt.batchId(),receipt);quantities.put(receipt.batchId(),new long[3]);
                for(UUID drop:receipt.dropIds())if(drops.putIfAbsent(drop,receipt.batchId())!=null)
                    throw new IllegalArgumentException("Drop belongs to another original batch");
            }else if(batch==null || !batch.sourceBlock().equals(receipt.sourceBlock()) || !batch.dropIds().equals(receipt.dropIds()))
                throw new IllegalArgumentException("Missing or changed original drop lineage");
            var q=quantities.get(receipt.batchId());int stage=receipt.stage().ordinal();
            q[stage]=Math.addExact(q[stage],receipt.quantity());
            if(q[1]>q[0] || q[2]>q[1])throw new IllegalArgumentException("Unattributable original resource prefix");
        }
    }
    /** Binding validation only; counts here never supply native saved custody. */
    void matches(NativeDeposit.Intent intent) {
        var first=receipts.getFirst();var destination=first.destination();
        if(receipts.size()>63 || !first.origin().equals(intent.origin()) || !first.runId().equals(intent.run())
                || !first.actor().citizenId().equals(intent.conditions().citizen())
                || !first.actor().entityId().toString().equals(intent.actor().address())
                || !first.actor().dimension().equals(intent.actor().dimension())
                || !destination.dimension().equals(intent.chest().dimension())
                || !(destination.x()+","+destination.y()+","+destination.z()).equals(intent.chest().address())
                || !first.item().id().equals(intent.item())
                || intent.conditions().usage().getOrDefault(dev.aivillages.core.kernel.Budgets.Kind.COMMITTED_EFFECTS,0L)<receipts.size())
            throw new IllegalArgumentException("Original resource prefix does not match deposit intent");
    }
    void result(ResourceDelivery.Receipt receipt) {
        if(receipt==null)return;
        var complete=new ArrayList<>(receipts);complete.add(receipt);new ResourceReceiptPrefix(complete);
    }
}
