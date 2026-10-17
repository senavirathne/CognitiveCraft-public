package dev.aivillages.core.kernel;
import java.time.*;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SurvivalGateway.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Explicit fake physical boundary; native placement has separate acceptance. */
final class PlacementTestSupport {
    static final Clock CLOCK=TableTestSupport.CLOCK;
    static final TrustedContext OWNER=TableTestSupport.OWNER;
    static final ActorRef ACTOR=TableTestSupport.ACTOR;
    static final ContainerRef SOURCE=TableTestSupport.SOURCE;
    static final FacilityRef TARGET=new FacilityRef(ACTOR.dimension(),4,0,1);
    static ValidatedRequest bound(int amount){return new ValidatedRequest(new CapabilityRequest(CraftingTablePlacement.ID,Map.of("actor",new ActorValue(ACTOR),"source",new ContainerValue(SOURCE),"target",new FacilityValue(TARGET),"amount",new IntValue(amount))),OWNER,JobLifecycleStoreTest.observation());}
    static String program(){return StrictJson.canonical(Map.of("schema",1,"capability",CraftingTablePlacement.ID.name(),"capabilityVersion",1,"dependencies",List.of(),"body",List.of(
            TableTestSupport.call(GatewayPrimitives.Operation.OBSERVE_TABLE_SITE),TableTestSupport.call(GatewayPrimitives.Operation.MOVE_TO_INPUT),TableTestSupport.call(GatewayPrimitives.Operation.WITHDRAW_TABLE_ITEM),
            TableTestSupport.call(GatewayPrimitives.Operation.MOVE_TO_PLACEMENT),TableTestSupport.call(GatewayPrimitives.Operation.PLACE_CRAFTING_TABLE),Map.of("op","result","value",Map.of("param","amount")))));}
    static final class Driver {
        final World world=new World();final ValidatedRequest bound=bound(1);final UUID run=UUID.randomUUID();
        final Budgets.Ledger usage=new Budgets.Ledger(OakPlankProduction.executionLimits(1000).total(),CLOCK);
        final SurvivalGateway gateway=new SurvivalGateway(world,(a,e,c)->world.authorized,new Limits(8,512,64,16,256,1,200,32),CLOCK);
        final List<ResourceDelivery.Receipt> receipts=new ArrayList<>();long effects;
        ActionReceipt action(GatewayPrimitives.Operation op){
            var args=new LinkedHashMap<String,Value>();op.signature().parameters().forEach(p->args.put(p.name(),bound.request().arguments().get(p.name())));
            var h=gateway.start(bound,new RunCorrelation(run,new ArtifactRef(CraftingTablePlacement.ID,"b".repeat(64)),null),op.requirement(),args,usage);
            ActionReceipt r=null;for(int i=0;i<240;i++){world.tick++;r=gateway.poll(h);if(r.terminal())break;}
            if(r==null || !r.terminal())throw new AssertionError("Unterminated fake operation");receipts.addAll(r.resourceReceipts());effects+=r.committedEffects();return r;
        }
        void prepare(){require(action(GatewayPrimitives.Operation.OBSERVE_TABLE_SITE));require(action(GatewayPrimitives.Operation.MOVE_TO_INPUT));require(action(GatewayPrimitives.Operation.WITHDRAW_TABLE_ITEM));require(action(GatewayPrimitives.Operation.MOVE_TO_PLACEMENT));}
        void place(){prepare();require(action(GatewayPrimitives.Operation.PLACE_CRAFTING_TABLE));}
        void require(ActionReceipt r){if(r.reason()!=null)throw new AssertionError(r.reason());}
    }
    static final class World implements WorldAccess {
        long tick;int source=3,held=7;boolean authorized=true,known=true,changed,occupied,unsupported,unreachable,inconsistent;UUID owner;
        String custody(){return RecipeTransaction.fingerprint("held:"+held);}String stock(){return RecipeTransaction.fingerprint("stock:"+source);}
        public boolean onGameThread(){return true;}public long tick(){return tick;}
        public ActorState actor(ActorRef a){return new ActorState(true,true,a.dimension(),1,0,1,0);}
        public boolean acquire(ActorRef a,UUID run){if(owner!=null && !owner.equals(run))return false;owner=run;return true;}
        public boolean holds(ActorRef a,UUID run){return run.equals(owner);}public void release(ActorRef a,UUID run){if(run.equals(owner))owner=null;}public void stopMovement(ActorRef a){}
        public Reason permit(ValidatedRequest r,Effect e,Cell c){return authorized?null:Reason.AUTHORITY_DENIED;}
        public CropState crop(Cell c){throw new AssertionError("No harvest");}public DropState dropped(UUID id){throw new AssertionError("No drops");}
        public StockState inventory(ActorRef a){return new StockState(ObservationStatus.PRESENT,"actor",0,0);}public StockState container(ContainerRef c){return new StockState(ObservationStatus.PRESENT,"box",0,0);}
        public ItemStock resourceInventory(ActorRef a,ResourceDelivery.ItemIdentity i){return new ItemStock(ObservationStatus.PRESENT,"actor",held,64,custody(),null);}
        public Movement move(ActorRef a,Cell c,int max){return new Movement(!unreachable,unreachable,0,0);}
        public Harvest harvest(ActorRef a,Cell c){throw new AssertionError("No harvest");}public Pickup pickup(ActorRef a,UUID id,int q){throw new AssertionError("No pickup");}public Transfer transferOne(ActorRef a,ContainerRef c){throw new AssertionError("No transfer");}
        public PlacementTransaction.Preflight placementPreflight(ValidatedRequest b){
            if(!known)return PlacementTransaction.Preflight.blocked(Reason.STALE_OBSERVATION);
            if(occupied || unsupported)return PlacementTransaction.Preflight.blocked(Reason.TARGET_INVALID);
            return new PlacementTransaction.Preflight(ObservationStatus.PRESENT,source,27,"source",stock(),custody(),RecipeTransaction.fingerprint("site"),TARGET,SOURCE,null);
        }
        public PlacementTransaction.Mutation withdrawTableItem(ActorRef a,PlacementTransaction.Preflight p,String custody,String stock){
            if(changed || !custody().equals(custody) || !stock().equals(stock))return PlacementTransaction.Mutation.blocked(Reason.STALE_OBSERVATION);
            if(source<1)return PlacementTransaction.Mutation.blocked(Reason.RESOURCE_MISSING);
            var before=custody();source--;held++;return new PlacementTransaction.Mutation(1,0,false,before,custody(),stock(),"",null);
        }
        public PlacementTransaction.Mutation placeTable(ActorRef a,PlacementTransaction.Preflight p,String custody,String stock){
            if(changed || occupied || unsupported || !custody().equals(custody) || !stock().equals(stock))return PlacementTransaction.Mutation.blocked(Reason.STALE_OBSERVATION);
            var before=custody();held--;occupied=true;return new PlacementTransaction.Mutation(0,1,true,before,custody(),"","real-facility",inconsistent?Reason.ACTION_FAILED:null);
        }
    }
}
