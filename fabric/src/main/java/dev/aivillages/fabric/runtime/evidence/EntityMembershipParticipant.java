package dev.aivillages.fabric.runtime.evidence;
import java.util.UUID;

/** Live complete movement tracking must start at actual H birth; no serialized trail authority. */
public interface EntityMembershipParticipant {
    EntityMembershipTrail cognitivecraft$beginMembership(UUID incarnation);
    EntityMembershipTrail cognitivecraft$membership();
}
