package dev.aivillages.core.kernel.storage;

import java.util.*;
import java.util.function.*;
import dev.aivillages.core.kernel.logistics.RecipientResolver;
import dev.aivillages.core.kernel.storage.contract.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;
import static dev.aivillages.core.kernel.storage.StorageBindingRules.*;
import static dev.aivillages.core.kernel.storage.StorageAccessPolicy.*;
import static dev.aivillages.core.kernel.storage.StorageResolution.Status.*;

/** Bounded, externally advanced query. No scheduler, chunk loading, inventory effects or POI registry. */
public final class StorageResolver {
    public static final int MAX_CANDIDATES=128,MAX_INSPECTED=256,PER_TICK=16;
    public record Position(String dimension,long xMilli,long yMilli,long zMilli){
        public Position{Objects.requireNonNull(dimension);if(!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || dimension.length()>96 || xMilli< -29999984000L || xMilli>29999984000L || zMilli< -29999984000L || zMilli>29999984000L || yMilli< -2048000 || yMilli>2047000)throw new IllegalArgumentException("anchor bounds");}
    }
    /** Revision covers loaded topology/capacity/protection AND caller visibility/control/membership changes.
     * J01 must capture this on the server actor; a missing complete revision means UNKNOWN. */
    public record View(Scope scope,UUID epoch,long revision,long tick,Position caller,Position actor,boolean complete){
        public View{Objects.requireNonNull(scope);Objects.requireNonNull(epoch);Objects.requireNonNull(caller);if(revision<0 || tick<0)throw new IllegalArgumentException("view bounds");}
    }
    public enum Eligibility { ELIGIBLE, INELIGIBLE, UNKNOWN }
    public record Observation(Scope scope,Binding binding,long tick,boolean loaded,boolean supported,Eligibility capacity){
        public Observation{Objects.requireNonNull(scope);Objects.requireNonNull(binding);Objects.requireNonNull(capacity);if(tick<0)throw new IllegalArgumentException("observation tick");}
    }
    public interface NativePort {
        View view(Scope scope,long tick);
        Observation observe(Scope scope,StorageRegistry.Record record,Permission operation,long tick);
    }
    /** Only this server-owned port can mint/consume selections. Decoded witness bytes confer no capability. */
    public interface SelectionPort {
        Selection consume(Scope scope,UUID selection,long tick);
        boolean current(Scope scope,Selection selection,Binding binding,long tick);
    }
    public record Selection(UUID storage,StorageWire.Message witness){
        public Selection{Objects.requireNonNull(storage);Objects.requireNonNull(witness);if(witness.kind()!=StorageWire.Kind.SelectionWitness)throw new IllegalArgumentException("selection witness required");}
    }
    private record Candidate(StorageRegistry.Record row,Binding binding,long distance,Member position) { }
    private static final Comparator<Candidate> ORDER=Comparator.comparingLong(Candidate::distance).thenComparingInt(c->c.position().x()).thenComparingInt(c->c.position().y()).thenComparingInt(c->c.position().z()).thenComparing(c->c.row().id().toString());
    private final Supplier<Snapshot> snapshots;private final StorageAccessPolicy policy;private final NativePort nativePort;private final SelectionPort selections;private final RecipientResolver recipients;private final LongSupplier clock;
    public StorageResolver(Supplier<Snapshot> snapshots,StorageAccessPolicy policy,NativePort nativePort,SelectionPort selections,RecipientResolver recipients,LongSupplier clock){
        this.snapshots=Objects.requireNonNull(snapshots);this.policy=Objects.requireNonNull(policy);this.nativePort=Objects.requireNonNull(nativePort);this.selections=Objects.requireNonNull(selections);this.recipients=Objects.requireNonNull(recipients);this.clock=Objects.requireNonNull(clock);
    }
    public Query begin(Scope scope,StorageWire.Message selector,Permission operation){
        if(selector.kind()!=StorageWire.Kind.StorageSelector)throw new IllegalArgumentException("storage selector required");
        if(!Set.of(Permission.INSPECT,Permission.DEPOSIT,Permission.WITHDRAW).contains(operation))throw new IllegalArgumentException("endpoint operation required");
        return new Query(scope,selector,operation);
    }
    /** MVP named places are exact registered storage labels; all other place kinds are unsupported. */
    public StorageResolution unsupportedPlace(){return StorageResolution.of(UNSUPPORTED);}
    public final class Bound {
        private final Scope scope;private final Permission operation;private final Binding binding;private final StorageAccessPolicy.Decision decision;private final View view;private final Snapshot snapshot;private final Selection selection;private final RecipientResolver.Bound owner;
        private Bound(Query q,Binding binding,StorageAccessPolicy.Decision decision){scope=q.scope;operation=q.operation;this.binding=binding;this.decision=decision;view=q.view;snapshot=q.snapshot;selection=q.selection;owner=q.owner;}
        public UUID storageId(){return binding.storage();}
        public Binding binding(){return binding;}
        /** Fresh server policy and loaded incarnation on every use. J still owns effect-time admission. */
        public boolean current(Scope requestedScope){
            try {long now=clock.getAsLong();if(!scope.equals(requestedScope) || !sameView(view,nativePort.view(scope,now),now) || snapshots.get()!=snapshot || owner!=null && !owner.current(scope) || !decision.authorizesEffect(scope,binding.storage(),operation))return false;
                Observation fresh=nativePort.observe(scope,snapshot.records().get(binding.storage()),operation,now);
                return validObservation(fresh,scope,snapshot.records().get(binding.storage()),now,view.epoch()) && currentEndpoint(binding,fresh.binding()) && (selection==null || selectionCurrent(selection,scope,view,fresh.binding(),now)) && sameView(view,nativePort.view(scope,now),now) && snapshots.get()==snapshot;
            }catch(RuntimeException unavailable){return false;}
        }
        /** Strict seam projection remains data, never a deserialized ALLOW capability. */
        public StorageWire.Message wire(){
            if(!current(scope))throw new IllegalStateException("endpoint no longer current");
            var fresh=policy.evaluate(scope,binding.storage(),operation);if(fresh.result()!=Result.ALLOW)throw new IllegalStateException("endpoint unavailable");
            return StorageCodec.decode(StorageMetadataCodec.canonical(Map.of("schema",StorageWire.VERSION,"kind","ResolvedEndpoint","body",Map.of("kind","STORAGE","binding",binding.wire().body(),"access",fresh.wire().body()))));
        }
    }
    /** Server-only bounded-work diagnostics; never a client existence/count projection. */
    public record Work(int metadataInspected,int eligibleCandidates,long lastTick){}
    public final class Query {
        private final Scope scope;private final Permission operation;private final Map<String,Object> selector;private final String kind,type;
        private Snapshot snapshot;private View view;private Position anchor;private Iterator<StorageRegistry.Record> rows;private Selection selection;private RecipientResolver.Query ownerQuery;private RecipientResolver.Bound owner;
        private long lastTick=-1;private int inspected,candidateCount,namedMatches;private final List<Candidate> candidates=new ArrayList<>();private StorageResolution result=StorageResolution.of(PENDING);
        private Query(Scope scope,StorageWire.Message selector,Permission operation){this.scope=Objects.requireNonNull(scope);this.operation=operation;this.selector=selector.body();kind=(String)this.selector.get("kind");type=(String)this.selector.get("container_type");}
        /** Repeat calls in the same server tick are inert; at most sixteen metadata rows are inspected. */
        public StorageResolution step(){
            if(result.status()!=PENDING)return result;
            try {long now=clock.getAsLong();if(now<0 || now<lastTick)return finish(UNKNOWN);if(now==lastTick)return result;lastTick=now;
                View current=nativePort.view(scope,now);if(current==null || !current.scope().equals(scope) || current.tick()!=now || !current.complete())return finish(UNKNOWN);
                if(view==null){view=current;snapshot=snapshots.get();if(!snapshot.world().equals(scope.world()))return finish(UNAVAILABLE);
                    anchor=kind.equals("NEAREST_PUBLIC") && selector.get("anchor").equals("ACTOR")?view.actor():view.caller();if(anchor==null || kind.equals("NEAREST_PUBLIC") && selector.get("anchor").equals("ACTOR") && scope.actorCitizen()==null)return finish(UNAVAILABLE);
                }
                if(!sameView(view,current,now) || snapshots.get()!=snapshot)return finish(UNKNOWN);
                if(rows==null){
                    if(kind.equals("DEFAULT") || kind.equals("OWNER_ROLE")){
                        if(ownerQuery==null)ownerQuery=recipients.begin(scope,RecipientResolver.Selector.from(object(selector.get("owner"))),Permission.INSPECT,true);
                        var resolved=ownerQuery.step();if(resolved.status()==RecipientResolver.Status.PENDING)return result;
                        if(resolved.status()!=RecipientResolver.Status.RESOLVED)return finish(resolved.status()==RecipientResolver.Status.CLARIFY?CLARIFY:resolved.status()==RecipientResolver.Status.UNKNOWN?UNKNOWN:UNAVAILABLE);
                        if(owner==null){owner=resolved.endpoint();return result;} // recipient and storage budgets never share one tick
                        if(!owner.current(scope))return finish(UNAVAILABLE);
                        Role role=kind.equals("DEFAULT")?Role.DEFAULT:Role.valueOf((String)selector.get("role"));
                        Assignment a=snapshot.assignments().get(new RoleKey(new Owner(OwnerKind.CITIZEN,owner.citizenId()),role));
                        if(a==null)return finish(CLARIFY);StorageRegistry.Record row=snapshot.records().get(a.storage());
                        if(row==null || a.storageGeneration()!=row.generation())return finish(UNAVAILABLE);rows=List.of(row).iterator();
                    }else if(kind.equals("SELECTED")){
                        selection=selections.consume(scope,UUID.fromString((String)selector.get("selection_id")),now);
                        if(selection==null || !selector.get("selection_id").equals(selection.witness().body().get("selection_id")))return finish(UNAVAILABLE);var row=snapshot.records().get(selection.storage());if(row==null)return finish(UNAVAILABLE);rows=List.of(row).iterator();
                    }else rows=snapshot.records().values().iterator();
                }
                for(int slice=0;slice<PER_TICK && rows.hasNext();slice++){
                    if(inspected==MAX_INSPECTED)return finish(UNKNOWN);var row=rows.next();inspected++;
                    if(row.state()!=State.ACTIVE || !matchesType(row.adapterId()) || kind.equals("NAMED") && !StorageResolution.labelKey(row.label()).equals(StorageResolution.labelKey((String)selector.get("label"))) || kind.equals("NEAREST_PUBLIC") && row.acl().stream().noneMatch(g->g.subject().kind()==OwnerKind.PUBLIC && g.permissions().contains(operation)))continue;
                    var access=policy.evaluate(scope,row.id(),operation);if(access.result()!=Result.ALLOW)continue;
                    if(++candidateCount>MAX_CANDIDATES)return finish(UNKNOWN);
                    if(kind.equals("NAMED"))namedMatches++;
                    Observation observed=nativePort.observe(scope,row,operation,now);
                    if(!validObservation(observed,scope,row,now,view.epoch()) || !currentEndpoint(Binding.from(snapshot.bindings().get(row.id())),observed.binding()))continue;
                    Binding binding=observed.binding();if(!inBounds(binding,anchor))continue;
                    if(selection!=null && !selectionCurrent(selection,scope,view,binding,now))return finish(UNAVAILABLE);
                    Member p=binding.topology().members().getFirst();long dx=p.x()*1000L+500-anchor.xMilli(),dy=p.y()*1000L+500-anchor.yMilli(),dz=p.z()*1000L+500-anchor.zMilli();
                    candidates.add(new Candidate(row,binding,dx*dx+dy*dy+dz*dz,p));
                }
                if(rows.hasNext())return inspected>=MAX_INSPECTED?finish(UNKNOWN):result;
                if(!sameView(view,nativePort.view(scope,now),now) || snapshots.get()!=snapshot)return finish(UNKNOWN);
                if(kind.equals("NAMED") && namedMatches>1)return finish(CLARIFY);
                if(candidates.isEmpty())return finish(UNAVAILABLE);
                candidates.sort(ORDER);Candidate winner=candidates.getFirst();
                Observation fresh=nativePort.observe(scope,winner.row(),operation,now);var access=policy.evaluate(scope,winner.row().id(),operation);
                if(!validObservation(fresh,scope,winner.row(),now,view.epoch()) || !currentEndpoint(winner.binding(),fresh.binding()) || !access.authorizesEffect(scope,winner.row().id(),operation) || owner!=null && !owner.current(scope) || selection!=null && !selectionCurrent(selection,scope,view,fresh.binding(),now))return finish(UNKNOWN);
                if(!sameView(view,nativePort.view(scope,now),now) || snapshots.get()!=snapshot)return finish(UNKNOWN);
                return result=StorageResolution.resolved(new Bound(this,fresh.binding(),access));
            }catch(RuntimeException unavailable){return finish(UNKNOWN);}
        }
        public Work work(){return new Work(inspected,candidateCount,lastTick);}
        private StorageResolution finish(StorageResolution.Status status){return result=StorageResolution.of(status);}
        private boolean matchesType(String adapter){return switch(type){case "ANY_MVP"->Set.of(SINGLE,DOUBLE,BARREL).contains(adapter);case "CHEST"->Set.of(SINGLE,DOUBLE).contains(adapter);case "BARREL"->BARREL.equals(adapter);default->false;};}
    }
    private boolean selectionCurrent(Selection selected,Scope scope,View view,Binding binding,long now){
        var b=selected.witness().body();var s=object(b.get("scope"));var member=object(b.get("member"));
        boolean memberMatches=binding.topology().members().stream().anyMatch(m->(Long)member.get("x")==m.x() && (Long)member.get("y")==m.y() && (Long)member.get("z")==m.z());
        return memberMatches && member.get("block_registry_id").equals(binding.topology().adapter().equals(BARREL)?"minecraft:barrel":"minecraft:chest") && selected.storage().equals(binding.storage()) && s.equals(scope.body()) && b.get("selection_id")!=null && b.get("dimension").equals(binding.topology().dimension()) && b.get("lifecycle_epoch").equals(view.epoch().toString()) && (Long)b.get("captured_tick")<=now && now<=(Long)b.get("expires_tick") && selections.current(scope,selected,binding,now);
    }
    private static boolean sameView(View a,View b,long tick){return b!=null && b.complete() && a.scope().equals(b.scope()) && a.epoch().equals(b.epoch()) && a.revision()==b.revision() && b.tick()==tick;}
    private static boolean validObservation(Observation o,Scope scope,StorageRegistry.Record row,long now,UUID epoch){return o!=null && o.scope().equals(scope) && o.tick()==now && o.loaded() && o.supported() && o.capacity()==Eligibility.ELIGIBLE && o.binding().tick()==now && o.binding().status().equals("VERIFIED_LIVE") && o.binding().epoch().equals(epoch) && o.binding().storage().equals(row.id()) && o.binding().generation()==row.generation() && o.binding().topology().world().equals(scope.world()) && o.binding().topology().adapter().equals(row.adapterId());}
    private static boolean inBounds(Binding b,Position anchor){
        if(!b.topology().dimension().equals(anchor.dimension()))return false;
        for(Member p:b.topology().members()){long dx=p.x()*1000L+500-anchor.xMilli(),dy=p.y()*1000L+500-anchor.yMilli(),dz=p.z()*1000L+500-anchor.zMilli();if(Math.abs(dx)>16000 || Math.abs(dz)>16000 || Math.abs(dy)>4000 || dx*dx+dz*dz>256000000)return false;}return true;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
}
