package dev.aivillages.core.kernel;

import java.time.*;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SurvivalGateway.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Test-only physical/authority fixture; never an admitted production method or native-loot proof. */
final class OakLogTestSupport {
    static final Clock CLOCK=Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC);
    static final TrustedContext OWNER=JobLifecycleStoreTest.owner(UUID.randomUUID());
    static final ActorRef ACTOR=JobLifecycleStoreTest.actor();
    static final Cuboid SOURCE=new Cuboid(ACTOR.dimension(),4,0,0,11,0,0);
    static final ContainerRef BOX=new ContainerRef(ACTOR.dimension(),13,0,0);
    static ValidatedRequest bound(int amount) {
        return new ValidatedRequest(new CapabilityRequest(OakLogDelivery.ID,Map.of(
                "actor",new ActorValue(ACTOR),"amount",new IntValue(amount),"source",new AreaValue(SOURCE),
                "destination",new ContainerValue(BOX))),OWNER,JobLifecycleStoreTest.observation());
    }
    static List<ResourceDelivery.Receipt> chain(ValidatedRequest bound,UUID run,long quantity) {
        UUID batch=UUID.randomUUID(),drop=UUID.randomUUID();
        return List.of(ResourceDelivery.Stage.HARVEST,ResourceDelivery.Stage.PICKUP,ResourceDelivery.Stage.DEPOSIT).stream().map(stage -> new ResourceDelivery.Receipt(UUID.randomUUID(),
                bound.context(),run,UUID.randomUUID(),batch,((ActorValue)bound.request().arguments().get("actor")).value(),
                ((AreaValue)bound.request().arguments().get("source")).value(),((ContainerValue)bound.request().arguments().get("destination")).value(),
                ResourceDelivery.OAK_LOG,stage,quantity,new ResourceDelivery.BlockPosition(ACTOR.dimension(),4,0,0),List.of(drop))).toList();
    }
    static ResourceDelivery.Receipt changed(ResourceDelivery.Receipt r,UUID id,TrustedContext origin,
                                            ResourceDelivery.ItemIdentity item,long quantity) {
        return new ResourceDelivery.Receipt(id,origin,r.runId(),r.operationId(),r.batchId(),r.actor(),r.source(),r.destination(),
                item,r.stage(),quantity,r.sourceBlock(),r.dropIds());
    }
    static String program() {
        var actor=Map.<String,Object>of("actor",Map.of("param","actor"));
        var source=Map.<String,Object>of("actor",Map.of("param","actor"),"source",Map.of("param","source"));
        var dest=Map.<String,Object>of("actor",Map.of("param","actor"),"destination",Map.of("param","destination"));
        var transfer=new HashMap<String,Object>(dest); transfer.put("amount",Map.of("int",1L));
        var ops=GatewayPrimitives.Operation.class;
        var body=List.of(Map.of("op","repeat","count",Map.of("param","amount"),"body",List.of(
                call(GatewayPrimitives.Operation.APPROACH_OAK_LOG,"approach",source),
                call(GatewayPrimitives.Operation.HARVEST_NEXT_OAK_LOG,"harvest",source),
                Map.of("op","repeat","count",Map.of("int",20L),"body",List.of(call(GatewayPrimitives.Operation.OBSERVE_OAK_INVENTORY,"wait",actor))),
                call(GatewayPrimitives.Operation.APPROACH_OAK_LOG,"atDrop",source),
                call(GatewayPrimitives.Operation.PICKUP_TRACKED_OAK_LOGS,"pickup",actor),
                call(GatewayPrimitives.Operation.MOVE_TO_DESTINATION,"atDestination",dest),
                call(GatewayPrimitives.Operation.TRANSFER_OAK_LOGS,"deposit",transfer))),
                Map.of("op","result","value",Map.of("param","amount")));
        return StrictJson.canonical(Map.of("schema",1L,"capability",OakLogDelivery.ID.name(),"capabilityVersion",1L,
                "dependencies",List.of(),"body",body));
    }
    static Map<String,Object> call(GatewayPrimitives.Operation op,String into,Map<String,Object> args) {
        return Map.of("op","call","kind","primitive","id",op.signature().id(),"version",1L,
                "fingerprint",op.signature().fingerprint(),"args",args,"into",into);
    }
    static final class World implements WorldAccess {
        long tick,actorLogs,chestLogs; int capacity=64,actorCapacity=64,harvests,pickups,transfers,moves,stops;
        boolean authorized=true,loaded=true,unreachable; UUID held;
        Cell actorPosition=new Cell(ACTOR.dimension(),0,0,0);
        final Set<Cell> logs=new LinkedHashSet<>(); final Map<UUID,ItemDropState> drops=new LinkedHashMap<>();
        World(int count,long actorLogs,long chestLogs) {
            this.actorLogs=actorLogs;this.chestLogs=chestLogs;
            for(int i=0;i<count;i++) logs.add(new Cell(ACTOR.dimension(),4+i,0,0));
        }
        public boolean onGameThread() { return true; }
        public long tick() { return tick; }
        public ActorState actor(ActorRef actor) { return new ActorState(loaded,true,actor.dimension(),actorPosition.x(),0,0,0); }
        public boolean acquire(ActorRef actor,UUID run) { if(held!=null)return false; held=run;return true; }
        public boolean holds(ActorRef actor,UUID run) { return run.equals(held); }
        public void release(ActorRef actor,UUID run) { if(run.equals(held))held=null; }
        public void stopMovement(ActorRef actor) { stops++; }
        public Reason permit(ValidatedRequest request,Effect effect,Cell target) { return authorized ? null : Reason.AUTHORITY_DENIED; }
        public CropState crop(Cell cell) { return new CropState(ObservationStatus.ABSENT,false,cell.identity()); }
        public StockState inventory(ActorRef actor) { return new StockState(ObservationStatus.PRESENT,"actor",0,0); }
        public StockState container(ContainerRef destination) { return new StockState(ObservationStatus.PRESENT,"container",0,0); }
        public DropState dropped(UUID id) { throw new AssertionError("Oak may not use wheat drop API"); }
        public Harvest harvest(ActorRef actor,Cell target) { throw new AssertionError("Oak may not harvest wheat"); }
        public Pickup pickup(ActorRef actor,UUID id,int expected) { throw new AssertionError("Oak may not pick wheat"); }
        public Transfer transferOne(ActorRef actor,ContainerRef target) { throw new AssertionError("Oak may not transfer wheat"); }
        public Movement move(ActorRef actor,Cell target,int maximum) {
            moves++; if(unreachable) return new Movement(false,true,1,0);
            int desired=target.x()-1;
            if(actorPosition.x()==desired)return new Movement(true,false,0,0);
            actorPosition=new Cell(actor.dimension(),actorPosition.x()+Integer.signum(desired-actorPosition.x()),0,0);
            return new Movement(actorPosition.x()==desired,false,1,1);
        }
        public Movement moveToSource(ActorRef actor,Cell target,int maximum) {
            if(logs.contains(target))throw new AssertionError("Solid logs require adjacent approach");
            return move(actor,target,maximum);
        }
        public ItemBlock resourceBlock(Cell cell,ResourceDelivery.ItemIdentity item) {
            return new ItemBlock(!loaded ? ObservationStatus.UNKNOWN : logs.contains(cell) ? ObservationStatus.PRESENT : ObservationStatus.ABSENT,
                    logs.contains(cell),cell.identity(),60);
        }
        public ItemStock resourceInventory(ActorRef actor,ResourceDelivery.ItemIdentity item) {
            return new ItemStock(ObservationStatus.PRESENT,"actor",actorLogs,Math.max(0,actorCapacity-actorLogs),"stock:"+actorLogs,null);
        }
        public ItemStock resourceContainer(ContainerRef box,ResourceDelivery.ItemIdentity item) {
            return new ItemStock(ObservationStatus.PRESENT,"box",chestLogs,Math.max(0,capacity-chestLogs),"",null);
        }
        public Reason resourceBreakEligibility(ActorRef actor,Cell target,ResourceDelivery.ItemIdentity item) {
            return Math.abs(actorPosition.x()-target.x())<=2 ? null : Reason.TARGET_UNAVAILABLE;
        }
        public ItemHarvest resourceHarvest(ActorRef actor,Cell cell,ResourceDelivery.ItemIdentity item,long elapsed) {
            if(elapsed<60) throw new AssertionError("Early break");
            if(!logs.remove(cell))return new ItemHarvest(false,List.of(),Reason.TARGET_INVALID);
            harvests++; UUID id=UUID.randomUUID();drops.put(id,new ItemDropState(ObservationStatus.PRESENT,cell,1));
            return new ItemHarvest(true,List.of(new ItemDrop(id,1)),null);
        }
        public ItemDropState resourceDrop(UUID id,ResourceDelivery.ItemIdentity item) {
            return drops.getOrDefault(id,new ItemDropState(ObservationStatus.ABSENT,new Cell(ACTOR.dimension(),0,0,0),0));
        }
        public ItemPickup resourcePickup(ActorRef actor,UUID id,int expected,ResourceDelivery.ItemIdentity item) {
            var d=drops.get(id); if(d==null || d.quantity()!=expected)return new ItemPickup(0,expected,Reason.STALE_OBSERVATION);
            int quantity=(int)Math.min(expected,actorCapacity-actorLogs); if(quantity<1)return new ItemPickup(0,expected,Reason.TARGET_UNAVAILABLE);
            actorLogs+=quantity;pickups++; if(quantity==expected)drops.remove(id);
            else drops.put(id,new ItemDropState(d.status(),d.cell(),expected-quantity));
            return new ItemPickup(quantity,expected-quantity,null);
        }
        public ItemTransfer resourceTransferOne(ActorRef actor,ContainerRef target,ResourceDelivery.ItemIdentity item) {
            if(chestLogs>=capacity)return new ItemTransfer(0,Reason.TARGET_UNAVAILABLE);
            actorLogs--;chestLogs++;transfers++;return new ItemTransfer(1,null);
        }
    }
    static final class GatewayScene {
        final World world; final ValidatedRequest request; final UUID run=UUID.randomUUID();
        final SurvivalGateway gateway; final Budgets.Ledger budget;
        final List<ResourceDelivery.Receipt> receipts=new ArrayList<>();
        GatewayScene(int amount,long actorLogs,long chestLogs) {this(amount,actorLogs,chestLogs,OakLogDelivery.ID);}
        GatewayScene(int amount,long actorLogs,long chestLogs,CapabilityId capability) {
            var original=bound(amount);request=new ValidatedRequest(new CapabilityRequest(capability,original.request().arguments()),original.context(),original.observation());world=new World(amount,actorLogs,chestLogs);
            gateway=new SurvivalGateway(world,(a,e,c)->world.authorized && c.equals(OWNER),
                    new Limits(1,64,64,16,256,1,200,32),CLOCK);
            budget=new Budgets.Ledger(OakLogDelivery.executionLimits(CLOCK.millis()).total(),CLOCK);
        }
        ActionHandle start(GatewayPrimitives.Operation op) {
            Map<String,Value> args=new HashMap<>();
            for(Parameter p:op.signature().parameters())args.put(p.name(),p.name().equals("amount") ? new IntValue(1) : request.request().arguments().get(p.name()));
            return gateway.start(request,new RunCorrelation(run,new ArtifactRef(request.request().capability(),"a".repeat(64)),null),op.requirement(),args,budget);
        }
        ActionReceipt finish(ActionHandle handle) {
            ActionReceipt r=null;
            for(int i=0;i<210;i++) { r=gateway.poll(handle); if(r.terminal()) { receipts.addAll(r.resourceReceipts());return r; } world.tick++; }
            throw new AssertionError("Unbounded action");
        }
        ActionReceipt action(GatewayPrimitives.Operation op) { world.tick++; return finish(start(op)); }
        void deliverOne() {
            for(var op:List.of(GatewayPrimitives.Operation.APPROACH_OAK_LOG,GatewayPrimitives.Operation.HARVEST_NEXT_OAK_LOG,
                    GatewayPrimitives.Operation.PICKUP_TRACKED_OAK_LOGS,GatewayPrimitives.Operation.MOVE_TO_DESTINATION,GatewayPrimitives.Operation.TRANSFER_OAK_LOGS))
                org.junit.jupiter.api.Assertions.assertNull(action(request.request().capability().equals(BirchLogDelivery.ID) ? BirchResourceFamilyTest.birch(op) : op).reason(),op.toString());
        }
    }
}
