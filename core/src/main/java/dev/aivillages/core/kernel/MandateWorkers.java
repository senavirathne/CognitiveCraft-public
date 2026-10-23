package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Named consent expands recruitment only. Citizen identity, private names and dispatcher state remain original. */
public final class MandateWorkers implements WorkerDispatcher.Workers {
    private final CitizenRegistry citizens;
    private final MandateRegistry mandates;
    public MandateWorkers(CitizenRegistry citizens, MandateRegistry mandates) {
        this.citizens = Objects.requireNonNull(citizens); this.mandates = Objects.requireNonNull(mandates);
    }
    public boolean ready() { return citizens.ready(); }
    public long revision() { return citizens.snapshot().revision(); }
    public boolean controls(ActorRef actor, TrustedContext caller) {
        return citizens.controls(actor, caller) || mandates.controls(actor, Effect.OBSERVE, caller);
    }
    public CitizenRegistry.Availability availability(ActorRef actor, TrustedContext caller) {
        if (!controls(actor, caller)) throw new SecurityException("Worker unavailable");
        var row = citizens.find(actor.citizenId());
        if (row == null || !row.actor().equals(actor)) return CitizenRegistry.Availability.UNAVAILABLE;
        return citizens.query(actor.citizenId(), row.owner()).availability();
    }
    public CitizenRegistry.ControlPage page(TrustedContext caller, UUID after, int maximum) {
        if (maximum < 1 || maximum > 3 || caller == null || !caller.scope().worldId().equals(citizens.snapshot().worldId()))
            throw new IllegalArgumentException("Bounded worker page");
        var rows = new TreeMap<String, ActorRef>();
        for (var row : citizens.snapshot().citizens()) if (row.owner().equals(caller)) rows.put(row.actor().citizenId().toString(), row.actor());
        for (var actor : mandates.workers(caller)) rows.put(actor.citizenId().toString(), actor);
        List<ActorRef> ordered = List.copyOf(rows.values()); var result = new ArrayList<CitizenRegistry.Address>();
        int start = 0;
        if (after != null) while (start < ordered.size() && ordered.get(start).citizenId().toString().compareTo(after.toString()) <= 0) start++;
        for (int n = 0; n < Math.min(maximum, ordered.size()); n++) {
            ActorRef actor = ordered.get((start + n) % ordered.size());
            result.add(new CitizenRegistry.Address(actor, null, availability(actor, caller)));
        }
        return new CitizenRegistry.ControlPage(result, ordered.size(), revision());
    }
}
