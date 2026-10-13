package dev.aivillages.fabric;

import com.google.gson.Gson;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import dev.aivillages.core.kernel.Outcomes.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static dev.aivillages.core.kernel.Budgets.Kind;

/** GP-24: real survival effects and owners, test-only known IR, a queued-action barrier and fresh JVM recovery. */
public final class MandateGameTests {
    @GameTest(maxTicks=2400,padding=32)
    public void queuedParentRevocationPreservesDeliveryAndReloadDeniesCachedAuthority(GameTestHelper h) {
        String mode=System.getenv("COGNITIVECRAFT_MANDATE_PHASE");
        h.assertTrue("cold".equals(mode)||"warm".equals(mode),"Explicit mandate cold/warm mode required");
        Driver d=new Driver(h,"warm".equals(mode));
        h.succeedWhen(()->{d.step();h.assertTrue(d.done,"Waiting for mandate fixture phase="+d.phase);});
    }
    private record Stores(CitizenIdentityStore citizens,JobJournal jobs,ResourceLeaseJournal leases,MandateJournal mandates,
                          CitizenRegistry.Snapshot identities,Jobs.Snapshot tasks,ResourceLeases.Snapshot claims,Mandates.Snapshot grants,Map<String,Object> marker) {}
    private static final class Barrier implements GatewayPort {
        final LeasedGateway delegate;
        ActionHandle held;
        int harvests;
        boolean released;
        Barrier(LeasedGateway delegate){this.delegate=delegate;}
        public ActionHandle start(ValidatedRequest request,RunCorrelation run,PrimitiveRequirement primitive,Map<String,Value> args,Budgets.Ledger usage){
            ActionHandle handle=delegate.start(request,run,primitive,args,usage);
            if(primitive.id().equals(Operation.HARVEST_NEXT_WHEAT.signature().id())&&++harvests==2)held=handle;
            return handle;
        }
        public ActionReceipt poll(ActionHandle handle){return handle.equals(held)&&!released?new ActionReceipt(handle,false,0,List.of(),null):delegate.poll(handle);}
        public ActionReceipt cancel(ActionHandle handle){return delegate.cancel(handle);}
    }
    private static final class Driver {
        final GameTestHelper h;
        final boolean warm;
        final Clock clock=Clock.systemUTC();
        final ExecutorService io=Executors.newSingleThreadExecutor();
        final Path root;
        final CompletableFuture<Stores> opening;
        Stores stores;
        CitizenRegistry citizens;
        JobLifecycleStore jobs;
        ResourceLeaseService leases;
        MandateRegistry mandates;
        SurvivalGateway raw;
        LeasedGateway gateway;
        Barrier barrier;
        BoundedSkillExecutor executor;
        BoundedSkillExecutor.Run run;
        BoundedSkillExecutor.Progress terminal;
        SkillArtifact artifact;
        BlockPos origin;
        ActorRef actor;
        Villager villager;
        UUID world,parent,child,job;
        TrustedContext owner,coordinator,caller;
        Cuboid source,workArea;
        ContainerRef destination;
        Mandates.Snapshot revokeExpected,revokeNext;
        CompletableFuture<Mandates.Snapshot> withheld;
        CompletableFuture<Void> closing;
        boolean holdRevocation,done;
        int phase;
        long ticks;
        Driver(GameTestHelper h,boolean warm){
            this.h=h;this.warm=warm;root=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("cognitivecraft-mandate-fixture");
            if(!warm){
                world=UUID.randomUUID();owner=context(world);coordinator=context(world);caller=context(world);origin=h.absolutePos(new BlockPos(8,0,8));
                BootstrapGameTests.forceFixtureChunks(h,origin);
                for(int x=0;x<8;x++)for(int z=0;z<7;z++)block(x,0,z,Blocks.STONE.defaultBlockState());
                for(int z=2;z<=3;z++){block(2,0,z,Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));block(2,1,z,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));}
                block(4,1,2,Blocks.CHEST.defaultBlockState());
                BlockPos relative=origin.offset(2,1,1).subtract(h.absolutePos(BlockPos.ZERO));
                villager=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,relative.getX(),relative.getY(),relative.getZ());villager.setNoAi(false);villager.setPersistenceRequired();geometry();
                opening=async(()->open(Map.of("world",world.toString())));
            }else opening=async(()->{try{return open(StrictJson.object(Files.readString(root.resolve("mandate-restart.json"))));}catch(Exception e){throw new CompletionException(e);}});
        }
        <T> CompletableFuture<T> async(java.util.function.Supplier<T> work){return CompletableFuture.supplyAsync(work,io);}
        Stores open(Map<String,Object> marker){try{
            UUID id=UUID.fromString((String)marker.get("world"));var c=CitizenIdentityStore.open(root,id);var j=JobJournal.open(root,id);var l=ResourceLeaseJournal.open(root,id);var m=MandateJournal.open(root,id);
            return new Stores(c,j,l,m,c.snapshot(),j.snapshot(),l.snapshot(),m.snapshot(),marker);
        }catch(Exception e){throw new CompletionException(e);}}
        static TrustedContext context(UUID world){return new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(world,UUID.randomUUID()));}
        void block(int x,int y,int z,net.minecraft.world.level.block.state.BlockState state){h.getLevel().setBlockAndUpdate(origin.offset(x,y,z),state);}
        void geometry(){String dim=h.getLevel().dimension().identifier().toString();source=new Cuboid(dim,origin.getX()+2,origin.getY()+1,origin.getZ()+2,origin.getX()+2,origin.getY()+1,origin.getZ()+3);
            workArea=new Cuboid(dim,origin.getX(),origin.getY(),origin.getZ(),origin.getX()+7,origin.getY()+3,origin.getZ()+6);
            destination=new ContainerRef(dim,origin.getX()+4,origin.getY()+1,origin.getZ()+2);}
        boolean control(ActorRef actor,TrustedContext caller){return citizens.controls(actor,caller)||mandates!=null&&mandates.controls(actor,Effect.OBSERVE,caller);}
        void compose(){
            stores=opening.join();
            if(warm){var m=stores.marker();world=UUID.fromString((String)m.get("world"));owner=readContext(m,"owner");coordinator=readContext(m,"coordinator");caller=readContext(m,"caller");
                parent=UUID.fromString((String)m.get("parent"));child=UUID.fromString((String)m.get("child"));job=UUID.fromString((String)m.get("job"));
                origin=new BlockPos(number(m,"x"),number(m,"y"),number(m,"z"));BootstrapGameTests.forceFixtureChunks(h,origin);geometry();actor=stores.identities().citizens().getFirst().actor();
                villager=(Villager)h.getLevel().getEntity(actor.entityId());require(villager!=null,"Original saved actor missing");}
            citizens=new CitizenRegistry(stores.identities(),(a,b)->async(()->{try{return stores.citizens().replace(a,b);}catch(Exception e){throw new CompletionException(e);}}),CitizenRegistry.privateAddresses(),clock,stores.citizens().readOnly());
            mandates=new MandateRegistry(stores.grants(),(a,b)->{
                if(holdRevocation){revokeExpected=a;revokeNext=b;withheld=new CompletableFuture<>();return withheld;}
                return async(()->{try{return stores.mandates().replace(a,b);}catch(Exception e){throw new CompletionException(e);}});
            },new MandateRegistry.References(){
                public boolean authority(ActorRef a,Effect e,TrustedContext c){return citizens.controls(a,c)&&h.getLevel().getEntity(a.entityId())!=null;}
                public boolean available(TrustedContext c){return true;}
            },new MandateJobExecutions(()->jobs),clock,Mandates.Limits.defaults(),stores.mandates().readOnly());
            jobs=new JobLifecycleStore(stores.tasks(),(a,b)->async(()->{try{return stores.jobs().replace(a,b);}catch(Exception e){throw new CompletionException(e);}}),JobLifecycleStore.privateJobs(this::control),c->{},
                    id->Optional.of(CropDelivery.SPEC),new RequestEnvironment(){
                        public boolean enrolled(ActorRef a,TrustedContext c){return control(a,c);}
                        public boolean loaded(Cuboid a,ObservationRef o){return true;}
                        public boolean available(ContainerRef c,ObservationRef o){return true;}
                    },clock,Jobs.Settings.defaults(),stores.jobs().readOnly());
            var direct=(AuthorityPolicy)(a,e,c)->citizens.controls(a,c);
            raw=new SurvivalGateway(new FabricGatewayWorld(h.getLevel()),new MandateAuthority(direct,mandates),new SurvivalGateway.Limits(2,512,64,16,256,1,200,32),clock);
            leases=new ResourceLeaseService(stores.claims(),(a,b)->async(()->{try{return stores.leases().replace(a,b);}catch(Exception e){throw new CompletionException(e);}}),
                    new JobLeaseOwners(jobs,clock),new FabricResourceLeaseWorld(h.getLevel().getServer()),ResourceLeases.Settings.production(),()->h.getLevel().getGameTime(),clock,stores.leases().readOnly());
            gateway=new LeasedGateway(raw,leases,(request,correlation)->LeasedGateway.cropWorkspaces(new JobLeaseOwners(jobs,clock),request,correlation),()->h.getLevel().getGameTime(),1200,200,8,512);
            gateway.admission(LeasedGateway.mandates(mandates,direct));barrier=new Barrier(gateway);artifact=compile();var ref=artifact.descriptor().ref();
            var catalog=new BoundedSkillExecutor.ArtifactSource(){
                public Optional<SkillArtifact> body(ArtifactRef r){return r.equals(ref)?Optional.of(artifact):Optional.empty();}
                public Optional<AdmissionRecord> admission(ArtifactRef r){return r.equals(ref)?Optional.of(new AdmissionRecord(ref,AdmissionStatus.ADMITTED,new EvidenceRef("test-only","mandate-fixture:1","GP-24"),null,1)):Optional.empty();}
                public Optional<Compatibility> compatibility(ArtifactRef r){return r.equals(ref)?Optional.of(new Compatibility(ref,CompatibilityStatus.COMPATIBLE,List.of(),"fixture")):Optional.empty();}
            };
            executor=new BoundedSkillExecutor(catalog,id->Optional.of(CropDelivery.SPEC),GatewayPrimitives.instance(),(a,r,c)->control(a,c),
                    (p,r,b)->{throw new AssertionError("No inference or trial authority");},new BoundedSkillExecutor.ControlPolicy(){
                        public boolean mayInspect(TrustedContext c,TrustedContext o,UUID id){return c.equals(o);}
                        public boolean mayCancel(TrustedContext c,TrustedContext o,UUID id){return c.equals(o);}
                    },barrier,gateway::releaseRun,clock,()->h.getLevel().getGameTime(),new BoundedSkillExecutor.Settings(8,16,32,64));
        }
        SkillArtifact compile(){
            var actor=Map.<String,Object>of("actor",Map.of("param","actor"));var src=Map.<String,Object>of("actor",Map.of("param","actor"),"source",Map.of("param","source"));
            var dest=Map.<String,Object>of("actor",Map.of("param","actor"),"destination",Map.of("param","destination"));
            var cycle=List.of(call(Operation.MOVE_TO_SOURCE,src,"approached"),call(Operation.HARVEST_NEXT_WHEAT,src,"harvested"),
                    Map.of("op","repeat","count",Map.of("int",30),"body",List.of(call(Operation.OBSERVE_INVENTORY,actor,"stock"))),
                    call(Operation.PICKUP_TRACKED_WHEAT,actor,"picked"),call(Operation.MOVE_TO_DESTINATION,dest,"arrived"),
                    call(Operation.TRANSFER_WHEAT,Map.of("actor",Map.of("param","actor"),"destination",Map.of("param","destination"),"amount",Map.of("int",1)),"delivered"));
            String text=new Gson().toJson(Map.of("schema",1,"capability",CropDelivery.ID.name(),"capabilityVersion",1,"dependencies",List.of(),
                    "body",List.of(Map.of("op","repeat","count",Map.of("param","amount"),"body",cycle),Map.of("op","result","value",Map.of("param","amount")))));
            var result=new SkillCompiler(id->Optional.of(CropDelivery.SPEC),GatewayPrimitives.instance(),r->Optional.empty()).compile(text);require(result instanceof SkillCompiler.Success,"Known fixture compile failed "+result);
            return ((SkillCompiler.Success)result).skill().artifact();
        }
        static Map<String,Object> call(Operation op,Map<String,Object> args,String into){return Map.of("op","call","kind","primitive","id",op.signature().id(),"version",1,"fingerprint",op.signature().fingerprint(),"args",args,"into",into);}
        Budgets.Limits budget(long size,long deadline){var values=new EnumMap<Kind,Long>(Kind.class);for(var kind:List.of(Kind.CALLS,Kind.INSTRUCTIONS,Kind.OBSERVATIONS,Kind.TRAVEL_BLOCKS,Kind.ATTEMPTED_EFFECTS,Kind.COMMITTED_EFFECTS,Kind.ELAPSED_TICKS))values.put(kind,size);return new Budgets.Limits(values,deadline);}
        Mandates.Rights rights(){return new Mandates.Rights(Set.of(actor),EnumSet.allOf(Effect.class),Set.of("minecraft:wheat"),workArea,Set.of(destination));}
        Jobs.Job job(){return jobs.query(job,caller);}
        int wheat(){var box=(Container)h.getLevel().getBlockEntity(new BlockPos(destination.x(),destination.y(),destination.z()));return box.countItem(Items.WHEAT);}
        boolean secondMature(){var state=h.getLevel().getBlockState(origin.offset(2,1,3));return state.is(Blocks.WHEAT)&&((CropBlock)Blocks.WHEAT).isMaxAge(state);}
        void step(){
            if(done)return;ticks++;java.util.concurrent.locks.LockSupport.parkNanos(phase<=6||phase>=10?20_000_000:1_000_000);
            if(stores!=null){citizens.tick();mandates.tick();jobs.tick();leases.tick();gateway.tick();raw.tick();}
            switch(phase){
                case 0 -> {require(opening.isDone(),"Opening stores");compose();if(warm){phase=20;return;}
                    var c=citizens.enroll(villager.getUUID(),h.getLevel().dimension().identifier().toString(),owner);require(c.accepted(),"Enroll actual worker "+c);actor=c.citizen().actor();phase=1;}
                case 1 -> {require(citizens.ready(),"Enrollment acknowledgement");citizens.observeAvailability(actor.citizenId(),CitizenRegistry.Availability.LOADED);phase=2;}
                case 2 -> {require(citizens.ready(),"Loaded worker acknowledgement");parent=UUID.randomUUID();require(mandates.grant(parent,null,null,rights(),clock.millis()+240000,budget(100000,clock.millis()+180000),coordinator,owner).accepted(),"Root mandate");phase=3;}
                case 3 -> {require(mandates.ready(),"Root acknowledgement");child=UUID.randomUUID();require(mandates.grant(child,parent,null,rights(),clock.millis()+150000,budget(30000,clock.millis()+120000),caller,coordinator).accepted(),"Child mandate");phase=4;}
                case 4 -> {require(mandates.ready(),"Child acknowledgement");var before=mandates.snapshot();
                    var wider=new Mandates.Rights(Set.of(actor),EnumSet.allOf(Effect.class),Set.of("minecraft:wheat","minecraft:oak_log"),workArea,Set.of(destination));
                    require(!mandates.grant(UUID.randomUUID(),child,null,wider,clock.millis()+100000,budget(10000,clock.millis()+90000),context(world),caller).accepted(),"Widened child rejected");
                    require(!mandates.grant(UUID.randomUUID(),child,null,rights(),clock.millis()+100000,budget(10000,clock.millis()+90000),owner,caller).accepted(),"Cycle rejected");require(before.equals(mandates.snapshot()),"No partial widening");
                    job=UUID.randomUUID();var request=new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(actor),"source",new AreaValue(source),"destination",new ContainerValue(destination),"amount",new IntValue(2)));
                    require(jobs.create(job,job,request,caller,new ObservationRef(UUID.randomUUID(),h.getLevel().getGameTime(),actor.dimension()),budget(10000,clock.millis()+90000),List.of()).accepted(),"Original Job creation");phase=5;}
                case 5 -> {require(jobs.ready(),"Job acknowledgement");var j=job();var ref=artifact.descriptor().ref();require(jobs.assign(job,j.guard(),UUID.randomUUID(),actor,ref,List.of(ref),j.allowance(),caller).accepted(),"Original assignment");phase=6;}
                case 6 -> {require(jobs.ready(),"Assignment acknowledgement");var a=job().current();var r=a.executions().getLast();
                    var result=executor.startAdmitted(a.bound(),r.artifact(),new RunCorrelation(r.runId(),r.artifact(),null),new Budgets.ExecutionLimits(a.allowance(),10000),new Budgets.Ledger(a.allowance(),clock));
                    require(result instanceof BoundedSkillExecutor.Started,"Known execution refused "+result);run=((BoundedSkillExecutor.Started)result).run();phase=7;}
                case 7 -> {var progress=run.tick(caller);require(progress.phase()!=BoundedSkillExecutor.Phase.TERMINAL,"Early work failure "+progress.summary());
                    if(barrier.held!=null){require(wheat()==1&&secondMature(),"One real delivery before queued second harvest");require(progress.summary().committedEffects()==3,"Three attributable prior effects");
                        holdRevocation=true;var p=mandates.snapshot().grants().stream().filter(g->g.id().equals(parent)).findFirst().orElseThrow();
                        require(mandates.revoke(parent,p.revision(),owner).status()==Mandates.Status.PENDING,"Revocation linearization");require(withheld!=null,"Durability barrier installed");barrier.released=true;phase=8;}}
                case 8 -> {terminal=run.tick(caller);require(terminal.phase()==BoundedSkillExecutor.Phase.TERMINAL,"Waiting for denied queued action");
                    require(terminal.summary().outcome().reason()==Reason.AUTHORITY_DENIED,"Effect-time current parent gate "+terminal.summary());require(!withheld.isDone(),"Revocation not acknowledged yet");
                    require(wheat()==1&&secondMature()&&terminal.summary().committedEffects()==3,"Prior stock/effects conserved, next harvest absent");
                    holdRevocation=false;async(()->{try{return stores.mandates().replace(revokeExpected,revokeNext);}catch(Exception e){throw new CompletionException(e);}}).whenComplete((s,e)->{if(e!=null)withheld.completeExceptionally(e);else withheld.complete(s);});phase=9;}
                case 9 -> {require(mandates.ready(),"Durable revoke acknowledgement");mandates.maintain(h.getLevel().getGameTime());phase=10;}
                case 10 -> {mandates.maintain(h.getLevel().getGameTime());require(jobs.ready()&&job().state()==Jobs.State.CANCELLING,"Owner cancellation acknowledgement");
                    var j=job();var a=j.current();var s=terminal.summary();require(run.stopped(caller),"Actual executor stopped");
                    require(jobs.recordExecutionResult(job,j.guard(),new Jobs.Report(a.id(),a.generation(),s.committedEffects(),s.usage(),s.receipts(),s.outcome()),caller).accepted(),"Owner committed partial report");phase=11;}
                case 11 -> {require(jobs.ready()&&job().state()==Jobs.State.CANCELLED,"Cancelled original Job");require(leases.ready()&&gateway.released(job().current().executions().getLast().runId()),"Original leases released after stop");
                    require(job().effects()==3&&job().fulfilled()==1&&job().current().receipts().size()==3,"Real Job accounting retained");finish();phase=12;}
                case 12 -> {require(closing.isDone(),"Closing fsynced stores");closing.join();h.getLevel().getServer().saveEverything(false,true,true);done=true;System.out.println("IMP-024 physical accepted warm="+warm+" modelCalls=0 delivered=1 effects=3 nextEffectDenied=true");}
                case 20 -> {require(jobs.ready()&&citizens.ready(),"Warm owner recovery");require(wheat()==1&&secondMature(),"Saved native stock/crop conserved");require(job().effects()==3&&job().fulfilled()==1,"Original receipt accounting reopened");
                    var b=mandates.snapshot().bindings().getFirst();var target=new EffectTarget(actor.dimension(),source.minX(),source.minY(),source.maxZ());
                    require(!mandates.allows(b.request(),b.run(),Effect.HARVEST,target),"Old binding/cached grant inactive");
                    var p=mandates.snapshot().grants().stream().filter(g->g.id().equals(parent)).findFirst().orElseThrow();require(p.revoked(),"Durable parent revocation present");
                    require(!mandates.revalidate(parent,p.revision(),owner).accepted(),"Revoked parent cannot regain consent");
                    require(!mandates.prepare(b.request(),new RunCorrelation(b.run(),b.artifact(),null)).accepted(),"No cached resume");
                    ActionHandle denied=raw.start(b.request(),new RunCorrelation(b.run(),b.artifact(),null),new PrimitiveRequirement(Operation.HARVEST_NEXT_WHEAT.signature().id(),1,Operation.HARVEST_NEXT_WHEAT.signature().fingerprint()),
                            Map.of("actor",new ActorValue(actor),"source",new AreaValue(source)),new Budgets.Ledger(budget(1000,clock.millis()+10000),clock));
                    var answer=raw.poll(denied);require(answer.terminal()&&answer.reason()==Reason.AUTHORITY_DENIED,"Native gateway denied old authority");raw.releaseRun(b.run());
                    require(wheat()==1&&secondMature(),"Warm denied use caused no effect");finish();phase=12;}
                default -> throw new AssertionError("Unknown mandate phase "+phase);
            }
        }
        void finish(){
            var marker=new LinkedHashMap<String,Object>();marker.put("world",world.toString());writeContext(marker,"owner",owner);writeContext(marker,"coordinator",coordinator);writeContext(marker,"caller",caller);
            marker.put("x",origin.getX());marker.put("y",origin.getY());marker.put("z",origin.getZ());marker.put("parent",parent.toString());marker.put("child",child.toString());marker.put("job",job.toString());
            var proof=new LinkedHashMap<String,Object>();
            proof.putAll(Map.of("schema",1,"status","PASS","warm",warm,"modelCalls",0,"delivered",wheat(),"effects",job().effects(),"receipts",job().current().receipts().size(),"nextEffectDenied",true,"secondMature",secondMature(),"ticks",ticks));
            proof.put("processId",ProcessHandle.current().pid());
            proof.put("processStarted",ProcessHandle.current().info().startInstant().orElseThrow().toString());
            mandates.close();closing=CompletableFuture.runAsync(()->{try{Files.writeString(root.resolve("mandate-restart.json"),new Gson().toJson(marker));Files.writeString(root.resolve(warm?"mandate-warm-proof.json":"mandate-cold-proof.json"),new Gson().toJson(proof));
                stores.mandates().close();stores.leases().close();stores.jobs().close();stores.citizens().close();}catch(Exception e){throw new CompletionException(e);}},io).whenComplete((v,e)->io.shutdown());
        }
        static int number(Map<String,Object> m,String key){return Math.toIntExact(((Number)m.get(key)).longValue());}
        static void writeContext(Map<String,Object> m,String key,TrustedContext c){m.put(key,Map.of("principal",c.principal().id().toString(),"world",c.scope().worldId().toString(),"domain",c.scope().domainId().toString()));}
        @SuppressWarnings("unchecked") static TrustedContext readContext(Map<String,Object> m,String key){
            var c=(Map<String,Object>)m.get(key);
            return new TrustedContext(new PrincipalRef(UUID.fromString((String)c.get("principal"))),
                    new ScopeRef(UUID.fromString((String)c.get("world")),UUID.fromString((String)c.get("domain"))));
        }
        void require(boolean condition,String message){h.assertTrue(condition,message);}
    }
}
