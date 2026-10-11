package dev.aivillages.fabric;

import com.google.gson.Gson;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.*;
import dev.aivillages.core.kernel.Prerequisites.*;
import dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** GP-18: actual installed recipes, loaded stocks, P0 effects, Jobs/Leases, and two saved-world JVMs. */
public final class PrerequisiteGameTests {
    @GameTest(maxTicks=3000,padding=32)
    public void stockFirstSharedProductionUsesRealReceiptsAndNoInference(GameTestHelper helper){run(helper,false);}
    static void run(GameTestHelper h,boolean warm) {
        h.assertTrue((warm?"warm":"cold").equals(System.getenv("COGNITIVECRAFT_PREREQUISITE_RESTART")),"Explicit process phase");
        Driver d=new Driver(h,warm);h.succeedWhen(()->{d.step();h.assertTrue(d.phase==40,"Waiting for GP-18 phase="+d.phase);});
    }
    private record Stores(CitizenIdentityStore identities,JobJournal jobs,ResourceLeaseJournal leases,Map<String,Object> marker) { }
    private record Credit(AllocationIntent intent,UUID receipt,long quantity) { }
    private static final class Driver {
        final GameTestHelper h;final boolean warm;final Clock clock=Clock.systemUTC();
        final ExecutorService io=Executors.newSingleThreadExecutor();final Path root;final CompletableFuture<Stores> opening;
        Stores stores;CitizenRegistry citizens;JobLifecycleStore jobs;ResourceLeaseService leases;
        SurvivalGateway raw;LeasedGateway gateway;BoundedSkillExecutor executor;BoundedSkillExecutor.Run run;
        FabricPrerequisiteRuntime prerequisites;SkillArtifact artifact;TrustedContext owner;ActorRef actor;Villager worker;
        UUID world,partial,first,second,producer,unsupported;BlockPos origin;Cuboid source;ContainerRef destination;
        Proposal shared;List<Credit> credits=List.of();int creditIndex,phase;boolean physical=true;
        CompletableFuture<Void> closing;long quietUntil;int coldJobCount;
        Driver(GameTestHelper h,boolean warm) {
            this.h=h;this.warm=warm;root=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("cognitivecraft-prerequisite-fixture");
            if(!warm) {
                world=UUID.randomUUID();owner=new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(world,UUID.randomUUID()));
                origin=h.absolutePos(new BlockPos(8,0,8));BootstrapGameTests.forceFixtureChunks(h,origin);geometry();
                for(int x=0;x<10;x++)for(int z=0;z<9;z++)block(x,0,z,Blocks.STONE.defaultBlockState());
                for(int x=2;x<=3;x++)for(int z=2;z<=4;z++){
                    block(x,0,z,Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
                    if(x!=3||z!=4)block(x,1,z,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
                }
                for(int x=1;x<=4;x++)for(int z=1;z<=5;z++)if((x==1||x==4||z==1||z==5)&&!(x==4&&z==4))block(x,1,z,Blocks.STONE.defaultBlockState());
                block(4,1,4,Blocks.CHEST.defaultBlockState());box().setItem(0,new ItemStack(Items.WHEAT,2));
                var relative=origin.offset(3,1,3).subtract(h.absolutePos(BlockPos.ZERO));
                worker=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,relative.getX(),relative.getY(),relative.getZ());worker.setNoAi(false);worker.setPersistenceRequired();
                opening=async(()->open(Map.of("world",world.toString())));
            } else opening=async(()->{try{return open(StrictJson.object(Files.readString(root.resolve("restart.json"))));}catch(Exception e){throw new CompletionException(e);}});
        }
        <T> CompletableFuture<T> async(Supplier<T> task){return CompletableFuture.supplyAsync(task,io);}
        Stores open(Map<String,Object> marker){try{UUID id=UUID.fromString((String)marker.get("world"));return new Stores(CitizenIdentityStore.open(root,id),JobJournal.open(root,id),ResourceLeaseJournal.open(root,id),marker);}catch(Exception e){throw new CompletionException(e);}}
        void geometry(){String dim=h.getLevel().dimension().identifier().toString();source=new Cuboid(dim,origin.getX()+2,origin.getY()+1,origin.getZ()+2,origin.getX()+3,origin.getY()+1,origin.getZ()+4);destination=new ContainerRef(dim,origin.getX()+4,origin.getY()+1,origin.getZ()+4);}
        void block(int x,int y,int z,net.minecraft.world.level.block.state.BlockState state){h.getLevel().setBlockAndUpdate(origin.offset(x,y,z),state);}
        Container box(){return (Container)h.getLevel().getBlockEntity(new BlockPos(destination.x(),destination.y(),destination.z()));}
        int stock(){return box().countItem(Items.WHEAT);}
        boolean controls(ActorRef a,TrustedContext c){return citizens.controls(a,c);}
        void compose() {
            stores=opening.join();
            if(warm){var m=stores.marker();world=id(m,"world");owner=new TrustedContext(new PrincipalRef(id(m,"principal")),new ScopeRef(world,id(m,"domain")));
                origin=new BlockPos(number(m,"x"),number(m,"y"),number(m,"z"));BootstrapGameTests.forceFixtureChunks(h,origin);geometry();
                actor=new ActorRef(id(m,"citizen"),id(m,"entity"),source.dimension());partial=id(m,"partial");first=id(m,"first");second=id(m,"second");producer=id(m,"producer");coldJobCount=number(m,"jobs");}
            citizens=new CitizenRegistry(stores.identities().snapshot(),(expected,next)->async(()->{try{return stores.identities().replace(expected,next);}catch(Exception e){throw new CompletionException(e);}}),CitizenRegistry.privateAddresses(),clock,false);
            jobs=new JobLifecycleStore(stores.jobs().snapshot(),(expected,next)->async(()->{try{return stores.jobs().replace(expected,next);}catch(Exception e){throw new CompletionException(e);}}),
                    JobLifecycleStore.privateJobs(this::controls),c->{},id->Optional.of(CropDelivery.SPEC),new RequestEnvironment(){
                        public boolean enrolled(ActorRef a,TrustedContext c){return controls(a,c);}
                        public boolean loaded(Cuboid area,ObservationRef ref){return area.equals(source);}
                        public boolean available(ContainerRef target,ObservationRef ref){return target.equals(destination);}
                    },clock,Jobs.Settings.defaults(),false);
            var owners=new JobLeaseOwners(jobs,clock);
            leases=new ResourceLeaseService(stores.leases().snapshot(),(expected,next)->async(()->{try{return stores.leases().replace(expected,next);}catch(Exception e){throw new CompletionException(e);}}),
                    owners,new FabricResourceLeaseWorld(h.getLevel().getServer()),ResourceLeases.Settings.production(),()->h.getLevel().getServer().overworld().getGameTime(),clock,false);
            raw=new SurvivalGateway(new FabricGatewayWorld(h.getLevel()),(a,e,c)->controls(a,c),new SurvivalGateway.Limits(1,512,64,16,256,1,200,32),clock);
            gateway=new LeasedGateway(raw,leases,(request,correlation)->new LeasedGateway.Binding(owners.byRun(request,correlation),
                    List.of(new ResourceLeases.Demand(ResourceLeases.Resource.crops(world,source),((IntValue)request.request().arguments().get("amount")).value()),
                            new ResourceLeases.Demand(ResourceLeases.Resource.facility(world,destination),1))),()->h.getLevel().getGameTime(),1200,200,64,512);
            artifact=compile();var ref=artifact.descriptor().ref();
            var candidate=new VersionedSkillRepository.Candidate(ref,AdmissionStatus.ADMITTED,new Compatibility(ref,CompatibilityStatus.COMPATIBLE,List.of(),"gp18"),VersionedSkillRepository.Integrity.VERIFIED);
            var candidates=new CapabilityResolver.CandidateSource(){
                public VersionedSkillRepository.Page page(CapabilityId cap,String after,int size){return new VersionedSkillRepository.Page(after.isEmpty()?List.of(candidate):List.of(),"",true,1);}
                public Optional<ArtifactDescriptor> descriptor(ArtifactRef key){return key.equals(ref)?Optional.of(artifact.descriptor()):Optional.empty();}
                public long revision(){return 1;}
            };
            PrimitiveCatalog primitives=(id,version)->GatewayPrimitives.instance().find(id,version).filter(signature->physical||!signature.effects().contains(Effect.HARVEST));
            var resolver=new CapabilityResolver.Engine(id->Optional.of(CropDelivery.SPEC),primitives,candidates,candidates,CapabilityResolver.cropDeliverySupport(),
                    this::controls,(a,key,c)->controls(a,c),(a,e,c)->controls(a,c),CapabilityResolver.cropPrerequisites(),()->h.getLevel().getServer().overworld().getGameTime(),CapabilityResolver.Limits.defaults());
            prerequisites=new FabricPrerequisiteRuntime(h.getLevel().getServer(),jobs,leases,resolver,clock);
            var bodies=new BoundedSkillExecutor.ArtifactSource(){
                public Optional<SkillArtifact> body(ArtifactRef key){return key.equals(ref)?Optional.of(artifact):Optional.empty();}
                public Optional<AdmissionRecord> admission(ArtifactRef key){return key.equals(ref)?Optional.of(new AdmissionRecord(ref,AdmissionStatus.ADMITTED,new EvidenceRef("test-only","gp18:1","native"),null,1)):Optional.empty();}
                public Optional<Compatibility> compatibility(ArtifactRef key){return Optional.of(candidate.compatibility());}
            };
            executor=new BoundedSkillExecutor(bodies,id->Optional.of(CropDelivery.SPEC),GatewayPrimitives.instance(),(a,key,c)->controls(a,c),
                    (permit,request,limit)->{throw new AssertionError("GP-18 models/planners/trials forbidden");},new BoundedSkillExecutor.ControlPolicy(){
                        public boolean mayInspect(TrustedContext caller,TrustedContext c,UUID id){return caller.equals(c);}
                        public boolean mayCancel(TrustedContext caller,TrustedContext c,UUID id){return caller.equals(c);}
                    },gateway,gateway::releaseRun,clock,()->h.getLevel().getGameTime(),new BoundedSkillExecutor.Settings(8,16,32,64));
        }
        SkillArtifact compile() {
            var actor=Map.<String,Object>of("actor",Map.of("param","actor"));var area=Map.<String,Object>of("actor",Map.of("param","actor"),"source",Map.of("param","source"));
            var target=Map.<String,Object>of("actor",Map.of("param","actor"),"destination",Map.of("param","destination"));
            var cycle=List.of(call(Operation.MOVE_TO_SOURCE,area,"approached"),call(Operation.HARVEST_NEXT_WHEAT,area,"harvested"),
                    Map.of("op","repeat","count",Map.of("int",30),"body",List.of(call(Operation.OBSERVE_INVENTORY,actor,"stock"))),call(Operation.PICKUP_TRACKED_WHEAT,actor,"picked"),
                    call(Operation.MOVE_TO_DESTINATION,target,"arrived"),call(Operation.TRANSFER_WHEAT,Map.of("actor",Map.of("param","actor"),"destination",Map.of("param","destination"),"amount",Map.of("int",1)),"delivered"));
            String ir=new Gson().toJson(Map.of("schema",1,"capability",CropDelivery.ID.name(),"capabilityVersion",1,"dependencies",List.of(),"body",List.of(
                    Map.of("op","repeat","count",Map.of("param","amount"),"body",cycle),Map.of("op","result","value",Map.of("param","amount")))));
            var result=new SkillCompiler(id->Optional.of(CropDelivery.SPEC),GatewayPrimitives.instance(),ref->Optional.empty()).compile(ir);
            require(result instanceof SkillCompiler.Success,"Existing P0 fixture IR failed compilation");return ((SkillCompiler.Success)result).skill().artifact();
        }
        Map<String,Object> call(Operation op,Map<String,Object> args,String into){return Map.of("op","call","kind","primitive","id",op.signature().id(),"version",1,"fingerprint",op.signature().fingerprint(),"args",args,"into",into);}
        UUID create(int quantity){UUID id=UUID.randomUUID();var request=new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(actor),"source",new AreaValue(source),"destination",new ContainerValue(destination),"amount",new IntValue(quantity)));
            var budget=new Budgets.Limits(Map.of(Budgets.Kind.CALLS,512L,Budgets.Kind.INSTRUCTIONS,8192L,Budgets.Kind.OBSERVATIONS,4096L,Budgets.Kind.TRAVEL_BLOCKS,128L,
                    Budgets.Kind.ATTEMPTED_EFFECTS,64L,Budgets.Kind.COMMITTED_EFFECTS,64L,Budgets.Kind.ELAPSED_TICKS,1800L),clock.millis()+600_000);
            require(jobs.create(id,id,request,owner,new ObservationRef(UUID.randomUUID(),h.getLevel().getGameTime(),source.dimension()),budget,List.of()).accepted(),"Actual job creation refused");return id;}
        Jobs.Job job(UUID id){return jobs.query(id,owner);}
        Need need(UUID id,String item,int quantity){var j=job(id);return new Need(id,j.guard(),j.request(),item,quantity,AllocationPolicy.ATTRIBUTABLE_WHEAT);}
        void step(){if(phase==40)return;long now=h.getLevel().getGameTime();if(citizens!=null){citizens.tick();jobs.tick();leases.tick();raw.tick();gateway.tick();}
            if(opening.isDone() || phase!=0)advance(now);}
        void advance(long now){switch(phase){
            case 0->{compose();if(warm){phase=30;return;}var result=citizens.enroll(worker.getUUID(),source.dimension(),owner);require(result.accepted(),"Actual enrollment refused");actor=result.citizen().actor();phase=1;}
            case 1->{require(citizens.ready()&&jobs.ready()&&leases.ready()&&worker.onGround(),"Await real owner/actor acknowledgements");partial=create(5);phase=2;}
            case 2->{require(jobs.ready(),"Await partial demand");var result=prerequisites.resolve(List.of(need(partial,"minecraft:wheat",5)),owner,List.of());
                require(result.admission().status()==ResolutionStatus.RESOLVED,"Partial native proposal refused: "+result);
                require(result.proposal().nodes().size()==1&&result.proposal().nodes().getFirst().output()==3,"Existing two units did not leave exactly three");phase=3;}
            case 3->{require(jobs.ready(),"Await deficit admission");require(jobs.cancel(partial,job(partial).guard(),owner).accepted(),"Preview cancellation refused");phase=4;}
            case 4->{require(jobs.ready(),"Await preview cancellation");require(job(partial).state()==Jobs.State.CANCELLED&&stock()==2,"Preview performed effects");unsupported=create(1);phase=5;}
            case 5->{require(jobs.ready(),"Await unsupported probe");var catalog=FabricPrerequisiteCatalog.capture(h.getLevel().getServer(),List.of("minecraft:bread"));
                require(catalog.complete()&&catalog.recipes().size()==1&&catalog.recipes().getFirst().output().equals("minecraft:bread"),"Actual installed bread data was not captured");
                var result=prerequisites.resolve(List.of(need(unsupported,"minecraft:bread",1)),owner,List.of("minecraft:bread"));
                require(result.admission().status()==ResolutionStatus.UNSUPPORTED_RUNTIME&&result.admission().producers().isEmpty(),"Recipe invented crafting physics");phase=6;}
            case 6->{require(jobs.ready(),"Await unsupported billing");box().clearContent();physical=false;var result=prerequisites.resolve(List.of(need(unsupported,"minecraft:wheat",1)),owner,List.of());
                require(result.admission().status()==ResolutionStatus.UNSUPPORTED_RUNTIME,"Removing physical harvest support did not block: "+result);physical=true;phase=7;}
            case 7->{require(jobs.ready(),"Await physics refusal");first=create(3);phase=8;}
            case 8->{require(jobs.ready(),"Await root A");second=create(2);phase=9;}
            case 9->{require(jobs.ready(),"Await root B");var result=prerequisites.resolve(List.of(need(first,"minecraft:wheat",3),need(second,"minecraft:wheat",2)),owner,List.of());
                require(result.admission().status()==ResolutionStatus.RESOLVED&&result.admission().producers().size()==1,"Compatible roots did not coalesce: "+result);
                shared=result.proposal();producer=result.admission().producers().getFirst();require(shared.nodes().getFirst().output()==5,"Shared production quantity changed");phase=10;}
            case 10->{require(jobs.ready(),"Await shared job transaction");var j=job(producer);var ref=artifact.descriptor().ref();require(jobs.assign(producer,j.guard(),UUID.randomUUID(),actor,ref,List.of(ref),j.allowance(),owner).accepted(),"Producer assignment refused");phase=11;}
            case 11->{require(jobs.ready(),"Await native execution pin");var a=job(producer).current();var execution=a.executions().getFirst();worker.setNoAi(false);
                var started=executor.startAdmitted(a.bound(),execution.artifact(),new RunCorrelation(execution.runId(),execution.artifact(),null),new Budgets.ExecutionLimits(a.allowance(),10_000),new Budgets.Ledger(a.allowance(),clock));
                require(started instanceof BoundedSkillExecutor.Started,"Native producer refused start: "+started);run=((BoundedSkillExecutor.Started)started).run();phase=12;}
            case 12->{var progress=run.tick(owner);if(progress.phase()!=BoundedSkillExecutor.Phase.TERMINAL)return;var sum=progress.summary();require(sum.outcome().status()==ExecutionStatus.SUCCEEDED,"Real P0 production failed: "+sum.outcome());
                var j=job(producer);var a=j.current();require(jobs.recordExecutionResult(producer,j.guard(),new Jobs.Report(a.id(),a.generation(),sum.committedEffects(),sum.usage(),sum.receipts(),sum.outcome()),owner).accepted(),"Actual receipts refused");phase=13;}
            case 13->{require(jobs.ready()&&leases.ready(),"Await real terminal receipts");require(stock()==5&&job(producer).fulfilled()==5,"Physical wheat or attributable delivery not conserved");
                var deposits=job(producer).current().receipts().stream().filter(r->r.stage()==CropDelivery.Stage.DEPOSIT).toList();var queue=new ArrayList<Credit>();var used=new HashMap<UUID,Long>();
                for(var intent:shared.allocations()){long remaining=intent.quantity();for(var receipt:deposits){long free=receipt.wheat()-used.getOrDefault(receipt.receiptId(),0L);long amount=Math.min(remaining,free);
                    if(amount>0){queue.add(new Credit(intent,receipt.receiptId(),amount));used.merge(receipt.receiptId(),amount,Math::addExact);remaining-=amount;}}require(remaining==0,"Insufficient actual deposited receipts");}
                credits=List.copyOf(queue);phase=14;}
            case 14->{require(jobs.ready(),"Await receipt allocation");if(creditIndex==credits.size()){phase=15;return;}var credit=credits.get(creditIndex++);var attempt=job(producer).current();
                require(prerequisites.allocate(credit.intent(),credit.quantity(),attempt.id(),credit.receipt(),job(credit.intent().demand()).guard(),owner).accepted(),"Existing owner refused unit credit");}
            case 15->{require(jobs.ready()&&leases.ready(),"Await final owned allocations");require(job(first).state()==Jobs.State.SUCCEEDED&&job(second).state()==Jobs.State.SUCCEEDED,"Root allocations incomplete");
                require(jobs.snapshot().allocations().stream().mapToLong(Jobs.Allocation::quantity).sum()==5,"Delivered unit credited twice");
                var before=jobs.snapshot();var credit=credits.getFirst();require(prerequisites.allocate(credit.intent(),credit.quantity(),job(producer).current().id(),credit.receipt(),job(credit.intent().demand()).guard(),owner).code()==Jobs.Code.DUPLICATE,"Allocation replay changed ownership");require(before.equals(jobs.snapshot()),"Replay changed durable state");save();phase=16;}
            case 16->{require(closing.isDone(),"Await actual persistence close");closing.join();accepted();phase=40;}
            case 30->{require(citizens.ready()&&jobs.ready()&&leases.ready(),"Await recovered owners");require(((Number)stores.marker().get("process")).longValue()!=ProcessHandle.current().pid(),"Warm phase reused cold JVM");
                require(stock()==5&&job(producer).fulfilled()==5&&job(first).state()==Jobs.State.SUCCEEDED&&job(second).state()==Jobs.State.SUCCEEDED,"Restart lost physical stock or credits");
                require(jobs.snapshot().allocations().stream().mapToLong(Jobs.Allocation::quantity).sum()==5,"Restart duplicated credit");
                require(jobs.snapshot().jobs().size()==coldJobCount&&leases.snapshot().leases().stream().noneMatch(l->l.state()==ResourceLeases.State.ACTIVE),"Restart reconstructed executable scratch/claims");
                var before=jobs.snapshot();var replay=prerequisites.resolve(List.of(need(partial,"minecraft:wheat",5)),owner,List.of());require(replay.admission().status()!=ResolutionStatus.RESOLVED&&before.equals(jobs.snapshot()),"Cancelled responsibility reactivated");quietUntil=now+30;phase=31;}
            case 31->{require(now>=quietUntil,"Observe quiet saved world");require(stock()==5&&jobs.snapshot().jobs().size()==coldJobCount,"Reload automatically replayed work");save();phase=16;}
        }}
        void save(){var marker=new LinkedHashMap<String,Object>();if(!warm){marker.put("world",world.toString());marker.put("principal",owner.principal().id().toString());marker.put("domain",owner.scope().domainId().toString());
                marker.put("x",origin.getX());marker.put("y",origin.getY());marker.put("z",origin.getZ());marker.put("citizen",actor.citizenId().toString());marker.put("entity",actor.entityId().toString());
                marker.put("partial",partial.toString());marker.put("first",first.toString());marker.put("second",second.toString());marker.put("producer",producer.toString());marker.put("jobs",jobs.snapshot().jobs().size());marker.put("process",ProcessHandle.current().pid());}
            var proof=Map.<String,Object>of("schema",1,"architecture","0.5","phase",warm?"warm":"cold","stock",stock(),"allocated",jobs.snapshot().allocations().stream().mapToLong(Jobs.Allocation::quantity).sum(),
                    "producer",producer.toString(),"modelCalls",0,"jobs",jobs.snapshot().jobs().size(),"newPrimitives",0);
            closing=async(()->{try{if(!warm)Files.writeString(root.resolve("restart.json"),StrictJson.canonical(marker)+"\n");Files.writeString(root.resolve(warm?"warm.json":"cold.json"),StrictJson.canonical(proof)+"\n");
                stores.leases().close();stores.jobs().close();stores.identities().close();return null;}catch(Exception e){throw new CompletionException(e);}});closing.whenComplete((ignored,error)->io.shutdown());}
        void accepted(){System.out.println("IMP-018 GP-18 accepted phase="+(warm?"warm":"cold")+" stock=5 allocated=5 modelCalls=0 newPrimitives=0 noReplay=true");}
        void require(boolean condition,String message){h.assertTrue(condition,message);}
        static UUID id(Map<String,Object> m,String name){return UUID.fromString((String)m.get(name));}
        static int number(Map<String,Object> m,String name){return ((Number)m.get(name)).intValue();}
    }
}
