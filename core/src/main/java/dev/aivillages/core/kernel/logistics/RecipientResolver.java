package dev.aivillages.core.kernel.logistics;

import java.util.*;
import java.util.function.LongSupplier;
import dev.aivillages.core.kernel.storage.StorageAccessPolicy.Scope;
import dev.aivillages.core.kernel.storage.StorageRegistry.Permission;
import dev.aivillages.core.kernel.storage.StorageResolution;
import static dev.aivillages.core.kernel.storage.StorageRegistryLimits.MAX_INTEGER;

/** Inert, read-only citizen port. A name is used only before admission; bound delivery never searches again. */
public final class RecipientResolver {
    public enum Kind { ADDRESSED, NAMED, EXPLICIT_ID }
    public record Selector(Kind kind,String label,UUID citizen) {
        public Selector {
            Objects.requireNonNull(kind);
            if((kind==Kind.NAMED)!=(label!=null) || (kind==Kind.EXPLICIT_ID)!=(citizen!=null))throw new IllegalArgumentException("citizen selector");
            if(label!=null)StorageResolution.labelKey(label);
        }
        public static Selector from(Map<String,Object> body) {
            Kind kind=Kind.valueOf((String)body.get("kind"));
            if(!body.keySet().equals(switch(kind){case ADDRESSED->Set.of("kind");case NAMED->Set.of("kind","label");case EXPLICIT_ID->Set.of("kind","citizen_id");}))throw new IllegalArgumentException("citizen selector fields");
            return new Selector(kind,kind==Kind.NAMED?(String)body.get("label"):null,kind==Kind.EXPLICIT_ID?UUID.fromString((String)body.get("citizen_id")):null);
        }
    }
    /** Fresh immutable server observations, never accepted from decoded endpoint or player claims. */
    public record Citizen(Scope scope,UUID epoch,UUID id,UUID entity,long generation,long policyRevision,
                          String label,String dimension,String inventoryRevision,long tick,
                          boolean visible,boolean controlled,boolean loaded,Set<Permission> permitted) {
        public Citizen {
            Objects.requireNonNull(scope);Objects.requireNonNull(epoch);Objects.requireNonNull(id);Objects.requireNonNull(entity);
            StorageResolution.labelKey(label);Objects.requireNonNull(dimension);Objects.requireNonNull(inventoryRevision);permitted=Set.copyOf(permitted);
            if(generation<1 || generation>MAX_INTEGER || policyRevision<0 || policyRevision>MAX_INTEGER || tick<0 || tick>MAX_INTEGER || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || dimension.length()>96 || !inventoryRevision.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("citizen observation");
        }
    }
    /** Complete bounded metadata projection; revision covers name/visibility/control/load/identity changes. */
    public record View(Scope scope,UUID epoch,long revision,long tick,List<Citizen> citizens,boolean complete) {
        public View { Objects.requireNonNull(scope);Objects.requireNonNull(epoch);citizens=List.copyOf(citizens);if(revision<0 || revision>MAX_INTEGER || tick<0 || tick>MAX_INTEGER || citizens.size()>256)throw new IllegalArgumentException("citizen view bounds"); }
    }
    public interface Port {
        View view(Scope scope,long tick);
        Citizen lookup(Scope scope,UUID citizen,long tick);
    }
    public enum Status { PENDING, RESOLVED, CLARIFY, UNAVAILABLE, UNKNOWN }
    public final class Bound {
        private final Citizen accepted;private final Permission operation;private final boolean requireControl;
        private Bound(Citizen accepted,Permission operation,boolean requireControl){this.accepted=accepted;this.operation=operation;this.requireControl=requireControl;}
        public UUID citizenId(){return accepted.id();}
        public UUID entityId(){return accepted.entity();}
        public long generation(){return accepted.generation();}
        public boolean current(Scope scope) { return fresh(scope)!=null; }
        /** A fresh immutable projection for J04, not effect authority or save proof. */
        public Citizen fresh(Scope scope) {
            try {long now=clock.getAsLong();Citizen c=port.lookup(scope,accepted.id(),now);
                return now>=accepted.tick() && valid(c,scope,now,operation,requireControl) && accepted.scope().equals(scope) && c.epoch().equals(accepted.epoch()) && c.id().equals(accepted.id()) && c.entity().equals(accepted.entity()) && c.generation()==accepted.generation() && c.policyRevision()==accepted.policyRevision()?c:null;
            }catch(RuntimeException unavailable){return null;}
        }
    }
    public record Resolution(Status status,Bound endpoint) { public Resolution { Objects.requireNonNull(status);if((status==Status.RESOLVED)!=(endpoint!=null))throw new IllegalArgumentException("recipient result"); } }
    private final Port port;private final LongSupplier clock;
    public RecipientResolver(Port port,LongSupplier clock){this.port=Objects.requireNonNull(port);this.clock=Objects.requireNonNull(clock);}
    public Query begin(Scope scope,Selector selector,Permission operation,boolean requireControl){return new Query(scope,selector,operation,requireControl);}
    public final class Query {
        private final Scope scope;private final Selector selector;private final Permission operation;private final boolean requireControl;
        private View view;private int offset,candidates;private long lastTick=-1;private Citizen match;private Resolution result=new Resolution(Status.PENDING,null);
        private Query(Scope scope,Selector selector,Permission operation,boolean requireControl){this.scope=Objects.requireNonNull(scope);this.selector=Objects.requireNonNull(selector);this.operation=Objects.requireNonNull(operation);this.requireControl=requireControl;}
        public Resolution step(){
            if(result.status()!=Status.PENDING)return result;
            try {long now=clock.getAsLong();if(now<0 || now<lastTick)return finish(Status.UNKNOWN);if(now==lastTick)return result;lastTick=now;
                View current=port.view(scope,now);
                if(current==null || !current.scope().equals(scope) || current.tick()!=now || !current.complete())return finish(Status.UNKNOWN);
                if(view==null)view=current;
                if(!view.epoch().equals(current.epoch()) || view.revision()!=current.revision())return finish(Status.UNKNOWN);
                // Direct selectors still require fresh visibility/control, never an unrestricted ID lookup.
                if(selector.kind()!=Kind.NAMED){UUID id=selector.kind()==Kind.ADDRESSED?scope.actorCitizen():selector.citizen();if(id==null)return finish(Status.UNAVAILABLE);
                    Citizen c=port.lookup(scope,id,now);if(!valid(c,scope,now,operation,requireControl) || !c.id().equals(id) || !c.epoch().equals(view.epoch()))return finish(Status.UNAVAILABLE);View after=port.view(scope,now);if(after==null || !after.complete() || after.tick()!=now || !after.scope().equals(scope) || after.revision()!=view.revision() || !after.epoch().equals(view.epoch()))return finish(Status.UNKNOWN);return resolved(c);}
                int end=Math.min(view.citizens().size(),offset+16);
                for(;offset<end;offset++){
                    Citizen c=view.citizens().get(offset);
                    if(!valid(c,scope,view.tick(),operation,requireControl) || !c.epoch().equals(view.epoch()))continue;
                    if(++candidates>128)return finish(Status.UNKNOWN);
                    if(StorageResolution.labelKey(c.label()).equals(StorageResolution.labelKey(selector.label()))){if(match!=null)return finish(Status.CLARIFY);match=c;}
                }
                if(offset<view.citizens().size())return result;
                if(match==null)return finish(Status.UNAVAILABLE);
                Citizen fresh=port.lookup(scope,match.id(),now);
                if(!valid(fresh,scope,now,operation,requireControl) || !sameIdentity(match,fresh) || fresh.policyRevision()!=match.policyRevision())return finish(Status.UNAVAILABLE);
                View after=port.view(scope,now);if(after==null || !after.complete() || after.tick()!=now || !after.scope().equals(scope) || after.revision()!=view.revision() || !after.epoch().equals(view.epoch()))return finish(Status.UNKNOWN);
                return resolved(fresh);
            }catch(RuntimeException unavailable){return finish(Status.UNKNOWN);}
        }
        private Resolution finish(Status status){return result=new Resolution(status,null);}
        private Resolution resolved(Citizen c){return result=new Resolution(Status.RESOLVED,new Bound(c,operation,requireControl));}
    }
    private static boolean sameIdentity(Citizen a,Citizen b){return a.id().equals(b.id()) && a.entity().equals(b.entity()) && a.generation()==b.generation() && a.epoch().equals(b.epoch());}
    private static boolean valid(Citizen c,Scope scope,long tick,Permission operation,boolean controlled){return c!=null && c.scope().equals(scope) && c.tick()==tick && c.loaded() && c.visible() && (!controlled || c.controlled()) && c.permitted().contains(operation);}
}
