package dev.aivillages.core;
/** Cooperative interpreter. At most one bounded primitive per tick; no blocking operations. */
public final class PlanRunner {
    public enum State { RUNNING, SUCCEEDED, FAILED, CANCELLED }
    private final Plan plan; private final WorldPort world; private final long started; private final int initialBread;
    private long stepStarted; private int cursor, completed; private State state = State.RUNNING; private String reason = "";
    public PlanRunner(Plan plan, WorldPort world, long tick) {
        this.plan = PlanCodec.validate(plan); this.world = world; started = stepStarted = tick; initialBread = world.breadCrafted();
    }
    public State tick(long tick) {
        if (state != State.RUNNING) return state;
        var s = world.snapshot();
        if (!s.alive()) return finish(State.FAILED, "villager_dead");
        if (tick - started >= plan.maxTicks()) return finish(State.FAILED, "plan_expired");
        if (!s.loaded()) return state;
        if (s.unsafe()) return finish(State.CANCELLED, "safety_interrupt");
        if (cursor >= plan.steps().size()) {
            boolean produced = world.breadCrafted() > initialBread;
            return finish(produced ? State.SUCCEEDED : State.FAILED, produced ? "bread_produced" : "no_food_produced");
        }
        var step = plan.steps().get(cursor);
        if (tick - stepStarted >= step.timeoutTicks()) return finish(State.FAILED, "step_timeout:" + step.action());
        boolean allowed = switch (step.when()) { case ALWAYS -> true; case HAS_WHEAT -> s.wheat() >= 3; case HAS_SEEDS -> s.seeds() > 0; case HAS_BREAD -> s.bread() > 0; };
        if (!allowed) { advance(tick); return state; }
        if (step.action() == Plan.Action.WAIT) {
            if (tick - stepStarted >= Math.min(20, step.timeoutTicks() - 1)) advance(tick);
            return state;
        }
        switch (world.perform(step)) {
            case WORKING -> { }
            case BLOCKED -> { return finish(State.FAILED, "blocked:" + step.action()); }
            case EXHAUSTED -> advance(tick);
            case DONE -> { if (++completed >= step.count()) advance(tick); }
        }
        return state;
    }
    private void advance(long tick) { world.stop(); cursor++; completed = 0; stepStarted = tick; }
    private State finish(State next, String message) { state = next; reason = message; world.stop(); return state; }
    public void cancel(String why) { if (state == State.RUNNING) finish(State.CANCELLED, why); }
    public Plan plan() { return plan; } public State state() { return state; } public String reason() { return reason; } public int cursor() { return cursor; }
}
