package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** GP-19: production composition, controlled planning proposals, actual P0 physics, saved-world restart. */
public final class PlanningGameTests {
    @GameTest(maxTicks=3600,padding=32)
    public void boundedPlanningPublishesOnceAndKnownChildrenFinishWithModelsDisabled(GameTestHelper h){run(h,false);}
    static void run(GameTestHelper h,boolean warm) {
        h.assertTrue((warm?"warm":"cold").equals(System.getenv("COGNITIVECRAFT_PLANNING_RESTART")),"Explicit planning process phase required");
        var d=new Driver(h,warm);h.succeedWhen(()->{d.step();h.assertTrue(d.phase==40,"Waiting for GP-19 phase="+d.phase);});
    }
    private static final class Model implements GenerationPort {
        final List<Call> calls=new ArrayList<>();boolean available=true;
        final class Call implements Generation.Handle {
            final Generation.Request request;final Budgets.ResponseAllowance allowance;
            final CompletableFuture<Generation.Result> result=new CompletableFuture<>();boolean cancelled;
            Call(Generation.Request request,Budgets.ResponseAllowance allowance){this.request=request;this.allowance=allowance;}
            public UUID id(){return request.id();}public CompletionStage<Generation.Result> result(){return result;}
            public boolean cancel(){cancelled=true;return true;}
            public Generation.Compute compute(){return result.isDone()?Generation.Compute.COMPLETED:cancelled?Generation.Compute.STOP_UNCONFIRMED:Generation.Compute.RUNNING;}
            void respond(String json){int bytes=json.getBytes(StandardCharsets.UTF_8).length;allowance.accept(bytes);allowance.close();
                result.complete(new Generation.Result(id(),request.bound().context(),request.role(),Generation.Outcome.CANDIDATE,null,json,descriptor(),
                        new Generation.Usage(128,bytes,-1,-1,Generation.Precision.UNKNOWN),Generation.Compute.COMPLETED));}
        }
        public Generation.Descriptor descriptor(){return new Generation.Descriptor("gp19-controlled-v1","shared-backend-fixture",null);}
        public Generation.Status status(){return new Generation.Status(available?Generation.State.READY:Generation.State.UNAVAILABLE,0,null,Generation.Compute.NOT_STARTED,false,false);}
        public Generation.Handle generate(Generation.Request request,Budgets.InferenceLimits limits,Budgets.Ledger ledger) {
            if(!available)throw new AssertionError("GP-19 called unavailable backend");
            if(request.role()!=Generation.Role.PLANNING || !request.primitives().isEmpty())throw new AssertionError("Plan became executable IR");
            var call=new Call(request,limits.chargeCall(ledger,128,false));calls.add(call);return call;
        }
    }
    private static final class Driver {
        final GameTestHelper h;final boolean warm;final Path root;final Model model=new Model();final CompletableFuture<Map<String,Object>> setup;
        Map<String,Object> marker;KernelSession session;Villager villager;ServerPlayer player,foreign;ActorRef actor;TrustedContext owner;
        BlockPos origin;Cuboid source;ContainerRef destination,other;UUID objective,proposal,cancelled,template;
        final List<UUID> rejected=new ArrayList<>();List<UUID> children=List.of();int phase,alternative;long quietUntil;
        CompletableFuture<Void> closing,saving;
        Driver(GameTestHelper h,boolean warm) {
            this.h=h;this.warm=warm;root=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("cognitivecraft-planning-fixture");
            System.setProperty("cognitivecraft.gametest.trace","true");
            if(warm){model.available=false;setup=CompletableFuture.supplyAsync(()->{try{return StrictJson.object(Files.readString(root.resolve("restart.json")));}catch(Exception e){throw new CompletionException(e);}});return;}
            origin=h.absolutePos(new BlockPos(8,0,8));BootstrapGameTests.forceFixtureChunks(h,origin);geometry();
            // Keep actual crops, tracked drops and two separate inventories within
            // physical reach. GP-19 checks plan ownership and execution, while
            // source navigation has its own acceptance fixtures.
            for(int x=0;x<8;x++)for(int z=0;z<7;z++)block(x,0,z,Blocks.STONE.defaultBlockState());
            for(int x=2;x<=4;x++)for(int z=2;z<=3;z++)if(x!=3||z!=3){
                block(x,0,z,Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
                block(x,1,z,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
            }
            block(2,1,4,Blocks.CHEST.defaultBlockState());block(4,1,4,Blocks.CHEST.defaultBlockState());
            var relative=origin.offset(3,1,3).subtract(h.absolutePos(BlockPos.ZERO));
            villager=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,relative.getX()+0.5f,relative.getY(),relative.getZ()+0.5f);villager.setPersistenceRequired();
            player=h.makeMockServerPlayerInLevel();player.setUUID(UUID.randomUUID());player.setPos(villager.getX(),villager.getY(),villager.getZ());
            UUID principal=player.getUUID();
            setup=CompletableFuture.supplyAsync(()->{
                try(var journal=BootstrapJournal.open(root)) {
                    journal.replace(journal.state(),null,List.of());UUID world=journal.state().worldId();
                    var context=new TrustedContext(new PrincipalRef(principal),new ScopeRef(world,principal));
                    var result=new SkillCompiler(PlanningGoals.catalog(),GatewayPrimitives.instance(),ref->Optional.empty()).compile(ResearchGameTests.fixtureIr());
                    if(!(result instanceof SkillCompiler.Success success))throw new AssertionError("Existing P0 fixture method did not compile");
                    var artifact=success.skill().artifact();
                    // Controlled method admission seeds the fixture; this is not evidence of learned method quality.
                    try(var repository=VersionedSkillRepository.open(root,new VersionedSkillRepository.RuntimeSnapshot("minecraft-26.3",PlanningGoals.catalog(),GatewayPrimitives.instance()),
                            VersionedSkillRepository.Limits.defaults(),d->true,TrustedContext::equals,VersionedSkillRepository.FaultInjector.none())) {
                        var evidence=new EvidenceRef("gp19-known-method-fixture","fixture:1","native");
                        var bundle=new VersionedSkillRepository.EvidenceBundle(evidence,evidence,evidence,"b".repeat(64));
                        var published=repository.publish(artifact,new VersionedSkillRepository.AdmissionDecision(UUID.randomUUID(),context,bundle),
                                new Provenance(1,null,"compiler-1","minecraft-26.3",world,List.of(),List.of(evidence)));
                        if(published.status()!=VersionedSkillRepository.PublishStatus.ADMITTED)throw new AssertionError("Fixture admission failed: "+published);
                    }
                    return Map.<String,Object>of("world",world.toString(),"principal",principal.toString(),"entity",villager.getUUID().toString(),"process",ProcessHandle.current().pid(),
                            "origin",List.of(origin.getX(),origin.getY(),origin.getZ()),"artifact",artifact.descriptor().ref().sha256());
                }catch(Exception e){throw new CompletionException(e);}
            });
        }
        void geometry(){String dim=h.getLevel().dimension().identifier().toString();source=new Cuboid(dim,origin.getX()+2,origin.getY()+1,origin.getZ()+2,origin.getX()+4,origin.getY()+1,origin.getZ()+3);
            destination=new ContainerRef(dim,origin.getX()+2,origin.getY()+1,origin.getZ()+4);other=new ContainerRef(dim,origin.getX()+4,origin.getY()+1,origin.getZ()+4);}
        void block(int x,int y,int z,net.minecraft.world.level.block.state.BlockState state){h.getLevel().setBlockAndUpdate(origin.offset(x,y,z),state);}
        int stock(ContainerRef ref){return ((Container)h.getLevel().getBlockEntity(new BlockPos(ref.x(),ref.y(),ref.z()))).countItem(Items.WHEAT);}
        int mature(){int n=0;for(int x=source.minX();x<=source.maxX();x++)for(int z=source.minZ();z<=source.maxZ();z++){
            var s=h.getLevel().getBlockState(new BlockPos(x,source.minY(),z));if(s.is(Blocks.WHEAT)&&s.getValue(CropBlock.AGE)==7)n++;}return n;}
        CapabilityRequest goal(){return new CapabilityRequest(PlanningGoals.ID,Map.of("actor",new ActorValue(actor),"source",new AreaValue(source),"destination",new ContainerValue(destination),
                "otherDestination",new ContainerValue(other),"amount",new IntValue(5),"firstAmount",new IntValue(2)));}
        UUID create(){UUID id=UUID.randomUUID();var result=session.createPlanningObjective(id,goal(),owner);require(result.accepted(),"Production planning objective refused: "+result);return id;}
        String valid(){return Plan.encode(new Plan.Proposal(List.of(new Plan.Node(0,0,2,List.of()),new Plan.Node(1,1,3,List.of(0)))));}
        Jobs.Job job(UUID id){return session.queuedJob(player,id);}
        GoalPlanner.View view(){return session.planStatus(proposal,owner);}
        void unchanged(){require(stock(destination)==0&&stock(other)==0&&mature()==5,"Planning performed physical effects");}
        void step(){if(phase==40)return;java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            if(session!=null&&phase<20)session.tick();switch(phase){
            case 0->{require(setup.isDone(),"Await fixture storage worker");marker=setup.join();
                UUID world=id("world"),principal=id("principal");owner=new TrustedContext(new PrincipalRef(principal),new ScopeRef(world,principal));
                if(warm){require(((Number)marker.get("process")).longValue()!=ProcessHandle.current().pid(),"Warm phase must use a second JVM");
                    var p=(List<?>)marker.get("origin");origin=new BlockPos(((Number)p.get(0)).intValue(),((Number)p.get(1)).intValue(),((Number)p.get(2)).intValue());BootstrapGameTests.forceFixtureChunks(h,origin);geometry();
                    var entity=h.getLevel().getEntity(id("entity"));require(entity instanceof Villager,"Saved citizen not loaded");villager=(Villager)entity;
                    player=h.makeMockServerPlayerInLevel();player.setUUID(principal);player.setPos(villager.getX(),villager.getY(),villager.getZ());
                    objective=id("objective");cancelled=id("cancelled");template=id("template");children=((List<?>)marker.get("children")).stream().map(v->UUID.fromString((String)v)).toList();}
                foreign=h.makeMockServerPlayerInLevel();foreign.setUUID(UUID.randomUUID());
                session=new KernelSession(h.getLevel().getServer(),root,null,model,PrimitiveDiagnostics.noop());phase=1;}
            case 1->{require(session.identityReady()&&session.jobsReady()&&session.leasesReady(),"Await production owners");
                if(warm){actor=session.enrollment().actor();require(actor.citizenId().toString().equals(marker.get("citizen")),"Saved authority identity changed");phase=15;return;}
                if(actor==null)actor=session.enroll(player,villager);require(session.identityReady()&&villager.onGround(),"Await enrolled actor");session.inference(true);cancelled=create();phase=2;}
            case 2->{require(session.jobsReady(),"Await objective ack");proposal=session.proposePlan(cancelled,owner).id();phase=3;}
            case 3->{require(view().state()==GoalPlanner.State.QUEUED&&model.calls.size()==1,"Await delayed shared backend");unchanged();
                require(job(cancelled).current()==null&&job(cancelled).children().isEmpty(),"Planner assigned a worker");
                try{session.planStatus(proposal,new TrustedContext(new PrincipalRef(foreign.getUUID()),new ScopeRef(owner.scope().worldId(),foreign.getUUID())));throw new AssertionError("Foreign scope inspected plan");}catch(SecurityException expected){}
                try{session.createPlanningObjective(UUID.randomUUID(),goal(),new TrustedContext(new PrincipalRef(foreign.getUUID()),new ScopeRef(owner.scope().worldId(),foreign.getUUID())));throw new AssertionError("Foreign scope created planning work");}catch(SecurityException expected){}
                require(session.cancelPlanProposal(proposal,owner),"Pending cancellation refused");model.calls.getFirst().respond(valid());quietUntil=h.getLevel().getGameTime()+10;phase=4;}
            case 4->{require(h.getLevel().getGameTime()>=quietUntil,"Await late replay fence");unchanged();require(job(cancelled).children().isEmpty()&&view().state()==GoalPlanner.State.CANCELLED,"Late output admitted work");
                require(job(cancelled).usage().get(Budgets.Kind.CANDIDATES)==1L,"Cancellation refunded candidate budget");require(session.cancelPlanningObjective(cancelled,owner).accepted(),"Objective cancellation refused");phase=5;}
            case 5->{require(session.jobsReady(),"Await rejected probe responsibility");objective=create();phase=6;}
            case 6->{require(session.jobsReady(),"Await probe ack");proposal=session.proposePlan(objective,owner).id();phase=7;}
            case 7->{require(view().state()==GoalPlanner.State.QUEUED&&model.calls.size()==alternative+2,"Await bounded invalid proposal");unchanged();String candidate=switch(alternative){
                case 0->Plan.encode(new Plan.Proposal(List.of(new Plan.Node(0,0,2,List.of(1)),new Plan.Node(1,1,3,List.of(0)))));
                case 1->valid().replace("\"schema\":1","\"actor\":\"foreign\",\"schema\":1");
                default->Plan.encode(new Plan.Proposal(java.util.stream.IntStream.range(0,9).mapToObj(i->new Plan.Node(i,i%2,1,List.of())).toList()));};
                model.calls.getLast().respond(candidate);phase=8;}
            case 8->{require(view().state()==GoalPlanner.State.REJECTED,"Await deterministic refusal");unchanged();require(job(objective).children().isEmpty(),"Invalid proposal left executable fragments");
                rejected.add(objective);require(session.cancelPlanningObjective(objective,owner).accepted(),"Rejected objective cancellation refused");alternative++;phase=alternative<3?5:9;}
            case 9->{require(session.jobsReady(),"Await final objective capacity");objective=create();phase=10;}
            case 10->{require(session.jobsReady(),"Await valid objective ack");proposal=session.proposePlan(objective,owner).id();phase=11;}
            case 11->{require(view().state()==GoalPlanner.State.QUEUED&&model.calls.size()==5,"Await valid controlled plan");unchanged();model.calls.getLast().respond(valid());phase=12;}
            case 12->{require(view().state()==GoalPlanner.State.ADMITTED,"Await whole Jobs admission");children=view().jobs();require(children.size()==2,"Valid plan did not admit exactly two children");
                require(job(objective).usage().get(Budgets.Kind.CANDIDATES)==1L,"Parent candidate usage changed");
                require(session.proposePlan(objective,owner).jobs().equals(children),"Replay changed acknowledged child identities");session.inference(false);model.available=false;phase=13;}
            case 13->{require(session.jobsReady()&&session.leasesReady()&&job(objective).state()==Jobs.State.SUCCEEDED,
                    "Await actual model-free P0 child execution root="+job(objective).state()+" stocks="+stock(destination)+","+stock(other)+" children="+
                        children.stream().map(id->{var child=job(id);return child.state()+":"+child.reason()+":"+child.fulfilled()+":"+child.usage();}).toList());
                require(stock(destination)==2&&stock(other)==3,"Actual deliveries did not cover both outcomes");require(model.calls.size()==5,"Known execution called the model");
                for(int i=0;i<2;i++)require(job(children.get(i)).state()==Jobs.State.SUCCEEDED&&job(children.get(i)).fulfilled()==(i==0?2:3),"Actual receipt attribution incomplete");
                require(session.retainPlanningTemplate(objective,owner),"Demonstrated topology retention pending");template=Plan.stable("demonstrated-template:1:"+objective);phase=14;}
            case 14->{require(session.planningTemplates(owner).stream().anyMatch(t->t.id().equals(template)),"Await template storage ack");quietUntil=h.getLevel().getGameTime()+20;phase=16;}
            case 15->{require(stock(destination)==2&&stock(other)==3&&job(objective).state()==Jobs.State.SUCCEEDED,"Restart lost effects or goal completion");
                require(job(objective).children().equals(children)&&session.proposePlan(objective,owner).jobs().equals(children),"Restart reconstructed child identities");
                require(job(cancelled).state()==Jobs.State.CANCELLED&&job(cancelled).children().isEmpty(),"Restart reactivated cancelled proposal");
                require(session.planningTemplates(owner).size()==1&&session.planningTemplates(owner).getFirst().id().equals(template),"Demonstrated topology lost on restart");
                require(session.queuedJobIds(player,false).size()==((Number)marker.get("jobs")).intValue(),"Restart invented jobs");
                require(model.calls.isEmpty(),"Restart invoked a model");quietUntil=h.getLevel().getGameTime()+20;phase=16;}
            case 16->{require(h.getLevel().getGameTime()>=quietUntil,"Observe quiet saved world");require(stock(destination)==2&&stock(other)==3,"Effects replayed in quiet world");save();phase=20;}
            case 20->{require(closing.isDone()&&saving.isDone(),"Await production owner closure");closing.join();saving.join();
                System.out.println("IMP-019 GP-19 accepted phase="+(warm?"warm":"cold")+" first=2 second=3 planningEffects=0 knownModelCalls=0 children=2 noReplay=true");phase=40;}
        }}
        void save(){var proof=new LinkedHashMap<String,Object>(marker);if(!warm){proof.put("citizen",actor.citizenId().toString());proof.put("objective",objective.toString());proof.put("cancelled",cancelled.toString());
                proof.put("template",template.toString());proof.put("children",children.stream().map(UUID::toString).toList());proof.put("jobs",session.queuedJobIds(player,false).size());proof.put("rejected",rejected.stream().map(UUID::toString).toList());}
            var witness=Map.<String,Object>of("schema",1,"architecture","0.5","phase",warm?"warm":"cold","first",stock(destination),"second",stock(other),"planningEffects",0,
                    "knownModelCalls",0,"proposalCalls",model.calls.size(),"children",children.stream().map(UUID::toString).toList(),"objective",objective.toString());
            session.close();closing=session.storeClosure();saving=CompletableFuture.runAsync(()->{try{if(!warm)Files.writeString(root.resolve("restart.json"),StrictJson.canonical(proof)+"\n");
                Files.writeString(root.resolve(warm?"warm.json":"cold.json"),StrictJson.canonical(witness)+"\n");}catch(Exception e){throw new CompletionException(e);}});}
        UUID id(String key){return UUID.fromString((String)marker.get(key));}
        void require(boolean condition,String message){h.assertTrue(condition,message);}
    }
}
