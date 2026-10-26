package dev.aivillages.fabric;

import java.util.*;
import java.util.function.*;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.RecipeTransaction.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;

/** Optional read-only listener adapter. It owns no receipt, preparation registry or durable state. */
final class FabricHostObservations {
    private final ServerLevel level;private final FabricGatewayWorld world;
    FabricHostObservations(ServerLevel level,FabricGatewayWorld world){this.level=level;this.world=world;}
    record Outcome(Outcomes.Reason reason,Map<String,Long> deltas){}
    record State(Map<String,List<ObservedSlot>> inventory,Map<String,String> sites,boolean complete){}
    private List<ObservedSlot> slots(Container c){var rows=new ArrayList<ObservedSlot>();for(int i=0;i<c.getContainerSize();i++)rows.add(new ObservedSlot(i,FabricConcreteRecipeChoices.itemKey(level,c.getItem(i)),c.getItem(i).getCount()));return List.copyOf(rows);}
    private State capture(ActorRef actor,ContainerRef source,ContainerRef destination,FacilityRef site) {
        try {
            var inventories=new TreeMap<String,List<ObservedSlot>>();var sites=new TreeMap<String,String>();var v=world.recipeActor(actor);
            if(v==null || !GatewayControl.controls(v))return new State(Map.of(),Map.of(),false);
            for(var ref:new ContainerRef[]{source,destination})if(ref!=null){var p=new BlockPos(ref.x(),ref.y(),ref.z());
                if(!world.discoverable(actor,p,Effect.OBSERVE))return new State(Map.of(),Map.of(),false);
                var c=world.recipeContainer(ref);if(c!=null)inventories.put("container:"+ref+":"+System.identityHashCode(c),slots(c));}
            inventories.put("actor:"+v.getUUID(),slots(v.getInventory()));
            if(site!=null){var p=new BlockPos(site.x(),site.y(),site.z());if(!world.discoverable(actor,p,Effect.OBSERVE))return new State(Map.of(),Map.of(),false);
                // Read current state only: observing must not allocate a facility identity/watch.
                sites.put(site.toString(),level.getBlockState(p).toString());}
            return new State(Map.copyOf(inventories),Map.copyOf(sites),true);
        } catch(RuntimeException unavailable){return new State(Map.of(),Map.of(),false);}
    }
    <T> T call(ActorRef actor,ContainerRef source,ContainerRef destination,FacilityRef site,String operation,String semantic,
               HostObserver observer,Supplier<T> action,Function<T,Outcome> result) {
        if(observer==null || observer==HostObserver.NONE)return action.get();
        if(!level.getServer().isSameThread())throw new IllegalStateException("Observation requires native server thread");
        var before=capture(actor,source,destination,site);String token=UUID.randomUUID().toString();
        T actual;
        try {actual=action.get();}
        catch(RuntimeException failure){publish(observer,token,semantic,operation,before,capture(actor,source,destination,site),
                new Outcome(Outcomes.Reason.ACTION_FAILED,Map.of()),true);throw failure;}
        publish(observer,token,semantic,operation,before,capture(actor,source,destination,site),result.apply(actual),false);return actual;
    }
    private void publish(HostObserver observer,String token,String semantic,String operation,State before,State after,Outcome outcome,boolean exception) {
        var ids=new TreeSet<String>();ids.addAll(before.inventory().keySet());ids.addAll(after.inventory().keySet());
        var participants=ids.stream().map(id->new ObservedParticipant(id,before.inventory().getOrDefault(id,List.of()),after.inventory().getOrDefault(id,List.of()))).toList();
        boolean changed=!before.inventory().equals(after.inventory()) || !before.sites().equals(after.sites());
        var kind=exception || !before.complete() || !after.complete()?ChoiceObservationKind.EXCEPTION_UNCERTAIN:
                outcome.reason()!=null && changed?ChoiceObservationKind.PARTIAL_UNCERTAIN:changed?ChoiceObservationKind.APPLIED:ChoiceObservationKind.PRECHECK_NO_EFFECT;
        var observation=new HostObservation(token,semantic,operation,participants,before.sites(),after.sites(),outcome.deltas(),kind,outcome.reason(),
                "Actual optional native before/after observation; listener cannot alter native return or certify saves");
        try{observer.observed(observation);}catch(RuntimeException listenerFailure){System.err.println("Optional B host listener failed after native observation; native outcome retained");}
    }
    static Outcome recipe(RecipeTransaction.Mutation m){return new Outcome(m.reason(),Map.of("consumed",(long)m.consumed(),"produced",(long)m.produced(),"moved",(long)m.moved(),"remainders",(long)m.remainders()));}
    static Outcome placement(PlacementTransaction.Mutation m){return new Outcome(m.reason(),Map.of("withdrawn",(long)m.withdrawn(),"consumed",(long)m.consumed(),"placed",m.placed()?1L:0L));}
}
