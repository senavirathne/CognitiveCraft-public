package dev.aivillages.core.kernel;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/**
 * Sole game-thread job owner. One immutable publication/acknowledgement is in
 * flight. Storage and cancellation ports must be asynchronous/nonblocking.
 * No method executes, dispatches, observes inventory, or calls a model.
 */
public final class JobLifecycleStore {
    @FunctionalInterface public interface Storage {
        CompletionStage<Snapshot> replace(Snapshot expected, Snapshot next);
    }
    public interface Policy {
        boolean mayRead(TrustedContext caller, Job job);
        boolean mayControl(TrustedContext caller, Job job);
        boolean controls(ActorRef actor, TrustedContext origin);
    }
    @FunctionalInterface public interface CancellationPort { void request(Cancellation cancellation); }
    public static Policy privateJobs(java.util.function.BiPredicate<ActorRef, TrustedContext> control) {
        Objects.requireNonNull(control);
        return new Policy() {
            public boolean mayRead(TrustedContext caller, Job job) { return job.origin().equals(caller); }
            public boolean mayControl(TrustedContext caller, Job job) { return job.origin().equals(caller); }
            public boolean controls(ActorRef actor, TrustedContext origin) { return control.test(actor, origin); }
        };
    }
    private record Written(Snapshot snapshot, Throwable error, long completedAt) { }
    private static final class Limited extends RuntimeException {
        final Reason reason;
        Limited(Reason reason) { this.reason = reason; }
    }
    private final class Work {
        int edges;
        void edge() { if (edges == settings.edges()) throw new Limited(Reason.BUDGET_EXHAUSTED); edges++; }
    }
    private final Storage storage;
    private final Policy policy;
    private final CancellationPort cancellations;
    private final CapabilityCatalog capabilities;
    private final RequestEnvironment environment;
    private final Clock clock;
    private final Settings settings;
    private final Thread gameThread = Thread.currentThread();
    private final ConcurrentLinkedQueue<Written> writes = new ConcurrentLinkedQueue<>();
    private Snapshot state, pending;
    private Runnable afterWrite;
    private long deadline;
    private boolean fenced, readOnly;
    private Reason failure;
    private RecoveryEvidenceAuthority recoveryAuthority;
    private Snapshot indexed;
    private Map<TrustedContext, List<Job>> dispatchIndex = Map.of();
    private java.util.function.Predicate<Snapshot> retentionGuard = next -> true;
    /** Trusted nonblocking pin publication, checked before any new job snapshot can be used. */
    public void retentionGuard(java.util.function.Predicate<Snapshot> guard) { thread(); retentionGuard = Objects.requireNonNull(guard); }

    public JobLifecycleStore(Snapshot initial, Storage storage, Policy policy,
                             CancellationPort cancellations, CapabilityCatalog capabilities,
                             RequestEnvironment environment, Clock clock, Settings settings,
                             boolean readOnly) {
        this.state = Objects.requireNonNull(initial); this.storage = Objects.requireNonNull(storage);
        this.policy = Objects.requireNonNull(policy); this.cancellations = Objects.requireNonNull(cancellations);
        this.capabilities = Objects.requireNonNull(capabilities); this.environment = Objects.requireNonNull(environment);
        this.clock = Objects.requireNonNull(clock); this.settings = Objects.requireNonNull(settings);
        this.readOnly = readOnly;
        validateSnapshot(initial, settings);
        if (!readOnly) {
            var rows = rows();
            boolean changed = false;
            for (Job job : initial.jobs()) if (job.state() == State.ACTIVE || job.state() == State.CANCELLING
                    || job.current() != null && job.current().open() && job.state() != State.INTERRUPTED
                    || job.preparation()!=null && !job.state().terminal() && job.state()!=State.INTERRUPTED) {
                var edit = new Edit(job);
                edit.state = State.INTERRUPTED; edit.reason = Reason.INTERRUPTED;
                var attempts = new ArrayList<>(job.attempts());
                if (!attempts.isEmpty()) {
                    Attempt a = attempts.getLast();
                    attempts.set(attempts.size() - 1, attempt(a, a.executions(), a.usage(), a.receipts(),
                            a.effects(), a.credited(), a.terminal(), true, a.cancellationDeliveries()));
                }
                edit.attempts = attempts;
                rows.put(job.id(), edit.finish(EventKind.RESTORED, settings.events())); changed = true;
            }
            if (changed) publish(rows, state.allocations(), null, null, 0);
        }
    }
    public boolean ready() { thread(); return !readOnly && !fenced && pending == null; }
    public Reason unavailableReason() {
        thread(); return readOnly || fenced ? Reason.STORAGE_UNAVAILABLE
                : pending != null ? Reason.BUDGET_EXHAUSTED : null;
    }
    public Reason lastFailure() { thread(); return failure; }
    /** Trusted composition/retention seam, not an unscoped player query. */
    public Snapshot snapshot() { thread(); return state; }
    public Settings settings() { return settings; }

    /** One process-local recovery client; Jobs remains the sole durable publisher. */
    public RecoveryEvidenceAuthority attachRecovery(TrustedStoppedSource source) {
        thread();
        if (recoveryAuthority != null) throw new IllegalStateException("Recovery owner already attached");
        recoveryAuthority = Jobs.recoveryEvidenceAuthority(source);
        return recoveryAuthority;
    }

    /** Disposable private index; rebuilt only when the authoritative publication changes. */
    public DispatchPage dispatchPage(TrustedContext caller, UUID after, int maximum) {
        thread();
        if (!sameWorld(caller)) throw new SecurityException("Dispatch scope");
        if (maximum < 1 || maximum > 4) throw new IllegalArgumentException("Dispatch page bound");
        if (indexed != state) {
            var groups = new HashMap<TrustedContext, List<Job>>();
            for (Job job : state.jobs()) groups.computeIfAbsent(job.origin(), ignored -> new ArrayList<>()).add(job);
            dispatchIndex = Map.copyOf(groups); indexed = state;
        }
        List<Job> own = dispatchIndex.getOrDefault(caller, List.of());
        if (own.isEmpty()) return new DispatchPage(List.of(), 0);
        int at = 0;
        if (after != null) {
            int low = 0, high = own.size();
            while (low < high) { int mid = (low + high) >>> 1;
                if (own.get(mid).id().toString().compareTo(after.toString()) <= 0) low = mid + 1; else high = mid; }
            at = low % own.size();
        }
        var found = new ArrayList<Job>();
        for (int n = 0; n < Math.min(maximum, own.size()); n++) {
            Job job = own.get((at + n) % own.size());
            if (policy.mayRead(caller, job) && policy.mayControl(caller, job)) found.add(job);
        }
        return new DispatchPage(found, own.size());
    }
    /** Preview only: the assign operation revalidates this binding and never trusts a preview. */
    public ValidatedRequest bindWorker(UUID id, Guard expected, ActorRef worker, TrustedContext caller) {
        thread(); Job job = controlled(id, caller);
        if (job == null || !job.guard().equals(expected) || !policy.controls(worker, job.origin()))
            throw new SecurityException("Current assignment authority required");
        return remainingRequest(job, worker);
    }
    /** Remaining inherited envelope, including open reservations and ancestor consumption. */
    public Budgets.Limits remainingAllowance(UUID id, TrustedContext caller) {
        thread(); Job job = controlled(id, caller);
        if (job == null) throw new SecurityException("Job budget unavailable");
        var maxima = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class);
        var rows = rows();
        for (Budgets.Kind kind : Budgets.Kind.values()) {
            long value = Long.MAX_VALUE;
            for (Job ancestor : ancestors(id, rows)) value = Math.min(value, available(ancestor.id(), kind, rows));
            maxima.put(kind, Math.max(0, value));
        }
        return new Budgets.Limits(maxima, job.allowance().deadlineEpochMillis());
    }
    /** Returns no foreign assignment metadata. The guarded assign still repeats this test. */
    public boolean workerAvailable(ActorRef worker, TrustedContext origin) {
        thread();
        return policy.controls(worker, origin) && state.jobs().stream().flatMap(j -> j.attempts().stream())
                .noneMatch(a -> a.open() && a.worker().entityId().equals(worker.entityId()));
    }

    public Job query(UUID id, TrustedContext caller) {
        thread(); Job job = find(id);
        if (job == null || !sameWorld(caller) || !policy.mayRead(caller, job))
            throw new SecurityException("Job unavailable in your scope");
        return job;
    }
    /** Trusted IMP-023 disclosure only. This grant is never accepted by control, assignment or cancellation. */
    public record NamedReadGrant(UUID jobId, TrustedContext origin, Set<TrustedContext> readers) {
        public NamedReadGrant {
            Objects.requireNonNull(jobId); Objects.requireNonNull(origin);
            if (readers.size() > 8) throw new IllegalArgumentException("Named reader cap");
            readers = Set.copyOf(readers);
            if (readers.size() > 8 || readers.stream().anyMatch(c -> !c.scope().worldId().equals(origin.scope().worldId())))
                throw new IllegalArgumentException("Named job read grant");
        }
    }
    /** The scope owner revalidates current policy and original-owner consent before this host-only call. */
    public Job scopedQuery(UUID id, TrustedContext caller, NamedReadGrant grant) {
        thread(); Job job = find(id);
        if (job == null || caller == null || !sameWorld(caller) || grant == null
                || !grant.jobId().equals(id) || !grant.origin().equals(job.origin())
                || !policy.mayRead(caller, job) && !grant.readers().contains(caller))
            throw new SecurityException("Job unavailable in your named view");
        return job;
    }
    /** Coordination consumers obtain no grant from a guessed ID or read-only sharing. */
    public boolean mayControl(UUID id, long generation, TrustedContext caller) {
        thread(); Job job=find(id);
        ActorRef worker=job==null?null:job.current()==null?job.responsible():job.current().worker();
        return job!=null && job.generation()==generation && sameWorld(caller)
                && policy.mayControl(caller,job) && policy.controls(worker,job.origin());
    }
    /** Coordination hold only: never grants action authority, new claims or control of a foreign Job. */
    public boolean stopPending(UUID id, long generation, TrustedContext caller) {
        thread(); Job job=find(id);
        if(job==null || !sameWorld(caller) || !job.origin().equals(caller) || job.generation()!=generation
                || !policy.mayControl(caller,job) || job.current()==null || !job.current().open())return false;
        return job.state()==State.CANCELLING || job.state()==State.INTERRUPTED
                || !policy.controls(job.current().worker(),job.origin())
                || clock.millis()>=job.current().allowance().deadlineEpochMillis();
    }
    public Optional<Job> bySubmission(UUID submission, TrustedContext caller) {
        thread();
        return state.jobs().stream().filter(j -> j.submissionId().equals(submission)
                && sameWorld(caller) && policy.mayRead(caller, j)).findFirst();
    }
    public ReadyPage readyJobs(TrustedContext caller, int offset) {
        thread();
        if (!sameWorld(caller) || offset < 0) throw new SecurityException("Job query unavailable");
        // The cursor counts authorized rows only; it never exposes a foreign job ID.
        List<Job> visible = state.jobs().stream().filter(j -> policy.mayRead(caller, j)).toList();
        if (offset > visible.size()) throw new IllegalArgumentException("Ready cursor");
        List<Job> result = new ArrayList<>();
        int at = offset;
        if (!ready()) return new ReadyPage(result, at, at < visible.size(), 0);
        for (; at < visible.size(); at++) {
            Job job = visible.get(at);
            if (job.state() == State.READY && clock.millis() <= job.allowance().deadlineEpochMillis()) {
                if (result.size() == settings.ready()) break;
                result.add(job);
            }
        }
        return new ReadyPage(result, at, at < visible.size(), 0);
    }
    public Change create(UUID id, UUID submission, CapabilityRequest request, TrustedContext caller,
                         ObservationRef observation, Budgets.Limits allowance, List<UUID> dependencies) {
        return create(id,submission,request,caller,observation,allowance,dependencies,false);
    }
    public Change stageComposition(UUID id,CapabilityRequest request,TrustedContext caller,ObservationRef observation,Budgets.Limits allowance) {
        if(!request.capability().equals(WoodenAxeProduction.ID) && !request.capability().equals(CraftingTableProduction.ID) && !request.capability().equals(BirchPlankProduction.ID) && !request.capability().equals(OakPlankProduction.ID))return new Change(Code.REJECTED,null,Reason.REQUEST_INVALID,0);
        return create(id,id,request,caller,observation,allowance,List.of(),true);
    }
    /** Owner-only supply demand. It never becomes an executable method. */
    public Change stageBlueprint(UUID id,CapabilityRequest request,TrustedContext caller,ObservationRef observation,Budgets.Limits allowance) {
        return stageBlueprint(id,request,caller,observation,allowance,0,0);
    }
    public Change stageBlueprint(UUID id,CapabilityRequest request,TrustedContext caller,ObservationRef observation,Budgets.Limits allowance,int instructions,int observations) {
        if(!BlueprintMaterials.valid(request))return new Change(Code.REJECTED,null,Reason.REQUEST_INVALID,0);
        if(instructions<0 || instructions>1024 || observations<0 || observations>1024)return new Change(Code.REJECTED,null,Reason.REQUEST_INVALID,0);
        return create(id,id,request,caller,observation,allowance,List.of(),true,instructions,observations);
    }
    public Change admitBlueprintChild(UUID id,Guard expected,long shortage,ObservationRef observation,TrustedContext caller) {
        thread();var work=new Work();if(!ready())return reject(unavailableReason(),work);
        var root=controlled(id,caller);
        if(root==null || !policy.controls(root.responsible(),root.origin()))return reject(Reason.AUTHORITY_DENIED,work);
        if(!root.guard().equals(expected) || !BlueprintMaterials.valid(root.request().request()) || !root.children().isEmpty()
                || root.state()!=State.WAITING || root.preparation()==null || root.preparation().method()!=null
                || shortage<1 || shortage>BlueprintMaterials.amount(root.request().request()))return reject(Reason.STALE_OBSERVATION,work);
        try {
            var request=BlueprintMaterials.production(root.request().request(),shortage);
            var checked=RequestValidator.validate(request,caller,observation,capabilities,environment);
            if(checked instanceof RequestValidator.Rejected rejected)return reject(rejected.reason(),work);
            var maxima=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
            for(var kind:Budgets.Kind.values())maxima.put(kind,Math.max(0,available(id,kind,rows())));
            for(var kind:List.of(Budgets.Kind.REPAIRS,Budgets.Kind.INPUT_BYTES,Budgets.Kind.OUTPUT_BYTES,Budgets.Kind.CANDIDATES,Budgets.Kind.TRIALS))maxima.put(kind,0L);
            UUID childId=UUID.nameUUIDFromBytes(("blueprint-materials:1:"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var child=fresh(childId,childId,((RequestValidator.Accepted)checked).value(),id,new Budgets.Limits(maxima,root.allowance().deadlineEpochMillis()),List.of());
            var ce=new Edit(child);ce.preparation=new Preparation(null,List.of());ce.state=State.WAITING;ce.reason=Reason.RESOURCE_MISSING;
            child=ce.finish(EventKind.DEPENDENCY,settings.events());var rows=rows();rows.put(childId,child);
            var re=new Edit(root);re.children=List.of(childId);re.dependencies=List.of(childId);re.reason=null;
            rows.put(id,re.finish(EventKind.DEPENDENCY,settings.events()));checkCapacity(rows);checkGraph(id,rows,work);
            return publish(rows,state.allocations(),child,null,work.edges);
        }catch(Limited limited){return reject(limited.reason,work);}
        catch(IllegalArgumentException|ArithmeticException invalid){return reject(Reason.REQUEST_INVALID,work);}
    }
    /** Current leases are the allocation owner. A past producer receipt is deliberately insufficient. */
    public Change completeBlueprint(UUID id,Guard expected,BlueprintMaterials.Report report,ResourceLeaseService leases,TrustedContext caller) {
        thread();var work=new Work();if(!ready())return reject(unavailableReason(),work);var root=controlled(id,caller);
        if(root==null || !policy.controls(root.responsible(),root.origin()))return reject(Reason.AUTHORITY_DENIED,work);
        if(!root.guard().equals(expected) || !BlueprintMaterials.valid(root.request().request())
                || root.state()!=State.READY && root.state()!=State.WAITING || !dependenciesSucceeded(root,rows(),work)
                || !report.canStage() || !report.ref().equals(BlueprintMaterials.ref(root.request().request()))
                || report.required()!=BlueprintMaterials.amount(root.request().request())
                || report.tick()>leases.currentTick() || leases.currentTick()-report.tick()>10
                || clock.millis()>root.allowance().deadlineEpochMillis())return reject(Reason.STALE_OBSERVATION,work);
        long held=BlueprintMaterials.held(root,leases,true);
        if(held!=report.required())return reject(Reason.RESOURCE_MISSING,work);
        var edit=new Edit(root);edit.state=State.SUCCEEDED;edit.reason=null;var rows=rows();rows.put(id,edit.finish(EventKind.ALLOCATED,settings.events()));
        return publish(rows,state.allocations(),rows.get(id),null,work.edges);
    }
    /** One table-only prerequisite publication; original Jobs owns every child and inherited budget. */
    public Change admitFacilityChild(UUID parent,Guard expected,boolean makeItem,ObservationRef observation,ArtifactRef method,TrustedContext caller) {
        thread();var work=new Work();if(!ready())return reject(unavailableReason(),work);
        try {
            Job root=controlled(parent,caller);
            if(root==null || !policy.controls(root.responsible(),root.origin()))return reject(Reason.AUTHORITY_DENIED,work);
            if(!root.guard().equals(expected) || !FacilityPrerequisites.root(root.request().request())
                    || root.state().terminal() || root.state()==State.INTERRUPTED || root.current()!=null
                    || root.preparation()==null || root.preparation().method()!=null)return reject(Reason.STALE_OBSERVATION,work);
            var rows=rows();
            if(!FacilityPrerequisites.completedChildren(root,rows) || makeItem && !root.children().isEmpty())return reject(Reason.STALE_OBSERVATION,work);
            var request=makeItem?FacilityPrerequisites.item(root.request().request()):FacilityPrerequisites.placement(root.request().request());
            if(makeItem ? method!=null : method==null || !method.capability().equals(CraftingTablePlacement.ID))return reject(Reason.REQUEST_INVALID,work);
            UUID id=UUID.nameUUIDFromBytes(("facility-child:1:"+parent+":"+request.capability().name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(rows.containsKey(id))return duplicate(rows.get(id),work);
            var checked=RequestValidator.validate(request,root.origin(),observation,capabilities,environment);
            if(checked instanceof RequestValidator.Rejected rejected)return reject(rejected.reason(),work);
            var maxima=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
            for(var kind:Budgets.Kind.values())maxima.put(kind,Math.max(0,available(parent,kind,rows)));
            for(var kind:List.of(Budgets.Kind.REPAIRS,Budgets.Kind.INPUT_BYTES,Budgets.Kind.OUTPUT_BYTES,Budgets.Kind.CANDIDATES,Budgets.Kind.TRIALS))maxima.put(kind,0L);
            var child=fresh(id,id,((RequestValidator.Accepted)checked).value(),parent,new Budgets.Limits(maxima,root.allowance().deadlineEpochMillis()),List.of());
            var childEdit=new Edit(child);childEdit.preparation=new Preparation(method,List.of());
            childEdit.state=makeItem?State.WAITING:State.READY;childEdit.reason=makeItem?Reason.RESOURCE_MISSING:null;
            child=childEdit.finish(EventKind.DEPENDENCY,settings.events());rows.put(id,child);
            var rootEdit=new Edit(root);var children=new ArrayList<>(root.children());children.add(id);
            rootEdit.children=List.copyOf(children);rootEdit.dependencies=rootEdit.children;rootEdit.state=State.WAITING;rootEdit.reason=null;
            rows.put(parent,rootEdit.finish(EventKind.DEPENDENCY,settings.events()));
            checkCapacity(rows);checkGraph(parent,rows,work);return publish(rows,state.allocations(),child,null,work.edges);
        }catch(Limited limited){return reject(limited.reason,work);}
        catch(IllegalArgumentException|NullPointerException|ArithmeticException invalid){return reject(Reason.REQUEST_INVALID,work);}
    }
    private Change create(UUID id,UUID submission,CapabilityRequest request,TrustedContext caller,ObservationRef observation,
                          Budgets.Limits allowance,List<UUID> dependencies,boolean staged) {
        return create(id,submission,request,caller,observation,allowance,dependencies,staged,0,0);
    }
    private Change create(UUID id,UUID submission,CapabilityRequest request,TrustedContext caller,ObservationRef observation,
                          Budgets.Limits allowance,List<UUID> dependencies,boolean staged,int initialInstructions,int initialObservations) {
        thread(); var work = new Work();
        if (!sameWorld(caller)) return reject(Reason.AUTHORITY_DENIED, work);
        if (!ready()) return reject(unavailableReason(), work);
        try {
            if (id == null || submission == null || find(id) != null
                    || state.jobs().stream().anyMatch(j -> j.submissionId().equals(submission)))
                return reject(Reason.REQUEST_INVALID, work);
            RequestValidator.Result validation = RequestValidator.validate(request, caller, observation,
                    capabilities, environment);
            if (validation instanceof RequestValidator.Rejected rejected) return reject(rejected.reason(), work);
            if (allowance == null || clock.millis() > allowance.deadlineEpochMillis())
                return reject(Reason.BUDGET_EXHAUSTED, work);
            Job job = fresh(id, submission, ((RequestValidator.Accepted) validation).value(),
                    null, allowance, dependencies);
            if(initialInstructions>0 || initialObservations>0) {
                if(initialInstructions>allowance.maximum(Budgets.Kind.INSTRUCTIONS) || initialObservations>allowance.maximum(Budgets.Kind.OBSERVATIONS))return reject(Reason.BUDGET_EXHAUSTED,work);
                var billed=new Edit(job);billed.usage=Jobs.usage(Map.of(Budgets.Kind.INSTRUCTIONS,(long)initialInstructions,Budgets.Kind.OBSERVATIONS,(long)initialObservations));
                job=billed.finish(EventKind.OBSERVED,settings.events());
            }
            var rows = rows(); rows.put(id, job);
            checkCapacity(rows);
            checkGraph(job.id(), rows, work);
            job = dependencyState(job, rows, false);
            if(staged) {var edit=new Edit(job);edit.preparation=new Preparation(null,List.of());edit.state=State.WAITING;edit.reason=Reason.RESOURCE_MISSING;job=edit.finish(EventKind.DEPENDENCY,settings.events());}
            rows.put(id, job);
            var failed = new HashSet<UUID>();
            for (UUID dependency : dependencies) if (rows.get(dependency).state() == State.CANCELLED
                    || rows.get(dependency).state() == State.FAILED) failed.add(dependency);
            propagate(rows, failed, work);
            return publish(rows, state.allocations(), rows.get(id), null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (IllegalArgumentException | NullPointerException | ArithmeticException invalid) {
            return reject(Reason.REQUEST_INVALID, work);
        }
    }
    public Change transition(UUID id, Guard guard, State target, Reason reason, TrustedContext caller) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        Change denied = guard(job, guard, work); if (denied != null) return denied;
        if (!Jobs.legal(job.state(), target) || job.current() != null && job.current().open())
            return reject(Reason.REQUEST_INVALID, work);
        // Completion, assignment and observed termination require their dedicated operations.
        if (target != State.WAITING && target != State.READY && target != State.FAILED && target != State.INTERRUPTED)
            return reject(Reason.REQUEST_INVALID, work);
        try {
            if (target == State.READY && (!dependenciesSucceeded(job, rows(), work)
                    || clock.millis() > job.allowance().deadlineEpochMillis()))
                return reject(Reason.BUDGET_EXHAUSTED, work);
            if (target == State.FAILED && reason == null || target == State.INTERRUPTED && reason != Reason.INTERRUPTED)
                return reject(Reason.REQUEST_INVALID, work);
            var edit = new Edit(job); edit.state = target; edit.reason = target == State.READY ? null : reason;
            Job changed = edit.finish(EventKind.TRANSITIONED, settings.events());
            var rows = rows(); rows.put(id, changed); propagate(rows, Set.of(id), work);
            return publish(rows, state.allocations(), rows.get(id), null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (ArithmeticException overflow) { return reject(Reason.BUDGET_EXHAUSTED, work); }
    }
    public Change addDependency(UUID id, Guard guard, UUID dependency, TrustedContext caller) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        Change denied = guard(job, guard, work); if (denied != null) return denied;
        if (job.state() != State.READY && job.state() != State.WAITING)
            return reject(Reason.REQUEST_INVALID, work);
        try {
            Job needed = find(dependency);
            if (needed == null || !needed.origin().equals(job.origin())) return reject(Reason.AUTHORITY_DENIED, work);
            if (job.dependencies().contains(dependency)) return duplicate(job, work);
            if (job.dependencies().size() >= settings.children()) return reject(Reason.STORAGE_LIMIT_REACHED, work);
            var edit = new Edit(job); var deps = new ArrayList<>(job.dependencies()); deps.add(dependency);
            edit.dependencies = deps;
            Job changed = edit.finish(EventKind.DEPENDENCY, settings.events());
            var rows = rows(); rows.put(id, changed);
            checkGraph(id, rows, work); changed = dependencyState(changed, rows, false); rows.put(id, changed);
            propagate(rows, Set.of(dependency), work);
            return publish(rows, state.allocations(), rows.get(id), null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (IllegalArgumentException | ArithmeticException invalid) { return reject(Reason.REQUEST_INVALID, work); }
    }
    public Change addChildren(UUID id, Guard guard, List<Child> children, TrustedContext caller) {
        thread(); var work = new Work(); Job parent = controlled(id, caller);
        if (parent == null) return reject(Reason.AUTHORITY_DENIED, work);
        Change denied = guard(parent, guard, work); if (denied != null) return denied;
        if (parent.state() != State.READY && parent.state() != State.WAITING)
            return reject(Reason.REQUEST_INVALID, work);
        try {
            if (children == null || children.isEmpty() || parent.children().size() + children.size() > settings.children()
                    || parent.dependencies().size() + children.size() > settings.children())
                return reject(Reason.STORAGE_LIMIT_REACHED, work);
            var rows = rows(); var ids = new ArrayList<>(parent.children()); var deps = new ArrayList<>(parent.dependencies());
            var admitted = new ArrayList<Job>();
            for (Child child : children) {
                if (child.id() == null || rows.containsKey(child.id())) return reject(Reason.REQUEST_INVALID, work);
                var validation = RequestValidator.validate(child.request(), parent.origin(), child.observation(),
                        capabilities, environment);
                if (validation instanceof RequestValidator.Rejected rejected) return reject(rejected.reason(), work);
                envelope(child.allowance(), parent.allowance());
                for (Budgets.Kind kind : Budgets.Kind.values())
                    if (child.allowance().maximum(kind) > available(parent.id(), kind, rows))
                        throw new Limited(Reason.BUDGET_EXHAUSTED);
                Job job = fresh(child.id(), child.id(), ((RequestValidator.Accepted)validation).value(),
                        parent.id(), child.allowance(), child.dependencies());
                rows.put(job.id(), job); admitted.add(job); ids.add(job.id()); deps.add(job.id());
            }
            var edit = new Edit(parent); edit.children = ids; edit.dependencies = deps;
            edit.state = State.WAITING; edit.reason = null;
            Job changed = edit.finish(EventKind.DEPENDENCY, settings.events()); rows.put(id, changed);
            checkCapacity(rows); checkGraph(id, rows, work);
            for (Job child : admitted) rows.put(child.id(), dependencyState(child, rows, false));
            return publish(rows, state.allocations(), changed, null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (IllegalArgumentException | NullPointerException | ArithmeticException invalid) {
            return reject(Reason.REQUEST_INVALID, work);
        }
    }
    /** IMP-019: one repeat-safe atomic child/dependency publication; Jobs remains the sole work owner. */
    public Change admitPlan(Plan.Validated plan, TrustedContext caller) {
        thread(); var work=new Work(); Job parent=controlled(plan.context().parent(),caller);
        if(parent==null || !policy.controls(parent.responsible(),parent.origin()))return reject(Reason.AUTHORITY_DENIED,work);
        if(!ready())return reject(unavailableReason(),work);
        if(parent.state()==State.CANCELLED || parent.state()==State.CANCELLING || parent.state()==State.FAILED)
            return reject(Reason.CANCELLED,work);
        if(!parent.request().equals(plan.context().goal()) || !PlanningGoals.valid(parent.request().request()))
            return reject(Reason.STALE_OBSERVATION,work);
        var children=plan.children(); var expected=children.stream().map(Child::id).toList();
        if(!parent.children().isEmpty()) {
            boolean same=new HashSet<>(parent.children()).equals(new HashSet<>(expected));
            for(var child:children) {
                Job old=find(child.id());
                same &= old!=null && old.parent().equals(parent.id()) && old.request().request().equals(child.request())
                        && old.origin().equals(parent.origin()) && old.dependencies().equals(child.dependencies());
            }
            return same?duplicate(parent,work):reject(Reason.STALE_OBSERVATION,work);
        }
        if(!parent.guard().equals(plan.context().guard()) || !parent.dependencies().isEmpty())return reject(Reason.STALE_OBSERVATION,work);
        if(!PlanningGoals.covers(parent.request().request(),children.stream().map(Child::request).toList()))return reject(Reason.REQUEST_INVALID,work);
        return addChildren(parent.id(),parent.guard(),children,caller);
    }
    /** IMP-018: charge real disposable expansion through the existing durable usage owner. */
    public Change chargePrerequisiteWork(List<Prerequisites.Need> needs, int instructions, TrustedContext caller) {
        thread(); var work = new Work();
        if (!ready()) return reject(unavailableReason(), work);
        try {
            var rows = rows(); preparation(rows, needs, instructions, caller, work);
            if (instructions == 0) return duplicate(rows.get(needs.getFirst().demand()), work);
            return publish(rows, state.allocations(), rows.get(needs.getFirst().demand()), null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (IllegalArgumentException | NullPointerException | ArithmeticException invalid) {
            return reject(Reason.REQUEST_INVALID, work);
        }
    }
    /**
     * IMP-018 compound admission. Uses schema-1 jobs/edges/usage only. Current
     * observation references are supplied by the runtime's revalidation adapter;
     * request, scope, quantity, graph, generations and allowance are checked here.
     */
    public Change admitPrerequisites(Prerequisites.Proposal proposal, Map<UUID, ObservationRef> observations,
                                    int instructions, TrustedContext caller) {
        thread(); var work = new Work();
        if (proposal == null || !proposal.accepted()) return reject(Reason.REQUEST_INVALID, work);
        if (!ready()) return reject(unavailableReason(), work);
        if(proposal.needs().stream().anyMatch(ResourceComposition::make))return admitResourcePrerequisites(proposal,observations,instructions,caller);
        try {
            var roots = new LinkedHashMap<UUID, Job>();
            for (var need : proposal.needs()) {
                Job root = controlled(need.demand(), caller);
                if (root == null) return reject(Reason.AUTHORITY_DENIED, work);
                if (!policy.controls(root.responsible(), root.origin())) return reject(Reason.AUTHORITY_DENIED, work);
                if (!root.request().equals(need.bound())) return reject(Reason.STALE_OBSERVATION, work);
                if (root.state() == State.CANCELLED || root.state() == State.CANCELLING || root.state() == State.FAILED)
                    return reject(Reason.INTERRUPTED, work);
                roots.put(root.id(), root);
            }
            if (roots.isEmpty() || roots.size() > 8 || proposal.nodes().size() > 16
                    || observations.size() != proposal.nodes().size()) return reject(Reason.REQUEST_INVALID, work);
            var expected = new HashMap<UUID, LinkedHashSet<UUID>>();
            roots.keySet().forEach(id -> expected.put(id, new LinkedHashSet<>()));
            for (var node : proposal.nodes()) {
                Job root = roots.get(node.owner());
                if (root == null || !node.item().equals("minecraft:wheat")
                        || !node.production().equals(Prerequisites.Production.cropDelivery())
                        || !node.request().context().equals(root.origin())
                        || !sameBindings(root.request(), node.request())
                        || !(node.request().request().arguments().get("amount") instanceof IntValue amount)
                        || amount.value() != node.output()) return reject(Reason.UNSUPPORTED_PRIMITIVE, work);
                if (observations.get(node.id()) == null) return reject(Reason.STALE_OBSERVATION, work);
                expected.get(root.id()).add(node.id());
            }
            for (var allocation : proposal.allocations()) if (roots.containsKey(allocation.demand())) {
                if (allocation.policy() != Prerequisites.AllocationPolicy.ATTRIBUTABLE_WHEAT)
                    return reject(Reason.UNSUPPORTED_PRIMITIVE, work);
                expected.get(allocation.demand()).add(allocation.producer());
            }
            boolean duplicate = !proposal.nodes().isEmpty();
            for (var node : proposal.nodes()) {
                Job existing = find(node.id());
                if (existing == null) { duplicate = false; continue; }
                if (!existing.request().request().equals(node.request().request())
                        || !existing.origin().equals(node.request().context())
                        || !Objects.equals(existing.parent(), node.owner())
                        || !existing.dependencies().equals(node.dependencies())) return reject(Reason.STALE_OBSERVATION, work);
            }
            for (var entry : expected.entrySet()) if (!roots.get(entry.getKey()).dependencies().containsAll(entry.getValue())) duplicate = false;
            if (duplicate) return duplicate(roots.values().iterator().next(), work);
            // A changed proposal must not manufacture another producer for already-linked equivalent work.
            for (var root : roots.values()) {
                var linked = new LinkedHashSet<>(root.children()); linked.addAll(root.dependencies());
                for (UUID producer : linked) {
                    Job existing = find(producer);
                    for (var node : proposal.nodes()) if (!node.id().equals(producer)
                            && existing.origin().equals(node.request().context())
                            && sameBindings(existing.request(), node.request()))
                        return reject(Reason.STALE_OBSERVATION, work);
                }
            }
            var rows = rows(); preparation(rows, proposal.needs(), instructions, caller, work);
            for (var node : proposal.nodes()) {
                if (rows.containsKey(node.id())) return reject(Reason.STALE_OBSERVATION, work);
                Job root = rows.get(node.owner());
                var validation = RequestValidator.validate(node.request().request(), root.origin(), observations.get(node.id()),
                        capabilities, environment);
                if (validation instanceof RequestValidator.Rejected rejected) return reject(rejected.reason(), work);
                var maxima = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class);
                for (Budgets.Kind kind : Budgets.Kind.values()) maxima.put(kind, Math.max(0, available(root.id(), kind, rows)));
                // CALLS also meters primitive invocation. Zero byte/repair/trial allowances prohibit inference.
                for (Budgets.Kind kind : List.of(Budgets.Kind.REPAIRS, Budgets.Kind.INPUT_BYTES,
                        Budgets.Kind.OUTPUT_BYTES, Budgets.Kind.CANDIDATES, Budgets.Kind.TRIALS)) maxima.put(kind, 0L);
                var allowance = new Budgets.Limits(maxima, root.allowance().deadlineEpochMillis());
                rows.put(node.id(), fresh(node.id(), node.id(), ((RequestValidator.Accepted)validation).value(),
                        root.id(), allowance, node.dependencies()));
            }
            for (var root : roots.values()) {
                var edit = new Edit(rows.get(root.id()));
                var children = new ArrayList<>(edit.children);
                for (var node : proposal.nodes()) if (node.owner().equals(root.id())) children.add(node.id());
                var dependencies = new LinkedHashSet<>(edit.dependencies); dependencies.addAll(expected.get(root.id()));
                if (children.size() > settings.children() || dependencies.size() > settings.children())
                    return reject(Reason.STORAGE_LIMIT_REACHED, work);
                edit.children = children; edit.dependencies = List.copyOf(dependencies);
                if (!expected.get(root.id()).isEmpty()) { edit.state = State.WAITING; edit.reason = null; }
                rows.put(root.id(), edit.finish(EventKind.DEPENDENCY, settings.events()));
            }
            checkCapacity(rows);
            for (var root : roots.values()) checkGraph(root.id(), rows, work);
            for (var node : proposal.nodes()) rows.put(node.id(), dependencyState(rows.get(node.id()), rows, false));
            return publish(rows, state.allocations(), rows.get(proposal.needs().getFirst().demand()), null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (IllegalArgumentException | NullPointerException | ArithmeticException invalid) {
            return reject(Reason.REQUEST_INVALID, work);
        }
    }
    private Change admitResourcePrerequisites(Prerequisites.Proposal proposal,Map<UUID,ObservationRef> observations,int instructions,TrustedContext caller) {
        var work=new Work();
        try {
            if(proposal.needs().size()!=1 || !ResourceComposition.make(proposal.needs().getFirst())
                    || proposal.nodes().isEmpty() || proposal.nodes().size()>4 || observations.size()!=proposal.nodes().size())return reject(Reason.REQUEST_INVALID,work);
            var need=proposal.needs().getFirst();Job root=controlled(need.demand(),caller);
            if(root==null || !policy.controls(root.responsible(),root.origin()))return reject(Reason.AUTHORITY_DENIED,work);
            if(!root.request().equals(need.bound()) || root.state().terminal() || root.state()==State.INTERRUPTED)return reject(Reason.STALE_OBSERVATION,work);
            var nodes=new HashMap<UUID,Prerequisites.Node>();
            for(var node:proposal.nodes()) {
                if(!node.owner().equals(root.id()) || !ResourceComposition.registered(node.production())
                        || !node.production().output().equals(node.item()) || !node.request().context().equals(root.origin())
                        || !ResourceComposition.project(need,node.production().capability(),node.output()).equals(node.request().request())
                        || !node.method().capability().equals(node.production().capability()) || observations.get(node.id())==null
                        || nodes.put(node.id(),node)!=null)return reject(Reason.REQUEST_INVALID,work);
            }
            var rootNode=nodes.get(root.id());
            if(rootNode==null || !rootNode.request().request().equals(root.request().request()))return reject(Reason.REQUEST_INVALID,work);
            var preparations=new HashMap<UUID,Preparation>();
            for(var node:proposal.nodes()) {
                var materials=new ArrayList<MaterialIntent>();
                for(var allocation:proposal.allocations())if(allocation.demand().equals(node.id())) {
                    var producer=nodes.get(allocation.producer());
                    if(producer==null || allocation.policy()!=Prerequisites.AllocationPolicy.MATERIAL_ONLY
                            || !node.dependencies().contains(producer.id())
                            || allocation.quantity()>ResourceComposition.inputQuantity(node.request().request(),producer.item()))return reject(Reason.REQUEST_INVALID,work);
                    materials.add(new MaterialIntent(producer.id(),producer.item(),allocation.quantity()));
                }
                preparations.put(node.id(),new Preparation(node.method(),materials));
            }
            if(root.preparation()!=null && root.preparation().method()!=null) {
                for(var node:proposal.nodes()) {
                    var old=find(node.id());
                    if(old==null || !old.request().request().equals(node.request().request()) || !old.preparation().equals(preparations.get(node.id())))return reject(Reason.STALE_OBSERVATION,work);
                }
                return duplicate(root,work);
            }
            if(!FacilityPrerequisites.completedChildren(root,rows()))return reject(Reason.STALE_OBSERVATION,work);
            var rows=rows();preparation(rows,proposal.needs(),instructions,caller,work);
            for(var node:proposal.nodes())if(!node.id().equals(root.id())) {
                if(rows.containsKey(node.id()))return reject(Reason.STALE_OBSERVATION,work);
                var validation=RequestValidator.validate(node.request().request(),root.origin(),observations.get(node.id()),capabilities,environment);
                if(validation instanceof RequestValidator.Rejected rejected)return reject(rejected.reason(),work);
                var maxima=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
                for(var kind:Budgets.Kind.values())maxima.put(kind,Math.max(0,available(root.id(),kind,rows)));
                for(var kind:List.of(Budgets.Kind.REPAIRS,Budgets.Kind.INPUT_BYTES,Budgets.Kind.OUTPUT_BYTES,Budgets.Kind.CANDIDATES,Budgets.Kind.TRIALS))maxima.put(kind,0L);
                var child=fresh(node.id(),node.id(),((RequestValidator.Accepted)validation).value(),root.id(),new Budgets.Limits(maxima,root.allowance().deadlineEpochMillis()),node.dependencies());
                var edit=new Edit(child);edit.preparation=preparations.get(node.id());rows.put(node.id(),edit.finish(EventKind.PINNED,settings.events()));
            }
            var edit=new Edit(rows.get(root.id()));edit.preparation=preparations.get(root.id());
            var allChildren=new ArrayList<>(root.children());allChildren.addAll(proposal.nodes().stream().map(Prerequisites.Node::id).filter(id->!id.equals(root.id())).toList());
            edit.children=List.copyOf(allChildren);
            // Existing Jobs requires all owned children to be dependencies; this also retains parent accounting.
            edit.dependencies=edit.children;edit.state=edit.dependencies.isEmpty()?State.READY:State.WAITING;edit.reason=null;
            rows.put(root.id(),edit.finish(EventKind.DEPENDENCY,settings.events()));
            checkCapacity(rows);checkGraph(root.id(),rows,work);
            for(var node:proposal.nodes())rows.put(node.id(),dependencyState(rows.get(node.id()),rows,false));
            return publish(rows,state.allocations(),rows.get(root.id()),null,work.edges);
        } catch(Limited limited){return reject(limited.reason,work);}
        catch(IllegalArgumentException|NullPointerException|ArithmeticException invalid){return reject(Reason.REQUEST_INVALID,work);}
    }
    public boolean materialReady(Job job) {
        thread();var p=job.preparation();if(p==null)return true;if(p.method()==null)return false;
        for(var intent:p.materials()) {
            long allocated=state.allocations().stream().filter(a->a.material() && a.demand().equals(job.id()) && a.producer().equals(intent.producer()) && a.item().equals(intent.item()))
                    .mapToLong(Allocation::quantity).reduce(0,Math::addExact);
            if(allocated!=intent.quantity())return false;
        }
        return true;
    }
    /** Receipt lineage + current stock proof. Neither alone creates an input entitlement. */
    public Change allocateMaterial(Allocation allocation,Guard guard,Prerequisites.Supply stock,long currentTick,TrustedContext caller) {
        thread();var work=new Work();Job demand=controlled(allocation.demand(),caller),producer=controlled(allocation.producer(),caller);
        if(demand==null||producer==null||!producer.origin().equals(demand.origin()))return reject(Reason.AUTHORITY_DENIED,work);
        var existing=state.allocations().stream().filter(a->a.id().equals(allocation.id())).findFirst();
        if(existing.isPresent())return existing.get().equals(allocation)?duplicate(demand,work):reject(Reason.REQUEST_INVALID,work);
        var denied=guard(demand,guard,work);if(denied!=null)return denied;
        if(!allocation.material() || demand.state()!=State.READY || producer.state()!=State.SUCCEEDED)return reject(Reason.REQUEST_INVALID,work);
        if(stock.reason()==Reason.AUTHORITY_DENIED)return reject(Reason.AUTHORITY_DENIED,work);
        if(stock.reason()!=null)return reject(stock.reason(),work);
        if(!stock.item().equals(allocation.item()) || stock.status()!=ObservationStatus.PRESENT || stock.tick()>currentTick
                || currentTick-stock.tick()>10 || stock.available()<allocation.quantity())return reject(Reason.STALE_OBSERVATION,work);
        try {
            var allocations=new ArrayList<>(state.allocations());allocations.add(allocation);
            validateMaterialAllocation(allocation,new Snapshot(state.worldId(),state.revision(),state.jobs(),allocations));
            var rows=rows();rows.put(demand.id(),new Edit(demand).finish(EventKind.ALLOCATED,settings.events()));
            return publish(rows,allocations,rows.get(demand.id()),null,work.edges);
        } catch(IllegalArgumentException|ArithmeticException invalid){return reject(Reason.REQUEST_INVALID,work);}
    }
    private static void validateMaterialAllocation(Allocation allocation,Snapshot snapshot) {
        Job producer=snapshot.jobs().stream().filter(j->j.id().equals(allocation.producer())).findFirst().orElseThrow();
        Job demand=snapshot.jobs().stream().filter(j->j.id().equals(allocation.demand())).findFirst().orElseThrow();
        var preparation=demand.preparation();
        if(!allocation.material() || preparation==null || !producer.origin().equals(demand.origin()) || !demand.dependencies().contains(producer.id()))throw new IllegalArgumentException("Material owner linkage");
        var intent=preparation.materials().stream().filter(m->m.producer().equals(producer.id())&&m.item().equals(allocation.item())).findFirst().orElseThrow();
        Attempt attempt=producer.attempts().stream().filter(a->a.id().equals(allocation.attempt())).findFirst().orElseThrow();
        var receipt=attempt.resourceReceipts().stream().filter(r->r.receiptId().equals(allocation.receipt())).findFirst().orElseThrow();
        if(attempt.open() || receipt.stage()!=ResourceDelivery.Stage.DEPOSIT || !receipt.item().equals(ResourceComposition.item(allocation.item()))
                || !(demand.request().request().arguments().get("source") instanceof ContainerValue source)
                || !source.value().equals(receipt.destination()) || ResourceComposition.inputQuantity(demand.request().request(),allocation.item())<intent.quantity())throw new IllegalArgumentException("Material receipt/source");
        long pair=0,receiptUse=0;
        for(var a:snapshot.allocations()) {
            if(a.receipt().equals(receipt.receiptId()))receiptUse=Math.addExact(receiptUse,a.quantity());
            if(a.material()&&a.demand().equals(demand.id())&&a.producer().equals(producer.id())&&a.item().equals(allocation.item()))pair=Math.addExact(pair,a.quantity());
        }
        if(pair>intent.quantity() || receiptUse>receipt.quantity() || batchQuantity(attempt,receipt.runId(),receipt.batchId())<receipt.quantity())throw new IllegalArgumentException("Material overspend");
    }
    private void preparation(Map<UUID, Job> rows, List<Prerequisites.Need> needs, int instructions,
                             TrustedContext caller, Work work) {
        if (needs == null || needs.isEmpty() || needs.size() > 8 || instructions < 0 || instructions > 512
                || needs.stream().map(Prerequisites.Need::demand).distinct().count() != needs.size())
            throw new Limited(Reason.REQUEST_INVALID);
        var charged = new LinkedHashSet<UUID>();
        for (var need : needs) {
            Job root = controlled(need.demand(), caller);
            if (root == null) throw new Limited(Reason.AUTHORITY_DENIED);
            if (!policy.controls(root.responsible(), root.origin())) throw new Limited(Reason.AUTHORITY_DENIED);
            if ((root.state() != State.READY && root.state() != State.WAITING)
                    || root.current() != null && root.current().open()) throw new Limited(Reason.INTERRUPTED);
            if (!root.guard().equals(need.guard()) || !root.request().equals(need.bound()))
                throw new Limited(Reason.STALE_OBSERVATION);
            if (clock.millis() > root.allowance().deadlineEpochMillis()) throw new Limited(Reason.BUDGET_EXHAUSTED);
            for (var ancestor : ancestors(root.id(), rows)) charged.add(ancestor.id());
        }
        for (UUID id : charged) {
            if (instructions > available(id, Budgets.Kind.INSTRUCTIONS, rows)) throw new Limited(Reason.BUDGET_EXHAUSTED);
            var edit = new Edit(rows.get(id)); var usage = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class);
            usage.putAll(edit.usage); usage.merge(Budgets.Kind.INSTRUCTIONS, (long)instructions, Math::addExact);
            edit.usage = usage; rows.put(id, edit.finish(EventKind.OBSERVED, settings.events()));
        }
    }
    /** Explicit worker input; null artifact reserves preparation, not executable authority. */
    public Change assign(UUID id, Guard guard, UUID attemptId, ActorRef worker, ArtifactRef artifact,
                         List<ArtifactRef> pinned, Budgets.Limits allowance, TrustedContext caller) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        Change denied = guard(job, guard, work); if (denied != null) return denied;
        try {
            if (!materialReady(job) || !ResourceDelivery.isDelivery(job.request().request().capability())
                    || (job.state() != State.READY && !(job.state() == State.WAITING && artifact == null))
                    || job.current() != null && job.current().open()
                    || !dependenciesSucceeded(job, rows(), work))
                return reject(Reason.REQUEST_INVALID, work);
            if (!policy.controls(worker, job.origin())) return reject(Reason.AUTHORITY_DENIED, work);
            if (job.attempts().size() == settings.attempts()) return reject(Reason.STORAGE_LIMIT_REACHED, work);
            for (Job other : state.jobs()) for (Attempt a : other.attempts())
                if (a.id().equals(attemptId) || a.open() && a.worker().entityId().equals(worker.entityId()))
                    return reject(Reason.ACTOR_UNAVAILABLE, work);
            envelope(allowance, job.allowance());
            if (clock.millis() > allowance.deadlineEpochMillis()) return reject(Reason.BUDGET_EXHAUSTED, work);
            for (Job ancestor : ancestors(job.id(), rows()))
                for (Budgets.Kind kind : Budgets.Kind.values())
                    if (allowance.maximum(kind) > available(ancestor.id(), kind, rows()))
                        return reject(Reason.BUDGET_EXHAUSTED, work);
            ValidatedRequest bound = remainingRequest(job, worker);
            var validated = RequestValidator.validate(bound.request(), job.origin(), bound.observation(), capabilities, environment);
            if (validated instanceof RequestValidator.Rejected rejected) return reject(rejected.reason(), work);
            if (artifact != null && (!artifact.capability().equals(bound.request().capability())
                    || job.preparation()!=null && !artifact.equals(job.preparation().method())))
                return reject(Reason.REQUEST_INVALID, work);
            var references = artifact == null ? List.<ExecutionReference>of()
                    : List.of(new ExecutionReference(attemptId, artifact, pinned));
            long generation = Math.addExact(job.generation(), 1);
            var attempt = new Attempt(attemptId, generation, worker, bound, allowance, references,
                    Map.of(), List.of(), 0, 0, null, false, 0);
            var edit = new Edit(job); edit.generation = generation; edit.state = State.ACTIVE; edit.reason = null;
            var attempts = new ArrayList<>(job.attempts()); attempts.add(attempt); edit.attempts = attempts;
            Job changed = edit.finish(EventKind.ASSIGNED, settings.events()); var rows = rows(); rows.put(id, changed);
            return publish(rows, state.allocations(), changed, null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (IllegalArgumentException | NullPointerException | ArithmeticException invalid) {
            return reject(Reason.BUDGET_EXHAUSTED, work);
        }
    }
    public Change pin(UUID id, Guard guard, ExecutionReference reference, TrustedContext caller) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        Change denied = guard(job, guard, work); if (denied != null) return denied;
        Attempt a = job.current();
        if (job.state() != State.ACTIVE || a == null || !a.open()) return reject(Reason.REQUEST_INVALID, work);
        if (!reference.artifact().capability().equals(job.request().request().capability()))
            return reject(Reason.REQUEST_INVALID, work);
        var same = a.executions().stream().filter(r -> r.runId().equals(reference.runId())).findFirst();
        if (same.isPresent()) return same.get().equals(reference) ? duplicate(job, work)
                : reject(Reason.REQUEST_INVALID, work);
        if (a.executions().size() == 8) return reject(Reason.STORAGE_LIMIT_REACHED, work);
        var refs = new ArrayList<>(a.executions()); refs.add(reference);
        Attempt next = attempt(a, refs, a.usage(), a.receipts(), a.effects(), a.credited(),
                a.terminal(), a.uncertain(), a.cancellationDeliveries());
        return replaceAttempt(job, next, EventKind.PINNED, state.allocations(), work, null);
    }
    /** Non-execution preparation work is still charged to every original parent. */
    public Change charge(UUID id, Guard guard, Map<Budgets.Kind, Long> delta, TrustedContext caller) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        Change denied = guard(job, guard, work); if (denied != null) return denied;
        try {
            delta = Jobs.usage(delta); var rows = rows();
            for (Job ancestor : ancestors(id, rows))
                for (var entry : delta.entrySet())
                    if (clock.millis() > ancestor.allowance().deadlineEpochMillis()
                            || entry.getValue() > available(ancestor.id(), entry.getKey(), rows))
                        return reject(Reason.BUDGET_EXHAUSTED, work);
            if (delta.isEmpty()) return duplicate(job, work);
            debit(rows, id, delta, EventKind.OBSERVED);
            return publish(rows, state.allocations(), rows.get(id), null, work.edges);
        } catch (IllegalArgumentException | ArithmeticException invalid) { return reject(Reason.BUDGET_EXHAUSTED, work); }
    }
    public Change recordExecutionResult(UUID id, Guard guard, Report report, TrustedContext caller) {
        return observe(id, guard, report, caller, false, false);
    }
    /** Certain owner observations are required; inventory totals cannot supply this report. */
    public Change reconcile(UUID id, Guard guard, Report report, boolean completeStoppedObservation,
                            TrustedContext caller) {
        return observe(id, guard, report, caller, true, completeStoppedObservation);
    }
    /** Typed host evidence for the original stopped attempt; legacy boolean API remains compatible. */
    public Change reconcileStopped(UUID id, Guard guard, StoppedEvidence evidence, TrustedContext caller) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        if (recoveryAuthority == null || !recoveryAuthority.authentic(evidence))
            return reject(Reason.STALE_OBSERVATION, work);
        var context = evidence.context(); var observation = evidence.observation();
        var current = job.current();
        if (current == null || !context.world().equals(state.worldId()) || !context.job().equals(id)
                || !context.origin().equals(job.origin()) || !context.attempt().equals(current.id())
                || context.generation() != job.generation() || !context.guard().equals(guard))
            return reject(Reason.STALE_OBSERVATION, work);
        // Stopped alone is not no-effect; partial custody needs the later native evidence protocol.
        if (observation.kind() != StoppedKind.NO_EFFECT && observation.kind() != StoppedKind.EFFECT_COMPLETE)
            return reject(Reason.INTERRUPTED, work);
        Report report = observation.report();
        if (!current.open() && current.effects() == report.effects() && current.usage().equals(report.usage())
                && current.receipts().equals(report.receipts()) && current.resourceReceipts().equals(report.resourceReceipts())
                && Objects.equals(current.terminal(), report.terminal())) return duplicate(job, work);
        if (!job.guard().equals(guard) || !context.original().equals(current)
                || observation.kind() == StoppedKind.NO_EFFECT && (current.effects() != 0 || current.receiptCount() != 0))
            return reject(Reason.STALE_OBSERVATION, work);
        return observe(id, guard, report, caller, true, true, true);
    }
    private Change observe(UUID id, Guard guard, Report report, TrustedContext caller,
                           boolean reconciliation, boolean certain) {
        return observe(id, guard, report, caller, reconciliation, certain, false);
    }
    private Change observe(UUID id, Guard guard, Report report, TrustedContext caller,
                           boolean reconciliation, boolean certain, boolean typedStopped) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        if (!ready()) return reject(unavailableReason(), work);
        try {
            Attempt old = job.attempts().stream().filter(a -> a.id().equals(report.attemptId())
                    && a.generation() == report.generation()).findFirst().orElse(null);
            if (old == null) return reject(Reason.STALE_OBSERVATION, work);
            boolean current = old.generation() == job.generation();
            if (reconciliation && (!current || job.state() != State.INTERRUPTED
                    && !(typedStopped && job.state() == State.CANCELLING) || !certain || report.terminal() == null))
                return reject(Reason.STALE_OBSERVATION, work);
            Map<UUID, CropDelivery.CropReceipt> receipts = new LinkedHashMap<>();
            old.receipts().forEach(r -> receipts.put(r.receiptId(), r));
            for (var receipt : report.receipts()) {
                validateReceipt(old, receipt);
                var previous = receipts.putIfAbsent(receipt.receiptId(), receipt);
                if (previous != null && !previous.equals(receipt)) return reject(Reason.REQUEST_INVALID, work);
                for (Job other : state.jobs()) for (Attempt otherAttempt : other.attempts())
                    if (!otherAttempt.id().equals(old.id()) && otherAttempt.receipts().stream()
                            .anyMatch(r -> r.receiptId().equals(receipt.receiptId())))
                        return reject(Reason.REQUEST_INVALID, work);
            }
            Map<UUID, ResourceDelivery.Receipt> resources = new LinkedHashMap<>();
            old.resourceReceipts().forEach(r -> resources.put(r.receiptId(),r));
            for (var receipt : report.resourceReceipts()) {
                validateReceipt(old,receipt);
                var previous = resources.putIfAbsent(receipt.receiptId(),receipt);
                if (previous != null && !previous.equals(receipt) || receipts.containsKey(receipt.receiptId()))
                    return reject(Reason.REQUEST_INVALID,work);
                for (Job other : state.jobs()) for (Attempt a : other.attempts())
                    if (!a.id().equals(old.id()) && (a.resourceReceipts().stream().anyMatch(r -> r.receiptId().equals(receipt.receiptId()))
                            || a.receipts().stream().anyMatch(r -> r.receiptId().equals(receipt.receiptId()))))
                        return reject(Reason.REQUEST_INVALID,work);
            }
            if (receipts.size() + resources.size() + job.attempts().stream().filter(a -> !a.id().equals(old.id()))
                    .mapToInt(Attempt::receiptCount).sum() > settings.receipts())
                return reject(Reason.STORAGE_LIMIT_REACHED, work);
            var usage = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class); usage.putAll(old.usage());
            for (var entry : report.usage().entrySet()) {
                if (entry.getValue() > old.allowance().maximum(entry.getKey()))
                    return reject(Reason.BUDGET_EXHAUSTED, work);
                usage.merge(entry.getKey(), entry.getValue(), Math::max);
            }
            long effects = Math.max(old.effects(), report.effects());
            var delta = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class);
            for (var entry : usage.entrySet()) {
                long amount = entry.getValue() - old.usage().getOrDefault(entry.getKey(), 0L);
                if (amount != 0) delta.put(entry.getKey(), amount);
            }
            boolean obsolete = !current || old.terminal() != null && !reconciliation;
            List<CropDelivery.CropReceipt> merged = List.copyOf(receipts.values());
            List<ResourceDelivery.Receipt> mergedResources = List.copyOf(resources.values());
            long credit = obsolete ? old.credited() : Math.min(quantity(old, merged, mergedResources),
                    ((IntValue)old.bound().request().arguments().get("amount")).value());
            Execution terminal = obsolete ? old.terminal() : report.terminal();
            if (!obsolete && terminal != null && terminal.status() == ExecutionStatus.SUCCEEDED
                    && old.executions().stream().noneMatch(r ->
                    ResourceDelivery.completed(old.bound(), r.runId(), merged, mergedResources)))
                return reject(Reason.ACTION_FAILED, work);
            boolean uncertain = reconciliation ? false : old.uncertain()
                    || !obsolete && terminal != null && terminal.status() == ExecutionStatus.INTERRUPTED;
            Attempt updated = attempt(old, old.executions(), usage, merged, effects, credit,
                    terminal, uncertain, old.cancellationDeliveries(), mergedResources);
            if (updated.equals(old)) return duplicate(job, work);
            if (current) {
                Change denied = guard(job, guard, work); if (denied != null) return denied;
            } // Historical owner evidence is fenced by the immutable attempt/generation, not current work.
            var rows = rows();
            for (Job ancestor : ancestors(id, rows))
                for (var entry : delta.entrySet())
                    if (Math.addExact(ancestor.usage().getOrDefault(entry.getKey(), 0L), entry.getValue())
                            > ancestor.allowance().maximum(entry.getKey()))
                        return reject(Reason.BUDGET_EXHAUSTED, work);
            var edit = new Edit(job);
            edit.attempts = job.attempts().stream().map(a -> a.id().equals(old.id()) ? updated : a).toList();
            if ((!obsolete && terminal != null && job.state() != State.INTERRUPTED) || reconciliation) {
                if (!reconciliation && terminal.status() == ExecutionStatus.INTERRUPTED) {
                    edit.state = State.INTERRUPTED; edit.reason = Reason.INTERRUPTED;
                } else if (job.cancellationOutcome() != null || job.state() == State.CANCELLING) {
                    edit.state = job.cancellationOutcome() == State.FAILED ? State.FAILED : State.CANCELLED;
                    edit.reason = edit.state == State.CANCELLED ? Reason.CANCELLED : Reason.ACTION_FAILED;
                } else switch (terminal.status()) {
                    case SUCCEEDED -> { edit.state = State.SUCCEEDED; edit.reason = null; }
                    case BLOCKED -> { edit.state = State.WAITING; edit.reason = terminal.reason(); }
                    case FAILED -> { edit.state = State.FAILED; edit.reason = terminal.reason(); }
                    case CANCELLED -> { edit.state = State.CANCELLED; edit.reason = Reason.CANCELLED; }
                    case INTERRUPTED -> {
                        edit.state = reconciliation ? State.READY : State.INTERRUPTED;
                        edit.reason = reconciliation ? null : Reason.INTERRUPTED;
                    }
                }
            }
            Job changed = edit.finish(obsolete ? EventKind.LATE_EFFECTS
                    : reconciliation ? EventKind.RECONCILED : EventKind.OBSERVED, settings.events());
            rows.put(id, changed);
            // The assignment usage was updated once; ancestor aggregates include the same delta once.
            if (!delta.isEmpty()) for (Job ancestor : ancestors(id, rows)) {
                var charged = new Edit(rows.get(ancestor.id()));
                var amounts = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class);
                amounts.putAll(charged.usage);
                delta.forEach((k, v) -> amounts.merge(k, v, Math::addExact));
                charged.usage = amounts;
                Job value = charged.old.id().equals(id)
                        ? copyUsage(rows.get(id), amounts)
                        : charged.finish(EventKind.OBSERVED, settings.events());
                rows.put(value.id(), value);
            }
            if (!obsolete) propagate(rows, Set.of(id), work);
            return publish(rows, state.allocations(), rows.get(id), null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (IllegalArgumentException | NullPointerException | ArithmeticException invalid) {
            return reject(Reason.REQUEST_INVALID, work);
        }
    }
    public Change cancel(UUID id, Guard guard, TrustedContext caller) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        if(job.state()==State.SUCCEEDED && BlueprintMaterials.valid(job.request().request())) {
            Change denied=guard(job,guard,work);if(denied!=null)return denied;
            var edit=new Edit(job);edit.state=State.CANCELLED;edit.reason=Reason.CANCELLED;
            var rows=rows();rows.put(id,edit.finish(EventKind.CANCEL_REQUESTED,settings.events()));
            return publish(rows,state.allocations(),rows.get(id),null,0);
        }
        if (job.state().terminal() || job.state() == State.CANCELLING) return duplicate(job, work);
        Change denied = guard(job, guard, work); if (denied != null) return denied;
        try {
            var rows = rows(); cascade(rows, id, State.CANCELLED, work, new HashSet<>());
            propagate(rows, Set.of(id), work);
            return publish(rows, state.allocations(), rows.get(id), null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (ArithmeticException overflow) { return reject(Reason.BUDGET_EXHAUSTED, work); }
    }
    /** Fail-closed preparation invalidation uses the same child cancellation fences as ordinary Jobs. */
    public Change failBlueprint(UUID id,Guard guard,Reason reason,TrustedContext caller) {
        thread();var work=new Work();var root=controlled(id,caller);
        if(root==null)return reject(Reason.AUTHORITY_DENIED,work);
        Change denied=guard(root,guard,work);if(denied!=null)return denied;
        if(!BlueprintMaterials.valid(root.request().request()) || root.state().terminal())return reject(Reason.REQUEST_INVALID,work);
        var rows=rows();cascade(rows,id,State.FAILED,work,new HashSet<>());var edit=new Edit(rows.get(id));edit.reason=reason;
        rows.put(id,edit.finish(EventKind.TRANSITIONED,settings.events()));return publish(rows,state.allocations(),rows.get(id),null,work.edges);
    }
    /** Allocate real deposited output; neither physical stock nor a resource lease is created. */
    public Change allocate(Allocation allocation, Guard demandGuard, TrustedContext caller) {
        return allocate(allocation, demandGuard, Long.MAX_VALUE, caller);
    }
    /** IMP-018 bounds partial native deposit receipts by the proposed producer/demand unit total. */
    public Change allocate(Allocation allocation, Guard demandGuard, long pairMaximum, TrustedContext caller) {
        thread(); var work = new Work(); Job demand = controlled(allocation.demand(), caller);
        Job producer = controlled(allocation.producer(), caller);
        if (allocation.material())return reject(Reason.REQUEST_INVALID,work);
        if (demand == null || producer == null || !producer.origin().equals(demand.origin()))
            return reject(Reason.AUTHORITY_DENIED, work);
        // A direct oak request promises newly acquired output from this invocation.
        // Historical production evidence is not a downstream material-demand contract.
        if (ResourceDelivery.resource(demand.request().request().capability()))
            return reject(Reason.REQUEST_INVALID, work);
        var existing = state.allocations().stream().filter(a -> a.id().equals(allocation.id())).findFirst();
        if (existing.isPresent()) return existing.get().equals(allocation) ? duplicate(demand, work)
                : reject(Reason.REQUEST_INVALID, work);
        Change denied = guard(demand, demandGuard, work); if (denied != null) return denied;
        try {
            if (state.allocations().size() == settings.allocations()) return reject(Reason.STORAGE_LIMIT_REACHED, work);
            if (demand.state() != State.READY || !dependenciesSucceeded(demand, rows(), work)
                    || !sameBindings(producer.request(), demand.request()))
                return reject(Reason.REQUEST_INVALID, work);
            Attempt attempt = producer.attempts().stream().filter(a -> a.id().equals(allocation.attempt())).findFirst().orElseThrow();
            if (attempt.terminal() == null || attempt.uncertain()) return reject(Reason.STALE_OBSERVATION, work);
            var receipt = credit(attempt,allocation.receipt()).filter(Credit::deposit).orElseThrow();
            if (pairMaximum < 1) return reject(Reason.REQUEST_INVALID, work);
            long receiptUse = 0, batchUse = 0, demandUse = 0, pairUse = 0;
            for (Allocation used : state.allocations()) {
                if(used.material())continue;
                if (used.demand().equals(demand.id())) demandUse = Math.addExact(demandUse, used.quantity());
                if (used.demand().equals(demand.id()) && used.producer().equals(producer.id()))
                    pairUse = Math.addExact(pairUse, used.quantity());
                if (used.producer().equals(producer.id()) && used.attempt().equals(attempt.id())) {
                    if (used.receipt().equals(receipt.receiptId())) receiptUse = Math.addExact(receiptUse, used.quantity());
                    var other = credit(attempt,used.receipt()).orElseThrow();
                    if (other.batchId().equals(receipt.batchId()) && other.runId().equals(receipt.runId()))
                        batchUse = Math.addExact(batchUse, used.quantity());
                }
            }
            long needed = ((IntValue)demand.request().request().arguments().get("amount")).value();
            long batch = batchQuantity(attempt, receipt.runId(), receipt.batchId());
            if (Math.addExact(pairUse, allocation.quantity()) > pairMaximum
                    || Math.addExact(receiptUse, allocation.quantity()) > receipt.quantity()
                    || Math.addExact(batchUse, allocation.quantity()) > batch
                    || Math.addExact(demandUse, allocation.quantity()) > needed - demand.fulfilled())
                return reject(Reason.RESOURCE_MISSING, work);
            var allocations = new ArrayList<>(state.allocations()); allocations.add(allocation);
            var edit = new Edit(demand);
            if (demandUse + allocation.quantity() + demand.fulfilled() == needed) {
                edit.state = State.SUCCEEDED; edit.reason = null;
            }
            Job changed = edit.finish(EventKind.ALLOCATED, settings.events()); var rows = rows(); rows.put(demand.id(), changed);
            propagate(rows, Set.of(demand.id()), work);
            return publish(rows, allocations, rows.get(demand.id()), null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (IllegalArgumentException | NoSuchElementException | ArithmeticException invalid) {
            return reject(Reason.REQUEST_INVALID, work);
        }
    }
    public Roots protectedRoots() {
        thread(); var artifacts = new HashSet<ArtifactRef>(); var runs = new HashSet<UUID>(); var jobs = new HashSet<UUID>();
        for (Snapshot snapshot : pending == null ? List.of(state) : List.of(state, pending))
            for (Job job : snapshot.jobs()) {
                jobs.add(job.id()); jobs.addAll(job.dependencies()); jobs.addAll(job.children());
                if(job.preparation()!=null && job.preparation().method()!=null)artifacts.add(job.preparation().method());
                for (Attempt a : job.attempts()) {
                    runs.add(a.id());
                    for (var ref : a.executions()) { runs.add(ref.runId()); artifacts.addAll(ref.pinned()); }
                }
            }
        return new Roots(artifacts, runs, jobs, !readOnly && !fenced);
    }
    /** One ack, or one durable notification preparation, per tick. */
    public void tick() {
        thread();
        if (fenced || readOnly) { writes.clear(); return; }
        Written written = writes.poll();
        if (written != null) {
            if (pending == null || written.error() != null || !pending.equals(written.snapshot())
                    || written.completedAt() > deadline) {
                fenced = true; failure = Reason.STORAGE_UNAVAILABLE; afterWrite = null; return;
            }
            state = pending; pending = null;
            Runnable after = afterWrite; afterWrite = null;
            if (after != null) try { after.run(); }
            catch (RuntimeException notificationFailure) { failure = Reason.ACTION_FAILED; }
            return;
        }
        if (pending != null) {
            if (clock.millis() >= deadline) { fenced = true; afterWrite = null; failure = Reason.STORAGE_UNAVAILABLE; }
            return;
        }
        for (Job job : state.jobs()) if (job.state() == State.CANCELLING && job.current() != null
                && job.current().open() && job.current().cancellationDeliveries() < settings.cancellationDeliveries()) {
            Attempt a = job.current();
            Attempt next = attempt(a, a.executions(), a.usage(), a.receipts(), a.effects(), a.credited(),
                    a.terminal(), a.uncertain(), a.cancellationDeliveries() + 1);
            var cancellation = new Cancellation(job.id(), a.id(), a.generation(), job.origin());
            replaceAttempt(job, next, EventKind.CANCEL_DELIVERY, state.allocations(), new Work(),
                    () -> cancellations.request(cancellation));
            return;
        }
    }
    private Change replaceAttempt(Job job, Attempt next, EventKind kind, List<Allocation> allocations,
                                  Work work, Runnable after) {
        try {
            var edit = new Edit(job);
            edit.attempts = job.attempts().stream().map(a -> a.id().equals(next.id()) ? next : a).toList();
            Job changed = edit.finish(kind, settings.events()); var rows = rows(); rows.put(job.id(), changed);
            return publish(rows, allocations, changed, after, work.edges);
        } catch (ArithmeticException overflow) { return reject(Reason.BUDGET_EXHAUSTED, work); }
    }
    private Change publish(Map<UUID, Job> rows, List<Allocation> allocations, Job changed,
                           Runnable after, int inspectedEdges) {
        try {
            if (!ready()) return new Change(Code.REJECTED, null, unavailableReason(), inspectedEdges);
            // Propagation can update accounting and state in the same transaction.
            // Publish one revision per changed job, including its bounded event summaries.
            for (Job prior : state.jobs()) {
                Job updated = rows.get(prior.id());
                if (updated != null && updated.revision() > Math.addExact(prior.revision(),1)) {
                    long revision = Math.addExact(prior.revision(),1);
                    var events = updated.events().stream().map(event -> event.revision() > prior.revision()
                            ? new Event(revision,event.kind(),event.attempt(),event.generation()) : event).toList();
                    rows.put(updated.id(),new Job(updated.id(),updated.submissionId(),revision,updated.request(),updated.parent(),
                            updated.dependencies(),updated.children(),updated.state(),updated.reason(),updated.cancellationOutcome(),
                            updated.allowance(),updated.usage(),updated.generation(),updated.attempts(),events,updated.preparation()));
                }
            }
            Snapshot next = new Snapshot(state.worldId(), Math.addExact(state.revision(), 1),
                    List.copyOf(rows.values()), allocations);
            if (!retentionGuard.test(next)) return new Change(Code.REJECTED, null, Reason.STORAGE_UNAVAILABLE, inspectedEdges);
            deadline = Math.addExact(clock.millis(), settings.publicationMillis());
            pending = next; afterWrite = after;
            storage.replace(state, next).whenComplete((value, error) ->
                    writes.add(new Written(value, error, clock.millis())));
            return new Change(Code.PENDING, changed == null ? null : rows.get(changed.id()), null, inspectedEdges);
        } catch (RuntimeException error) {
            fenced = true; afterWrite = null; failure = Reason.STORAGE_UNAVAILABLE;
            return new Change(Code.REJECTED, null, failure, inspectedEdges);
        }
    }
    private Job fresh(UUID id, UUID submission, ValidatedRequest bound, UUID parent,
                      Budgets.Limits allowance, List<UUID> dependencies) {
        return new Job(id, submission, 1, bound, parent, dependencies, List.of(),
                dependencies.isEmpty() ? State.READY : State.WAITING, null, null, allowance,
                Map.of(), 0, List.of(), List.of(new Event(1, EventKind.CREATED, null, 0)));
    }
    private Change guard(Job job, Guard guard, Work work) {
        if (!ready()) return reject(unavailableReason(), work);
        if (guard == null || !job.guard().equals(guard))
            return new Change(Code.CONFLICT, null, Reason.STALE_OBSERVATION, work.edges);
        return null;
    }
    private Job controlled(UUID id, TrustedContext caller) {
        Job job = find(id);
        return job != null && sameWorld(caller) && policy.mayControl(caller, job) ? job : null;
    }
    private boolean sameWorld(TrustedContext caller) {
        return caller != null && caller.scope().worldId().equals(state.worldId());
    }
    private Job find(UUID id) { return state.jobs().stream().filter(j -> j.id().equals(id)).findFirst().orElse(null); }
    private LinkedHashMap<UUID, Job> rows() {
        var rows = new LinkedHashMap<UUID, Job>(); state.jobs().forEach(j -> rows.put(j.id(), j)); return rows;
    }
    private Change reject(Reason reason, Work work) {
        return new Change(Code.REJECTED, null, Objects.requireNonNull(reason), Math.min(work.edges, settings.edges()));
    }
    private Change duplicate(Job job, Work work) { return new Change(Code.DUPLICATE, job, null, work.edges); }
    private void checkCapacity(Map<UUID, Job> rows) {
        if (rows.size() > settings.total() || rows.values().stream().filter(j -> !j.state().terminal()).count() > settings.active())
            throw new Limited(Reason.STORAGE_LIMIT_REACHED);
    }
    private void checkGraph(UUID id, Map<UUID, Job> rows, Work work) {
        graph(id, rows, work, new HashSet<>(), 0);
    }
    private void graph(UUID id, Map<UUID, Job> rows, Work work, Set<UUID> path, int depth) {
        if (depth > settings.depth()) throw new Limited(Reason.BUDGET_EXHAUSTED);
        if (!path.add(id)) throw new Limited(Reason.REQUEST_INVALID);
        Job job = rows.get(id); if (job == null) throw new Limited(Reason.REQUEST_INVALID);
        for (UUID dependency : job.dependencies()) {
            work.edge(); Job needed = rows.get(dependency);
            if (needed == null || !needed.origin().equals(job.origin())) throw new Limited(Reason.AUTHORITY_DENIED);
            graph(dependency, rows, work, path, depth + 1);
        }
        path.remove(id);
    }
    private boolean dependenciesSucceeded(Job job, Map<UUID, Job> rows, Work work) {
        for (UUID id : job.dependencies()) { work.edge(); if (rows.get(id).state() != State.SUCCEEDED) return false; }
        return true;
    }
    private Job dependencyState(Job job, Map<UUID, Job> rows, boolean increment) {
        boolean ready = job.dependencies().stream().allMatch(id -> rows.get(id).state() == State.SUCCEEDED);
        if (job.state() == State.READY && !ready || job.state() == State.WAITING && ready && job.reason() == null) {
            var edit = new Edit(job); edit.state = ready ? State.READY : State.WAITING;
            return increment ? edit.finish(EventKind.TRANSITIONED, settings.events())
                    : new Job(job.id(), job.submissionId(), job.revision(), job.request(), job.parent(), job.dependencies(),
                    job.children(), edit.state, job.reason(), job.cancellationOutcome(), job.allowance(),
                    job.usage(), job.generation(), job.attempts(), job.events(),job.preparation());
        }
        return job;
    }
    private void propagate(Map<UUID, Job> rows, Set<UUID> changed, Work work) {
        Map<UUID, List<UUID>> reverse = new HashMap<>();
        for (Job job : rows.values()) for (UUID dep : job.dependencies())
            reverse.computeIfAbsent(dep, ignored -> new ArrayList<>()).add(job.id());
        var queue = new ArrayDeque<>(changed); var visited = new HashSet<UUID>();
        while (!queue.isEmpty()) {
            UUID id = queue.removeFirst(); if (!visited.add(id)) continue;
            for (UUID dependent : reverse.getOrDefault(id, List.of())) {
                work.edge(); Job job = rows.get(dependent);
                if (job.state().terminal()) continue;
                Job child = rows.get(id);
                if (child.state() == State.CANCELLED || child.state() == State.FAILED)
                    cascade(rows, dependent, child.state() == State.CANCELLED ? State.CANCELLED : State.FAILED,
                            work, new HashSet<>());
                else if (job.state() == State.WAITING && (job.reason() == null || job.reason() == Reason.INTERRUPTED)
                        && (job.current() == null || !job.current().open())
                        && dependenciesSucceeded(job, rows, work)) {
                    var edit = new Edit(job); edit.state = job.request().request().capability().equals(PlanningGoals.ID)
                            && PlanningGoals.covers(job.request().request(),job.children().stream().map(childId->rows.get(childId).request().request()).toList())
                            ? State.SUCCEEDED : State.READY; edit.reason = null;
                    rows.put(dependent, edit.finish(EventKind.TRANSITIONED, settings.events()));
                }
                if (rows.get(dependent).state().terminal()) queue.addLast(dependent);
            }
        }
        for (Job job : List.copyOf(rows.values())) if (job.state() == State.CANCELLING
                && (job.current() == null || !job.current().open())
                && job.children().stream().allMatch(id -> rows.get(id).state().terminal())) {
            var edit = new Edit(job);
            edit.state = job.cancellationOutcome() == State.FAILED ? State.FAILED : State.CANCELLED;
            edit.reason = edit.state == State.CANCELLED ? Reason.CANCELLED : Reason.ACTION_FAILED;
            rows.put(job.id(), edit.finish(EventKind.TRANSITIONED, settings.events()));
        }
    }
    private void cascade(Map<UUID, Job> rows, UUID id, State terminal, Work work, Set<UUID> visited) {
        if (!visited.add(id)) return;
        Job job = rows.get(id); if (job.state().terminal()) return;
        if (job.cancellationOutcome() == State.FAILED) terminal = State.FAILED;
        if (job.state() == State.CANCELLING && job.cancellationOutcome() == terminal) return;
        for (UUID child : job.children()) { work.edge(); cascade(rows, child, terminal, work, visited); }
        boolean open = job.current() != null && job.current().open()
                || job.children().stream().anyMatch(child -> !rows.get(child).state().terminal());
        var edit = new Edit(job); edit.cancellationOutcome = terminal;
        edit.state = open ? State.CANCELLING : terminal;
        edit.reason = terminal == State.CANCELLED ? Reason.CANCELLED : Reason.ACTION_FAILED;
        rows.put(id, edit.finish(EventKind.CANCEL_REQUESTED, settings.events()));
    }
    private List<Job> ancestors(UUID id, Map<UUID, Job> rows) {
        var result = new ArrayList<Job>(); var seen = new HashSet<UUID>();
        while (id != null) {
            if (result.size() > settings.depth() || !seen.add(id)) throw new Limited(Reason.BUDGET_EXHAUSTED);
            Job job = rows.get(id); if (job == null) throw new Limited(Reason.REQUEST_INVALID);
            result.add(job); id = job.parent();
        }
        return result;
    }
    private long available(UUID id, Budgets.Kind kind, Map<UUID, Job> rows) {
        Job job = rows.get(id); long reserved = 0;
        for (Job child : rows.values()) if (ancestors(child.id(), rows).stream().anyMatch(a -> a.id().equals(id)))
            for (Attempt a : child.attempts()) if (a.open())
                reserved = Math.addExact(reserved, a.allowance().maximum(kind) - a.usage().getOrDefault(kind, 0L));
        return Math.subtractExact(Math.subtractExact(job.allowance().maximum(kind),
                job.usage().getOrDefault(kind, 0L)), reserved);
    }
    private void debit(Map<UUID, Job> rows, UUID id, Map<Budgets.Kind, Long> delta, EventKind event) {
        for (Job ancestor : ancestors(id, rows)) {
            var edit = new Edit(rows.get(ancestor.id()));
            var values = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class); values.putAll(edit.usage);
            delta.forEach((kind, amount) -> values.merge(kind, amount, Math::addExact)); edit.usage = values;
            rows.put(ancestor.id(), edit.finish(event, settings.events()));
        }
    }
    private static void envelope(Budgets.Limits child, Budgets.Limits parent) {
        if (child == null || child.deadlineEpochMillis() > parent.deadlineEpochMillis())
            throw new Limited(Reason.BUDGET_EXHAUSTED);
        for (Budgets.Kind kind : Budgets.Kind.values())
            if (child.maximum(kind) > parent.maximum(kind)) throw new Limited(Reason.BUDGET_EXHAUSTED);
    }
    private ValidatedRequest remainingRequest(Job job, ActorRef worker) {
        var args = new HashMap<>(job.request().request().arguments()); args.put("actor", new ActorValue(worker));
        long allocated = state.allocations().stream().filter(a -> a.demand().equals(job.id()) && !a.material())
                .mapToLong(Allocation::quantity).reduce(0, Math::addExact);
        long remaining = ((IntValue)args.get("amount")).value() - job.fulfilled() - allocated;
        if (remaining <= 0) throw new Limited(Reason.REQUEST_INVALID);
        args.put("amount", new IntValue(remaining));
        return new ValidatedRequest(new CapabilityRequest(job.request().request().capability(), args),
                job.origin(), job.request().observation());
    }
    private static Attempt attempt(Attempt old, List<ExecutionReference> refs, Map<Budgets.Kind, Long> usage,
                                   List<CropDelivery.CropReceipt> receipts, long effects, long credited,
                                   Execution terminal, boolean uncertain, int deliveries) {
        return attempt(old,refs,usage,receipts,effects,credited,terminal,uncertain,deliveries,old.resourceReceipts());
    }
    private static Attempt attempt(Attempt old,List<ExecutionReference> refs,Map<Budgets.Kind,Long> usage,
                                   List<CropDelivery.CropReceipt> receipts,long effects,long credited,
                                   Execution terminal,boolean uncertain,int deliveries,List<ResourceDelivery.Receipt> resources) {
        return new Attempt(old.id(), old.generation(), old.worker(), old.bound(), old.allowance(),
                refs, usage, receipts, effects, credited, terminal, uncertain, deliveries,resources);
    }
    private static Job copyUsage(Job job, Map<Budgets.Kind, Long> usage) {
        return new Job(job.id(), job.submissionId(), job.revision(), job.request(), job.parent(),
                job.dependencies(), job.children(), job.state(), job.reason(), job.cancellationOutcome(),
                job.allowance(), usage, job.generation(), job.attempts(), job.events(),job.preparation());
    }
    private static void validateReceipt(Attempt attempt, CropDelivery.CropReceipt receipt) {
        if (!attempt.bound().request().capability().equals(CropDelivery.ID)
                || attempt.executions().stream().noneMatch(r -> r.runId().equals(receipt.runId()))
                || !receipt.actor().equals(attempt.worker())
                || !receipt.source().equals(((AreaValue)attempt.bound().request().arguments().get("source")).value())
                || !receipt.destination().equals(((ContainerValue)attempt.bound().request().arguments().get("destination")).value()))
            throw new Limited(Reason.AUTHORITY_DENIED);
    }
    private static void validateReceipt(Attempt attempt,ResourceDelivery.Receipt receipt) {
        if (!ResourceDelivery.resource(attempt.bound().request().capability())
                || attempt.executions().stream().noneMatch(r -> r.runId().equals(receipt.runId()))
                || !ResourceDelivery.matches(attempt.bound(),receipt.runId(),receipt))
            throw new Limited(Reason.AUTHORITY_DENIED);
    }
    private static long quantity(Attempt attempt, List<CropDelivery.CropReceipt> receipts) {
        return quantity(attempt,receipts,attempt.resourceReceipts());
    }
    private static long quantity(Attempt attempt,List<CropDelivery.CropReceipt> receipts,List<ResourceDelivery.Receipt> resources) {
        if (ResourceDelivery.resource(attempt.bound().request().capability())) {
            if (!receipts.isEmpty()) throw new IllegalArgumentException("Crop evidence for oak");
            long total=0;
            for (var run : attempt.executions()) total=Math.addExact(total,ResourceDelivery.quantity(attempt.bound(),
                    run.runId(),resources.stream().filter(r -> r.runId().equals(run.runId())).toList()));
            return total;
        }
        if (!resources.isEmpty()) throw new IllegalArgumentException("Resource evidence for wheat");
        var batches = new HashSet<List<UUID>>(); long result = 0;
        for (var receipt : receipts) batches.add(List.of(receipt.runId(), receipt.batchId()));
        for (var batch : batches) result = Math.addExact(result, batchQuantity(receipts, batch.getFirst(), batch.getLast()));
        return result;
    }
    private static long batchQuantity(List<CropDelivery.CropReceipt> receipts, UUID run, UUID batch) {
        long[] stages = new long[3];
        for (var receipt : receipts) if (receipt.runId().equals(run) && receipt.batchId().equals(batch))
            stages[receipt.stage().ordinal()] = Math.addExact(stages[receipt.stage().ordinal()], receipt.wheat());
        return Math.min(stages[0], Math.min(stages[1], stages[2]));
    }
    private record Credit(UUID receiptId,UUID runId,UUID batchId,long quantity,boolean deposit) { }
    private static Optional<Credit> credit(Attempt a,UUID id) {
        if (ResourceDelivery.resource(a.bound().request().capability()))
            return a.resourceReceipts().stream().filter(r -> r.receiptId().equals(id)).findFirst()
                    .map(r -> new Credit(r.receiptId(),r.runId(),r.batchId(),r.quantity(),r.stage()==ResourceDelivery.Stage.DEPOSIT));
        return a.receipts().stream().filter(r -> r.receiptId().equals(id)).findFirst()
                .map(r -> new Credit(r.receiptId(),r.runId(),r.batchId(),r.wheat(),r.stage()==CropDelivery.Stage.DEPOSIT));
    }
    private static long batchQuantity(Attempt a,UUID run,UUID batch) {
        return ResourceDelivery.resource(a.bound().request().capability())
                ? ResourceDelivery.quantity(a.bound(),run,a.resourceReceipts().stream()
                        .filter(r -> r.runId().equals(run) && r.batchId().equals(batch)).toList())
                : batchQuantity(a.receipts(),run,batch);
    }
    private static Map<String,Value> withWorker(Map<String,Value> original, ActorRef worker) {
        var values = new HashMap<>(original); values.put("actor",new ActorValue(worker)); return values;
    }
    private static boolean sameBindings(ValidatedRequest a, ValidatedRequest b) {
        var left = new HashMap<>(a.request().arguments()); var right = new HashMap<>(b.request().arguments());
        left.remove("amount"); right.remove("amount");
        return a.context().equals(b.context()) && a.request().capability().equals(b.request().capability()) && left.equals(right);
    }
    /** Bounded load/import verification. No unknown field or graph becomes active by parsing alone. */
    public static void validateSnapshot(Snapshot snapshot, Settings settings) {
        if (snapshot.jobs().size() > settings.total()
                || snapshot.jobs().stream().filter(j -> !j.state().terminal()).count() > settings.active()
                || snapshot.allocations().size() > settings.allocations()) throw new IllegalArgumentException("Job quota");
        Map<UUID, Job> rows = new HashMap<>(); snapshot.jobs().forEach(j -> rows.put(j.id(), j));
        for (Allocation allocation : snapshot.allocations()) {
            if(allocation.material()){validateMaterialAllocation(allocation,snapshot);continue;}
            Job producer = rows.get(allocation.producer()), demand = rows.get(allocation.demand());
            if (producer == null || demand == null || producer.id().equals(demand.id())
                    || ResourceDelivery.resource(demand.request().request().capability())
                    || !sameBindings(producer.request(), demand.request()))
                throw new IllegalArgumentException("Allocation scope/bindings");
            Attempt attempt = producer.attempts().stream().filter(a -> a.id().equals(allocation.attempt()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Allocation attempt"));
            var receipt = credit(attempt,allocation.receipt()).orElseThrow(() -> new IllegalArgumentException("Allocation receipt"));
            if (attempt.open() || !receipt.deposit())
                throw new IllegalArgumentException("Unobserved allocation");
            long receiptSpent = 0, batchSpent = 0, demandSpent = 0;
            for (Allocation other : snapshot.allocations()) {
                if(other.material())continue;
                if (other.receipt().equals(receipt.receiptId())) receiptSpent = Math.addExact(receiptSpent, other.quantity());
                if (other.producer().equals(producer.id()) && other.attempt().equals(attempt.id())) {
                    var source = credit(attempt,other.receipt());
                    if (source.isPresent() && source.get().batchId().equals(receipt.batchId())
                            && source.get().runId().equals(receipt.runId())) batchSpent = Math.addExact(batchSpent, other.quantity());
                }
                if (other.demand().equals(demand.id())) demandSpent = Math.addExact(demandSpent, other.quantity());
            }
            if (receiptSpent > receipt.quantity() || batchSpent > batchQuantity(attempt, receipt.runId(), receipt.batchId())
                    || Math.addExact(demandSpent, demand.fulfilled()) > ((IntValue)demand.request().request().arguments().get("amount")).value())
                throw new IllegalArgumentException("Allocation overspent");
        }
        Set<UUID> attempts = new HashSet<>(), receipts = new HashSet<>(), openWorkers = new HashSet<>();
        for (Job job : snapshot.jobs()) {
            if (job.children().size() > settings.children() || job.dependencies().size() > settings.children()
                    || job.attempts().size() > settings.attempts() || job.events().size() > settings.events()
                    || job.attempts().stream().mapToInt(Attempt::receiptCount).sum() > settings.receipts()
                    || job.attempts().stream().anyMatch(a -> a.cancellationDeliveries() > settings.cancellationDeliveries()))
                throw new IllegalArgumentException("Configured job bounds");
            boolean goal=job.request().request().capability().equals(PlanningGoals.ID);
            boolean staging=job.request().request().capability().equals(BlueprintMaterials.ID);
            if ((!ResourceDelivery.isDelivery(job.request().request().capability()) && !goal && !staging)
                    || goal && (!PlanningGoals.valid(job.request().request()) || !job.attempts().isEmpty())
                    || staging && (!BlueprintMaterials.valid(job.request().request()) || !job.attempts().isEmpty()
                            || job.preparation()==null || job.preparation().method()!=null)
                    || !(job.request().request().arguments().get("amount") instanceof IntValue quantity)
                    || quantity.value() < (staging?0:1) || quantity.value() > 64
                    || !(job.request().request().arguments().get("actor") instanceof ActorValue)
                    || (staging || RecipeProduction.is(job.request().request().capability()) || CraftingTablePlacement.is(job.request().request().capability())
                        ? !(job.request().request().arguments().get("source") instanceof ContainerValue)
                        : !(job.request().request().arguments().get("source") instanceof AreaValue))
                    || (CraftingTablePlacement.is(job.request().request().capability()) ? !CraftingTablePlacement.valid(job.request().request()) : !(job.request().request().arguments().get("destination") instanceof ContainerValue)))
                throw new IllegalArgumentException("Bound job request");
            if(job.preparation()!=null) {
                var p=job.preparation();
                if(p.method()!=null&&!p.method().capability().equals(job.request().request().capability()))throw new IllegalArgumentException("Prepared method capability");
                for(var m:p.materials())if(!job.dependencies().contains(m.producer()) || m.quantity()>ResourceComposition.inputQuantity(job.request().request(),m.item()))throw new IllegalArgumentException("Prepared material");
            }
            var syntax = RequestValidator.validate(job.request().request(), job.origin(), job.request().observation(),
                    PlanningGoals.catalog(),
                    new RequestEnvironment() {
                        public boolean enrolled(ActorRef actor, TrustedContext caller) { return true; }
                        public boolean loaded(Cuboid source, ObservationRef observation) { return true; }
                        public boolean available(ContainerRef target, ObservationRef observation) { return true; }
                    });
            if (!(syntax instanceof RequestValidator.Accepted)) throw new IllegalArgumentException("Malformed job bindings");
            validateGraphLoad(job.id(), rows, new HashSet<>(), 0, settings.depth(), new int[]{0}, settings.edges());
            for (UUID child : job.children())
                if (!job.dependencies().contains(child) || !job.id().equals(rows.get(child).parent())
                        || !job.origin().equals(rows.get(child).origin())) throw new IllegalArgumentException("Child linkage");
            if(staging) {
                if(job.children().size()>1 || !job.dependencies().equals(job.children()))throw new IllegalArgumentException("Bounded staging graph");
                for(UUID childId:job.children()) {
                    var child=rows.get(childId);long shortage=((IntValue)child.request().request().arguments().get("amount")).value();
                    if(shortage<1 || shortage>quantity.value() || !child.request().request().equals(BlueprintMaterials.production(job.request().request(),shortage)))
                        throw new IllegalArgumentException("Exact staging deficit child");
                }
            }
            if (job.parent() != null && (!rows.get(job.parent()).children().contains(job.id())
                    || !rows.get(job.parent()).origin().equals(job.origin()))) throw new IllegalArgumentException("Parent linkage");
            for (Attempt attempt : job.attempts()) {
                if (!attempts.add(attempt.id()) || attempt.open() && !openWorkers.add(attempt.worker().entityId()))
                    throw new IllegalArgumentException("Duplicate assignment");
                envelope(attempt.allowance(), job.allowance());
                if (!sameBindings(attempt.bound(), new ValidatedRequest(new CapabilityRequest(job.request().request().capability(),
                        withWorker(job.request().request().arguments(),attempt.worker())),job.origin(),job.request().observation()))
                        || attempt.executions().stream().anyMatch(r -> !r.artifact().capability().equals(job.request().request().capability())))
                    throw new IllegalArgumentException("Changed assignment bindings");
                for (var receipt : attempt.receipts()) {
                    validateReceipt(attempt, receipt);
                    if (!receipts.add(receipt.receiptId())) throw new IllegalArgumentException("Duplicate receipt ownership");
                }
                for (var receipt : attempt.resourceReceipts()) {
                    validateReceipt(attempt,receipt);
                    if (!receipts.add(receipt.receiptId())) throw new IllegalArgumentException("Duplicate resource receipt ownership");
                }
                if (attempt.credited() > quantity(attempt, attempt.receipts()))
                    throw new IllegalArgumentException("Unattributed credit");
            }
            for (var entry : job.usage().entrySet())
                if (entry.getValue() > job.allowance().maximum(entry.getKey())) throw new IllegalArgumentException("Budget widened");
            var minimum = new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
            for (Attempt a : job.attempts())
                a.usage().forEach((k,v) -> minimum.merge(k,v,Math::addExact));
            for (UUID child : job.children())
                rows.get(child).usage().forEach((k,v) -> minimum.merge(k,v,Math::addExact));
            for (var entry : minimum.entrySet())
                if (job.usage().getOrDefault(entry.getKey(),0L) < entry.getValue())
                    throw new IllegalArgumentException("Lost cumulative parent work");
            if (job.current() != null && job.current().open()
                    && job.state() != State.ACTIVE && job.state() != State.CANCELLING && job.state() != State.INTERRUPTED)
                throw new IllegalArgumentException("Unreconciled assignment");
            if (job.state() == State.FAILED && job.reason() == null
                    || job.state() == State.ACTIVE && (job.current() == null || !job.current().open()))
                throw new IllegalArgumentException("Job state evidence");
            long allocated = snapshot.allocations().stream().filter(a -> a.demand().equals(job.id()) && !a.material())
                    .mapToLong(Allocation::quantity).reduce(0,Math::addExact);
            if (job.state() == State.SUCCEEDED && (staging
                    ? !job.children().stream().allMatch(childId->rows.get(childId).state()==State.SUCCEEDED)
                    : goal
                    ? !PlanningGoals.covers(job.request().request(),job.children().stream().map(childId->rows.get(childId).request().request()).toList())
                            || !job.children().stream().allMatch(childId->rows.get(childId).state()==State.SUCCEEDED)
                    : Math.addExact(job.fulfilled(),ResourceDelivery.resource(job.request().request().capability())
                            ? 0 : allocated) < quantity.value()))
                throw new IllegalArgumentException("Unattributed job success");
        }
    }
    private static void validateGraphLoad(UUID id, Map<UUID, Job> rows, Set<UUID> path, int depth, int maximum, int[] edges, int edgeLimit) {
        if (depth > maximum || !path.add(id)) throw new IllegalArgumentException("Job graph cycle/depth");
        Job job = rows.get(id);
        for (UUID dependency : job.dependencies()) {
            if (!job.origin().equals(rows.get(dependency).origin())) throw new IllegalArgumentException("Foreign dependency");
            if (edges[0] == edgeLimit) throw new IllegalArgumentException("Job graph work");
            edges[0]++;
            validateGraphLoad(dependency, rows, path, depth + 1, maximum, edges, edgeLimit);
        }
        path.remove(id);
    }
    private void thread() {
        if (Thread.currentThread() != gameThread) throw new IllegalStateException("Server thread required");
    }
}
