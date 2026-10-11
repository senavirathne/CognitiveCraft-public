package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.Plan.*;

/** Pure finite structural validation plus current read-only authority/resolution ports. */
public final class PlanValidator {
    public interface Current {
        boolean controls(ActorRef actor,TrustedContext scope);
        CapabilityResolver.Decision resolve(ValidatedRequest request);
    }
    private final PlanningBudget budget;
    public PlanValidator(PlanningBudget budget){this.budget=Objects.requireNonNull(budget);}
    public Check validate(String candidate,Context context,Budgets.Limits remaining,Current current,long tick) {
        int work=0;
        try {
            work=Math.addExact(context.work(),1+(candidate==null?0:(candidate.getBytes(StandardCharsets.UTF_8).length+63)/64));
            if(work>budget.work())return denied(Diagnostic.BUDGET,Reason.BUDGET_EXHAUSTED,"$",budget.work());
            var proposal=Plan.decode(candidate,budget.outputBytes());
            if(!context.needsPlanning() || !PlanningGoals.valid(context.goal().request()))
                return denied(Diagnostic.UNBOUND,Reason.REQUEST_INVALID,"$.goal",work);
            if(proposal.nodes().size()<2)return denied(Diagnostic.GRANULARITY,Reason.REQUEST_INVALID,"$.nodes",work);
            if(proposal.nodes().size()>budget.nodes() || proposal.nodes().size()>budget.createdWork())
                return denied(Diagnostic.SIZE,Reason.BUDGET_EXHAUSTED,"$.nodes",work);
            var slots=PlanningGoals.slots(context.goal().request());
            if(context.slots().size()!=slots.size())return denied(Diagnostic.UNBOUND,Reason.REQUEST_INVALID,"$.slots",work);
            for(int i=0;i<slots.size();i++)if(!context.slots().get(i).bound().request().equals(slots.get(i))
                    || !context.slots().get(i).bound().context().equals(context.goal().context()))
                return denied(Diagnostic.AUTHORITY,Reason.AUTHORITY_DENIED,"$.slots",work);
            var nodes=new TreeMap<Integer,Node>();int edges=0;
            for(var node:proposal.nodes()) {
                work++;
                if(node.id()<0 || node.id()>7 || node.slot()<0 || node.slot()>=slots.size() || nodes.put(node.id(),node)!=null)
                    return denied(Diagnostic.UNBOUND,Reason.REQUEST_INVALID,"$.nodes",work);
                if(node.amount()<1 || node.amount()>64)return denied(Diagnostic.QUANTITY,Reason.REQUEST_INVALID,"$.nodes.amount",work);
                edges=Math.addExact(edges,node.dependencies().size());work+=node.dependencies().size();
                if(edges>budget.edges() || work>budget.work())return denied(Diagnostic.SIZE,Reason.BUDGET_EXHAUSTED,"$.nodes.dependencies",Math.min(work,budget.work()));
                if(new HashSet<>(node.dependencies()).size()!=node.dependencies().size())return denied(Diagnostic.MALFORMED,Reason.REQUEST_INVALID,"$.nodes.dependencies",work);
            }
            var heights=new HashMap<Integer,Integer>();
            work=Math.addExact(work,nodes.size()+edges);
            if(work>budget.work())return denied(Diagnostic.BUDGET,Reason.BUDGET_EXHAUSTED,"$.nodes.dependencies",budget.work());
            for(var node:nodes.values()) {
                for(int dep:node.dependencies())if(!nodes.containsKey(dep))return denied(Diagnostic.UNBOUND,Reason.REQUEST_INVALID,"$.nodes.dependencies",work);
                int height=height(node.id(),nodes,new HashSet<>(),heights);
                if(height<0)return denied(Diagnostic.CYCLE,Reason.DEPENDENCY_INCOMPATIBLE,"$.nodes.dependencies",work);
                if(height>budget.depth())return denied(Diagnostic.SIZE,Reason.BUDGET_EXHAUSTED,"$.nodes.dependencies",work);
            }
            var children=new ArrayList<Jobs.Child>();
            long desired=((IntValue)context.goal().request().arguments().get("amount")).value();
            if(context.slots().stream().anyMatch(s->s.observation().counts().getOrDefault("unknown_cells",1L)!=0
                    || s.observation().counts().getOrDefault("mature_wheat",0L)<desired))
                return denied(Diagnostic.FABRICATED_RESOURCE,Reason.RESOURCE_MISSING,"$.evidence",work);
            for(var node:nodes.values()) {
                var slot=context.slots().get(node.slot());
                var a=new HashMap<>(slot.bound().request().arguments());a.put("amount",new IntValue(node.amount()));
                var request=new CapabilityRequest(CropDelivery.ID,a);var origin=context.goal().context();
                var actor=((ActorValue)a.get("actor")).value();
                if(!current.controls(actor,origin))return denied(Diagnostic.AUTHORITY,Reason.AUTHORITY_DENIED,"$.nodes.actor",work);
                var observed=slot.observation();
                if(observed.status()!=ObservationStatus.PRESENT || tick<observed.observedTick())
                    return denied(Diagnostic.STALE,Reason.STALE_OBSERVATION,"$.evidence",work);
                var binding=new ValidatedRequest(request,origin,slot.bound().observation());
                int ceiling=resolutionWorkLimit(binding);
                if(ceiling>budget.work()-work)return denied(Diagnostic.BUDGET,Reason.BUDGET_EXHAUSTED,"$",work);
                work=Math.addExact(work,ceiling); // Reserve before the read-only port performs any scans/searches.
                var decision=current.resolve(binding);
                if(1L+decision.candidateWork()+decision.queriedPages()+decision.observation().scannedCells()>ceiling)
                    return denied(Diagnostic.BUDGET,Reason.BUDGET_EXHAUSTED,"$",work);
                if(decision.routing().status()==ResolutionStatus.UNAUTHORIZED)return denied(Diagnostic.AUTHORITY,Reason.AUTHORITY_DENIED,"$.nodes",work);
                if(decision.routing().status()!=ResolutionStatus.RESOLVED || !decision.complete())return denied(Diagnostic.METHOD,
                        decision.routing().reason()==null?Reason.ARTIFACT_INVALID:decision.routing().reason(),"$.nodes",work);
                if(!slot.method().ref().equals(decision.routing().artifact()))return denied(Diagnostic.METHOD,Reason.ARTIFACT_INCOMPATIBLE,"$.nodes.method",work);
                if(!decision.selectedBindings().request().equals(request) || !decision.selectedBindings().context().equals(origin))
                    return denied(Diagnostic.AUTHORITY,Reason.AUTHORITY_DENIED,"$.nodes",work);
                if(!samePremise(observed,decision.observation()) || !decision.observation().reference().equals(decision.selectedBindings().observation())
                        || tick<decision.observation().observedTick() || tick-decision.observation().observedTick()>budget.observationAge())
                    return denied(Diagnostic.STALE,Reason.STALE_OBSERVATION,"$.evidence",work);
                var maxima=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
                for(var kind:Budgets.Kind.values())maxima.put(kind,Set.of(Budgets.Kind.REPAIRS,Budgets.Kind.INPUT_BYTES,
                        Budgets.Kind.OUTPUT_BYTES,Budgets.Kind.CANDIDATES,Budgets.Kind.TRIALS).contains(kind)?0:
                        remaining.maximum(kind)/nodes.size());
                children.add(new Jobs.Child(Plan.child(context.parent(),node.id()),request,decision.selectedBindings().observation(),
                        new Budgets.Limits(maxima,remaining.deadlineEpochMillis()),node.dependencies().stream().map(id->Plan.child(context.parent(),id)).toList()));
            }
            if(!PlanningGoals.covers(context.goal().request(),children.stream().map(Jobs.Child::request).toList()))
                return denied(Diagnostic.QUANTITY,Reason.REQUEST_INVALID,"$.nodes.amount",work);
            return new Check(new Validated(context,proposal,children),null,null,"",work);
        } catch(StrictJson.Invalid invalid) {
            Diagnostic d;try {d=Diagnostic.valueOf(invalid.code());}catch(IllegalArgumentException other){d=Diagnostic.MALFORMED;}
            return denied(d,d==Diagnostic.SIZE?Reason.BUDGET_EXHAUSTED:Reason.REQUEST_INVALID,invalid.path(),work);
        } catch(ArithmeticException overflow){return denied(Diagnostic.BUDGET,Reason.BUDGET_EXHAUSTED,"$",Math.min(work,budget.work()));}
        catch(SecurityException denied){return denied(Diagnostic.AUTHORITY,Reason.AUTHORITY_DENIED,"$",work);}
        catch(RuntimeException malformed){return denied(Diagnostic.MALFORMED,Reason.REQUEST_INVALID,"$",work);}
    }
    private int height(int id,Map<Integer,Node> nodes,Set<Integer> active,Map<Integer,Integer> memo) {
        if(memo.containsKey(id))return memo.get(id);
        if(!active.add(id))return -1;
        int height=1;
        for(int dependency:nodes.get(id).dependencies()) {
            int child=height(dependency,nodes,active,memo);if(child<0)return -1; height=Math.max(height,1+child);
        }
        active.remove(id);memo.put(id,height);return height;
    }
    public static int resolutionWorkLimit(ValidatedRequest request) {
        var source=((AreaValue)request.request().arguments().get("source")).value();
        long cells=Math.multiplyExact(Math.multiplyExact(1L+source.maxX()-source.minX(),1L+source.maxY()-source.minY()),1L+source.maxZ()-source.minZ());
        var limits=CapabilityResolver.Limits.defaults();
        return Math.toIntExact(1L+limits.maxCandidates()+limits.maxQueries()+Math.min(512,cells));
    }
    public static boolean samePremise(ObservationSnapshot before,ObservationSnapshot now) {
        return before.status()==now.status() && before.targetIdentity().equals(now.targetIdentity())
                && before.reference().dimension().equals(now.reference().dimension()) && before.counts().equals(now.counts())
                && before.scannedCells()==now.scannedCells();
    }
    private static Check denied(Diagnostic diagnostic,Reason reason,String path,int work) {
        return new Check(null,diagnostic,reason,path,work);
    }
}
