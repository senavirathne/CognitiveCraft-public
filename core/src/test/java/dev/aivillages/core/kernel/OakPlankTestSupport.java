package dev.aivillages.core.kernel;

import java.time.*;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SurvivalGateway.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Explicit fake physical boundary. Native crafting claims require the separate Fabric cases. */
final class OakPlankTestSupport {
    static final Clock CLOCK=Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC);
    static final TrustedContext OWNER=JobLifecycleStoreTest.owner(UUID.randomUUID());
    static final ActorRef ACTOR=JobLifecycleStoreTest.actor();
    static final ContainerRef SOURCE=new ContainerRef(ACTOR.dimension(),1,0,1),TARGET=new ContainerRef(ACTOR.dimension(),4,0,1);
    static ValidatedRequest bound(int amount) {return bound(amount,OakPlankProduction.ID);}
    static ValidatedRequest bound(int amount,CapabilityId capability) {
        return new ValidatedRequest(new CapabilityRequest(capability,Map.of("actor",new ActorValue(ACTOR),
                "amount",new IntValue(amount),"source",new ContainerValue(SOURCE),"destination",new ContainerValue(TARGET))),OWNER,JobLifecycleStoreTest.observation());
    }
    static String program() {
        var count=Map.of("operator","DIV","left",Map.of("operator","ADD","left",Map.of("param","amount"),"right",Map.of("int",3)),"right",Map.of("int",4));
        return StrictJson.canonical(Map.of("schema",1,"capability",OakPlankProduction.ID.name(),"capabilityVersion",1,"dependencies",List.of(),
                "body",List.of(call(GatewayPrimitives.Operation.OBSERVE_PLANK_INPUTS),Map.of("op","repeat","count",count,"body",List.of(
                        call(GatewayPrimitives.Operation.MOVE_TO_INPUT),call(GatewayPrimitives.Operation.WITHDRAW_OAK_LOG),
                        call(GatewayPrimitives.Operation.CRAFT_OAK_PLANKS),call(GatewayPrimitives.Operation.MOVE_TO_DESTINATION),
                        call(GatewayPrimitives.Operation.TRANSFER_OAK_PLANKS))),Map.of("op","result","value",Map.of("param","amount")))));
    }
    static Map<String,Object> call(GatewayPrimitives.Operation op) {
        var args=new LinkedHashMap<String,Object>();op.signature().parameters().forEach(p->args.put(p.name(),Map.of("param",p.name())));
        return Map.of("op","call","kind","primitive","id",op.signature().id(),"version",1,"fingerprint",op.signature().fingerprint(),
                "args",args,"into",op.name().toLowerCase(Locale.ROOT));
    }
    static final class Driver {
        final World world;final ValidatedRequest bound;final UUID run=UUID.randomUUID();final Budgets.Ledger usage;
        final SurvivalGateway gateway;final List<ResourceDelivery.Receipt> receipts=new ArrayList<>();long effects;
        Driver(int amount) {this(amount,new World());}
        Driver(int amount,World world) {
            this.world=world;bound=bound(amount,world.capability);usage=new Budgets.Ledger(OakPlankProduction.executionLimits(1000).total(),CLOCK);
            gateway=new SurvivalGateway(world,(a,e,c)->world.authorized,new Limits(8,512,64,16,256,1,200,32),CLOCK);
        }
        ActionReceipt action(GatewayPrimitives.Operation op) {
            var args=new LinkedHashMap<String,Value>();op.signature().parameters().forEach(p->args.put(p.name(),bound.request().arguments().get(p.name())));
            var ref=new ArtifactRef(world.capability,"a".repeat(64));
            var h=gateway.start(bound,new RunCorrelation(run,ref,null),op.requirement(),args,usage);
            ActionReceipt result=null;
            for(int i=0;i<240;i++) {world.tick++;result=gateway.poll(h);if(result.terminal())break;}
            if(result==null || !result.terminal())throw new AssertionError("Fake action did not terminate");
            receipts.addAll(result.resourceReceipts());effects+=result.committedEffects();return result;
        }
        void produce() {
            var ops=RecipeProduction.operations(world.capability);require(action(ops.getFirst()));
            for(int i=0;i<OakPlankProduction.batches(((IntValue)bound.request().arguments().get("amount")).value());i++) {
                for(var op:ops.subList(1,ops.size()))require(action(op));
            }
        }
        void require(ActionReceipt receipt) {if(receipt.reason()!=null)throw new AssertionError(receipt.reason());}
    }
    static final class World implements WorldAccess {
        long tick;int logs=16,actorLogs,actorPlanks,chestPlanks,withdrawals,crafts,deposits,other;
        int outputCapacity=4096;boolean authorized=true,known=true,recipeChanged,fullCustody,targetChanged,unreachable;
        UUID held;
        final CapabilityId capability;final RecipeTransaction.Snapshot recipe;
        World() {this(OakPlankProduction.ID);}
        World(CapabilityId capability) {this.capability=capability;recipe=new RecipeTransaction.Snapshot(RecipeProduction.of(capability).recipe(),RecipeTransaction.fingerprint("test-recipe"),1,1,4,0);}
        String custody() {return RecipeTransaction.fingerprint(actorLogs+":"+actorPlanks+":"+other);}
        String sourceHash() {return RecipeTransaction.fingerprint("logs:"+logs);}
        public boolean onGameThread() {return true;}public long tick() {return tick;}
        public ActorState actor(ActorRef actor) {return new ActorState(true,true,actor.dimension(),1,0,1,0);}
        public boolean acquire(ActorRef actor,UUID run) {if(held!=null && !held.equals(run))return false;held=run;return true;}
        public boolean holds(ActorRef actor,UUID run) {return run.equals(held);}
        public void release(ActorRef actor,UUID run) {if(run.equals(held))held=null;}public void stopMovement(ActorRef actor) { }
        public Reason permit(ValidatedRequest r,Effect e,Cell c) {return authorized ? null : Reason.AUTHORITY_DENIED;}
        public CropState crop(Cell c) {throw new AssertionError("No harvesting");}
        public DropState dropped(UUID id) {throw new AssertionError("No drops");}
        public StockState inventory(ActorRef actor) {return new StockState(ObservationStatus.PRESENT,"actor",0,0);}
        public StockState container(ContainerRef ref) {return new StockState(ObservationStatus.PRESENT,"box",0,0);}
        public Movement move(ActorRef actor,Cell target,int n) {return new Movement(!unreachable,unreachable,0,0);}
        public Harvest harvest(ActorRef actor,Cell cell) {throw new AssertionError("No harvesting");}
        public Pickup pickup(ActorRef actor,UUID id,int amount) {throw new AssertionError("No pickup");}
        public Transfer transferOne(ActorRef actor,ContainerRef ref) {throw new AssertionError("No wheat transfer");}
        public RecipeTransaction.Preflight recipePreflight(ValidatedRequest r) {
            return new RecipeTransaction.Preflight(known ? ObservationStatus.PRESENT : ObservationStatus.UNKNOWN,
                    known ? recipe : null,logs,outputCapacity,"source","destination",sourceHash(),custody(),27,27,
                    known ? null : Reason.STALE_OBSERVATION);
        }
        public RecipeTransaction.Mutation withdrawRecipeInput(ActorRef actor,ContainerRef source,RecipeTransaction.Preflight capture,String expected,String from) {
            if(!custody().equals(expected) || !sourceHash().equals(from))return RecipeTransaction.Mutation.blocked(Reason.STALE_OBSERVATION);
            if(logs<1)return RecipeTransaction.Mutation.blocked(Reason.RESOURCE_MISSING);
            if(fullCustody)return RecipeTransaction.Mutation.blocked(Reason.TARGET_UNAVAILABLE);
            String before=custody();logs--;actorLogs++;withdrawals++;return new RecipeTransaction.Mutation(0,0,1,0,before,custody(),sourceHash(),null);
        }
        public RecipeTransaction.Mutation craftRecipe(ActorRef actor,RecipeTransaction.Preflight capture,String expected) {
            if(!custody().equals(expected))return RecipeTransaction.Mutation.blocked(Reason.STALE_OBSERVATION);
            if(recipeChanged)return RecipeTransaction.Mutation.blocked(Reason.ARTIFACT_INCOMPATIBLE);
            if(actorLogs<1)return RecipeTransaction.Mutation.blocked(Reason.RESOURCE_MISSING);
            if(fullCustody)return RecipeTransaction.Mutation.blocked(Reason.TARGET_UNAVAILABLE);
            String before=custody();actorLogs--;actorPlanks+=4;crafts++;return new RecipeTransaction.Mutation(1,4,0,0,before,custody(),"",null);
        }
        public RecipeTransaction.Mutation depositRecipeOutput(ActorRef actor,ContainerRef target,RecipeTransaction.Preflight capture,String expected,int amount) {
            if(!custody().equals(expected) || targetChanged)return RecipeTransaction.Mutation.blocked(Reason.STALE_OBSERVATION);
            if(amount>outputCapacity)return RecipeTransaction.Mutation.blocked(Reason.TARGET_UNAVAILABLE);
            String before=custody();actorPlanks-=amount;chestPlanks+=amount;outputCapacity-=amount;deposits++;
            return new RecipeTransaction.Mutation(0,0,amount,0,before,custody(),"",null);
        }
    }
}
