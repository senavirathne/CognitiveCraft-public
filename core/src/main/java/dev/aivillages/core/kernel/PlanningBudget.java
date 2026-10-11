package dev.aivillages.core.kernel;

/** IMP-019 contract 1. Policy ceilings, never model-supplied permission or a renewable allowance. */
public record PlanningBudget(int nodes, int depth, int edges, int createdWork,
                             int contextBytes, int outputBytes, int work, long observationAge) {
    public static final int SCHEMA = 1;
    public PlanningBudget {
        if (nodes < 1 || nodes > 8 || depth < 1 || depth > 3 || edges < 1 || edges > 32
                || createdWork < 1 || createdWork > 8 || contextBytes < 256 || contextBytes > 8192
                || outputBytes < 128 || outputBytes > 8192 || work < 1 || work > 1024
                || observationAge < 0 || observationAge > 40)
            throw new IllegalArgumentException("Finite planning budget");
    }
    public static PlanningBudget defaults() { return new PlanningBudget(8, 3, 16, 8, 8192, 8192, 512, 10); }
}
