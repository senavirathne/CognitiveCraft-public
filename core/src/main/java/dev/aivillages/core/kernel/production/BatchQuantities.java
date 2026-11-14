package dev.aivillages.core.kernel.production;

import java.util.Objects;

/** Pure quantity proposal. It grants no physical stock, allocation or delivery credit. */
public record BatchQuantities(Role role, long demand, long yield, long batches, long physicalOutput,
                              long requiredDelivery, long maximumAllocation, long surplus) {
    public enum Role { ROOT, MATERIAL_CHILD }

    public BatchQuantities {
        Objects.requireNonNull(role);
        if(demand<1 || yield<1)throw new IllegalArgumentException("Positive demand and yield required");
        long count=demand/yield+(demand%yield==0?0:1);
        long output=Math.multiplyExact(count,yield);
        if(batches!=count || physicalOutput!=output || maximumAllocation!=demand || surplus!=output-demand
                || requiredDelivery!=(role==Role.ROOT?demand:output))
            throw new IllegalArgumentException("Inconsistent batch quantity proposal");
    }

    public static BatchQuantities project(long demand,long yield,long maximumDemand,long maximumBatches,Role role) {
        if(demand<1 || yield<1 || maximumDemand<1 || maximumBatches<1 || demand>maximumDemand)
            throw new IllegalArgumentException("Unsupported demand/yield bound");
        long count=demand/yield+(demand%yield==0?0:1);
        if(count>maximumBatches)throw new IllegalArgumentException("Unsupported batch bound");
        long output=Math.multiplyExact(count,yield);
        return new BatchQuantities(role,demand,yield,count,output,role==Role.ROOT?demand:output,demand,output-demand);
    }
}
