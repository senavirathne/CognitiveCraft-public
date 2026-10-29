package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.SurvivalGateway.Cell;
import dev.aivillages.core.kernel.runtime.evidence.HarvestProtocol.Request;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Lets one exact harvest recheck destination permissions through its own exclusion during a read callback. */
public final class HarvestPreflightScope {
    private final Thread thread=Thread.currentThread();private Request checking;
    public boolean test(Request request,BooleanSupplier current) {
        check();Objects.requireNonNull(request);Objects.requireNonNull(current);
        if(checking!=null)throw new IllegalStateException("Reentrant native harvest preflight");
        checking=request;try{return current.getAsBoolean();}finally{checking=null;}
    }
    public boolean destination(Request ticket,ValidatedRequest incoming,Effect effect,Cell target) {
        check();if(checking!=ticket || !ticket.bound().equals(incoming) || effect!=Effect.TRANSFER)return false;
        var destination=ticket.destination();return target.equals(new Cell(destination.dimension(),destination.x(),destination.y(),destination.z()));
    }
    private void check(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Harvest preflight game-thread owner");}
}
