package dev.aivillages.core.kernel;

import java.time.*;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SurvivalGateway.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Explicit fake physical boundary. Native crafting claims require the separate Fabric cases. */
final class AxeTestSupport {
    static final Clock CLOCK=Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC);
    static final TrustedContext OWNER=JobLifecycleStoreTest.owner(UUID.randomUUID());
    static final ActorRef ACTOR=JobLifecycleStoreTest.actor();
    static final ContainerRef SOURCE=new ContainerRef(ACTOR.dimension(),1,0,1),TARGET=new ContainerRef(ACTOR.dimension(),4,0,1);
    static final FacilityRef TABLE=new FacilityRef(ACTOR.dimension(),2,0,2);
    static ValidatedRequest bound(int amount) {
        return new ValidatedRequest(new CapabilityRequest(WoodenAxeProduction.ID,Map.of("actor",new ActorValue(ACTOR),
                "amount",new IntValue(amount),"source",new ContainerValue(SOURCE),"destination",new ContainerValue(TARGET),"table",new FacilityValue(TABLE))),OWNER,JobLifecycleStoreTest.observation());
    }
    static String program() {
        var count=Map.of("operator","DIV","left",Map.of("operator","ADD","left",Map.of("param","amount"),"right",Map.of("int",0)),"right",Map.of("int",1));
        return StrictJson.canonical(Map.of("schema",1,"capability",WoodenAxeProduction.ID.name(),"capabilityVersion",1,"dependencies",List.of(),
                "body",List.of(call(GatewayPrimitives.Operation.OBSERVE_AXE_INPUTS),Map.of("op","repeat","count",count,"body",List.of(
                        call(GatewayPrimitives.Operation.MOVE_TO_INPUT),call(GatewayPrimitives.Operation.WITHDRAW_AXE_INPUTS),
                        call(GatewayPrimitives.Operation.MOVE_TO_TABLE),call(GatewayPrimitives.Operation.CRAFT_WOODEN_AXE),call(GatewayPrimitives.Operation.MOVE_TO_DESTINATION),
                        call(GatewayPrimitives.Operation.TRANSFER_WOODEN_AXES))),Map.of("op","result","value",Map.of("param","amount")))));
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
            this.world=world;bound=bound(amount);usage=new Budgets.Ledger(WoodenAxeProduction.executionLimits(1000).total(),CLOCK);
            gateway=new SurvivalGateway(world,(a,e,c)->world.authorized,new Limits(8,512,64,16,256,1,200,32),CLOCK);
        }
        ActionReceipt action(GatewayPrimitives.Operation op) {
            var args=new LinkedHashMap<String,Value>();op.signature().parameters().forEach(p->args.put(p.name(),bound.request().arguments().get(p.name())));
            var ref=new ArtifactRef(WoodenAxeProduction.ID,"a".repeat(64));
            var h=gateway.start(bound,new RunCorrelation(run,ref,null),op.requirement(),args,usage);
            ActionReceipt result=null;
            for(int i=0;i<240;i++) {world.tick++;result=gateway.poll(h);if(result.terminal())break;}
            if(result==null || !result.terminal())throw new AssertionError("Fake action did not terminate");
            receipts.addAll(result.resourceReceipts());effects+=result.committedEffects();return result;
        }
        void produce() {
            require(action(GatewayPrimitives.Operation.OBSERVE_AXE_INPUTS));
            for(int i=0;i<WoodenAxeProduction.batches(((IntValue)bound.request().arguments().get("amount")).value());i++) {
                require(action(GatewayPrimitives.Operation.MOVE_TO_INPUT));require(action(GatewayPrimitives.Operation.WITHDRAW_AXE_INPUTS));
                require(action(GatewayPrimitives.Operation.MOVE_TO_TABLE));require(action(GatewayPrimitives.Operation.CRAFT_WOODEN_AXE));require(action(GatewayPrimitives.Operation.MOVE_TO_DESTINATION));
                require(action(GatewayPrimitives.Operation.TRANSFER_WOODEN_AXES));
            }
        }
        void require(ActionReceipt receipt) {if(receipt.reason()!=null)throw new AssertionError(receipt.reason());}
    }
    static final class World implements WorldAccess {
        long tick;int logs=12,sticks=8,actorSticks,actorPlanks,actorAxes,chestAxes,withdrawals,crafts,deposits,other;
        int outputCapacity=4096;boolean authorized=true,known=true,recipeChanged,fullCustody,targetChanged,unreachable;
        UUID held;
        final RecipeTransaction.Snapshot recipe=new RecipeTransaction.Snapshot(WoodenAxeProduction.RECIPE,RecipeTransaction.fingerprint("test-recipe"),1,5,1,0);
        String custody() {return RecipeTransaction.fingerprint(actorPlanks+":"+actorSticks+":"+actorAxes+":"+other);}
        String sourceHash() {return RecipeTransaction.fingerprint("logs:"+logs+":"+sticks);}
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
                    known ? null : Reason.STALE_OBSERVATION,sticks,TABLE,"fake-table");
        }
        public RecipeTransaction.Mutation withdrawRecipeInput(ActorRef actor,ContainerRef source,RecipeTransaction.Preflight capture,String expected,String from) {
            if(!custody().equals(expected) || !sourceHash().equals(from))return RecipeTransaction.Mutation.blocked(Reason.STALE_OBSERVATION);
            if(logs<3 || sticks<2)return RecipeTransaction.Mutation.blocked(Reason.RESOURCE_MISSING);
            if(fullCustody)return RecipeTransaction.Mutation.blocked(Reason.TARGET_UNAVAILABLE);
            String before=custody();logs-=3;sticks-=2;actorPlanks+=3;actorSticks+=2;withdrawals++;return new RecipeTransaction.Mutation(0,0,5,0,before,custody(),sourceHash(),null);
        }
        public RecipeTransaction.Mutation craftRecipe(ActorRef actor,RecipeTransaction.Preflight capture,String expected) {
            if(!custody().equals(expected))return RecipeTransaction.Mutation.blocked(Reason.STALE_OBSERVATION);
            if(recipeChanged)return RecipeTransaction.Mutation.blocked(Reason.ARTIFACT_INCOMPATIBLE);
            if(actorPlanks<3 || actorSticks<2)return RecipeTransaction.Mutation.blocked(Reason.RESOURCE_MISSING);
            if(fullCustody)return RecipeTransaction.Mutation.blocked(Reason.TARGET_UNAVAILABLE);
            String before=custody();actorPlanks-=3;actorSticks-=2;actorAxes++;crafts++;return new RecipeTransaction.Mutation(5,1,0,0,before,custody(),"",null);
        }
        public RecipeTransaction.Mutation depositRecipeOutput(ActorRef actor,ContainerRef target,RecipeTransaction.Preflight capture,String expected,int amount) {
            if(!custody().equals(expected) || targetChanged)return RecipeTransaction.Mutation.blocked(Reason.STALE_OBSERVATION);
            if(amount>outputCapacity)return RecipeTransaction.Mutation.blocked(Reason.TARGET_UNAVAILABLE);
            String before=custody();actorAxes-=amount;chestAxes+=amount;outputCapacity-=amount;deposits++;
            return new RecipeTransaction.Mutation(0,0,amount,0,before,custody(),"",null);
        }
    }
}
