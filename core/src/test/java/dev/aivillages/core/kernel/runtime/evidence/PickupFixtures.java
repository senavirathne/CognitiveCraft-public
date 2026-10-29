package dev.aivillages.core.kernel.runtime.evidence;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import java.util.*;

/** Synthetic H/P records for storage/owner contracts, never native proof. */
final class PickupFixtures {
    static NativePickup.Intent intent(UUID world){return intent(world,"minecraft:overworld",2);}
    static NativePickup.Intent intent(UUID world,String dimension,int requested) {
        var actor=new ActorRef(UUID.randomUUID(),UUID.randomUUID(),dimension);var root=UUID.randomUUID();
        var origin=new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(world,UUID.randomUUID()));
        var slots=new ArrayList<NativeDeposit.Slot>();for(int n=0;n<8;n++)slots.add(new NativeDeposit.Slot("minecraft:air",0));
        var participant=new NativeDeposit.Participant(NativeDeposit.Kind.ACTOR,dimension,actor.entityId().toString(),UUID.randomUUID(),0,slots);
        UUID group=UUID.randomUUID();var claims=new ArrayList<NativeDeposit.Claim>();
        for(int n=0;n<4;n++)claims.add(new NativeDeposit.Claim(UUID.randomUUID(),UUID.randomUUID(),group,1));
        var usage=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);for(var kind:Budgets.Kind.values())usage.put(kind,0L);
        var c=new NativeDeposit.Conditions(actor.citizenId(),0,100_000,10_000,64,10,2500,1000,claims,usage);
        var before=new NativeHarvest.Source(dimension,2,64,2,UUID.randomUUID(),0,"minecraft:oak_log[axis=y]");
        var h=new NativeHarvest.Intent(new NativeDeposit.Key(world,root,root,UUID.randomUUID(),1,1),origin,UUID.randomUUID(),UUID.randomUUID(),
                actor,new Cuboid(dimension,2,64,2,10,64,10),new ContainerRef(dimension,12,64,12),"minecraft:oak_log",before,c,participant);
        var drop=new NativeHarvest.Drop(dimension,UUID.randomUUID(),UUID.randomUUID(),0,h.item(),1);
        var after=new NativeHarvest.Source(dimension,2,64,2,before.incarnation(),1,"minecraft:air");
        var receipt=new ResourceDelivery.Receipt(UUID.randomUUID(),origin,h.run(),h.operation(),UUID.randomUUID(),actor,h.area(),h.destination(),
                new ResourceDelivery.ItemIdentity(h.item(),"default"),ResourceDelivery.Stage.HARVEST,1,new ResourceDelivery.BlockPosition(dimension,2,64,2),List.of(drop.entity()));
        usage.put(Budgets.Kind.COMMITTED_EFFECTS,1L);usage.put(Budgets.Kind.ATTEMPTED_EFFECTS,1L);
        var entry=new NativeHarvest.Entry(h,NativeHarvest.observed(h,after,List.of(drop),receipt,usage,"",participant));
        var chunks=new ArrayList<NativePickup.Chunk>();for(int x=0;x<8;x++)chunks.add(new NativePickup.Chunk(dimension,x,0));
        var p=new NativeDeposit.Conditions(actor.citizenId(),1,100_000,12_000,63,20,2500,1000,claims,usage);
        var key=h.key();return new NativePickup.Intent(new NativeDeposit.Key(world,root,root,key.attempt(),1,2),entry,UUID.randomUUID(),requested,p,
                new NativePickup.Trail(actor.entityId(),participant.incarnation(),chunks,chunks.getLast()),
                new NativePickup.Trail(drop.entity(),drop.incarnation(),chunks,chunks.getLast()));
    }
    static final class HarvestPort implements HarvestEvidenceStore.NativePort {
        final NativeHarvest.Entry entry;boolean restored;
        HarvestPort(NativeHarvest.Entry e){entry=e;}
        public boolean current(NativeHarvest.Intent i){return i.equals(entry.intent());}
        public NativeHarvest.Result execute(NativeHarvest.Intent i){return entry.result();}
        private NativeDeposit.Marker marker(){return new NativeDeposit.Marker(entry.token(),entry.intent().digest(),NativeDeposit.MarkerPhase.RESULT,entry.result().digest());}
        public NativeDeposit.Witness restoredActor(NativeDeposit.Participant expected){return new NativeDeposit.Witness(entry.result().actor(),marker(),restored);}
        public NativeHarvest.SourceWitness restoredSource(NativeHarvest.Source expected){return new NativeHarvest.SourceWitness(entry.result().source(),marker(),restored);}
        public List<NativeHarvest.DropWitness> restoredDrops(List<NativeHarvest.Drop> expected){return entry.result().drops().stream().map(d->new NativeHarvest.DropWitness(d,marker(),restored)).toList();}
    }
    static HarvestEvidenceStore liveHarvest(NativeHarvest.Entry entry,HarvestPort port,java.time.Clock clock) {
        var h=new HarvestEvidenceStore(new HarvestJournal.Snapshot(entry.intent().key().world(),UUID.randomUUID(),0,List.of()),(a,b)->java.util.concurrent.CompletableFuture.completedFuture(b),port,clock,100,false);
        h.prepare(entry.intent());h.tick();var prepared=h.prepare(entry.intent()).prepared();h.perform(prepared);h.tick();return h;
    }
}
