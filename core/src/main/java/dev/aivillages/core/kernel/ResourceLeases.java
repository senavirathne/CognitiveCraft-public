package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Immutable lease schema 1; architecture extension 0.3. No claim grants authority. */
public final class ResourceLeases {
    private ResourceLeases() { }
    public static final int SCHEMA = 1;
    public static final String ARCHITECTURE_VERSION = "0.3";
    public enum Kind { STOCK, EQUIPMENT, FACILITY, SPACE }
    public enum State { ACTIVE, RELEASED, EXPIRED, REVOKED, INTERRUPTED }
    public enum Code { PENDING, GRANTED, VALID, RELEASED, CONFLICT, EXPIRED, STALE, REJECTED }
    public record Settings(int active, int retained, int perJob, int group, long duration,
                           long renewalInterval, int renewals, int reconcile, int cells,
                           int axis, long observationAge, long acknowledgementMillis) {
        public Settings {
            if (active < 1 || active > 64 || retained < active || retained > 128
                    || perJob < 1 || perJob > 8 || group < 1 || group > 4 || group > perJob
                    || duration < 1 || duration > 1200 || renewalInterval < 1
                    || renewalInterval > duration || renewals < 1 || renewals > 8
                    || reconcile < 1 || reconcile > 8 || cells < 1 || cells > 256
                    || axis < 1 || axis > 256 || observationAge < 0 || observationAge > 10
                    || acknowledgementMillis < 1 || acknowledgementMillis > 30000)
                throw new IllegalArgumentException("Finite lease settings");
        }
        public static Settings fixture() { return new Settings(64,128,8,4,100,10,8,8,256,16,0,30000); }
        public static Settings production() { return new Settings(64,128,8,4,1200,10,8,8,256,256,0,30000); }
    }
    public record Resource(UUID world, Kind kind, Cuboid area, String item, int slot) {
        public Resource {
            Objects.requireNonNull(world); Objects.requireNonNull(kind); Objects.requireNonNull(area);
            Objects.requireNonNull(item);
            boolean point = area.minX()==area.maxX() && area.minY()==area.maxY() && area.minZ()==area.maxZ();
            boolean valid = switch (kind) {
                case STOCK -> slot == -1 && (item.equals("minecraft:mature_wheat")
                        || point && item.equals("minecraft:wheat"));
                case EQUIPMENT -> point && slot >= 0 && slot < 128 && item.equals("minecraft:iron_hoe");
                case FACILITY -> point && slot == -1 && (item.equals("minecraft:storage")
                        || item.equals("minecraft:crafting_table"));
                case SPACE -> slot == -1 && item.equals("space");
            };
            if (!valid || cells(area) > 256) throw new IllegalArgumentException("Unsupported resource");
        }
        public static Resource stock(UUID world, ContainerRef c) {
            return new Resource(world,Kind.STOCK,point(c),"minecraft:wheat",-1);
        }
        public static Resource crops(UUID world, Cuboid a) {
            return new Resource(world,Kind.STOCK,a,"minecraft:mature_wheat",-1);
        }
        public static Resource facility(UUID world, ContainerRef c) {
            return new Resource(world,Kind.FACILITY,point(c),"minecraft:storage",-1);
        }
        public static Resource space(UUID world, Cuboid a) {
            return new Resource(world,Kind.SPACE,a,"space",-1);
        }
        public String dimension() { return area.dimension(); }
    }
    public static Cuboid point(ContainerRef c) {
        return new Cuboid(c.dimension(),c.x(),c.y(),c.z(),c.x(),c.y(),c.z());
    }
    public static Cuboid region(String dimension,int x1,int y1,int z1,int x2,int y2,int z2) {
        return new Cuboid(dimension,Math.min(x1,x2),Math.min(y1,y2),Math.min(z1,z2),
                Math.max(x1,x2),Math.max(y1,y2),Math.max(z1,z2));
    }
    public static long cells(Cuboid a) {
        return Math.multiplyExact(Math.multiplyExact((long)a.maxX()-a.minX()+1,
                (long)a.maxY()-a.minY()+1),(long)a.maxZ()-a.minZ()+1);
    }
    public static boolean overlaps(Cuboid a,Cuboid b) {
        return a.dimension().equals(b.dimension()) && a.minX()<=b.maxX() && b.minX()<=a.maxX()
                && a.minY()<=b.maxY() && b.minY()<=a.maxY() && a.minZ()<=b.maxZ() && b.minZ()<=a.maxZ();
    }
    public record Demand(Resource resource,long quantity) {
        public Demand {
            Objects.requireNonNull(resource);
            if (quantity < 1 || quantity > 4096 || resource.kind()!=Kind.STOCK && quantity!=1)
                throw new IllegalArgumentException("Resource quantity");
        }
    }
    public record Owner(UUID job,long generation) {
        public Owner { Objects.requireNonNull(job); if(generation<0)throw new IllegalArgumentException("Job generation"); }
    }
    public record OwnerFacts(TrustedContext origin,ActorRef worker,UUID run,boolean live,Reason reason) {
        public OwnerFacts { Objects.requireNonNull(origin); Objects.requireNonNull(worker); }
    }
    public record Observation(Resource resource,ObservationStatus status,long quantity,String identity,
                              long tick,int inspected,Reason reason) {
        public Observation {
            Objects.requireNonNull(resource); Objects.requireNonNull(status);
            if(quantity<0 || quantity>8192 || identity==null || identity.isBlank() || identity.length()>128
                    || tick<0 || inspected<0 || inspected>256)
                throw new IllegalArgumentException("Bounded resource observation");
        }
    }
    public record Ref(UUID id,UUID epoch,long generation) {
        public Ref { Objects.requireNonNull(id); Objects.requireNonNull(epoch);
            if(generation<1)throw new IllegalArgumentException("Lease generation"); }
    }
    public record Lease(UUID id,UUID group,UUID epoch,long generation,Owner owner,TrustedContext origin,
                        Resource resource,long quantity,long remaining,String identity,long granted,
                        long renewed,long expires,int renewals,State state,Reason reason,Map<UUID,Long> consumed) {
        public Lease {
            Objects.requireNonNull(id); Objects.requireNonNull(group); Objects.requireNonNull(epoch);
            Objects.requireNonNull(owner); Objects.requireNonNull(origin); Objects.requireNonNull(resource);
            Objects.requireNonNull(state); consumed=Map.copyOf(consumed);
            if(generation<1 || quantity<1 || quantity>4096 || remaining<0 || remaining>quantity
                    || resource.kind()!=Kind.STOCK && quantity!=1 || identity==null || identity.isBlank()
                    || identity.length()>128 || granted<0 || renewed<granted || expires<=renewed
                    || renewals<0 || renewals>8 || consumed.size()>64 || !resource.world().equals(origin.scope().worldId())
                    || state==State.ACTIVE && reason!=null || state!=State.ACTIVE && reason==null)
                throw new IllegalArgumentException("Lease meaning/bounds");
            long spent=0;for(long value:consumed.values()) {
                if(value<1)throw new IllegalArgumentException("Consumption");spent=Math.addExact(spent,value);
            }
            if(spent!=quantity-remaining)throw new IllegalArgumentException("Claim conservation");
        }
        public Ref ref() { return new Ref(id,epoch,generation); }
    }
    public record Snapshot(UUID world,UUID epoch,long revision,long generation,List<Lease> leases) {
        public Snapshot {
            Objects.requireNonNull(world); Objects.requireNonNull(epoch); leases=List.copyOf(leases);
            if(revision<0 || generation<0 || leases.size()>128
                    || leases.stream().map(Lease::id).distinct().count()!=leases.size()
                    || leases.stream().anyMatch(l->!l.resource().world().equals(world) || l.generation()>generation))
                throw new IllegalArgumentException("Lease snapshot");
            for(Lease l:leases) if(l.state()==State.ACTIVE && !l.epoch().equals(epoch))
                throw new IllegalArgumentException("Active epoch");
        }
        public static Snapshot empty(UUID world) { return new Snapshot(world,UUID.randomUUID(),0,0,List.of()); }
    }
    public record Result(Code code,Reason reason,UUID group,List<Ref> leases,long available,int inspected) {
        public Result { Objects.requireNonNull(code); leases=List.copyOf(leases);
            if(leases.size()>4 || available<0 || inspected<0 || inspected>1024)
                throw new IllegalArgumentException("Lease result"); }
        public boolean usable() { return code==Code.GRANTED || code==Code.VALID; }
    }
    public record Roots(Set<UUID> jobs,boolean complete) {
        public Roots { jobs=Set.copyOf(jobs); if(jobs.size()>128)throw new IllegalArgumentException("Root bound"); }
    }
}
