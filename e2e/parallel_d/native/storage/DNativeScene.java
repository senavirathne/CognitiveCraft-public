package dev.aivillages.e2e.paralleld;

import com.google.gson.Gson;
import dev.aivillages.core.kernel.storage.*;
import dev.aivillages.core.kernel.storage.StorageBindingRules.Binding;
import dev.aivillages.fabric.storage.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Actual native host fixture only. No connected player, Jobs context or participant save authority. */
final class DNativeScene {
    static final UUID PRINCIPAL=UUID.fromString("d0200000-0000-4000-8000-000000000002");
    final GameTestHelper h;final ServerLevel level;final StorageMetadataStore store;final StorageRegistry registry;
    final StorageIncarnationAdapter adapter;final UUID world;final StorageRegistry.Caller caller;
    final AtomicBoolean allow=new AtomicBoolean(true);final AtomicReference<Binding> attested=new AtomicReference<>();
    final String caseId;final Path evidence;
    private DNativeScene(GameTestHelper h,String caseId,StorageMetadataStore store) {
        this.h=h;this.level=h.getLevel();this.caseId=caseId;this.store=store;world=DTestAuthority.acquireTaggedWorld().world();caller=new StorageRegistry.Caller(world,PRINCIPAL);
        registry=new StorageRegistry(store,new StorageRegistry.Authority() {
            public boolean controls(StorageRegistry.Caller c,StorageRegistry.Owner o) { return allow.get() && c.equals(caller) && PRINCIPAL.equals(o.id()); }
            public boolean mayGrant(StorageRegistry.Caller c,StorageRegistry.Record r,StorageRegistry.Grant g){return allow.get() && c.equals(caller) && Set.of(StorageRegistry.Permission.INSPECT,StorageRegistry.Permission.DEPOSIT,StorageRegistry.Permission.WITHDRAW).containsAll(g.permissions());}
            public boolean allows(StorageRegistry.Caller c,StorageRegistry.Action a,StorageRegistry.Record r) {
                return allow.get() && c.equals(caller) && (a!=StorageRegistry.Action.COMMIT_BIND || attested.get()!=null && attested.get().storage().equals(r.id()) && attested.get().generation()==r.generation());
            }
        });
        adapter=new StorageIncarnationAdapter(level,world,(a,w,s,p)->allow.get() && w.equals(world));adapter.updateMetadata(store.snapshot());
        evidence=Path.of(System.getProperty("cognitivecraft.parallelD.evidence"));
    }
    static Path root() { return Path.of(System.getProperty("cognitivecraft.parallelD.shared")); }
    static void open(GameTestHelper h,String id,boolean existing,Consumer<DNativeScene> next) {
        UUID world=DTestAuthority.acquireTaggedWorld().world();Path path=root().resolve(id);
        var future=existing?StorageMetadataStore.open(path,world):StorageMetadataStore.createNew(path,world);
        when(h,future,store->{h.assertTrue(!store.readOnly(),"metadata owner read only: "+store.reason());next.accept(new DNativeScene(h,id,store));});
    }
    static <T> void when(GameTestHelper h,CompletionStage<T> stage,Consumer<T> next) {
        stage.whenComplete((result,error)->h.getLevel().getServer().execute(()->h.runAfterDelay(1,()->{
            if(error!=null)throw new CompletionException(error);next.accept(result);
        })));
    }
    void ack(StorageMetadataStore.Ack ack) { h.assertTrue(ack.status()==StorageMetadataStore.Status.ACKNOWLEDGED,"journal acknowledgement: "+ack); }
    void register(BlockPos pos,String adapterId,String label,Consumer<UUID> next) {
        when(h,registry.registerMetadata(caller,registry.snapshot().revision(),new StorageRegistry.Owner(StorageRegistry.OwnerKind.PLAYER,PRINCIPAL),label,adapterId,Set.of(),List.of()),a->{ack(a);adapter.updateMetadata(registry.snapshot());next.accept(a.storage());});
    }
    void registerShared(BlockPos pos,String adapterId,String label,List<StorageRegistry.Grant> grants,Consumer<UUID> next){
        when(h,registry.registerMetadata(caller,registry.snapshot().revision(),new StorageRegistry.Owner(StorageRegistry.OwnerKind.PLAYER,PRINCIPAL),label,adapterId,Set.of(),grants),a->{ack(a);adapter.updateMetadata(registry.snapshot());next.accept(a.storage());});
    }
    void bind(UUID id,BlockPos pos,boolean publish,Consumer<Binding> next) {
        var s=registry.snapshot();var row=s.records().get(id);long generation=s.bindings().containsKey(id)?row.generation()+1:row.generation();
        var plan=adapter.plan(id,generation,pos);
        when(h,registry.prepareBinding(caller,s.revision(),id,row.revision(),plan.intent().wire()),a->{
            ack(a);adapter.updateMetadata(registry.snapshot());var live=adapter.write(plan);attested.set(live);
            if(!publish){next.accept(live);return;}
            var prepared=registry.snapshot();
            when(h,registry.acknowledgeBinding(caller,prepared.revision(),id,prepared.records().get(id).revision(),live.wire()),b->{ack(b);adapter.updateMetadata(registry.snapshot());next.accept(live);});
        });
    }
    UUID named(String name) { return registry.snapshot().records().values().stream().filter(r->r.label().equals(name)).findFirst().orElseThrow().id(); }
    BlockEntity be(BlockPos pos) { return Objects.requireNonNull(level.getBlockEntity(pos)); }
    StorageMarkerAccess.Marker marker(BlockPos pos) { return ((StorageMarkerAccess)be(pos)).cognitivecraft$marker(); }
    void frame(String event,UUID storage,BlockPos... positions) {
        var out=new TreeMap<String,Object>();out.put("case",caseId);out.put("event",event);out.put("phase",System.getProperty("cognitivecraft.parallelD.phase"));out.put("world",world.toString());out.put("principal",PRINCIPAL.toString());out.put("tick",level.getGameTime());
        var s=registry.snapshot();out.put("metadata_revision",s.revision());out.put("storage",storage.toString());var r=s.records().get(storage);
        out.put("record_revision",r.revision());out.put("binding_generation",r.generation());out.put("record_state",r.state().name());out.put("label",r.label());
        var raw=s.bindings().get(storage);out.put("binding",raw==null?null:raw.body());out.put("resolved",adapter.resolve(storage).map(b->b.wire().body()).orElse(null));
        var nativeRows=new ArrayList<Map<String,Object>>();
        for(var p:positions) {
            var state=level.getBlockState(p);var row=new TreeMap<String,Object>();row.put("x",p.getX());row.put("y",p.getY());row.put("z",p.getZ());row.put("block_state",state.toString());
            if(state.is(Blocks.CHEST)){row.put("type",state.getValue(ChestBlock.TYPE).name());row.put("facing",state.getValue(ChestBlock.FACING).name());}
            if(state.is(Blocks.BARREL)){row.put("type","BARREL");row.put("facing",state.getValue(BarrelBlock.FACING).name());}
            var entity=level.getBlockEntity(p);var m=entity instanceof StorageMarkerAccess access?access.cognitivecraft$marker():null;
            row.put("incarnation",m==null?null:m.incarnation().toString());row.put("marker_generation",m==null?null:m.generation());row.put("quarantined",m!=null && adapter.quarantined(m.incarnation()));
            if(entity!=null)row.put("saved_nbt",entity.saveWithFullMetadata(level.registryAccess()).toString());nativeRows.add(row);
        }
        out.put("native",nativeRows);
        try { Files.createDirectories(evidence);Files.writeString(evidence.resolve("observations.jsonl"),new com.google.gson.GsonBuilder().serializeNulls().create().toJson(out)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
        catch(Exception e){throw new RuntimeException(e);}
    }
    void finish() { adapter.close();when(h,store.closeAsync(),ignored->h.succeed()); }
}
