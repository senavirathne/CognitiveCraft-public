package dev.aivillages.core.kernel;

import java.util.Objects;
import java.util.UUID;
import static dev.aivillages.core.kernel.Contracts.*;

/** Composition of existing direct authority and current, durably reserved delegated authority. */
public final class MandateAuthority implements AuthorityPolicy {
    private final AuthorityPolicy direct;
    private final MandateRegistry mandates;
    public MandateAuthority(AuthorityPolicy direct, MandateRegistry mandates) {
        this.direct = Objects.requireNonNull(direct); this.mandates = Objects.requireNonNull(mandates);
    }
    @Override public boolean currentlyAllows(ActorRef actor, Effect effect, TrustedContext caller) {
        return direct.currentlyAllows(actor, effect, caller) || mandates.controls(actor, effect, caller);
    }
    @Override public boolean currentlyAllows(ValidatedRequest request, UUID run, Effect effect, EffectTarget target) {
        if (mandates.reservedRun(run)) return mandates.allows(request, run, effect, target);
        return direct.currentlyAllows(request, run, effect, target) || mandates.allows(request, run, effect, target);
    }
}
