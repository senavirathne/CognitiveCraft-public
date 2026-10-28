package dev.aivillages.core.kernel.storage;

import dev.aivillages.core.kernel.storage.contract.StorageCodec;
import dev.aivillages.core.kernel.storage.contract.StorageWire;
import java.util.*;

/** Pure incarnation/topology values. Equality is continuity, never a permission or save proof. */
public final class StorageBindingRules {
    public static final String SINGLE="minecraft:chest_single/v1", DOUBLE="minecraft:chest_double/v1", BARREL="minecraft:barrel/v1";
    public record Member(int x,int y,int z,UUID incarnation,long markerGeneration) {
        public Member {
            Objects.requireNonNull(incarnation);
            if (Math.abs((long)x)>29999984 || Math.abs((long)z)>29999984 || y < -2048 || y>2047
                    || markerGeneration<1 || markerGeneration>StorageRegistryLimits.MAX_INTEGER) throw new IllegalArgumentException("member bounds");
        }
        public Map<String,Object> body() { return Map.of("x",(long)x,"y",(long)y,"z",(long)z,"incarnation_id",incarnation.toString(),"marker_generation",markerGeneration); }
    }
    public record Topology(UUID world,String dimension,String adapter,String facing,List<Member> members) {
        public Topology {
            Objects.requireNonNull(world); Objects.requireNonNull(dimension); Objects.requireNonNull(adapter); Objects.requireNonNull(facing);
            var ordered=new ArrayList<>(members); ordered.sort(Comparator.comparingInt(Member::x).thenComparingInt(Member::y).thenComparingInt(Member::z).thenComparing(m -> m.incarnation().toString()));
            members=List.copyOf(ordered);
            if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || dimension.length()>96
                    || !Set.of(SINGLE,DOUBLE,BARREL).contains(adapter) || members.size()!=(adapter.equals(DOUBLE)?2:1)
                    || !(adapter.equals(BARREL)?Set.of("NORTH","SOUTH","EAST","WEST","UP","DOWN"):Set.of("NORTH","SOUTH","EAST","WEST")).contains(facing))
                throw new IllegalArgumentException("unsupported topology");
            if (members.size()==2) {
                var a=members.get(0);var b=members.get(1);
                if (a.incarnation().equals(b.incarnation()) || a.y()!=b.y() || Math.abs((long)a.x()-b.x())+Math.abs((long)a.z()-b.z())!=1
                        || (Set.of("NORTH","SOUTH").contains(facing)?a.z()!=b.z():a.x()!=b.x())) throw new IllegalArgumentException("double chest membership");
            }
        }
        public Map<String,Object> body() { return Map.of("world_id",world.toString(),"dimension",dimension,"adapter_id",adapter,"facing",facing,"members",members.stream().map(Member::body).toList()); }
        public String digest() { return StorageMetadataCodec.digest(StorageMetadataCodec.canonical(body())); }
    }
    public record Binding(UUID storage,long generation,UUID epoch,long tick,String status,Topology topology) {
        public Binding {
            Objects.requireNonNull(storage);Objects.requireNonNull(epoch);Objects.requireNonNull(topology);
            if (generation<1 || generation>StorageRegistryLimits.MAX_INTEGER || tick<0 || tick>StorageRegistryLimits.MAX_INTEGER
                    || !Set.of("UNKNOWN","VERIFIED_LIVE","INVALIDATED").contains(status)) throw new IllegalArgumentException("binding bounds");
        }
        public StorageWire.Message wire() {
            var body=new TreeMap<String,Object>(topology.body());body.put("storage_id",storage.toString());body.put("binding_generation",generation);
            body.put("lifecycle_epoch",epoch.toString());body.put("observed_tick",tick);body.put("status",status);body.put("topology_sha256",topology.digest());
            return StorageCodec.decode(StorageMetadataCodec.canonical(Map.of("schema",StorageWire.VERSION,"kind","PhysicalStorageBinding","body",body)));
        }
        @SuppressWarnings("unchecked") public static Binding from(StorageWire.Message message) {
            if (message.kind()!=StorageWire.Kind.PhysicalStorageBinding) throw new IllegalArgumentException("physical binding required");
            var b=message.body();var members=new ArrayList<Member>();
            for (Object value:(List<?>)b.get("members")) { var m=(Map<String,Object>)value;members.add(new Member(Math.toIntExact((Long)m.get("x")),Math.toIntExact((Long)m.get("y")),Math.toIntExact((Long)m.get("z")),UUID.fromString((String)m.get("incarnation_id")),(Long)m.get("marker_generation"))); }
            var t=new Topology(UUID.fromString((String)b.get("world_id")),(String)b.get("dimension"),(String)b.get("adapter_id"),(String)b.get("facing"),members);
            if (!t.digest().equals(b.get("topology_sha256"))) throw new IllegalArgumentException("topology digest");
            return new Binding(UUID.fromString((String)b.get("storage_id")),(Long)b.get("binding_generation"),UUID.fromString((String)b.get("lifecycle_epoch")),(Long)b.get("observed_tick"),(String)b.get("status"),t);
        }
    }
    /** Fresh observation must be obtained from the native adapter; wire input itself cannot establish it. */
    public static boolean sameIdentity(Binding a,Binding b) { return a.storage().equals(b.storage()) && a.generation()==b.generation() && a.topology().equals(b.topology()); }
    public static boolean currentEndpoint(Binding endpoint,Binding fresh) { return endpoint.status().equals("VERIFIED_LIVE") && fresh.status().equals("VERIFIED_LIVE") && endpoint.epoch().equals(fresh.epoch()) && sameIdentity(endpoint,fresh); }
    private StorageBindingRules() { }
}
