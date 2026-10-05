package dev.aivillages.core;
import java.util.List;

/** Bounded sequence, conditional steps and bounded repetitions: no arbitrary code or loops. */
public record Plan(String id, int version, String goal, int maxTicks, List<Step> steps) {
    public Plan { steps = List.copyOf(steps); }
    public enum Action { HARVEST_WHEAT, PLANT_WHEAT, COLLECT_WHEAT, CRAFT_BREAD, DELIVER_BREAD, GO_HOME, SPEAK, WAIT }
    public enum Condition { ALWAYS, HAS_WHEAT, HAS_SEEDS, HAS_BREAD }
    public record Step(Action action, int count, int timeoutTicks, Condition when, String text) { }
    public static Plan food() {
        return new Plan("maintain_wheat_supply", 1, "FOOD_SUPPLY", 6000, List.of(
            new Step(Action.HARVEST_WHEAT, 24, 2400, Condition.ALWAYS, ""),
            new Step(Action.COLLECT_WHEAT, 32, 600, Condition.ALWAYS, ""),
            new Step(Action.PLANT_WHEAT, 32, 1200, Condition.HAS_SEEDS, ""),
            new Step(Action.CRAFT_BREAD, 8, 600, Condition.HAS_WHEAT, ""),
            new Step(Action.DELIVER_BREAD, 8, 600, Condition.HAS_BREAD, "")
        ));
    }
}
