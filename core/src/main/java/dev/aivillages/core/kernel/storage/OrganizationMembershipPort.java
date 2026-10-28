package dev.aivillages.core.kernel.storage;

import java.util.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.Permission;

/** Read-only, bounded, IO-free projection of current trusted organization/member policy. J01 supplies it. */
@FunctionalInterface
public interface OrganizationMembershipPort {
    View observe(StorageAccessPolicy.Scope scope,Set<UUID> organizations);
    enum State { MEMBER, NON_MEMBER, UNKNOWN }
    record Membership(State state,Set<Permission> permitted,Set<Permission> delegable,boolean administrator) {
        public Membership {
            Objects.requireNonNull(state);permitted=Set.copyOf(permitted);delegable=Set.copyOf(delegable);
            if (!permitted.containsAll(delegable) || state!=State.MEMBER && (administrator || !delegable.isEmpty())
                    || state==State.UNKNOWN && !permitted.isEmpty()) throw new IllegalArgumentException("membership policy");
        }
        public static Membership unknown() { return new Membership(State.UNKNOWN,Set.of(),Set.of(),false); }
    }
    record View(StorageAccessPolicy.Scope scope,long revision,Map<UUID,Membership> memberships) {
        public View {
            Objects.requireNonNull(scope);memberships=Map.copyOf(memberships);
            if(revision<0 || revision>StorageRegistryLimits.MAX_INTEGER || memberships.size()>StorageRegistryLimits.MAX_GRANTS+1)throw new IllegalArgumentException("membership projection bounds");
        }
        public Membership membership(UUID organization) { return memberships.getOrDefault(organization,Membership.unknown()); }
    }
}
