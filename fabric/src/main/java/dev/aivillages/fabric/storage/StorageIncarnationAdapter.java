package dev.aivillages.fabric.storage;

import dev.aivillages.core.kernel.storage.StorageRegistry;
import dev.aivillages.core.kernel.storage.StorageBindingRules;
import static dev.aivillages.core.kernel.storage.StorageBindingRules.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.util.*;

/** Inert until explicitly installed by an owner. No tick task, chunk load, permission default or item effect. */
public final class StorageIncarnationAdapter implements AutoCloseable {
    public enum Action { PLAN, WRITE_MARKER, OBSERVE }
    @FunctionalInterface public interface Authority { boolean allows(Action action,UUID world,UUID storage,BlockPos position); }
    private static final Map<ServerLevel,StorageIncarnationAdapter> OWNERS=new WeakHashMap<>();
    private static final int MAX_MARKERS=8192;
    private final ServerLevel level;
    private final UUID world,epoch=UUID.randomUUID();
    private final Authority authority;
    private StorageRegistry.Snapshot metadata;
    private final Map<BlockPos,Set<UUID>> byPosition=new HashMap<>();
    private final Map<UUID,Set<UUID>> byMarker=new HashMap<>();
    private final Map<UUID,Set<BlockPos>> seen=new HashMap<>();
    private final Set<UUID> quarantined=new HashSet<>(),retired=new HashSet<>(),reloadCandidates=new HashSet<>();
    private final Map<UUID,UUID> liveEpochs=new HashMap<>();
    private final Map<UUID,Long> invalidated=new HashMap<>();
    private boolean overflow,closed;
    public StorageIncarnationAdapter(ServerLevel level,UUID world,Authority authority) {
        this.level=Objects.requireNonNull(level);this.world=Objects.requireNonNull(world);this.authority=Objects.requireNonNull(authority);
        thread();synchronized(OWNERS) { if (OWNERS.containsKey(level)) throw new IllegalStateException("one D owner per level");OWNERS.put(level,this); }
        metadata=StorageRegistry.Snapshot.empty(world);
    }
    private void thread() { if (!level.getServer().isSameThread() || closed) throw new IllegalStateException("live server thread/owner required"); }
    private void allowed(Action action,UUID storage,BlockPos pos) { thread();if (!authority.allows(action,world,storage,pos)) throw new SecurityException("explicit native authority denied"); }
    /** Install acknowledged immutable metadata on the server thread; bounded rebuild, never a per-tick scan. */
    public void updateMetadata(StorageRegistry.Snapshot snapshot) {
        thread();if (!snapshot.world().equals(world) || snapshot.revision()<metadata.revision()) throw new IllegalArgumentException("metadata world/revision");
        metadata=snapshot;byPosition.clear();byMarker.clear();liveEpochs.keySet().retainAll(snapshot.records().keySet());reloadCandidates.retainAll(snapshot.records().keySet());
        invalidated.keySet().retainAll(snapshot.records().keySet());
        for (var raw:snapshot.bindings().values()) {
            var b=Binding.from(raw);if (snapshot.records().get(b.storage()).state()==StorageRegistry.State.TOMBSTONED) continue;
            if (!b.topology().dimension().equals(dimension())) continue;
            for (var m:b.topology().members()) {
                byPosition.computeIfAbsent(pos(m),k -> new HashSet<>()).add(b.storage());
                var ids=byMarker.computeIfAbsent(m.incarnation(),k -> new HashSet<>());ids.add(b.storage());
                if (ids.size()>1) quarantine(m.incarnation());
            }
        }
    }
    public final class Plan {
        private final Binding intent;
        private final List<BlockEntity> entities;
        private final List<StorageMarkerAccess.Marker> markers;
        private boolean used;
        private final List<Long> lifecycles;
        private Plan(Binding intent,List<BlockEntity> entities,List<StorageMarkerAccess.Marker> markers) { this.intent=intent;this.entities=List.copyOf(entities);this.markers=List.copyOf(markers);this.lifecycles=entities.stream().map(e->((StorageMarkerAccess)e).cognitivecraft$lifecycle()).toList(); }
        public Binding intent() { return intent; }
    }
    /** No marker is written while observing or preparing a plan. UUIDs are generated only by this server. */
    public Plan plan(UUID storage,long generation,BlockPos pos) {
        allowed(Action.PLAN,storage,pos);var row=metadata.records().get(storage);
        if(row==null || row.state()==StorageRegistry.State.TOMBSTONED || generation!=(metadata.bindings().containsKey(storage)?row.generation()+1:row.generation()))throw new IllegalStateException("known metadata and next generation required");
        List<BlockEntity> entities=members(pos);var markers=new ArrayList<StorageMarkerAccess.Marker>();
        for (var be:entities) {
            allowed(Action.PLAN,storage,be.getBlockPos());var access=(StorageMarkerAccess)be;
            if (access.cognitivecraft$malformedMarker()) throw new IllegalStateException("malformed marker requires operator resolution");
            var marker=access.cognitivecraft$marker();if (marker==null) marker=new StorageMarkerAccess.Marker(UUID.randomUUID(),1);
            if (quarantined.contains(marker.incarnation()) || retired.contains(marker.incarnation()) || access.cognitivecraft$lifecycle()==Long.MAX_VALUE || overflow) throw new IllegalStateException("quarantined marker");markers.add(marker);
        }
        return new Plan(binding(storage,generation,"UNKNOWN",entities,markers),entities,markers);
    }
    public static final class WritePermit {
        private final BlockEntity entity;private boolean active=true;
        private WritePermit(BlockEntity entity) { this.entity=entity; }
        public void check(BlockEntity candidate) { if (!active || candidate!=entity || !(candidate.getLevel() instanceof ServerLevel l) || !l.getServer().isSameThread()) throw new SecurityException("invalid marker write permit"); }
    }
    /** Must follow durable UNKNOWN intent. Rechecks exact instances, topology and provided authority before writes. */
    public Binding write(Plan plan) {
        thread();if (plan.used || overflow) throw new IllegalStateException("used/overflow plan");
        var b=plan.intent;var raw=metadata.bindings().get(b.storage());var row=metadata.records().get(b.storage());
        if (raw==null || row==null || row.state()==StorageRegistry.State.ACTIVE || row.state()==StorageRegistry.State.TOMBSTONED
                || !Binding.from(raw).equals(b)) throw new IllegalStateException("durable exact intent required");
        var current=members(plan.entities.get(0).getBlockPos());
        if (!current.equals(plan.entities) || !binding(b.storage(),b.generation(),"UNKNOWN",current,plan.markers).topology().equals(b.topology())) throw new IllegalStateException("plan changed");
        for (int i=0;i<current.size();i++) {
            var be=current.get(i);allowed(Action.WRITE_MARKER,b.storage(),be.getBlockPos());var access=(StorageMarkerAccess)be;var old=access.cognitivecraft$marker();
            if (access.cognitivecraft$lifecycle()!=plan.lifecycles.get(i) || access.cognitivecraft$malformedMarker() || (old!=null && !old.equals(plan.markers.get(i))) || quarantined.contains(plan.markers.get(i).incarnation()) || retired.contains(plan.markers.get(i).incarnation())) throw new IllegalStateException("marker changed/quarantined");
        }
        plan.used=true;
        for (int i=0;i<current.size();i++) {
            var be=current.get(i);allowed(Action.WRITE_MARKER,b.storage(),be.getBlockPos());var permit=new WritePermit(be);
            try { ((StorageMarkerAccess)be).cognitivecraft$writeMarker(plan.markers.get(i),permit);be.setChanged(); } finally { permit.active=false; }
            observed(be);
        }
        invalidated.remove(b.storage());reloadCandidates.remove(b.storage());liveEpochs.put(b.storage(),b.epoch());
        return binding(b.storage(),b.generation(),"VERIFIED_LIVE",current,plan.markers);
    }
    /** Metadata plus fresh loaded native state only. Caller must separately obtain a current access decision. */
    public Optional<Binding> resolve(UUID storage) {
        thread();var row=metadata.records().get(storage);var raw=metadata.bindings().get(storage);
        if (overflow || row==null || raw==null || row.state()!=StorageRegistry.State.ACTIVE || (invalidated.getOrDefault(storage,0L)>=row.generation() && !reloadCandidates.contains(storage))) return Optional.empty();
        var expected=Binding.from(raw);if (!expected.status().equals("VERIFIED_LIVE") || !expected.topology().dimension().equals(dimension())) return Optional.empty();
        try {
            var entities=members(pos(expected.topology().members().get(0)));var markers=new ArrayList<StorageMarkerAccess.Marker>();
            for (var be:entities) {
                allowed(Action.OBSERVE,storage,be.getBlockPos());observed(be);var access=(StorageMarkerAccess)be;var marker=access.cognitivecraft$marker();
                if (access.cognitivecraft$malformedMarker() || marker==null || quarantined.contains(marker.incarnation()) || retired.contains(marker.incarnation())) return Optional.empty();markers.add(marker);
            }
            var live=binding(storage,row.generation(),"VERIFIED_LIVE",entities,markers);
            if(!StorageBindingRules.sameIdentity(expected,live))return Optional.empty();
            invalidated.remove(storage);reloadCandidates.remove(storage);return Optional.of(live);
        } catch (IllegalStateException | SecurityException | IllegalArgumentException refused) { return Optional.empty(); }
    }
    public boolean validates(Binding endpoint) { return resolve(endpoint.storage()).filter(live -> StorageBindingRules.currentEndpoint(endpoint,live)).isPresent(); }
    public boolean quarantined(UUID incarnation) { thread();return quarantined.contains(incarnation) || overflow; }
    private String dimension() { return level.dimension().identifier().toString(); }
    private static BlockPos pos(Member m) { return new BlockPos(m.x(),m.y(),m.z()); }
    private BlockEntity loaded(BlockPos pos) {
        if (!level.hasChunkAt(pos)) throw new IllegalStateException("not loaded");var be=level.getBlockEntity(pos);
        if (be==null || be.isRemoved() || !eligible(be)) throw new IllegalStateException("unsupported/missing native storage");return be;
    }
    private List<BlockEntity> members(BlockPos pos) {
        var be=loaded(pos);var state=be.getBlockState();var list=new ArrayList<BlockEntity>();list.add(be);
        if (state.is(Blocks.CHEST) && state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE) {
            var other=loaded(ChestBlock.getConnectedBlockPos(pos,state));var s=other.getBlockState();
            if (!s.is(Blocks.CHEST) || s.getValue(ChestBlock.FACING)!=state.getValue(ChestBlock.FACING)
                    || s.getValue(ChestBlock.TYPE)==ChestType.SINGLE || s.getValue(ChestBlock.TYPE)==state.getValue(ChestBlock.TYPE)
                    || !ChestBlock.getConnectedBlockPos(other.getBlockPos(),s).equals(pos)) throw new IllegalStateException("incomplete native double chest");list.add(other);
        }
        list.sort(Comparator.comparingInt((BlockEntity e)->e.getBlockPos().getX()).thenComparingInt(e->e.getBlockPos().getY()).thenComparingInt(e->e.getBlockPos().getZ()));return List.copyOf(list);
    }
    private Binding binding(UUID storage,long generation,String status,List<BlockEntity> entities,List<StorageMarkerAccess.Marker> markers) {
        var state=entities.get(0).getBlockState();String adapter=state.is(Blocks.BARREL)?BARREL:entities.size()==2?DOUBLE:SINGLE;
        String facing=(state.is(Blocks.BARREL)?state.getValue(BarrelBlock.FACING):state.getValue(ChestBlock.FACING)).name();var members=new ArrayList<Member>();
        for (int i=0;i<entities.size();i++) { var p=entities.get(i).getBlockPos();var m=markers.get(i);members.add(new Member(p.getX(),p.getY(),p.getZ(),m.incarnation(),m.generation())); }
        return new Binding(storage,generation,liveEpochs.computeIfAbsent(storage,k->epoch),Math.max(0,level.getGameTime()),status,new Topology(world,dimension(),adapter,facing,members));
    }
    public static boolean eligible(BlockEntity be) { return be!=null && (be.getClass()==ChestBlockEntity.class && be.getBlockState().is(Blocks.CHEST) || be.getClass()==BarrelBlockEntity.class && be.getBlockState().is(Blocks.BARREL)); }
    public static boolean topologyChanged(BlockState a,BlockState b) {
        if (a.getBlock()!=b.getBlock()) return true;
        if (a.is(Blocks.CHEST)) return a.getValue(ChestBlock.TYPE)!=b.getValue(ChestBlock.TYPE) || a.getValue(ChestBlock.FACING)!=b.getValue(ChestBlock.FACING);
        if (a.is(Blocks.BARREL)) return a.getValue(BarrelBlock.FACING)!=b.getValue(BarrelBlock.FACING);return false;
    }
    public static void changed(BlockEntity be) { if (be.getLevel() instanceof ServerLevel l) { StorageIncarnationAdapter owner;synchronized(OWNERS){owner=OWNERS.get(l);}if(owner!=null)owner.invalidate(be); } }
    public static void replaced(BlockEntity be) {
        if(be.getLevel() instanceof ServerLevel l) { StorageIncarnationAdapter owner;synchronized(OWNERS){owner=OWNERS.get(l);}
            if(owner!=null && be instanceof StorageMarkerAccess a && a.cognitivecraft$marker()!=null) {
                owner.thread();if(owner.retired.size()>=MAX_MARKERS)owner.overflow=true;else owner.retired.add(a.cognitivecraft$marker().incarnation());owner.invalidate(be);
            }
        }
    }
    public static void loadedMarker(BlockEntity be) { if (be.getLevel() instanceof ServerLevel l) { StorageIncarnationAdapter owner;synchronized(OWNERS){owner=OWNERS.get(l);}if(owner!=null){owner.observed(be);if(be instanceof StorageMarkerAccess a && a.cognitivecraft$loadedFromTag())owner.reloadCandidates.addAll(owner.byPosition.getOrDefault(be.getBlockPos(),Set.of()));} } }
    private void invalidate(BlockEntity be) {
        thread();for (UUID id:byPosition.getOrDefault(be.getBlockPos(),Set.of())) { invalidated.put(id,metadata.records().get(id).generation());liveEpochs.put(id,UUID.randomUUID());reloadCandidates.remove(id); }
        var marker=((StorageMarkerAccess)be).cognitivecraft$marker();if (marker!=null) { var positions=seen.get(marker.incarnation());if(positions!=null){positions.remove(be.getBlockPos());if(positions.isEmpty())seen.remove(marker.incarnation());} }
    }
    private void observed(BlockEntity be) {
        thread();if (!eligible(be) || be.isRemoved()) return;var marker=((StorageMarkerAccess)be).cognitivecraft$marker();if(marker==null)return;
        if (!seen.containsKey(marker.incarnation()) && seen.size()>=MAX_MARKERS) { overflow=true;return; }
        var positions=seen.computeIfAbsent(marker.incarnation(),k -> new HashSet<>());
        if (!positions.contains(be.getBlockPos()) && positions.size()>=2) { quarantine(marker.incarnation());return; }
        positions.add(be.getBlockPos().immutable());if(positions.size()>1)quarantine(marker.incarnation());
    }
    private void quarantine(UUID marker) {
        if(quarantined.size()>=MAX_MARKERS){overflow=true;return;}quarantined.add(marker);
        for(UUID id:byMarker.getOrDefault(marker,Set.of()))invalidated.put(id,metadata.records().get(id).generation());
    }
    public void close() { thread();synchronized(OWNERS){OWNERS.remove(level,this);}closed=true;seen.clear();byPosition.clear();byMarker.clear(); }
}
