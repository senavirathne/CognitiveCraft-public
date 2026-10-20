package dev.aivillages.core.kernel;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Game-thread proposal owner. Inference goes through the existing broker; only Jobs publishes work. */
public final class GoalPlanner implements AutoCloseable {
    public enum State {CONTEXT,RESERVING,QUEUED,CORRECTING,PUBLISHING,LINKING,ADMITTED,DETERMINISTIC,REJECTED,CANCELLED;
        public boolean terminal(){return ordinal()>=ADMITTED.ordinal();}}
    public record View(UUID id,UUID parent,State state,Reason reason,Plan.Diagnostic diagnostic,String path,
                       List<UUID> jobs,Map<Budgets.Kind,Long> reserved,Map<Budgets.Kind,Long> actual,Generation.Compute compute) {
        public View{jobs=List.copyOf(jobs);reserved=Map.copyOf(reserved);actual=Map.copyOf(actual);}
    }
    public interface ContextPort extends PlanValidator.Current {
        Plan.Context route(Jobs.Job root,PlanningBudget budget);
        CompletionStage<Plan.Context> capture(Jobs.Job root,PlanningBudget budget);
        long tick();
    }
    @FunctionalInterface public interface Storage {CompletionStage<PlanJournal.Snapshot> replace(PlanJournal.Snapshot expected,PlanJournal.Snapshot next);}
    @FunctionalInterface public interface Sharing {boolean mayUse(PlanJournal.Template template,TrustedContext caller);}
    private final JobLifecycleStore jobs;private final AIWorkBroker broker;private final ContextPort context;
    private final PlanningBudget budget;private final PlanValidator validator;private final Clock clock;private final Storage storage;private final Sharing sharing;
    private final Thread owner=Thread.currentThread();private final LinkedHashMap<UUID,Pending> history=new LinkedHashMap<>();
    private PlanJournal.Snapshot retained;private CompletableFuture<PlanJournal.Snapshot> writing;private Pending writingOwner;private boolean closed;
    private final class Pending {
        final UUID id,parent;final TrustedContext caller;final Jobs.Job original;final long deadline;
        boolean corrected;String correctionPrompt;Generation.Request modelRequest;
        State state=State.CONTEXT;Reason reason;Plan.Diagnostic diagnostic;String path="";
        CompletableFuture<Plan.Context> capture;Plan.Context captured;Generation.Handle model;Budgets.Ledger ledger;
        Map<Budgets.Kind,Long> reserved=Map.of();Plan.Validated plan;List<UUID> children=List.of();String template;PlanJournal.Template templateGrant;
        Pending(Jobs.Job root,TrustedContext caller) {
            original=root;parent=root.id();this.caller=caller;deadline=root.allowance().deadlineEpochMillis();
            id=Plan.stable("goal-candidate:1:"+parent+":"+root.usage().getOrDefault(Budgets.Kind.CANDIDATES,0L));
        }
        View view(){return new View(id,parent,state,reason,diagnostic,path,children,reserved,ledger==null?Map.of():ledger.snapshot(),model==null?Generation.Compute.NOT_STARTED:model.compute());}
    }
    public GoalPlanner(JobLifecycleStore jobs,AIWorkBroker broker,ContextPort context,PlanningBudget budget,Clock clock,
                       PlanJournal.Snapshot retained,Storage storage,Sharing sharing) {
        this.jobs=Objects.requireNonNull(jobs);this.broker=Objects.requireNonNull(broker);this.context=Objects.requireNonNull(context);
        this.budget=Objects.requireNonNull(budget);validator=new PlanValidator(budget);this.clock=Objects.requireNonNull(clock);
        this.retained=Objects.requireNonNull(retained);this.storage=Objects.requireNonNull(storage);this.sharing=Objects.requireNonNull(sharing);
    }
    private void thread(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Planner owner thread");}
    private Jobs.Job root(UUID parent,TrustedContext caller) {
        var root=jobs.query(parent,caller);
        if(!jobs.mayControl(parent,root.generation(),caller) || !context.controls(root.responsible(),root.origin()))throw new SecurityException("Planning control required");
        return root;
    }
    public View propose(UUID parent,TrustedContext caller){return begin(parent,caller,null);}
    public View reuse(UUID parent,UUID template,TrustedContext caller) {
        thread();var row=retained.templates().stream().filter(t->t.id().equals(template) && sharing.mayUse(t,caller)).findFirst().orElseThrow(()->new SecurityException("Template unavailable"));
        return begin(parent,caller,row);
    }
    private View begin(UUID parent,TrustedContext caller,PlanJournal.Template template) {
        thread();var root=root(parent,caller);
        for(var prior:history.values())if(prior.parent.equals(parent) && !prior.state.terminal()) {
            if(!prior.caller.equals(caller))throw new SecurityException("Private proposal");return prior.view();
        }
        if(history.size()==16)history.values().removeIf(p->p.state.terminal());
        if(history.size()==16)throw new IllegalStateException("Planner capacity");
        var pending=new Pending(root,caller);history.put(pending.id,pending);
        if(closed || !jobs.ready()){refuse(pending,Reason.STORAGE_UNAVAILABLE,Plan.Diagnostic.PUBLICATION,"$");return pending.view();}
        if(root.state()==Jobs.State.CANCELLED || root.state()==Jobs.State.CANCELLING || root.state()==Jobs.State.FAILED) {
            refuse(pending,Reason.CANCELLED,Plan.Diagnostic.CANCELLED,"$.parent");return pending.view();
        }
        if(!root.children().isEmpty() && PlanningGoals.valid(root.request().request())) {
            var acknowledged=root.children().stream().map(id->jobs.query(id,caller)).toList();
            if(!PlanningGoals.covers(root.request().request(),acknowledged.stream().map(j->j.request().request()).toList())
                    || acknowledged.stream().anyMatch(j->!root.id().equals(j.parent()) || !root.origin().equals(j.origin()))) {
                refuse(pending,Reason.REQUEST_INVALID,Plan.Diagnostic.PUBLICATION,"$.publication");return pending.view();
            }
            pending.children=root.children();pending.state=State.ADMITTED;return pending.view();
        }
        if(root.state()!=Jobs.State.READY && root.state()!=Jobs.State.WAITING) {
            refuse(pending,Reason.INTERRUPTED,Plan.Diagnostic.CANCELLED,"$.parent");return pending.view();
        }
        if(clock.millis()>=pending.deadline){refuse(pending,Reason.BUDGET_EXHAUSTED,Plan.Diagnostic.BUDGET,"$");return pending.view();}
        try {
            if(template!=null){pending.template=Plan.encode(template.topology());pending.templateGrant=template;}
            var available=jobs.remainingAllowance(parent,caller);
            var envelope=template==null?Map.of(Budgets.Kind.CALLS,1L,Budgets.Kind.INPUT_BYTES,32768L,
                    Budgets.Kind.OUTPUT_BYTES,(long)budget.outputBytes(),Budgets.Kind.CANDIDATES,1L,Budgets.Kind.INSTRUCTIONS,(long)budget.work())
                    :Map.of(Budgets.Kind.CANDIDATES,1L,Budgets.Kind.INSTRUCTIONS,(long)budget.work());
            if(PlanningGoals.valid(root.request().request()) && (envelope.entrySet().stream().anyMatch(e->e.getValue()>available.maximum(e.getKey()))
                    || 8L+PlanValidator.resolutionWorkLimit(root.request())>budget.work())) {
                refuse(pending,Reason.BUDGET_EXHAUSTED,Plan.Diagnostic.BUDGET,"$");return pending.view();
            }
            var route=context.route(root,budget);
            if(!route.needsPlanning()){pending.state=State.DETERMINISTIC;pending.reason=route.direct().routing().reason();return pending.view();}
            if(!route.parent().equals(root.id()) || !route.goal().equals(root.request()) || !route.guard().equals(root.guard())
                    || !PlanningGoals.valid(root.request().request())) {
                refuse(pending,Reason.REQUEST_INVALID,Plan.Diagnostic.UNBOUND,"$.goal");return pending.view();
            }
            // Consume the whole finite envelope before any asynchronous context or model work is queued.
            pending.reserved=envelope;
            var change=jobs.charge(root.id(),root.guard(),pending.reserved,caller);
            if(!change.accepted()){refuse(pending,change.reason(),Plan.Diagnostic.BUDGET,"$");return pending.view();}
            pending.state=State.RESERVING;
        }catch(SecurityException denied){refuse(pending,Reason.AUTHORITY_DENIED,Plan.Diagnostic.AUTHORITY,"$.context");
        }catch(RuntimeException unavailable){refuse(pending,Reason.STORAGE_UNAVAILABLE,Plan.Diagnostic.PUBLICATION,"$.context");}
        return pending.view();
    }
    /** One finite pass over at most sixteen proposals. Never waits for inference or disk. */
    public void tick() {
        thread();if(closed)return;
        if(writing!=null && writing.isDone()) {
            try{retained=writing.join();}catch(CompletionException failed){
                if(writingOwner!=null){writingOwner.reason=Reason.STORAGE_UNAVAILABLE;writingOwner.diagnostic=Plan.Diagnostic.PUBLICATION;writingOwner.path="$.linkage";}
            }
            writing=null;writingOwner=null;
        }
        for(var p:history.values())if(!p.state.terminal()) {
            try{advance(p);}catch(SecurityException denied){refuse(p,Reason.AUTHORITY_DENIED,Plan.Diagnostic.AUTHORITY,"$");}
            catch(RuntimeException unavailable){refuse(p,Reason.STORAGE_UNAVAILABLE,Plan.Diagnostic.PUBLICATION,"$");}
        }
        if(writing==null && jobs.ready() && !retained.links().isEmpty()) {
            var states=new HashMap<UUID,Jobs.State>();for(var row:jobs.snapshot().jobs())states.put(row.id(),row.state());
            var active=retained.links().stream().filter(l->!states.containsKey(l.parent()) || !states.get(l.parent()).terminal()).toList();
            if(active.size()!=retained.links().size())try {
                writing=storage.replace(retained,new PlanJournal.Snapshot(retained.world(),Math.addExact(retained.revision(),1),active,retained.templates())).toCompletableFuture();
            }catch(RuntimeException unavailable){/* Conservative retention until its owner can acknowledge pruning. */}
        }
    }
    private void advance(Pending p) {
        var root=root(p.parent,p.caller);
        if(p.templateGrant!=null && !sharing.mayUse(p.templateGrant,p.caller)) {
            refuse(p,Reason.AUTHORITY_DENIED,Plan.Diagnostic.AUTHORITY,"$.template");return;
        }
        if(root.state()==Jobs.State.CANCELLED || root.state()==Jobs.State.CANCELLING || root.state()==Jobs.State.FAILED) {
            refuse(p,Reason.CANCELLED,Plan.Diagnostic.CANCELLED,"$.parent");return;
        }
        if(clock.millis()>=p.deadline && p.state!=State.LINKING && p.state!=State.PUBLISHING) {
            refuse(p,Reason.BUDGET_EXHAUSTED,Plan.Diagnostic.BUDGET,"$");return;
        }
        if(p.state==State.RESERVING) {
            if(!jobs.ready())return;
            if(!root.children().isEmpty()){refuse(p,Reason.STALE_OBSERVATION,Plan.Diagnostic.STALE,"$.parent");return;}
            // Two routing passes, two slot searches, bounded coverage/history and maximum context serialization.
            if(4L*PlanValidator.resolutionWorkLimit(root.request())+24+(budget.contextBytes()+63)/64>budget.work()) {
                refuse(p,Reason.BUDGET_EXHAUSTED,Plan.Diagnostic.BUDGET,"$.context");return;
            }
            p.capture=context.capture(root,budget).toCompletableFuture();p.state=State.CONTEXT;return;
        }
        if(p.state==State.CONTEXT) {
            if(!p.capture.isDone())return;p.captured=p.capture.join();
            if(!p.captured.parent().equals(root.id()) || !p.captured.goal().equals(root.request()) || !p.captured.guard().equals(root.guard())) {
                refuse(p,Reason.STALE_OBSERVATION,Plan.Diagnostic.STALE,"$.parent");return;
            }
            if(!p.captured.needsPlanning()) {
                p.state=State.DETERMINISTIC;p.reason=p.captured.direct().routing().reason();p.captured=null;return;
            }
            if(!PlanningGoals.valid(root.request().request())){refuse(p,Reason.REQUEST_INVALID,Plan.Diagnostic.UNBOUND,"$.goal");return;}
            if(!jobs.ready())return;
            if(p.captured.work()>budget.work() || p.captured.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>budget.contextBytes()) {
                refuse(p,Reason.BUDGET_EXHAUSTED,Plan.Diagnostic.SIZE,"$.context");return;
            }
            if(p.captured.slots().size()!=2){refuse(p,Reason.ARTIFACT_INVALID,Plan.Diagnostic.METHOD,"$.slots");return;}
            if(p.template!=null) {
                try {
                    var skeleton=Plan.decode(p.template,8192);var fresh=PlanningGoals.slots(root.request().request());
                    var rebound=new Plan.Proposal(skeleton.nodes().stream().map(n->new Plan.Node(n.id(),n.slot(),
                            ((IntValue)fresh.get(n.slot()).arguments().get("amount")).value(),n.dependencies())).toList());
                    admit(p,Plan.encode(rebound));
                }catch(StrictJson.Invalid invalid){refuse(p,Reason.REQUEST_INVALID,Plan.Diagnostic.MALFORMED,"$.template");}
                return;
            }
            var limits=new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,1L,Budgets.Kind.REPAIRS,0L,
                    Budgets.Kind.INPUT_BYTES,32768L,Budgets.Kind.OUTPUT_BYTES,(long)budget.outputBytes()),p.deadline),32768,budget.outputBytes());
            p.ledger=new Budgets.Ledger(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,2L,Budgets.Kind.REPAIRS,1L,
                    Budgets.Kind.INPUT_BYTES,65536L,Budgets.Kind.OUTPUT_BYTES,2L*budget.outputBytes()),p.deadline),clock);
            var request=new Generation.Request(p.id,p.captured.goal(),PlanningGoals.SPEC,List.of(),
                    p.captured.slots().stream().map(Plan.Slot::method).distinct().toList(),Generation.Role.PLANNING,p.captured.prompt());
            p.modelRequest=request;p.model=broker.submit(request,limits,p.ledger,Plan.OUTPUT,AIWorkBroker.Priority.NORMAL);p.state=State.QUEUED;return;
        }
        if(p.state==State.CORRECTING) {
            if(!jobs.ready())return;
            if(!root.guard().equals(p.captured.guard())){refuse(p,Reason.STALE_OBSERVATION,Plan.Diagnostic.STALE,"$.parent");return;}
            var limits=new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,1L,Budgets.Kind.REPAIRS,1L,
                    Budgets.Kind.INPUT_BYTES,32768L,Budgets.Kind.OUTPUT_BYTES,(long)budget.outputBytes()),p.deadline),32768,budget.outputBytes());
            p.modelRequest=new Generation.Request(Plan.stable(p.id+":correction:1"),p.captured.goal(),PlanningGoals.SPEC,List.of(),
                    p.captured.slots().stream().map(Plan.Slot::method).distinct().toList(),Generation.Role.PLANNING_REPAIR,p.correctionPrompt);
            p.model=broker.submit(p.modelRequest,limits,p.ledger,Plan.OUTPUT,AIWorkBroker.Priority.NORMAL);p.state=State.QUEUED;return;
        }
        if(p.state==State.QUEUED) {
            if(!p.model.result().toCompletableFuture().isDone())return;
            var result=p.model.result().toCompletableFuture().join();
            if(!result.id().equals(p.modelRequest.id()) || !result.owner().equals(p.captured.goal().context()) || result.role()!=p.modelRequest.role()) {
                refuse(p,Reason.AUTHORITY_DENIED,Plan.Diagnostic.AUTHORITY,"$.response");return;
            }
            if(result.outcome()!=Generation.Outcome.CANDIDATE) {refuse(p,result.reason(),Plan.Diagnostic.MALFORMED,"$.response");return;}
            if(!jobs.ready())return;admit(p,result.candidateIr());return;
        }
        if(p.state==State.PUBLISHING) {
            if(!jobs.ready())return;
            if(!new HashSet<>(root.children()).equals(new HashSet<>(p.children))) {refuse(p,Reason.STORAGE_UNAVAILABLE,Plan.Diagnostic.PUBLICATION,"$.publication");return;}
            p.state=State.LINKING;
        }
        if(p.state==State.LINKING) {
            // Jobs has already acknowledged the whole graph. Metadata congestion must not hide that admission.
            if(writing!=null){p.reason=Reason.STORAGE_UNAVAILABLE;p.diagnostic=Plan.Diagnostic.PUBLICATION;p.path="$.linkage";
                p.state=State.ADMITTED;p.captured=null;p.plan=null;p.template=null;return;}
            var link=new PlanJournal.Link(Plan.id(p.parent),p.parent,root.origin(),p.children,
                    p.captured.slots().stream().map(s->s.method().ref()).distinct().toList());
            var rows=new ArrayList<>(retained.links());rows.removeIf(l->l.id().equals(link.id()));rows.add(link);
            try{writing=storage.replace(retained,new PlanJournal.Snapshot(retained.world(),Math.addExact(retained.revision(),1),rows,retained.templates())).toCompletableFuture();writingOwner=p;}
            catch(RuntimeException failed){p.reason=Reason.STORAGE_UNAVAILABLE;}
            p.state=State.ADMITTED;p.captured=null;p.plan=null;p.template=null;
        }
    }
    private void admit(Pending p,String bytes) {
        var root=root(p.parent,p.caller);
        if(!root.guard().equals(p.captured.guard())){refuse(p,Reason.STALE_OBSERVATION,Plan.Diagnostic.STALE,"$.parent");return;}
        var result=validator.validate(bytes,p.captured,jobs.remainingAllowance(p.parent,p.caller),context,context.tick());
        if(!result.accepted()){
            // Only a semantic quantity mistake is repairable. Authority, stock, method and freshness refusals stay final.
            if(p.template==null && !p.corrected && result.diagnostic()==Plan.Diagnostic.QUANTITY) {
                String prompt;
                try{prompt=PlanningFeedback.correction(p.captured.prompt(),bytes,result.diagnostic().name(),result.path());}
                catch(IllegalArgumentException tooLarge){refuse(p,Reason.BUDGET_EXHAUSTED,Plan.Diagnostic.SIZE,"$.correction");return;}
                var extra=Map.of(Budgets.Kind.CALLS,1L,Budgets.Kind.REPAIRS,1L,Budgets.Kind.INPUT_BYTES,32768L,
                        Budgets.Kind.OUTPUT_BYTES,(long)budget.outputBytes(),Budgets.Kind.INSTRUCTIONS,(long)budget.work());
                var charge=jobs.charge(p.parent,root.guard(),extra,p.caller);
                if(charge.accepted()) {
                    var reserved=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);reserved.putAll(p.reserved);
                    extra.forEach((kind,n)->reserved.merge(kind,n,Math::addExact));p.reserved=Map.copyOf(reserved);
                    p.corrected=true;p.correctionPrompt=prompt;
                    p.captured=new Plan.Context(p.captured.parent(),charge.job().guard(),p.captured.goal(),p.captured.direct(),
                            p.captured.prerequisites(),p.captured.slots(),p.captured.history());
                    p.state=State.CORRECTING;return;
                }
            }
            refuse(p,result.reason(),result.diagnostic(),result.path());return;
        }
        var change=jobs.admitPlan(result.value(),p.caller);
        if(!change.accepted()){refuse(p,change.reason(),Plan.Diagnostic.PUBLICATION,"$.publication");return;}
        p.plan=result.value();p.children=p.plan.children().stream().map(Jobs.Child::id).toList();p.state=State.PUBLISHING;
    }
    private void refuse(Pending p,Reason reason,Plan.Diagnostic diagnostic,String path) {
        if(p.model!=null)p.model.cancel();p.state=reason==Reason.CANCELLED?State.CANCELLED:State.REJECTED;
        p.reason=reason;p.diagnostic=diagnostic;p.path=path;p.captured=null;p.capture=null;p.plan=null;p.template=null;
    }
    public View view(UUID id,TrustedContext caller){thread();var p=Objects.requireNonNull(history.get(id),"Unknown proposal");
        if(!p.caller.equals(caller))throw new SecurityException("Private proposal");root(p.parent,caller);return p.view();}
    /** Pending proposal cancellation stops future admission; it never cancels already admitted jobs. */
    public boolean cancelProposal(UUID id,TrustedContext caller) {
        thread();var p=Objects.requireNonNull(history.get(id));view(id,caller);
        if(p.state.terminal() || p.state==State.PUBLISHING || p.state==State.LINKING)return false;
        refuse(p,Reason.CANCELLED,Plan.Diagnostic.CANCELLED,"$");return true;
    }
    /** Explicit request policy: cancel this root's owned subtree through Jobs, preserving unrelated roots and receipts. */
    public Jobs.Change cancelObjective(UUID parent,TrustedContext caller) {
        thread();var root=root(parent,caller);
        for(var p:history.values())if(p.parent.equals(parent) && !p.state.terminal() && p.state!=State.PUBLISHING && p.state!=State.LINKING)
            refuse(p,Reason.CANCELLED,Plan.Diagnostic.CANCELLED,"$");
        return jobs.cancel(parent,root.guard(),caller);
    }
    public List<PlanJournal.Template> templates(TrustedContext caller) {
        thread();return retained.templates().stream().filter(t->sharing.mayUse(t,caller)).toList();
    }
    /** Retain a reusable two-outcome topology only after Jobs proves the actual objective and both children succeeded. */
    public boolean demonstrate(UUID parent,TrustedContext caller) {
        thread();var root=root(parent,caller);
        if(writing!=null || root.state()!=Jobs.State.SUCCEEDED || root.children().size()!=2 || !PlanningGoals.valid(root.request().request()))return false;
        var children=root.children().stream().map(id->jobs.query(id,caller)).toList();
        if(children.stream().anyMatch(j->j.state()!=Jobs.State.SUCCEEDED) || !PlanningGoals.covers(root.request().request(),children.stream().map(j->j.request().request()).toList()))return false;
        var slots=PlanningGoals.slots(root.request().request());var nodes=new ArrayList<Plan.Node>();
        for(int i=0;i<2;i++) {
            var child=children.get(i);int slot=((ContainerValue)child.request().request().arguments().get("destination")).equals(slots.get(0).arguments().get("destination"))?0:1;
            var dependencies=new ArrayList<Integer>();for(UUID dependency:child.dependencies()) {
                int index=root.children().indexOf(dependency);if(index<0)return false;dependencies.add(index);
            }
            nodes.add(new Plan.Node(i,slot,1,dependencies));
        }
        UUID id=Plan.stable("demonstrated-template:1:"+parent);var templates=new ArrayList<>(retained.templates());
        if(templates.size()==8 && templates.stream().noneMatch(t->t.id().equals(id)))return false;
        templates.removeIf(t->t.id().equals(id));templates.add(new PlanJournal.Template(id,root.origin(),new Plan.Proposal(nodes)));
        var links=retained.links().stream().filter(l->!l.parent().equals(parent)).toList();
        writing=storage.replace(retained,new PlanJournal.Snapshot(retained.world(),Math.addExact(retained.revision(),1),links,templates)).toCompletableFuture();return true;
    }
    public Set<WorldRetentionManager.Key> retentionRoots() {
        thread();var roots=new HashSet<WorldRetentionManager.Key>();
        for(var p:history.values())if(p.captured!=null)for(var slot:p.captured.slots())roots.add(WorldRetentionManager.artifact(slot.method().ref()));
        for(var link:retained.links())for(var method:link.methods())roots.add(WorldRetentionManager.artifact(method));return Set.copyOf(roots);
    }
    @Override public void close(){thread();closed=true;for(var p:history.values())if(!p.state.terminal())refuse(p,Reason.INTERRUPTED,Plan.Diagnostic.CANCELLED,"$");}
}
