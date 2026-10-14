package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.*;
import dev.aivillages.core.kernel.KnowledgeAccess.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** GP-22: test-only known wheat body, real repository/Jobs/evidence and native survival effects. */
public final class ApprenticeshipGameTests {
    @GameTest(maxTicks=3600,padding=32)
    public void supervisedPracticeConsumesRealResourcesWithoutCopyingMethods(GameTestHelper h){run(h,false);}
    static void run(GameTestHelper h,boolean warm){
        h.assertTrue((warm?"warm":"cold").equals(System.getenv("COGNITIVECRAFT_APPRENTICESHIP_PHASE")),"Explicit GP-22 process phase required");
        var driver=new Driver(h,warm);h.succeedWhen(()->{driver.step();h.assertTrue(driver.phase==99,"Await GP-22 phase="+driver.phase);});
    }
    private record Stores(CitizenIdentityStore citizens,JobJournal jobs,ResourceLeaseJournal leases,
                          VersionedSkillRepository repository,AccomplishmentLedger evidence,KnowledgeAccessSource source,
                          RetentionRoots roots,Map<String,Object> marker){ }
    private static final class Driver {
        final GameTestHelper h;final boolean warm;final Clock clock=Clock.systemUTC();final Path root;
        final long processStarted=ManagementFactory.getRuntimeMXBean().getStartTime();
        final ExecutorService io=Executors.newSingleThreadExecutor();final CompletableFuture<Stores> opening;
        Stores stores;KnowledgeAccessStore access;CitizenRegistry citizens;JobLifecycleStore jobs;ResourceLeaseService leases;
        SurvivalGateway raw;LeasedGateway gateway;BoundedSkillExecutor executor;BoundedSkillExecutor.Run run;
        TrustedContext owner;UUID world;BlockPos origin;ContainerRef chest;ArtifactRef method;
        final List<ActorRef> actors=new ArrayList<>();final List<Villager> villagers=new ArrayList<>();final List<UUID> outcomes=new ArrayList<>();
        CompletableFuture<?> operation;UUID current;int phase,work;boolean supervisionLost;long partialEffects;long quietUntil;
        Driver(GameTestHelper h,boolean warm){
            this.h=h;this.warm=warm;root=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("cognitivecraft-apprenticeship-fixture");
            if(warm){opening=async(()->{try{return open(StrictJson.object(Files.readString(root.resolve("restart.json"))));}catch(Exception e){throw new CompletionException(e);}});return;}
            world=UUID.randomUUID();owner=new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(world,UUID.randomUUID()));
            origin=h.absolutePos(new BlockPos(8,0,8));BootstrapGameTests.forceFixtureChunks(h,origin);geometry();
            for(int x=0;x<14;x++)for(int z=0;z<12;z++)block(x,0,z,Blocks.STONE.defaultBlockState());
            for(int[] field:fields())for(int z=field[1];z<=field[1]+1;z++){int x=field[0];
                block(x,0,z,Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
                block(x,1,z,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));}
            block(4,1,4,Blocks.CHEST.defaultBlockState());
            for(int n=0;n<4;n++){var p=origin.offset(11,1,7+n).subtract(h.absolutePos(BlockPos.ZERO));
                var villager=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,p.getX()+0.5f,p.getY(),p.getZ()+0.5f);villager.setPersistenceRequired();villagers.add(villager);}
            opening=async(()->open(Map.of("world",world.toString(),"principal",owner.principal().id().toString(),"domain",owner.scope().domainId().toString())));
        }
        <T>CompletableFuture<T> async(Supplier<T> task){return CompletableFuture.supplyAsync(task,io);}
        Stores open(Map<String,Object> marker){try{
            UUID id=id(marker,"world");var owner=new TrustedContext(new PrincipalRef(id(marker,"principal")),new ScopeRef(id,id(marker,"domain")));
            var identities=CitizenIdentityStore.open(root,id);var jobs=JobJournal.open(root,id);var leases=ResourceLeaseJournal.open(root,id);
            CapabilityCatalog specs=c->c.equals(CropDelivery.ID)?Optional.of(CropDelivery.SPEC):Optional.empty();
            var repo=VersionedSkillRepository.open(root,new VersionedSkillRepository.RuntimeSnapshot("minecraft-26.3",specs,GatewayPrimitives.instance()),
                    VersionedSkillRepository.Limits.defaults(),d->true,TrustedContext::equals,VersionedSkillRepository.FaultInjector.none());
            var skill=((SkillCompiler.Success)new SkillCompiler(specs,GatewayPrimitives.instance(),ref->Optional.empty()).compile(ResearchGameTests.fixtureIr())).skill().artifact();
            if(!warm){var witness=new EvidenceRef("gp22-controlled-known-method","fixture-1","native");
                var bundle=new VersionedSkillRepository.EvidenceBundle(witness,witness,witness,"b".repeat(64));
                var published=repo.publish(skill,new VersionedSkillRepository.AdmissionDecision(UUID.randomUUID(),owner,bundle),
                        new Provenance(1,null,"compiler-1","minecraft-26.3",id,List.of(),List.of(witness)));
                if(published.status()!=VersionedSkillRepository.PublishStatus.ADMITTED)throw new IllegalStateException("Known test method publication: "+published);}
            var roots=new RetentionRoots();var evidence=AccomplishmentLedger.open(root,id,new JobOutcomeSource(jobs::snapshot,identities::snapshot),roots);
            var source=new KnowledgeAccessSource(jobs::snapshot,identities::snapshot,evidence::snapshot);
            return new Stores(identities,jobs,leases,repo,evidence,source,roots,marker);
        }catch(Exception failed){throw new CompletionException(failed);}}
        void geometry(){String dim=h.getLevel().dimension().identifier().toString();chest=new ContainerRef(dim,origin.getX()+4,origin.getY()+1,origin.getZ()+4);}
        static int[][] fields(){return new int[][]{{2,2},{8,2},{6,2},{2,5},{4,5},{6,5}};}
        Cuboid source(){int x=fields()[work][0],z=fields()[work][1];
            return new Cuboid(chest.dimension(),origin.getX()+x,origin.getY()+1,origin.getZ()+z,origin.getX()+x,origin.getY()+1,origin.getZ()+z+1);}
        void block(int x,int y,int z,net.minecraft.world.level.block.state.BlockState state){h.getLevel().setBlockAndUpdate(origin.offset(x,y,z),state);}
        int stock(){return ((Container)h.getLevel().getBlockEntity(new BlockPos(chest.x(),chest.y(),chest.z()))).countItem(Items.WHEAT);}
        int mature(){int count=0;for(int[] field:fields())for(int z=field[1];z<=field[1]+1;z++){int x=field[0];var state=h.getLevel().getBlockState(origin.offset(x,1,z));
            if(state.is(Blocks.WHEAT)&&state.getValue(CropBlock.AGE)==7)count++;}return count;}
        int failedDrops(){return h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(origin.getX()+7,origin.getY(),origin.getZ()+1,origin.getX()+10,origin.getY()+4,origin.getZ()+5),
                e->e.getItem().is(Items.WHEAT)).stream().mapToInt(e->e.getItem().getCount()).sum();}
        int failedInventory(){return villagers.size()>3?villagers.get(3).getInventory().countItem(Items.WHEAT):0;}
        int failedResources(){return failedDrops()+failedInventory();}
        void compose(){
            stores=opening.join();method=((SkillCompiler.Success)new SkillCompiler(c->Optional.of(CropDelivery.SPEC),GatewayPrimitives.instance(),ref->Optional.empty())
                    .compile(ResearchGameTests.fixtureIr())).skill().artifact().descriptor().ref();
            if(warm){var marker=stores.marker();world=id(marker,"world");owner=new TrustedContext(new PrincipalRef(id(marker,"principal")),new ScopeRef(world,id(marker,"domain")));
                var pos=(List<?>)marker.get("origin");origin=new BlockPos(((Number)pos.get(0)).intValue(),((Number)pos.get(1)).intValue(),((Number)pos.get(2)).intValue());BootstrapGameTests.forceFixtureChunks(h,origin);geometry();
                for(Object value:(List<?>)marker.get("actors")){UUID actor=UUID.fromString((String)value);var c=stores.citizens().snapshot().citizens().stream().filter(v->v.actor().citizenId().equals(actor)).findFirst().orElseThrow();actors.add(c.actor());
                    var entity=h.getLevel().getEntity(c.actor().entityId());require(entity instanceof Villager,"Saved enrolled native villager must remain loaded");villagers.add((Villager)entity);}
                outcomes.addAll(((List<?>)marker.get("outcomes")).stream().map(v->UUID.fromString((String)v)).toList());partialEffects=((Number)marker.get("partialEffects")).longValue();}
            citizens=new CitizenRegistry(stores.citizens().snapshot(),(expected,next)->async(()->{try{return stores.citizens().replace(expected,next);}catch(Exception e){throw new CompletionException(e);}}),CitizenRegistry.privateAddresses(),clock,false);
            jobs=new JobLifecycleStore(stores.jobs().snapshot(),(expected,next)->async(()->{try{return stores.jobs().replace(expected,next);}catch(Exception e){throw new CompletionException(e);}}),JobLifecycleStore.privateJobs(citizens::controls),c->{},c->Optional.of(CropDelivery.SPEC),
                    new RequestEnvironment(){public boolean enrolled(ActorRef a,TrustedContext c){return citizens.controls(a,c);}public boolean loaded(Cuboid a,ObservationRef o){return true;}public boolean available(ContainerRef c,ObservationRef o){return c.equals(chest);}},clock,Jobs.Settings.defaults(),false);
            var owners=new JobLeaseOwners(jobs,clock);leases=new ResourceLeaseService(stores.leases().snapshot(),(expected,next)->async(()->{try{return stores.leases().replace(expected,next);}catch(Exception e){throw new CompletionException(e);}}),owners,new FabricResourceLeaseWorld(h.getLevel().getServer()),ResourceLeases.Settings.production(),()->h.getLevel().getGameTime(),clock,false);
            raw=new SurvivalGateway(new FabricGatewayWorld(h.getLevel()),(a,e,c)->citizens.controls(a,c),new SurvivalGateway.Limits(1,512,64,16,256,1,200,32),clock);
            gateway=new LeasedGateway(raw,leases,(request,run)->LeasedGateway.cropWorkspaces(owners,request,run),()->h.getLevel().getGameTime(),1200,200,64,512);
            var knowledge=new AttemptEligibilityPolicy(){
                public boolean mayUse(ActorRef actor,ArtifactRef ref,TrustedContext caller){return access==null?citizens.controls(actor,caller):access.mayUse(actor,ref,caller);}
                public boolean mayUseInRun(ValidatedRequest request,RunCorrelation run,ArtifactRef ref){return access==null?mayUse(((ActorValue)request.request().arguments().get("actor")).value(),ref,request.context()):access.mayUseInRun(request,run,ref);}
                public boolean mayContinue(ValidatedRequest request,RunCorrelation run,Set<ArtifactRef> refs){return access==null||access.mayContinue(request,run,refs);}
            };
            executor=new BoundedSkillExecutor(new ResearchAdmissionController.TrialArtifacts(stores.repository()),c->Optional.of(CropDelivery.SPEC),GatewayPrimitives.instance(),knowledge,
                    (p,r,b)->{throw new AssertionError("Teaching/known reuse must never use candidate trials or models");},new BoundedSkillExecutor.ControlPolicy(){public boolean mayInspect(TrustedContext a,TrustedContext b,UUID id){return a.equals(b);}public boolean mayCancel(TrustedContext a,TrustedContext b,UUID id){return a.equals(b);}},gateway,gateway::releaseRun,clock,()->h.getLevel().getGameTime(),new BoundedSkillExecutor.Settings(8,16,32,64));
        }
        void presence(){var pairs=new HashSet<Pair>();if(actors.size()>1){for(int n=1;n<actors.size();n++)if(villagers.getFirst().distanceToSqr(villagers.get(n))<=256)pairs.add(new Pair(actors.getFirst(),actors.get(n)));}
            stores.source().observe(citizens.snapshot(),jobs.snapshot(),pairs);if(access!=null)access.checkSupervision();}
        Jobs.Job job(){return jobs.query(current,owner);}
        ActorRef actor(){return actors.get(work==0?0:work==1?3:work==3?2:1);}
        void create(){var source=source();var request=new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(actor()),"source",new AreaValue(source),"destination",new ContainerValue(chest),"amount",new IntValue(2)));
            var allowance=new Budgets.Limits(Map.of(Budgets.Kind.CALLS,512L,Budgets.Kind.INSTRUCTIONS,8192L,Budgets.Kind.OBSERVATIONS,4096L,Budgets.Kind.ATTEMPTED_EFFECTS,64L,Budgets.Kind.COMMITTED_EFFECTS,64L,Budgets.Kind.ELAPSED_TICKS,1800L),clock.millis()+600000);
            current=UUID.randomUUID();require(jobs.create(current,current,request,owner,new ObservationRef(UUID.randomUUID(),h.getLevel().getGameTime(),source.dimension()),allowance,List.of()).accepted(),"Native practice Job creation refused");outcomes.add(current);}
        void step(){if(phase==99)return;java.util.concurrent.locks.LockSupport.parkNanos(50_000_000);
            if(citizens!=null&&phase<80){citizens.tick();jobs.tick();leases.tick();raw.tick();gateway.tick();presence();}
            switch(phase){
                case 0->{require(opening.isDone(),"Await owner storage");compose();if(warm){require(((Number)stores.marker().get("process")).longValue()!=ProcessHandle.current().pid()||((Number)stores.marker().get("processStarted")).longValue()!=processStarted,"Reuse requires another JVM");
                        require(stock()==8&&mature()==3&&failedResources()==1,"Saved physical stock/resources must remain conserved");operation=async(()->{try{return KnowledgeAccessStore.open(root,world,stores.source(),KnowledgeAccessStore.repositoryCatalog(stores.repository()),clock,stores.roots());}catch(Exception e){throw new CompletionException(e);}});phase=13;}else phase=1;}
                case 1->{require(citizens.ready(),"Await teacher enrollment");var result=citizens.enroll(villagers.getFirst().getUUID(),chest.dimension(),owner);require(result.accepted(),"Teacher enrollment refused");actors.add(result.citizen().actor());phase=3;}
                case 3->{require(citizens.ready()&&jobs.ready()&&leases.ready(),"Await acknowledged identities/Jobs");create();phase=4;}
                case 4->{require(jobs.ready()&&leases.ready(),"Await acknowledged practice request");presence();
                    if(work>=1&&work<=3){require(!access.mayUse(actor(),method,owner),"Unlearned method must require knowledge");
                        operation=async(()->access.startApprenticeship(current,actors.getFirst(),method,owner,()->false));phase=5;}
                    else{require(access==null||access.mayUse(actor(),method,owner),"Learned method must execute without inference");operation=CompletableFuture.completedFuture(null);phase=5;}}
                case 5->{require(operation.isDone(),"Await learning reference publication");if(work>=1&&work<=3)require(((KnowledgeAccess.Result)operation.join()).code()==Code.APPLIED,"Practice access refused: "+operation.join());
                    var job=job();require(jobs.assign(current,job.guard(),UUID.randomUUID(),actor(),method,List.of(method),job.allowance(),owner).accepted(),"Ordinary Jobs assignment refused");phase=6;}
                case 6->{require(jobs.ready()&&leases.ready(),"Await ordinary assignment and pin");var a=job().current();var e=a.executions().getFirst();var source=source();
                    int selected=work==0?0:work==1?3:work==3?2:1;villagers.get(selected).setPos(source.minX()+(source.minX()-origin.getX()<=4?1.5:-0.5),source.minY(),source.minZ()+0.5);presence();
                    var started=executor.startAdmitted(a.bound(),method,new RunCorrelation(e.runId(),method,null),new Budgets.ExecutionLimits(a.allowance(),10000),new Budgets.Ledger(a.allowance(),clock));
                    require(started instanceof BoundedSkillExecutor.Started,"Supervised native method refused: "+started);run=((BoundedSkillExecutor.Started)started).run();phase=7;}
                case 7->{var progress=run.tick(owner);
                    if(work==1&&!supervisionLost&&progress.summary().committedEffects()>0){partialEffects=progress.summary().committedEffects();villagers.getFirst().setPos(origin.getX()+30.5,origin.getY()+1,origin.getZ()+6.5);supervisionLost=true;presence();return;}
                    if(progress.phase()!=BoundedSkillExecutor.Phase.TERMINAL)return;var sum=progress.summary();
                    if(work==1){require(sum.outcome().status()==ExecutionStatus.BLOCKED&&sum.outcome().reason()==Reason.KNOWLEDGE_REQUIRED&&sum.committedEffects()==partialEffects,"Lost supervision must fence subsequent effects");require(stock()==2&&failedResources()==1&&mature()==9,"Partial harvest must remain physically conserved");}
                    else require(sum.outcome().status()==ExecutionStatus.SUCCEEDED,"Native known outcome failed work="+work+" outcome="+sum.outcome()+" trace="+progress.trace());
                    var j=job();var a=j.current();require(jobs.recordExecutionResult(current,j.guard(),new Jobs.Report(a.id(),a.generation(),sum.committedEffects(),sum.usage(),sum.receipts(),sum.outcome()),owner).accepted(),"Actual Jobs receipts refused");phase=8;}
                case 8->{require(jobs.ready()&&leases.ready(),"Await owner acknowledgement");var acknowledged=job();presence();
                    operation=async(()->{var result=stores.evidence().recordOutcomes(List.of(Accomplishments.OutcomeRef.of(acknowledged)),()->false);
                        if(result.code()!=Accomplishments.Code.APPLIED&&result.code()!=Accomplishments.Code.DUPLICATE)throw new AssertionError("Evidence owner refused: "+result);
                        if(work>=1&&work<=3){var learned=access.recordLearningOutcome(current,owner,()->false);if(learned.code()!=Code.APPLIED)throw new AssertionError("Learning owner refused: "+learned);}return null;});phase=9;}
                case 9->{require(operation.isDone(),"Await evidence-backed learning");operation.join();int selected=work==0?0:work==1?3:work==3?2:1;
                    villagers.get(selected).setPos(origin.getX()+11.5,origin.getY()+1,origin.getZ()+7.5+selected);
                    if(work==0){operation=async(()->{try{return KnowledgeAccessStore.open(root,world,stores.source(),KnowledgeAccessStore.repositoryCatalog(stores.repository()),clock,stores.roots());}catch(Exception e){throw new CompletionException(e);}});phase=10;return;}
                    if(work==1){require(access.snapshot().grants().isEmpty()&&!access.mayUse(actor(),method,owner),"Failed supervision granted access");villagers.getFirst().setPos(origin.getX()+11.5,origin.getY()+1,origin.getZ()+7.5);}
                    if(work>=2&&work<=3)require(access.mayUse(actor(),method,owner),"Verified practice must grant exactly the permitted method");
                    work++;if(work<5&&!warm){phase=3;return;}quietUntil=h.getLevel().getGameTime()+20;phase=70;}
                case 10->{require(operation.isDone(),"Await shared-access migration");access=(KnowledgeAccessStore)operation.join();require(access.mayUse(actors.getFirst(),method,owner),"Absent personal row erased teacher shared access");
                    require(citizens.ready(),"Await learner enrollment");var enrolled=citizens.enroll(villagers.get(1).getUUID(),chest.dimension(),owner);require(enrolled.accepted(),"Learner enrollment refused");actors.add(enrolled.citizen().actor());phase=11;}
                case 11->{require(citizens.ready(),"Await second learner enrollment");var enrolled=citizens.enroll(villagers.get(2).getUUID(),chest.dimension(),owner);require(enrolled.accepted(),"Second learner refused");actors.add(enrolled.citizen().actor());phase=15;}
                case 15->{require(citizens.ready(),"Await isolated failed learner enrollment");var enrolled=citizens.enroll(villagers.get(3).getUUID(),chest.dimension(),owner);require(enrolled.accepted(),"Failed-practice learner refused");actors.add(enrolled.citizen().actor());phase=12;}
                case 12->{require(citizens.ready(),"Await acknowledged apprentice identities");presence();
                    operation=async(()->{for(int n=1;n<4;n++)if(access.requirePersonalLearning(actors.get(n),owner,()->false).code()!=Code.APPLIED)throw new AssertionError("Explicit apprentice opt-in refused");return null;});phase=14;}
                case 14->{require(operation.isDone(),"Await explicit personal-access enrollment");operation.join();work=1;phase=3;}
                case 13->{require(operation.isDone(),"Await personal-access reload");access=(KnowledgeAccessStore)operation.join();require(access.snapshot().grants().size()==2,"Reload lost/duplicated learned references");
                    require(access.mayUse(actors.get(1),method,owner),"Reload erased personal learning");work=5;phase=3;}
                case 70->{require(h.getLevel().getGameTime()>=quietUntil,"Observe stable post-completion physical stock");verifyAndSave();phase=80;}
                case 80->{require(operation.isDone(),"Await off-thread close/proof");operation.join();io.shutdown();System.out.println("IMP-022 GP-22 accepted phase="+(warm?"warm":"cold")+" stock="+stock()+" grants=2 bodies=1 failedGrant=false modelCalls=0 noReplay=true");phase=99;}
            }
        }
        void verifyAndSave(){
            require(stock()==(warm?10:8)&&mature()==(warm?1:3)&&failedResources()==1,"World item/block conservation mismatch stock="+stock()+" mature="+mature()+" failedDrops="+failedDrops()+" failedInventory="+failedInventory());
            require(access.snapshot().grants().size()==2&&access.snapshot().practices().size()==3,"Learning reference count mismatch");
            require(access.snapshot().practices().stream().filter(p->p.stage()==Stage.STOPPED).count()==1,"Lost-supervision history must remain explainable");
            var foreign=new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(world,UUID.randomUUID()));
            require(!access.mayUse(actors.get(1),method,foreign),"Learning widened physical/principal scope");
            for(var p:access.snapshot().practices())require(access.recordLearningOutcome(p.job(),owner,()->false).code()==Code.DUPLICATE,"Replay changed learned credit");
            var marker=new LinkedHashMap<String,Object>();marker.put("world",world.toString());marker.put("principal",owner.principal().id().toString());marker.put("domain",owner.scope().domainId().toString());
            marker.put("origin",List.of(origin.getX(),origin.getY(),origin.getZ()));marker.put("actors",actors.stream().map(a->a.citizenId().toString()).toList());marker.put("outcomes",outcomes.stream().map(UUID::toString).toList());
            marker.put("process",ProcessHandle.current().pid());marker.put("processStarted",processStarted);marker.put("partialEffects",partialEffects);
            var proof=new LinkedHashMap<String,Object>(marker);proof.put("schema",1);proof.put("architecture","0.5");proof.put("phase",warm?"warm":"cold");proof.put("stock",stock());proof.put("mature",mature());proof.put("failedDrops",failedDrops());proof.put("failedInventory",failedInventory());proof.put("failedResources",failedResources());proof.put("grants",2);proof.put("practices",3);proof.put("modelCalls",0);proof.put("revision",access.snapshot().revision());
            operation=async(()->{try{
                var state=root.resolve(KnowledgeAccessStore.WORLD_RELATIVE_PATH).resolve("state.jsonl");proof.put("accessBytes",Files.size(state));proof.put("accessSha256",sha(Files.readAllBytes(state)));
                var bodies=root.resolve(VersionedSkillRepository.WORLD_RELATIVE_PATH).resolve("bodies");try(var files=Files.list(bodies)){proof.put("bodies",files.filter(p->p.getFileName().toString().endsWith(".json")).count());}
                try(var files=Files.list(state.getParent())){proof.put("ownerBytes",files.mapToLong(p->{try{return Files.size(p);}catch(Exception e){throw new CompletionException(e);}}).sum());}
                if(!warm)Files.writeString(root.resolve("restart.json"),StrictJson.canonical(marker)+"\n");Files.writeString(root.resolve(warm?"warm.json":"cold.json"),StrictJson.canonical(proof)+"\n");
                access.close();stores.evidence().close();stores.repository().close();stores.leases().close();stores.jobs().close();stores.citizens().close();return null;
            }catch(Exception e){throw new CompletionException(e);}});
        }
        static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        static UUID id(Map<String,Object> marker,String name){return UUID.fromString((String)marker.get(name));}
        void require(boolean value,String message){h.assertTrue(value,message);}
    }
}
