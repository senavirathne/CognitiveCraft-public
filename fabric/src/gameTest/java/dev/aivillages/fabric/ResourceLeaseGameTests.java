package dev.aivillages.fabric;

import com.google.gson.Gson;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.ResourceLeases.*;
import dev.aivillages.core.kernel.Outcomes.*;
import dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static dev.aivillages.core.kernel.Budgets.Kind;

/** Independent two-client GP-13 driver; real owners, gateway, actors and disk, with no model port. */
public final class ResourceLeaseGameTests {
    private static final String MARKER="lease-restart.json";
    @GameTest(maxTicks=2400,padding=32)
    public void fivePhysicalUnitsFenceTwoClientsAndRecoverInactiveAfterRestart(GameTestHelper h) {
        String phase=System.getenv("COGNITIVECRAFT_LEASE_RESTART");
        h.assertTrue("cold".equals(phase)||"warm".equals(phase),"Explicit cold/warm lease process required");
        Driver d=new Driver(h,"warm".equals(phase));
        h.failIfEver(()->{if(d.fatal!=null)h.fail(d.fatal);});
        h.succeedWhen(()->{d.step();h.assertTrue(d.phase==30,"Waiting for lease fixture phase="+d.phase);});
    }
    private record Stores(CitizenIdentityStore citizens,JobJournal jobs,ResourceLeaseJournal leases,
                          CitizenRegistry.Snapshot identities,Jobs.Snapshot tasks,Snapshot claims,Map<String,Object> proof) {}
    private static final class Driver {
        final GameTestHelper h;
        final boolean warm;
        final Clock clock=Clock.systemUTC();
        final ExecutorService io=Executors.newSingleThreadExecutor();
        final Path root;
        final CompletableFuture<Stores> opening;
        final long startedNanos=System.nanoTime();
        Stores stores;
        CitizenRegistry citizens;
        JobLifecycleStore jobs;
        ResourceLeaseService leases;
        JobLeaseOwners owners;
        SurvivalGateway raw;
        LeasedGateway gateway;
        BoundedSkillExecutor executor;
        BoundedSkillExecutor.Run run;
        SkillArtifact artifact;
        TrustedContext a,b;
        ActorRef actorA,actorB;
        Villager first,second;
        UUID world,idA,idB,idC,idD;
        BlockPos origin;
        Cuboid source;
        ContainerRef destination,auxiliary;
        Owner ownerC,ownerD;
        Result claim;
        List<Ref> old;
        CompletableFuture<Void> closing;
        long quietUntil,expires,peakHeap,maxSlice;
        int phase,lastReported=-1;
        boolean revoked,conflict;
        String fatal;
        Driver(GameTestHelper h,boolean warm) {
            this.h=h;this.warm=warm;
            root=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("cognitivecraft-resource-lease-fixture");
            if(!warm) {
                world=UUID.randomUUID();a=context(world);b=context(world);
                origin=h.absolutePos(new BlockPos(8,0,8));
                BootstrapGameTests.forceFixtureChunks(h,origin);
                for(int x=0;x<10;x++)for(int z=0;z<9;z++)block(x,0,z,Blocks.STONE.defaultBlockState());
                for(int x=2;x<=3;x++)for(int z=2;z<=4;z++) {
                    block(x,0,z,Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
                    if(x!=3||z!=4)block(x,1,z,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
                }
                for(int x=1;x<=4;x++)for(int z=1;z<=5;z++)
                    if((x==1||x==4||z==1||z==5)&&!(x==4&&z==4))block(x,1,z,Blocks.STONE.defaultBlockState());
                block(4,1,4,Blocks.CHEST.defaultBlockState());block(7,1,0,Blocks.CHEST.defaultBlockState());
                first=spawn(3,3);second=spawn(2,4);geometry();
                Map<String,Object> initial=Map.of("world",world.toString());
                opening=async(()->open(initial));
            } else opening=async(()-> {
                try{return open(StrictJson.object(Files.readString(root.resolve(MARKER))));}
                catch(Exception e){throw new CompletionException(e);}
            });
        }
        <T> CompletableFuture<T> async(Supplier<T> work){return CompletableFuture.supplyAsync(work,io);}
        Stores open(Map<String,Object> proof) {
            try {
                UUID id=UUID.fromString((String)proof.get("world"));
                var c=CitizenIdentityStore.open(root,id);var j=JobJournal.open(root,id);var l=ResourceLeaseJournal.open(root,id);
                return new Stores(c,j,l,c.snapshot(),j.snapshot(),l.snapshot(),proof);
            }catch(Exception e){throw new CompletionException(e);}
        }
        static TrustedContext context(UUID world){return new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(world,UUID.randomUUID()));}
        void geometry(){
            String dim=h.getLevel().dimension().identifier().toString();
            source=new Cuboid(dim,origin.getX()+2,origin.getY()+1,origin.getZ()+2,origin.getX()+3,origin.getY()+1,origin.getZ()+4);
            destination=container(4,1,4);auxiliary=container(7,1,0);
        }
        ContainerRef container(int x,int y,int z){return new ContainerRef(h.getLevel().dimension().identifier().toString(),origin.getX()+x,origin.getY()+y,origin.getZ()+z);}
        void block(int x,int y,int z,net.minecraft.world.level.block.state.BlockState state){h.getLevel().setBlockAndUpdate(origin.offset(x,y,z),state);}
        Villager spawn(int x,int z){
            BlockPos p=origin.offset(x,1,z);
            BlockPos rel=p.subtract(h.absolutePos(BlockPos.ZERO));
            Villager v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,rel.getX(),rel.getY(),rel.getZ());v.setNoAi(true);v.setPersistenceRequired();return v;
        }
        boolean control(ActorRef actor,TrustedContext caller){return !revoked&&citizens.controls(actor,caller);}
        void compose() {
            stores=opening.join();
            if(warm) {
                var p=stores.proof();world=UUID.fromString((String)p.get("world"));
                origin=new BlockPos(number(p,"x"),number(p,"y"),number(p,"z"));
                BootstrapGameTests.forceFixtureChunks(h,origin);geometry();
                a=readContext(p,"a");b=readContext(p,"b");
                idA=id(p,"jobA");idB=id(p,"jobB");idC=id(p,"jobC");idD=id(p,"jobD");
                old=List.of(new Ref(id(p,"lease"),id(p,"epoch"),((Number)p.get("generation")).longValue()));
            }
            citizens=new CitizenRegistry(stores.identities(),(expected,next)->async(()-> {
                try{return stores.citizens().replace(expected,next);}catch(Exception e){throw new CompletionException(e);}
            }),CitizenRegistry.privateAddresses(),clock,stores.citizens().readOnly());
            jobs=new JobLifecycleStore(stores.tasks(),(expected,next)->async(()-> {
                try{return stores.jobs().replace(expected,next);}catch(Exception e){throw new CompletionException(e);}
            }),JobLifecycleStore.privateJobs(this::control),c->{},id->Optional.of(CropDelivery.SPEC),
                    new RequestEnvironment() {
                        public boolean enrolled(ActorRef actor,TrustedContext caller){return control(actor,caller);}
                        public boolean loaded(Cuboid area,ObservationRef obs){return area.equals(source);}
                        public boolean available(ContainerRef c,ObservationRef obs){return c.equals(destination);}
                    },clock,Jobs.Settings.defaults(),stores.jobs().readOnly());
            owners=new JobLeaseOwners(jobs,clock);
            leases=new ResourceLeaseService(stores.claims(),(expected,next)->async(()-> {
                try{return stores.leases().replace(expected,next);}catch(Exception e){throw new CompletionException(e);}
            }),owners,new FabricResourceLeaseWorld(h.getLevel().getServer()),Settings.production(),
                    ()->h.getLevel().getServer().overworld().getGameTime(),clock,stores.leases().readOnly());
            raw=new SurvivalGateway(new FabricGatewayWorld(h.getLevel()),
                    (actor,effect,caller)->control(actor,caller),new SurvivalGateway.Limits(1,512,64,16,256,1,200,32),clock);
            gateway=new LeasedGateway(raw,leases,(request,correlation)->new LeasedGateway.Binding(owners.byRun(request,correlation),
                    List.of(new Demand(Resource.crops(world,source),((IntValue)request.request().arguments().get("amount")).value()),
                            new Demand(Resource.facility(world,destination),1))),
                    ()->h.getLevel().getGameTime(),1200,200,64,512);
            artifact=compile();
            var ref=artifact.descriptor().ref();
            var catalog=new BoundedSkillExecutor.ArtifactSource() {
                public Optional<SkillArtifact> body(ArtifactRef key){return key.equals(ref)?Optional.of(artifact):Optional.empty();}
                public Optional<AdmissionRecord> admission(ArtifactRef key){return key.equals(ref)?Optional.of(new AdmissionRecord(ref,
                        AdmissionStatus.ADMITTED,new EvidenceRef("test-only","lease-fixture:1","two-client"),null,1)):Optional.empty();}
                public Optional<Compatibility> compatibility(ArtifactRef key){return key.equals(ref)?Optional.of(
                        new Compatibility(ref,CompatibilityStatus.COMPATIBLE,List.of(),"fixture")):Optional.empty();}
            };
            executor=new BoundedSkillExecutor(catalog,id->Optional.of(CropDelivery.SPEC),GatewayPrimitives.instance(),
                    (actor,key,caller)->control(actor,caller),(permit,request,limits)->{throw new AssertionError("Models and trials disabled");},
                    new BoundedSkillExecutor.ControlPolicy() {
                        public boolean mayInspect(TrustedContext caller,TrustedContext owner,UUID run){return caller.equals(owner);}
                        public boolean mayCancel(TrustedContext caller,TrustedContext owner,UUID run){return caller.equals(owner);}
                    },gateway,gateway::releaseRun,clock,()->h.getLevel().getGameTime(),new BoundedSkillExecutor.Settings(8,16,32,64));
        }
        SkillArtifact compile() {
            var actor=Map.<String,Object>of("actor",Map.of("param","actor"));
            List<Object> cycle=List.of(call(Operation.HARVEST_NEXT_WHEAT,Map.of("actor",Map.of("param","actor"),"source",Map.of("param","source")),"harvested"),
                    Map.of("op","repeat","count",Map.of("int",30),"body",List.of(call(Operation.OBSERVE_INVENTORY,actor,"stock"))),
                    call(Operation.PICKUP_TRACKED_WHEAT,actor,"picked"),
                    call(Operation.TRANSFER_WHEAT,Map.of("actor",Map.of("param","actor"),"destination",Map.of("param","destination"),"amount",Map.of("int",1)),"delivered"));
            String text=new Gson().toJson(Map.of("schema",1,"capability",CropDelivery.ID.name(),"capabilityVersion",1,
                    "dependencies",List.of(),"body",List.of(Map.of("op","repeat","count",Map.of("param","amount"),"body",cycle),
                            Map.of("op","result","value",Map.of("param","amount")))));
            var result=new SkillCompiler(id->Optional.of(CropDelivery.SPEC),GatewayPrimitives.instance(),ref->Optional.empty()).compile(text);
            require(result instanceof SkillCompiler.Success,"Test-only IR did not compile: "+result);
            return ((SkillCompiler.Success)result).skill().artifact();
        }
        Map<String,Object> call(Operation op,Map<String,Object> args,String into){
            return Map.of("op","call","kind","primitive","id",op.signature().id(),"version",1,
                    "fingerprint",op.signature().fingerprint(),"args",args,"into",into);
        }
        Budgets.Limits budget(){return new Budgets.Limits(Map.of(Kind.CALLS,512L,Kind.INSTRUCTIONS,4096L,Kind.OBSERVATIONS,4096L,
                Kind.TRAVEL_BLOCKS,128L,Kind.ATTEMPTED_EFFECTS,64L,Kind.COMMITTED_EFFECTS,64L,Kind.ELAPSED_TICKS,1200L),clock.millis()+120_000);}
        CapabilityRequest request(ActorRef actor){return new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(actor),
                "source",new AreaValue(source),"destination",new ContainerValue(destination),"amount",new IntValue(3)));}
        UUID create(ActorRef actor,TrustedContext caller) {
            UUID id=UUID.randomUUID();var change=jobs.create(id,id,request(actor),caller,
                    new ObservationRef(UUID.randomUUID(),h.getLevel().getGameTime(),actor.dimension()),budget(),List.of());
            require(change.accepted(),"Actual job creation failed "+change);return id;
        }
        Jobs.Job job(UUID id,TrustedContext caller){return jobs.query(id,caller);}
        void assign(UUID id,TrustedContext caller) {
            var job=job(id,caller);var ref=artifact.descriptor().ref();
            require(jobs.assign(id,job.guard(),UUID.randomUUID(),job.responsible(),ref,List.of(ref),job.allowance(),caller).accepted(),"Actual assignment failed");
        }
        void start(UUID id,TrustedContext caller) {
            var a=job(id,caller).current();var reference=a.executions().getLast();
            var started=executor.startAdmitted(a.bound(),reference.artifact(),new RunCorrelation(reference.runId(),reference.artifact(),null),
                    new Budgets.ExecutionLimits(a.allowance(),10_000),new Budgets.Ledger(a.allowance(),clock));
            require(started instanceof BoundedSkillExecutor.Started,"Actual executor refused "+started);run=((BoundedSkillExecutor.Started)started).run();
        }
        void report(UUID id,TrustedContext caller,BoundedSkillExecutor.Progress progress) {
            var job=job(id,caller);var sum=progress.summary();var attempt=job.current();
            require(jobs.recordExecutionResult(id,job.guard(),new Jobs.Report(attempt.id(),attempt.generation(),
                    sum.committedEffects(),sum.usage(),sum.receipts(),sum.outcome()),caller).accepted(),"Actual result refused");
        }
        void step() {
            if(phase==30)return;
            java.util.concurrent.locks.LockSupport.parkNanos(phase==0||warm&&phase==20?50_000_000:1_000_000);
            if(lastReported!=phase){System.out.println("IMP-012 fixture phase="+phase+" warm="+warm);lastReported=phase;}
            long before=System.nanoTime();
            if(citizens!=null){citizens.tick();jobs.tick();raw.tick();leases.tick();gateway.tick();}
            maxSlice=Math.max(maxSlice,System.nanoTime()-before);
            Runtime memory=Runtime.getRuntime();peakHeap=Math.max(peakHeap,memory.totalMemory()-memory.freeMemory());
            advance();
        }
        void advance() {
            switch(phase) {
                case 0 -> {
                    require(opening.isDone(),"Opening real stores");compose();
                    if(warm){phase=20;return;}
                    var c=citizens.enroll(first.getUUID(),source.dimension(),a);require(c.accepted(),"Enrollment A refused");actorA=c.citizen().actor();phase=1;
                }
                case 1 -> {require(citizens.ready()&&leases.ready(),"Acknowledging enrollment and runtime epoch");
                    var c=citizens.enroll(second.getUUID(),source.dimension(),b);require(c.accepted(),"Enrollment B refused");actorB=c.citizen().actor();phase=2;}
                case 2 -> {require(citizens.ready(),"Acknowledging enrollment B");require(first.onGround()&&second.onGround(),"Waiting for real actors to tick");
                    require(citizens.controls(actorA,a)&&citizens.controls(actorB,b),"Two actual enrolled workers required");idA=create(actorA,a);phase=3;}
                case 3 -> {require(jobs.ready(),"Acknowledging job A");idB=create(actorB,b);phase=4;}
                case 4 -> {require(jobs.ready(),"Acknowledging job B");assign(idA,a);phase=5;}
                case 5 -> {require(jobs.ready(),"Acknowledging exact execution pin");start(idA,a);phase=6;}
                case 6 -> {
                    var progress=run.tick(a);
                    if(!conflict&&leases.ready()&&leases.snapshot().leases().stream().anyMatch(l->l.state()==State.ACTIVE)) {
                        var j=job(idB,b);var denied=leases.acquire(new Owner(idB,j.generation()),List.of(new Demand(Resource.crops(world,source),3)),1200,b);
                        require(denied.code()==Code.CONFLICT&&denied.available()==2&&denied.leases().isEmpty(),"Five units admitted two claims of three: "+denied);
                        var privateRef=leases.snapshot().leases().getFirst().ref();
                        require(leases.validate(privateRef,b).reason()==Reason.AUTHORITY_DENIED,"Foreign lease became authority");conflict=true;
                    }
                    long delivered=progress.summary().receipts().stream().filter(r->r.stage()==CropDelivery.Stage.DEPOSIT).mapToLong(CropDelivery.CropReceipt::quantity).sum();
                    if(delivered==2) {
                        require(conflict,"Second client was never checked");var stopped=run.cancel(a);report(idA,a,stopped);phase=7;return;
                    }
                    require(progress.phase()!=BoundedSkillExecutor.Phase.TERMINAL,"Worker A terminated early "+progress.summary().outcome());
                }
                case 7 -> {require(jobs.ready()&&leases.ready(),"Acknowledging cancellation and release");
                    require(job(idA,a).state()==Jobs.State.CANCELLED&&job(idA,a).fulfilled()==2,"Cancellation lost two real deliveries");
                    require(stock(destination)==2&&mature()==3,"Cancellation minted or lost wheat");assign(idB,b);phase=8;}
                case 8 -> {require(jobs.ready(),"Acknowledging second assignment");start(idB,b);phase=9;}
                case 9 -> {var progress=run.tick(b);if(progress.phase()==BoundedSkillExecutor.Phase.TERMINAL){
                    require(progress.summary().outcome().status()==ExecutionStatus.SUCCEEDED,"Worker B failed "+progress.summary().outcome());report(idB,b,progress);phase=10;}}
                case 10 -> {require(jobs.ready()&&leases.ready(),"Acknowledging second completion");
                    require(job(idB,b).fulfilled()==3&&stock(destination)==5&&mature()==0,"Five physical units were not conserved");
                    idC=create(actorA,a);phase=11;}
                case 11 -> {require(jobs.ready(),"Acknowledging ready job C");idD=create(actorB,b);phase=12;}
                case 12 -> {require(jobs.ready(),"Acknowledging ready job D");ownerC=new Owner(idC,0);ownerD=new Owner(idD,0);
                    box(auxiliary).setItem(0,new ItemStack(Items.WHEAT,5));claim=acquire(ownerC,a,Resource.stock(world,auxiliary),3,1200);phase=13;}
                case 13 -> {require(leases.ready(),"Acknowledging real stock claim");claim=leases.group(claim.group(),ownerC,a);
                    require(claim.usable(),"Stock claim not usable");var denied=leases.acquire(ownerD,List.of(new Demand(Resource.stock(world,auxiliary),3)),1200,b);
                    require(denied.code()==Code.CONFLICT&&denied.available()==2,"Container stock overcommitted");
                    box(auxiliary).setItem(0,new ItemStack(Items.WHEAT,2));
                    require(leases.validate(claim.leases().getFirst(),a).reason()==Reason.RESOURCE_MISSING,"Player stock removal ignored");phase=14;}
                case 14 -> {require(leases.ready(),"Acknowledging stock revocation");box(auxiliary).setItem(0,new ItemStack(Items.WHEAT,5));
                    claim=acquire(ownerC,a,Resource.stock(world,auxiliary),3,1200);phase=15;}
                case 15 -> {require(leases.ready(),"Acknowledging replacement probe");claim=leases.group(claim.group(),ownerC,a);
                    block(7,1,0,Blocks.AIR.defaultBlockState());block(7,1,0,Blocks.CHEST.defaultBlockState());box(auxiliary).setItem(0,new ItemStack(Items.WHEAT,5));
                    require(leases.validate(claim.leases().getFirst(),a).reason()==Reason.TARGET_INVALID,"Identical replacement container retained old lease identity");phase=16;}
                case 16 -> {require(leases.ready(),"Acknowledging replacement revocation");claim=acquire(ownerC,a,Resource.space(world,source),1,100);phase=17;}
                case 17 -> {require(leases.ready(),"Acknowledging finite spatial claim");claim=leases.group(claim.group(),ownerC,a);
                    expires=leases.snapshot().leases().stream().filter(l->l.id().equals(claim.leases().getFirst().id())).findFirst().orElseThrow().expires();phase=18;}
                case 18 -> {require(h.getLevel().getGameTime()>=expires,"Waiting for original 100-tick expiry");
                    require(leases.validate(claim.leases().getFirst(),a).code()==Code.EXPIRED,"Boundary lease remained active");require(stock(destination)==5,"Expiry changed actual wheat");phase=19;}
                case 19 -> {require(leases.ready(),"Acknowledging expiry");claim=acquire(ownerC,a,Resource.space(world,source),1,1200);phase=21;}
                case 21 -> {require(leases.ready(),"Acknowledging authority probe");claim=leases.group(claim.group(),ownerC,a);revoked=true;
                    require(leases.validate(claim.leases().getFirst(),a).reason()==Reason.AUTHORITY_DENIED,"Lost current control retained lease authority");
                    phase=22;}
                case 22 -> {require(leases.ready()&&leases.snapshot().leases().stream().noneMatch(l->l.state()==State.ACTIVE),
                            "Acknowledging bounded authority cleanup");revoked=false;assign(idD,b);phase=23;}
                case 23 -> {require(jobs.ready(),"Acknowledging interrupted execution fixture");claim=acquire(ownerC,a,Resource.space(world,source),1,1200);phase=24;}
                case 24 -> {require(leases.ready(),"Acknowledging saved active claim");claim=leases.group(claim.group(),ownerC,a);old=claim.leases();
                    require(old.size()==1,"Expected one saved reference");saveAndClose(false);phase=25;}
                case 25 -> {require(closing.isDone(),"Closing publication owners");closing.join();accepted(false);phase=30;}
                case 20 -> {
                    require(citizens.ready()&&jobs.ready()&&leases.ready(),"Acknowledging conservative restart");
                    require(!id(stores.proof(),"epoch").equals(leases.snapshot().epoch()),"Runtime epoch reused after Minecraft restart");
                    require(leases.snapshot().leases().stream().noneMatch(l->l.state()==State.ACTIVE),"Restored lease was executable");
                    require(leases.validate(old.getFirst(),a).code()==Code.STALE,"Old epoch callback remained authority");
                    require(job(idA,a).state()==Jobs.State.CANCELLED&&job(idA,a).fulfilled()==2,"Restart lost cancellation credit");
                    require(job(idB,b).state()==Jobs.State.SUCCEEDED&&job(idB,b).fulfilled()==3,"Restart lost success credit");
                    require(job(idC,a).state()==Jobs.State.READY&&job(idD,b).state()==Jobs.State.INTERRUPTED,"Restart dispatched ready or uncertain job");
                    try{jobs.query(idA,b);throw new AssertionError("Private job became shared");}catch(SecurityException expected){}
                    require(stock(destination)==5&&mature()==0,"Restart replayed physical work");quietUntil=h.getLevel().getGameTime()+20;phase=26;
                }
                case 26 -> {require(h.getLevel().getGameTime()>=quietUntil,"Quiet models-disabled restart");
                    require(stock(destination)==5&&mature()==0,"Quiet restart replayed wheat");saveAndClose(true);phase=27;}
                case 27 -> {require(closing.isDone(),"Closing warm owners");closing.join();accepted(true);phase=30;}
                default -> throw new AssertionError("Unknown fixture phase "+phase);
            }
        }
        Result acquire(Owner owner,TrustedContext caller,Resource resource,long quantity,long duration){
            Result result=leases.acquire(owner,List.of(new Demand(resource,quantity)),duration,caller);
            require(result.code()==Code.PENDING&&result.group()!=null,"Physical lease not pending "+result);return result;
        }
        Container box(ContainerRef c){return (Container)h.getLevel().getBlockEntity(new BlockPos(c.x(),c.y(),c.z()));}
        int stock(ContainerRef c){return box(c).countItem(Items.WHEAT);}
        int mature(){int count=0;for(int x=source.minX();x<=source.maxX();x++)for(int z=source.minZ();z<=source.maxZ();z++)
            if(h.getLevel().getBlockState(new BlockPos(x,source.minY(),z)).is(Blocks.WHEAT))count++;return count;}
        void saveAndClose(boolean warm) {
            var proof=new LinkedHashMap<String,Object>();
            if(!warm) {
                proof.put("world",world.toString());proof.put("x",origin.getX());proof.put("y",origin.getY());proof.put("z",origin.getZ());
                putContext(proof,"a",a);putContext(proof,"b",b);
                proof.put("jobA",idA.toString());proof.put("jobB",idB.toString());proof.put("jobC",idC.toString());proof.put("jobD",idD.toString());
                Ref ref=old.getFirst();proof.put("lease",ref.id().toString());proof.put("epoch",ref.epoch().toString());proof.put("generation",ref.generation());
                proof.put("process",ProcessHandle.current().pid());
            } else require(((Number)stores.proof().get("process")).longValue()!=ProcessHandle.current().pid(),"Warm phase reused cold JVM");
            var evidence=Map.<String,Object>of("schema",1,"architecture","0.3","leaseSchema",1,"warm",warm,"modelCalls",0,
                    "physicalWheat",stock(destination),"cancelledCredit",2,"succeededCredit",3,"peakHeapBytes",peakHeap,"maxOwnerSliceNanos",maxSlice);
            closing=async(()-> {
                try {
                    if(!warm)Files.writeString(root.resolve(MARKER),StrictJson.canonical(proof)+"\n");
                    Files.writeString(root.resolve(warm?"lease-warm-evidence.json":"lease-cold-evidence.json"),StrictJson.canonical(evidence)+"\n");
                    stores.leases().close();stores.jobs().close();stores.citizens().close();return null;
                }catch(Exception e){throw new CompletionException(e);}
            });closing.whenComplete((ignored,error)->io.shutdown());
        }
        void accepted(boolean warm){System.out.println("IMP-012 physical accepted warm="+warm+
                " scopes=2 workers=2 requests=3+3 initialStock=5 cancelledCredit=2 succeededCredit=3 physicalWheat=5"+
                " noReplay=true oldEpochFenced=true modelCalls=0 leaseSchema=1 architecture=0.3 elapsedNanos="+(System.nanoTime()-startedNanos));}
        static void putContext(Map<String,Object> p,String key,TrustedContext context){
            p.put(key+"Principal",context.principal().id().toString());p.put(key+"Domain",context.scope().domainId().toString());
        }
        static TrustedContext readContext(Map<String,Object> p,String key){
            return new TrustedContext(new PrincipalRef(id(p,key+"Principal")),new ScopeRef(id(p,"world"),id(p,key+"Domain")));
        }
        static UUID id(Map<String,Object> p,String key){return UUID.fromString((String)p.get(key));}
        static int number(Map<String,Object> p,String key){return ((Number)p.get(key)).intValue();}
        void require(boolean condition,String message){h.assertTrue(condition,message);}
    }
}
