package dev.aivillages.core;
/** Called only on the logical server thread. */
public interface WorldPort {
    enum Outcome { WORKING, DONE, EXHAUSTED, BLOCKED }
    record Snapshot(boolean loaded, boolean alive, boolean unsafe, int wheat, int seeds, int bread) { }
    Snapshot snapshot();
    Outcome perform(Plan.Step step);
    void stop();
    int breadCrafted();
}
