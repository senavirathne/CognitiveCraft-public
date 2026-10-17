package dev.aivillages.fabric;

/** Transient native travel debt; sampling never moves an actor or replenishes a budget. */
final class MovementTravel {
    static final int MAX_PENDING = 129;
    private long tick;
    private int x, y, z, pending;
    private boolean invalid;
    MovementTravel(long tick, int x, int y, int z) {
        this.tick=tick;this.x=x;this.y=y;this.z=z;
    }
    void observe(long now, int nextX, int nextY, int nextZ) {
        long distance=Math.max(Math.abs((long)nextX-x),
                Math.max(Math.abs((long)nextY-y),Math.abs((long)nextZ-z)));
        if(now<tick || distance>1 || distance>MAX_PENDING-pending)invalid=true;
        else pending+=(int)distance;
        tick=now;x=nextX;y=nextY;z=nextZ;
    }
    long charge() {
        if(invalid)return 2; // Preserve the gateway's existing invalid-travel guard.
        if(pending==0)return 0;
        pending--;return 1;
    }
    boolean drained() {return !invalid && pending==0;}
}
