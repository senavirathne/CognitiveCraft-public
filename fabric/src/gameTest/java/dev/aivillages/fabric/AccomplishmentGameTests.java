package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.*;
import dev.aivillages.core.kernel.Accomplishments.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** GP-21: two real citizens, registered P0 physics, authoritative Jobs, derived evidence and saved-world reload. */
public final class AccomplishmentGameTests {
    @GameTest(maxTicks=3000,padding=32)
    public void realKnownOutcomesAreCreditedOnceAndTitlesHaveNoAuthority(GameTestHelper h){run(h,false);}
    static void run(GameTestHelper h,boolean warm){
        h.assertTrue((warm?"warm":"cold").equals(System.getenv("COGNITIVECRAFT_ACCOMPLISHMENT_PHASE")),"Explicit GP-21 process phase required");
        var driver=new Driver(h,warm);h.succeedWhen(()->{driver.step();h.assertTrue(driver.phase==40,"Await GP-21 phase="+driver.phase);});
    }
    private record Stores(CitizenIdentityStore citizens,JobJournal jobs,ResourceLeaseJournal leases,AccomplishmentLedger ledger,Map<String,Object> marker){ }
    private static final class Driver {
        final GameTestHelper h;final boolean warm;final Clock clock=Clock.systemUTC();final Path root;
        final long processStarted=ManagementFactory.getRuntimeMXBean().getStartTime();
        final ExecutorService io=Executors.newSingleThreadExecutor();final CompletableFuture<Stores> opening;
        Stores stores;CitizenRegistry citizens;JobLifecycleStore jobs;ResourceLeaseService leases;AccomplishmentService evidence;
        SurvivalGateway raw;LeasedGateway gateway;BoundedSkillExecutor executor;BoundedSkillExecutor.Run run;SkillArtifact artifact;
        TrustedContext owner;UUID world;BlockPos origin;Cuboid small,wide;ContainerRef chest;
        final List<ActorRef> actors=new ArrayList<>();final List<Villager> villagers=new ArrayList<>();final List<UUID> outcomes=new ArrayList<>();
        UUID current;int phase,work;CompletableFuture<Void> closing;CompletableFuture<Map<String,Object>> inspecting;long quietUntil;
        Driver(GameTestHelper h,boolean warm){
            this.h=h;this.warm=warm;root=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("cognitivecraft-accomplishment-fixture");
            if(warm){opening=async(()->{try{return open(StrictJson.object(Files.readString(root.resolve("restart.json"))));}catch(Exception e){throw new CompletionException(e);}});return;}
            world=UUID.randomUUID();owner=new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(world,UUID.randomUUID()));
            origin=h.absolutePos(new BlockPos(8,0,8));BootstrapGameTests.forceFixtureChunks(h,origin);geometry();
            for(int x=0;x<9;x++)for(int z=0;z<8;z++)block(x,0,z,Blocks.STONE.defaultBlockState());
            for(int x=2;x<=4;x++)for(int z=2;z<=3;z++)if(x!=3||z!=3){
                block(x,0,z,Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
                block(x,1,z,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));}
            block(3,1,4,Blocks.CHEST.defaultBlockState());
            // Keep the idle worker outside the active run's tracked-drop pickup radius.
            // Another Minecraft actor taking a drop must still invalidate custody.
            for(int n=0;n<2;n++){var p=origin.offset(7,1,6+n).subtract(h.absolutePos(BlockPos.ZERO));
                var villager=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,p.getX()+0.5f,p.getY(),p.getZ()+0.5f);villager.setPersistenceRequired();villagers.add(villager);}
            opening=async(()->open(Map.of("world",world.toString())));
        }
        <T>CompletableFuture<T> async(Supplier<T> task){return CompletableFuture.supplyAsync(task,io);}
        Stores open(Map<String,Object> marker){try{
            var world=UUID.fromString((String)marker.get("world"));var citizens=CitizenIdentityStore.open(root,world);var jobs=JobJournal.open(root,world);var leases=ResourceLeaseJournal.open(root,world);
            var ledger=AccomplishmentLedger.open(root,world,new JobOutcomeSource(jobs::snapshot,citizens::snapshot),new RetentionRoots());return new Stores(citizens,jobs,leases,ledger,marker);
        }catch(Exception e){throw new CompletionException(e);}}
        void geometry(){String dim=h.getLevel().dimension().identifier().toString();
            small=new Cuboid(dim,origin.getX()+2,origin.getY()+1,origin.getZ()+2,origin.getX()+3,origin.getY()+1,origin.getZ()+3);
            wide=new Cuboid(dim,origin.getX()+1,origin.getY()+1,origin.getZ()+1,origin.getX()+6,origin.getY()+1,origin.getZ()+6);
            chest=new ContainerRef(dim,origin.getX()+3,origin.getY()+1,origin.getZ()+4);}
        void block(int x,int y,int z,net.minecraft.world.level.block.state.BlockState state){h.getLevel().setBlockAndUpdate(origin.offset(x,y,z),state);}
        int stock(){return ((Container)h.getLevel().getBlockEntity(new BlockPos(chest.x(),chest.y(),chest.z()))).countItem(Items.WHEAT);}
        void compose(){
            stores=opening.join();if(warm){var m=stores.marker();world=id(m,"world");owner=new TrustedContext(new PrincipalRef(id(m,"principal")),new ScopeRef(world,id(m,"domain")));
                var p=(List<?>)m.get("origin");origin=new BlockPos(((Number)p.get(0)).intValue(),((Number)p.get(1)).intValue(),((Number)p.get(2)).intValue());BootstrapGameTests.forceFixtureChunks(h,origin);geometry();
                for(var c:stores.citizens().snapshot().citizens())actors.add(c.actor());actors.sort(Comparator.comparing(a->a.citizenId().toString()));
                outcomes.addAll(((List<?>)m.get("outcomes")).stream().map(v->UUID.fromString((String)v)).toList());}
            citizens=new CitizenRegistry(stores.citizens().snapshot(),(expected,next)->async(()->{try{return stores.citizens().replace(expected,next);}catch(Exception e){throw new CompletionException(e);}}),CitizenRegistry.privateAddresses(),clock,false);
            jobs=new JobLifecycleStore(stores.jobs().snapshot(),(expected,next)->async(()->{try{return stores.jobs().replace(expected,next);}catch(Exception e){throw new CompletionException(e);}}),JobLifecycleStore.privateJobs(citizens::controls),c->{},id->Optional.of(CropDelivery.SPEC),
                    new RequestEnvironment(){public boolean enrolled(ActorRef a,TrustedContext c){return citizens.controls(a,c);}public boolean loaded(Cuboid a,ObservationRef o){return true;}public boolean available(ContainerRef c,ObservationRef o){return c.equals(chest);}},clock,Jobs.Settings.defaults(),false);
            evidence=new AccomplishmentService(stores.ledger());var owners=new JobLeaseOwners(jobs,clock);
            leases=new ResourceLeaseService(stores.leases().snapshot(),(expected,next)->async(()->{try{return stores.leases().replace(expected,next);}catch(Exception e){throw new CompletionException(e);}}),owners,new FabricResourceLeaseWorld(h.getLevel().getServer()),ResourceLeases.Settings.production(),()->h.getLevel().getGameTime(),clock,false);
            raw=new SurvivalGateway(new FabricGatewayWorld(h.getLevel()),(a,e,c)->citizens.controls(a,c),new SurvivalGateway.Limits(1,512,64,16,256,1,200,32),clock);
            gateway=new LeasedGateway(raw,leases,(request,correlation)->LeasedGateway.cropWorkspaces(owners,request,correlation),()->h.getLevel().getGameTime(),1200,200,64,512);
            var compiled=new SkillCompiler(id->Optional.of(CropDelivery.SPEC),GatewayPrimitives.instance(),ref->Optional.empty()).compile(ResearchGameTests.fixtureIr());
            require(compiled instanceof SkillCompiler.Success,"Test-only known P0 method must compile");artifact=((SkillCompiler.Success)compiled).skill().artifact();var ref=artifact.descriptor().ref();
            var bodies=new BoundedSkillExecutor.ArtifactSource(){
                public Optional<SkillArtifact> body(ArtifactRef key){return key.equals(ref)?Optional.of(artifact):Optional.empty();}
                public Optional<AdmissionRecord> admission(ArtifactRef key){return key.equals(ref)?Optional.of(new AdmissionRecord(ref,AdmissionStatus.ADMITTED,new EvidenceRef("gp21-known-method","fixture:1","native"),null,1)):Optional.empty();}
                public Optional<Compatibility> compatibility(ArtifactRef key){return key.equals(ref)?Optional.of(new Compatibility(ref,CompatibilityStatus.COMPATIBLE,List.of(),"minecraft-26.3")):Optional.empty();}
            };
            executor=new BoundedSkillExecutor(bodies,id->Optional.of(CropDelivery.SPEC),GatewayPrimitives.instance(),(a,key,c)->citizens.controls(a,c),
                    (permit,request,budget)->{throw new AssertionError("GP-21 must never call a model");},new BoundedSkillExecutor.ControlPolicy(){public boolean mayInspect(TrustedContext a,TrustedContext b,UUID id){return a.equals(b);}public boolean mayCancel(TrustedContext a,TrustedContext b,UUID id){return a.equals(b);}},gateway,gateway::releaseRun,clock,()->h.getLevel().getGameTime(),new BoundedSkillExecutor.Settings(8,16,32,64));
        }
        UUID create(){ActorRef actor=actors.get(work==1||work==3?1:0);Cuboid source=work==0?small:wide;int amount=work==0?2:work==1?3:1;
            var request=new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(actor),"source",new AreaValue(source),"destination",new ContainerValue(chest),"amount",new IntValue(amount)));
            var budget=new Budgets.Limits(Map.of(Budgets.Kind.CALLS,512L,Budgets.Kind.INSTRUCTIONS,8192L,Budgets.Kind.OBSERVATIONS,4096L,Budgets.Kind.ATTEMPTED_EFFECTS,64L,Budgets.Kind.COMMITTED_EFFECTS,64L,Budgets.Kind.ELAPSED_TICKS,1800L),clock.millis()+600000);
            UUID id=UUID.randomUUID();require(jobs.create(id,id,request,owner,new ObservationRef(UUID.randomUUID(),h.getLevel().getGameTime(),source.dimension()),budget,List.of()).accepted(),"Native job creation refused");outcomes.add(id);return id;
        }
        Jobs.Job job(UUID id){return jobs.query(id,owner);}
        void step(){if(phase==40)return;java.util.concurrent.locks.LockSupport.parkNanos(50_000_000);
            if(citizens!=null&&phase<20){citizens.tick();jobs.tick();leases.tick();raw.tick();gateway.tick();evidence.maintain(jobs.snapshot(),io);}
            switch(phase){
                case 0->{require(opening.isDone(),"Await native owner storage");compose();phase=warm?15:1;}
                case 1->{require(citizens.ready(),"Await first enrollment");var result=citizens.enroll(villagers.getFirst().getUUID(),small.dimension(),owner);require(result.accepted(),"First citizen refused");actors.add(result.citizen().actor());phase=2;}
                case 2->{require(citizens.ready(),"Await second enrollment");var result=citizens.enroll(villagers.get(1).getUUID(),small.dimension(),owner);require(result.accepted(),"Second citizen refused");actors.add(result.citizen().actor());phase=3;}
                case 3->{require(citizens.ready()&&jobs.ready()&&leases.ready(),"Await acknowledged owners");current=create();phase=4;}
                case 4->{require(jobs.ready()&&leases.ready(),"Await immutable job");var j=job(current);ActorRef actor=j.responsible();var ref=artifact.descriptor().ref();require(jobs.assign(current,j.guard(),UUID.randomUUID(),actor,ref,List.of(ref),j.allowance(),owner).accepted(),"Native assignment refused");phase=5;}
                case 5->{require(jobs.ready()&&leases.ready(),"Await native pin");var a=job(current).current();var ref=a.executions().getFirst();
                    if(work<2){var villager=villagers.get(work);villager.setPos(origin.getX()+3.5,origin.getY()+1,origin.getZ()+3.5);}
                    var started=executor.startAdmitted(a.bound(),ref.artifact(),new RunCorrelation(ref.runId(),ref.artifact(),null),new Budgets.ExecutionLimits(a.allowance(),10000),new Budgets.Ledger(a.allowance(),clock));
                    require(started instanceof BoundedSkillExecutor.Started,"Known native work refused");run=((BoundedSkillExecutor.Started)started).run();if(work==3)run.cancel(owner);phase=6;}
                case 6->{var progress=run.tick(owner);if(progress.phase()!=BoundedSkillExecutor.Phase.TERMINAL)return;var sum=progress.summary();
                    boolean expected=work<2?sum.outcome().status()==ExecutionStatus.SUCCEEDED:work==2?sum.outcome().status()==ExecutionStatus.BLOCKED:sum.outcome().status()==ExecutionStatus.CANCELLED;
                    if(!expected)throw new AssertionError("Actual outcome mismatch work="+work+" outcome="+sum.outcome()+" stock="+stock()+" trace="+progress.trace());
                    var j=job(current);var a=j.current();require(jobs.recordExecutionResult(current,j.guard(),new Jobs.Report(a.id(),a.generation(),sum.committedEffects(),sum.usage(),sum.receipts(),sum.outcome()),owner).accepted(),"Native receipts refused");phase=7;}
                case 7->{require(jobs.ready()&&leases.ready(),"Await terminal owner ack");if(work<2)villagers.get(work).setPos(origin.getX()+7.5,origin.getY()+1,origin.getZ()+6.5);
                    work++;if(work<4){phase=3;return;}require(stock()==5,"Actual deposit total must be five");var result=citizens.rename(actors.getFirst().citizenId(),"Measured Farmer",owner);require(result.accepted(),"Native rename refused");phase=8;}
                case 8->{require(citizens.ready()&&!evidence.pending(),"Await registry and evidence owner");inspect();phase=16;}
                case 15->{require(((Number)stores.marker().get("process")).longValue()!=ProcessHandle.current().pid()
                            ||((Number)stores.marker().get("processStarted")).longValue()!=processStarted,"Warm phase requires another server JVM");
                    require(stock()==5&&jobs.snapshot().jobs().size()==4,"Restart lost physical stock or invented work");require(citizens.snapshot().citizens().stream().anyMatch(c->"Measured Farmer".equals(c.displayName())),"Rename did not survive");inspect();phase=16;}
                case 16->{require(inspecting.isDone(),"Await scoped repeat-safe inspection");var proof=inspecting.join();require(((Number)proof.get("quantity")).longValue()==5,"Ledger did not attribute exactly five units");
                    require(((Number)proof.get("completed")).longValue()==2&&((Number)proof.get("titles")).longValue()==2,"Completion or recognition duplicated");
                    require(stock()==5,"Evidence/title evaluation changed world stock");quietUntil=h.getLevel().getGameTime()+20;phase=17;}
                case 17->{require(h.getLevel().getGameTime()>=quietUntil&&!evidence.pending(),"Observe no replay during quiet ticks");require(stock()==5,"Restart replayed an effect");save();phase=20;}
                case 20->{require(closing.isDone(),"Await native store closure");closing.join();System.out.println("IMP-021 GP-21 accepted phase="+(warm?"warm":"cold")+" stock=5 quantity=5 completed=2 titles=2 modelCalls=0 noReplay=true");phase=40;}
            }
        }
        void inspect(){var references=outcomes.stream().map(id->OutcomeRef.of(job(id))).toList();var reversed=new ArrayList<>(references);Collections.reverse(reversed);var before=jobs.snapshot();
            inspecting=async(()->{var ledger=stores.ledger();var result=ledger.recordOutcomes(reversed,()->false);if(result.code()!=Code.APPLIED&&result.code()!=Code.DUPLICATE)throw new AssertionError("Owner ingestion failed: "+result);
                var snapshot=ledger.snapshot();long quantity=snapshot.aggregates().stream().mapToLong(Aggregate::quantity).sum();long completed=snapshot.aggregates().stream().mapToLong(Aggregate::completedJobs).sum();
                long titles=snapshot.recognition().stream().filter(Recognition::active).count();var foreign=new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(world,UUID.randomUUID()));
                for(var actor:actors){if(ledger.query(actor,owner,16).code()!=Code.APPLIED||ledger.query(actor,foreign,16).code()!=Code.DENIED)throw new AssertionError("Private evidence authority mismatch");}
                if(!before.equals(stores.jobs().snapshot()))throw new AssertionError("Ledger changed Jobs");
                if(ledger.recordOutcomes(references,()->false).code()!=Code.DUPLICATE||!snapshot.equals(ledger.snapshot()))throw new AssertionError("Replay changed evidence");
                if(snapshot.aggregates().stream().map(a->a.key().environment().sourceSize()).distinct().count()!=2)throw new AssertionError("Native context was pooled");
                if(snapshot.aggregates().stream().flatMap(a->a.outcomes().keySet().stream()).noneMatch(c->c==OutcomeClass.ENVIRONMENT)
                        ||snapshot.aggregates().stream().flatMap(a->a.outcomes().keySet().stream()).noneMatch(c->c==OutcomeClass.CANCELLATION))throw new AssertionError("Environmental/cancellation evidence missing");
                return Map.<String,Object>of("quantity",quantity,"completed",completed,"titles",titles,"progress",snapshot.progress().size(),"aggregates",snapshot.aggregates().size(),"revision",snapshot.revision());});
        }
        void save(){evidence.close();var marker=new LinkedHashMap<String,Object>();marker.put("world",world.toString());marker.put("principal",owner.principal().id().toString());marker.put("domain",owner.scope().domainId().toString());
            marker.put("origin",List.of(origin.getX(),origin.getY(),origin.getZ()));marker.put("outcomes",outcomes.stream().map(UUID::toString).toList());marker.put("process",ProcessHandle.current().pid());marker.put("processStarted",processStarted);
            var proof=new LinkedHashMap<String,Object>(inspecting.join());proof.put("schema",1);proof.put("architecture","0.5");proof.put("phase",warm?"warm":"cold");proof.put("stock",stock());proof.put("modelCalls",0);proof.put("outcomes",outcomes.stream().map(UUID::toString).toList());
            proof.put("process",ProcessHandle.current().pid());proof.put("processStarted",processStarted);
            closing=async(()->{try{if(!warm)Files.writeString(root.resolve("restart.json"),StrictJson.canonical(marker)+"\n");
                Path state=root.resolve(AccomplishmentLedger.WORLD_RELATIVE_PATH).resolve("state.jsonl");proof.put("ledgerBytes",Files.size(state));
                proof.put("ledgerSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(state))));
                proof.put("sampleMaximum",stores.ledger().snapshot().aggregates().stream().mapToInt(a->a.samples().size()).max().orElse(0));
                try(var files=Files.list(state.getParent())){proof.put("ownerBytes",files.mapToLong(file->{try{return Files.size(file);}catch(Exception e){throw new CompletionException(e);}}).sum());}
                Files.writeString(root.resolve(warm?"warm.json":"cold.json"),StrictJson.canonical(proof)+"\n");
                stores.ledger().close();stores.leases().close();stores.jobs().close();stores.citizens().close();return null;}catch(Exception e){throw new CompletionException(e);}});closing.whenComplete((ignored,error)->io.shutdown());
        }
        static UUID id(Map<String,Object> map,String key){return UUID.fromString((String)map.get(key));}
        void require(boolean condition,String message){h.assertTrue(condition,message);}
    }
}
