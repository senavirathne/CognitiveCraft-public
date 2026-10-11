package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.aivillages.core.kernel.Contracts.*;

/** GP-20 test driver: distinct JVMs, supplied test geometry, temporary stores, no Minecraft construction. */
public final class BlueprintBoundaryProbe {
    private static final UUID WORLD=UUID.fromString("f9ac2d64-5b77-4caa-b0e0-a15a6a292020");
    private static final TrustedContext OWNER=new TrustedContext(
            new PrincipalRef(UUID.fromString("e9ac2d64-5b77-4caa-b0e0-a15a6a292020")),
            new ScopeRef(WORLD,UUID.fromString("d9ac2d64-5b77-4caa-b0e0-a15a6a292020")));
    private BlueprintBoundaryProbe() { }
    public static void main(String[] args)throws Exception {
        if(args.length!=2 || !Set.of("cold","warm").contains(args[0]))throw new IllegalArgumentException("phase and temporary root");
        String phase=args[0];Path root=Path.of(args[1]);Files.createDirectories(root);
        var gatewayCalls=new AtomicInteger();var modelCalls=new AtomicInteger();
        GatewayPort gateway=(GatewayPort)java.lang.reflect.Proxy.newProxyInstance(GatewayPort.class.getClassLoader(),new Class<?>[]{GatewayPort.class},
                (p,m,a)->{gatewayCalls.incrementAndGet();throw new AssertionError("No structural world effects");});
        GenerationPort model=(GenerationPort)java.lang.reflect.Proxy.newProxyInstance(GenerationPort.class.getClassLoader(),new Class<?>[]{GenerationPort.class},
                (p,m,a)->{modelCalls.incrementAndGet();throw new AssertionError("Models unavailable");});
        require(gateway!=null && model!=null,"spies");
        var executor=Executors.newSingleThreadExecutor(r->new Thread(r,"blueprint-gp20-storage"));
        Map<String,Object> proof;
        try {proof=executor.submit(()->run(phase,root)).get(30,TimeUnit.SECONDS);}
        finally{executor.shutdownNow();}
        require(gatewayCalls.get()==0 && modelCalls.get()==0,"effects or inference");
        var completed=new TreeMap<>(proof);completed.put("gatewayCalls",gatewayCalls.get());completed.put("modelCalls",modelCalls.get());
        Files.writeString(root.resolve(phase+".json"),StrictJson.canonical(completed)+"\n");
        System.out.println("IMP-020 GP-20 accepted phase="+phase+" cells=331 planks=224 slabs=99 doors=2 torches=4 effects=0 modelCalls=0 immutable=true");
        System.out.println("IMP-020 structural evidence "+StrictJson.canonical(completed));
    }
    private static Map<String,Object> run(String phase,Path root)throws Exception {
        require(Thread.currentThread().getName().equals("blueprint-gp20-storage"),"off-thread driver");
        Path marker=root.resolve("ref.json"),world=root.resolve("structural-world");
        var registry=BlueprintFixture.registry();
        String supplied=BlueprintFixture.timber().canonical();
        var validated=BlueprintValidator.validate(supplied,registry,BlueprintValidator.Limits.defaults(),()->false);
        require(validated.valid() && validated.materials().equals(BlueprintFixture.bill()),"fixture bill");
        Blueprint.Ref ref=validated.body().ref();
        try(var store=BlueprintStore.open(world,WORLD,new BlueprintStore.Limits(1,4_194_304,4,4),TrustedContext::equals,p->{})) {
            var roots=new RetentionRoots();
            var retention=new WorldRetentionManager(WorldRetentionManager.Policy.defaults(),roots,c->false);
            retention.register(store.retentionOwner());
            if(phase.equals("cold")) {
                require(!Files.exists(marker) && store.variants()==0,"fresh cold store");
                var published=store.store(supplied,registry,new BlueprintStore.Provenance(OWNER,"two-room test-only structure"),()->false);
                require(published.status()==BlueprintStore.Status.STORED && ref.equals(published.ref()),"durable publication");
                var cells=new ArrayList<>(BlueprintFixture.timber().cells());Collections.reverse(cells);
                var fixture=BlueprintFixture.timber();
                var reordered=new Blueprint.Body(fixture.dimensions(),fixture.constraints(),fixture.palette(),cells);
                require(store.store(reordered.canonical(),registry,new BlueprintStore.Provenance(OWNER,"two-room test-only structure"),()->false).ref().equals(ref),"dedup");
                require(store.store(" ".repeat(65537),registry,new BlueprintStore.Provenance(OWNER,"invalid"),()->false).status()==BlueprintStore.Status.QUOTA,"oversized");
                require(store.store(supplied.substring(0,supplied.length()-1)+",\"program\":[]}",registry,
                        new BlueprintStore.Provenance(OWNER,"invalid"),()->false).status()==BlueprintStore.Status.INVALID_DATA,"executable");
                require(store.recordCompatibility(ref,OWNER,BlueprintFixture.changedMeaning(),()->false).status()==BlueprintStore.Status.INCOMPATIBLE,"compatibility");
                Files.writeString(marker,StrictJson.canonical(Map.of("schema",1,"world",WORLD.toString(),"ref",ref.sha256()))+"\n");
            } else {
                var saved=StrictJson.object(Files.readString(marker));
                require(WORLD.toString().equals(saved.get("world")) && ref.sha256().equals(saved.get("ref")),"reloaded identity");
            }
            var inspection=store.inspect(ref,OWNER,registry,()->false);
            require(inspection.compatible() && inspection.materials().equals(BlueprintFixture.bill()),"compatible reuse");
            require(inspection.evidence().size()==1 && inspection.evidence().getFirst().status()==BlueprintValidator.Status.INCOMPATIBLE,"retained prior evidence");
            String canonical=inspection.body().canonical();
            for(int turns=0;turns<4;turns++) {
                var view=BlueprintValidator.bind(inspection.body(),new Blueprint.SiteBinding(ref,"minecraft:overworld",100,70,-100,turns));
                var result=BlueprintValidator.validate(view.geometry().canonical(),registry,BlueprintValidator.Limits.defaults(),()->false);
                require(result.valid() && result.materials().equals(BlueprintFixture.bill()),"rotation bill");
                require(canonical.equals(inspection.body().canonical()) && ref.equals(view.source()),"immutable site binding");
            }
            Path manifest=world.resolve(BlueprintStore.WORLD_RELATIVE_PATH).resolve("manifest.jsonl");
            byte[] before=Files.readAllBytes(manifest);
            require(store.store(BlueprintFixture.changed().canonical(),registry,new BlueprintStore.Provenance(OWNER,"variant"),()->false).status()==BlueprintStore.Status.QUOTA,"variant quota");
            require(Arrays.equals(before,Files.readAllBytes(manifest)) && store.variants()==1,"no eviction");
            require(roots.snapshot().roots().contains(Blueprint.retentionKey(ref)),"registered structural root");
            for(String path:List.of(JobJournal.WORLD_RELATIVE_PATH,BootstrapJournal.WORLD_RELATIVE_PATH,ResourceLeaseJournal.WORLD_RELATIVE_PATH))
                require(!Files.exists(world.resolve(path)),"no execution state");
            return Map.ofEntries(Map.entry("schema",1),Map.entry("architecture","0.5"),Map.entry("phase",phase),
                    Map.entry("ref",ref.sha256()),Map.entry("cells",inspection.body().cells().size()),
                    Map.entry("materials",inspection.materials()),Map.entry("candidateBytes",validated.inputBytes()),
                    Map.entry("canonicalBytes",canonical.getBytes(StandardCharsets.UTF_8).length),Map.entry("validationWork",validated.work()),
                    Map.entry("manifestBytes",before.length),Map.entry("variants",store.variants()),Map.entry("evidence",inspection.evidence().size()));
        }
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
