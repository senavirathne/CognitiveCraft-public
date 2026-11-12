package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;

/** Explicit metadata-only quota load. Never an active claim, receipt, or native delivery. */
public final class RuntimeARetiredLeaseFixture {
    private RuntimeARetiredLeaseFixture() { }
    public static Lease expired(UUID world,int number) {
        String name="fixture:a03-quota:"+number;
        var context=new TrustedContext(new PrincipalRef(id("principal")),new ScopeRef(world,id("domain")));
        var resource=Resource.space(world,new Cuboid("minecraft:overworld",0,0,0,0,0,0));
        return new Lease(id(name),id(name+":group"),id("epoch"),1,new Owner(id(name+":job"),1),context,
                resource,1,1,"SYNTHETIC_METADATA_ONLY",0,0,1,0,State.EXPIRED,
                Outcomes.Reason.TARGET_UNAVAILABLE,Map.of());
    }
    public static byte[] encode(Lease lease) {return ResourceLeaseCodec.encode(lease).getBytes(StandardCharsets.UTF_8);}
    private static UUID id(String name) {return UUID.nameUUIDFromBytes(("fixture:a03-quota:"+name).getBytes(StandardCharsets.UTF_8));}
}
