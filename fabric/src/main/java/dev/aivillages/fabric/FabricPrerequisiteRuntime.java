package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.Prerequisites.*;

/** Runtime composition for IMP-018. No planner/model port, worker selection, or crafting implementation. */
public final class FabricPrerequisiteRuntime {
    private final MinecraftServer server;
    private final JobLifecycleStore jobs;
    private final CapabilityResolver.Engine resolver;
    private final Clock clock;
    private final Limits limits=Limits.defaults();
    private final LeasePrerequisiteSupplies supplies;
    private final ResourceLeaseService leases;
    private final PrerequisiteAdmission admission;
    private final java.util.function.Function<CapabilityRequest,ObservationSnapshot> observations;
    private final Map<UUID,Cuboid> staged=new LinkedHashMap<>();
    private java.util.function.Function<Jobs.Job,BlueprintMaterials.Report> blueprintObservations;
    private record BlueprintCheck(BlueprintMaterials.Report report,long tick,boolean childComplete) { }
    private final Map<UUID,BlueprintCheck> blueprintChecks=new HashMap<>();
    public void blueprints(java.util.function.Function<Jobs.Job,BlueprintMaterials.Report> capture){thread();blueprintObservations=Objects.requireNonNull(capture);}
    /** Read-only support preview before a staging demand exists. No Jobs, claims, inference or effects. */
    public Proposal preview(ValidatedRequest request,Cuboid acquisition,Supplies stock,Access access,Budgets.Limits allowance) {
        thread();var need=new Need(UUID.randomUUID(),new Jobs.Guard(0,0),request,BlueprintMaterials.PLANKS,
                ((IntValue)request.request().arguments().get("amount")).value(),AllocationPolicy.MATERIAL_ONLY,acquisition);
        return new DeterministicPrerequisiteResolver(limits,()->server.overworld().getGameTime()).resolve(List.of(need),
                FabricPrerequisiteCatalog.capture(server,ResourceComposition.RECIPE_KEYS),stock,this::method,access,new Budgets.Ledger(allowance,clock));
    }
    public record FacilityObservation(boolean existing,ObservationSnapshot axe,ObservationSnapshot placement,Reason reason) { }
    private java.util.function.Function<CapabilityRequest,FacilityObservation> facilities;
    private static final class FacilityStage {final Cuboid oak;boolean charged;FacilityStage(Cuboid oak){this.oak=oak;}}
    private final Map<UUID,FacilityStage> facilityStages=new LinkedHashMap<>();
    public void facilities(java.util.function.Function<CapabilityRequest,FacilityObservation> capture){thread();facilities=Objects.requireNonNull(capture);}
    public void stageFacility(UUID root,Cuboid oak){thread();if(facilityStages.size()+staged.size()>=32)throw new IllegalStateException("Bounded staged facility admission");facilityStages.put(root,new FacilityStage(oak));}
    private long linkedTick=-1;
    public FabricPrerequisiteRuntime(MinecraftServer server,JobLifecycleStore jobs,ResourceLeaseService leases,
                                      CapabilityResolver.Engine resolver,Clock clock) {this(server,jobs,leases,resolver,clock,null);}
    public FabricPrerequisiteRuntime(MinecraftServer server,JobLifecycleStore jobs,ResourceLeaseService leases,
                                      CapabilityResolver.Engine resolver,Clock clock,java.util.function.Function<CapabilityRequest,ObservationSnapshot> observations) {
        this.observations=observations;this.server=Objects.requireNonNull(server);this.jobs=Objects.requireNonNull(jobs);
        this.resolver=Objects.requireNonNull(resolver);this.clock=Objects.requireNonNull(clock);this.leases=Objects.requireNonNull(leases);
        supplies=new LeasePrerequisiteSupplies(leases,()->server.overworld().getGameTime());
        admission=new PrerequisiteAdmission(jobs,limits,()->server.overworld().getGameTime());
    }
    public Result resolve(List<Need> needs,TrustedContext caller,List<String> recipeKeys) {
        thread();
        if(!jobs.ready())return rejected(ResolutionStatus.BLOCKED,Reason.STORAGE_UNAVAILABLE);
        if(needs.isEmpty() || needs.size()>limits.demands())return rejected(ResolutionStatus.BLOCKED,Reason.REQUEST_INVALID);
        try {
            for(Need need:needs) {
                var job=jobs.query(need.demand(),caller);
                if(!job.origin().equals(need.origin()) || !job.request().equals(need.bound())
                        || !jobs.mayControl(job.id(),need.guard().generation(),caller))
                    return rejected(ResolutionStatus.UNAUTHORIZED,Reason.AUTHORITY_DENIED);
                if(!job.guard().equals(need.guard()))return rejected(ResolutionStatus.BLOCKED,Reason.STALE_OBSERVATION);
                if(job.state()!=Jobs.State.READY && job.state()!=Jobs.State.WAITING)
                    return rejected(ResolutionStatus.BLOCKED,Reason.INTERRUPTED);
            }
        } catch(SecurityException denied) {return rejected(ResolutionStatus.UNAUTHORIZED,Reason.AUTHORITY_DENIED);}
        Proposal proposal=propose(needs,recipeKeys);
        return new Result(proposal,admission.submitExpanded(proposal,caller,FabricPrerequisiteCatalog.capture(server,recipeKeys),supplies,this::method,this::current));
    }
    private Result rejected(ResolutionStatus status,Reason reason) {
        return new Result(null,new Admission(status,reason,new Jobs.Change(Jobs.Code.REJECTED,null,reason,0),List.of(),Qualification.NONE));
    }
    private Proposal propose(List<Need> needs,List<String> recipeKeys) {
        thread();if(needs.isEmpty() || needs.size()>limits.demands())throw new IllegalArgumentException("Bounded demands");
        var remaining=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);long deadline=Long.MAX_VALUE;
        for(Need need:needs) {
            var allowance=jobs.remainingAllowance(need.demand(),need.origin());
            deadline=Math.min(deadline,allowance.deadlineEpochMillis());
            remaining.merge(Budgets.Kind.INSTRUCTIONS,allowance.maximum(Budgets.Kind.INSTRUCTIONS),Math::min);
        }
        var ledger=new Budgets.Ledger(new Budgets.Limits(remaining,deadline),clock);
        return new DeterministicPrerequisiteResolver(limits,()->server.overworld().getGameTime()).resolve(needs,
                FabricPrerequisiteCatalog.capture(server,recipeKeys),supplies,this::method,this::current,ledger);
    }
    public Jobs.Change allocate(AllocationIntent intent,long quantity,UUID attempt,UUID receipt,Jobs.Guard demand,TrustedContext caller) {
        thread();return admission.allocate(intent,quantity,attempt,receipt,demand,caller);
    }
    private boolean current(Need need) {
        return jobs.mayControl(need.demand(),need.guard().generation(),need.origin());
    }
    private MethodSelection method(Production production,long batches,long output,Need need) {
        if(ResourceComposition.make(need)) {
            if(observations==null || !ResourceComposition.registered(production))throw new IllegalArgumentException("Registered resource preparation");
            if(LogAcquisition.is(production.capability())&&need.acquisitionSource()==null)throw new IllegalArgumentException("Missing acquisition source");
            var request=ResourceComposition.project(need,production.capability(),output);var seen=observations.apply(request);
            var bound=new ValidatedRequest(request,need.origin(),seen.reference());
            var selected=resolver.resolveForPrerequisite(bound,seen);
            if(LogAcquisition.is(production.capability())&&seen.counts().getOrDefault(LogAcquisition.count(production.capability()),0L)<output)
                selected=new CapabilityResolver.Decision(new Resolution(ResolutionStatus.BLOCKED,Reason.RESOURCE_MISSING,null,"insufficient oak source"),null,selected.constraints(),seen,selected.catalogRevision(),selected.candidates(),selected.queriedPages(),selected.candidateWork(),selected.complete(),selected.fallbackUsed(),null);
            return new MethodSelection(selected,seen.scannedCells());
        }
        if(!production.equals(Production.cropDelivery()) || output>64)throw new IllegalArgumentException("Existing P0 production only");
        var supply=supplies.inspect(need,"minecraft:mature_wheat");
        var arguments=new HashMap<>(need.bound().request().arguments());arguments.put("amount",new IntValue(Math.toIntExact(output)));
        var request=new ValidatedRequest(new CapabilityRequest(production.capability(),arguments),need.origin(),supply.observation());
        var area=((AreaValue)arguments.get("source")).value();
        String identity="source:"+UUID.nameUUIDFromBytes(area.toString().getBytes(StandardCharsets.UTF_8));
        var observation=new ObservationSnapshot(supply.observation(),supply.status(),identity,supply.tick(),
                supply.inspected(),Map.of("mature_wheat",supply.available(),"unknown_cells",supply.status()==ObservationStatus.UNKNOWN?1L:0L));
        return new MethodSelection(resolver.resolve(request,observation),supply.inspected());
    }
    public void stage(UUID root,Cuboid oakSource) {thread();if(staged.size()+facilityStages.size()>=32)throw new IllegalStateException("Bounded staged admission");staged.put(root,oakSource);}
    /** Only acknowledgement/linkage, one Job publication per tick. Scheduling remains WorkerDispatcher. */
    public void tick() {
        thread();long tick=server.overworld().getGameTime();if(linkedTick==tick || !jobs.ready())return;linkedTick=tick;
        if(!staged.isEmpty()) {
            var entry=staged.entrySet().iterator().next();
            if(stagedFailures.containsKey(entry.getKey())) {
                var root=jobs.query(entry.getKey(),jobs.snapshot().jobs().stream().filter(j->j.id().equals(entry.getKey())).findFirst().orElseThrow().origin());
                var change=jobs.transition(root.id(),root.guard(),Jobs.State.FAILED,stagedFailures.get(root.id()),root.origin());
                if(change.accepted()){stagedFailures.remove(root.id());staged.remove(root.id());}return;
            }
            JobLink result=admitStaged(entry.getKey(),entry.getValue());
            if(result.done)staged.remove(entry.getKey());return;
        }
        if(advanceFacility())return;
        if(advanceBlueprint())return;
        for(var demand:jobs.snapshot().jobs()) {
            if(demand.preparation()==null || demand.state()!=Jobs.State.READY || jobs.materialReady(demand))continue;
            for(var intent:demand.preparation().materials()) {
                long assigned=jobs.snapshot().allocations().stream().filter(a->a.material() && a.demand().equals(demand.id()) && a.producer().equals(intent.producer()) && a.item().equals(intent.item()))
                        .mapToLong(Jobs.Allocation::quantity).sum();
                long missing=intent.quantity()-assigned;if(missing<=0)continue;
                var producer=jobs.query(intent.producer(),demand.origin());
                if(producer.state()!=Jobs.State.SUCCEEDED)continue;
                for(var attempt:producer.attempts())if(!attempt.open())for(var receipt:attempt.resourceReceipts()) {
                    if(receipt.stage()!=ResourceDelivery.Stage.DEPOSIT || !receipt.item().id().equals(intent.item()))continue;
                    long spent=jobs.snapshot().allocations().stream().filter(a->a.receipt().equals(receipt.receiptId())).mapToLong(Jobs.Allocation::quantity).sum();
                    long quantity=Math.min(missing,receipt.quantity()-spent);if(quantity<=0)continue;
                    var need=new Need(demand.id(),demand.guard(),demand.request(),intent.item(),quantity,AllocationPolicy.MATERIAL_ONLY);
                    var stock=supplies.inspect(need,intent.item());
                    UUID id=UUID.nameUUIDFromBytes(("material:1:"+demand.id()+":"+producer.id()+":"+receipt.receiptId()).getBytes(StandardCharsets.UTF_8));
                    var change=jobs.allocateMaterial(new Jobs.Allocation(id,producer.id(),attempt.id(),receipt.receiptId(),demand.id(),quantity,intent.item()),demand.guard(),stock,tick,demand.origin());
                    if(change.accepted())return;
                }
            }
        }
    }
    private record JobLink(boolean done) { }
    /** One bounded preparation/linkage pass; the existing dispatcher executes every producer. */
    private boolean advanceBlueprint() {
        if(blueprintObservations==null)return false;
        blueprintChecks.keySet().removeIf(id->jobs.snapshot().jobs().stream().noneMatch(j->j.id().equals(id)&&!j.state().terminal()&&j.state()!=Jobs.State.INTERRUPTED));
        for(var root:jobs.snapshot().jobs()) {
            if(!BlueprintMaterials.valid(root.request().request()) || root.state().terminal() || root.state()==Jobs.State.INTERRUPTED || root.state()==Jobs.State.CANCELLING)continue;
            long tick=server.overworld().getGameTime();
            if(!jobs.mayControl(root.id(),root.generation(),root.origin()))return blueprintFailure(root,Reason.AUTHORITY_DENIED);
            if(clock.millis()>root.allowance().deadlineEpochMillis())return blueprintFailure(root,Reason.BUDGET_EXHAUSTED);
            boolean childComplete=!root.children().isEmpty() && root.children().stream().allMatch(id->jobs.query(id,root.origin()).state()==Jobs.State.SUCCEEDED);
            var checked=blueprintChecks.get(root.id());
            if(checked==null || tick-checked.tick()>=10 || childComplete&&!checked.childComplete()) {
                var report=blueprintObservations.apply(root);
                if(!report.canStage() || report.required()!=BlueprintMaterials.amount(root.request().request()))return blueprintFailure(root,
                        report.reason()==null?Reason.STALE_OBSERVATION:report.reason());
                var charge=jobs.charge(root.id(),root.guard(),Map.of(Budgets.Kind.INSTRUCTIONS,1L,
                        Budgets.Kind.OBSERVATIONS,(long)report.inspected()+128,Budgets.Kind.ELAPSED_TICKS,checked==null?1L:tick-checked.tick()),root.origin());
                if(charge.accepted())blueprintChecks.put(root.id(),new BlueprintCheck(report,tick,childComplete));
                else if(charge.reason()!=Reason.STORAGE_UNAVAILABLE)return blueprintFailure(root,charge.reason()==null?Reason.BUDGET_EXHAUSTED:charge.reason());
                return true;
            }
            if(!leases.ready())return false;
            long held=BlueprintMaterials.held(root,leases,true),required=BlueprintMaterials.amount(root.request().request());
            if(held<0)return blueprintFailure(root,Reason.RESOURCE_MISSING);
            // Renew only existing finite claims; never reset the original Job deadline or allowance.
            for(var lease:leases.snapshot().leases())if(lease.owner().equals(new ResourceLeases.Owner(root.id(),root.generation()))
                    && lease.state()==ResourceLeases.State.ACTIVE && lease.expires()-tick<200) {
                var refs=leases.snapshot().leases().stream().filter(l->l.group().equals(lease.group())&&l.state()==ResourceLeases.State.ACTIVE).map(ResourceLeases.Lease::ref).toList();
                var result=leases.renew(refs,1200,root.origin());
                if(result.code()!=ResourceLeases.Code.PENDING && !result.usable())return blueprintFailure(root,result.reason()==null?Reason.BUDGET_EXHAUSTED:result.reason());
                return true;
            }
            if(held==required && (root.children().isEmpty() || childComplete)) {
                var change=jobs.completeBlueprint(root.id(),root.guard(),checked.report(),leases,root.origin());
                if(!change.accepted() && change.reason()!=Reason.STORAGE_UNAVAILABLE)return blueprintFailure(root,change.reason()==null?Reason.STALE_OBSERVATION:change.reason());
                return true;
            }
            if(!root.children().isEmpty() && !childComplete)return false;
            long claim=Math.min(required-held,Math.max(0,checked.report().available()-held));
            if(claim>0) {
                var result=leases.acquire(new ResourceLeases.Owner(root.id(),root.generation()),List.of(new ResourceLeases.Demand(BlueprintMaterials.resource(root),claim)),1200,root.origin());
                if(result.code()!=ResourceLeases.Code.PENDING && !result.usable())return blueprintFailure(root,result.reason()==null?Reason.RESOURCE_MISSING:result.reason());
                return true;
            }
            if(childComplete)return blueprintFailure(root,Reason.RESOURCE_MISSING);
            var change=jobs.admitBlueprintChild(root.id(),root.guard(),required-held,root.request().observation(),root.origin());
            if(change.accepted())stage(change.job().id(),((AreaValue)root.request().request().arguments().get("acquisition")).value());
            else if(change.reason()!=Reason.STORAGE_UNAVAILABLE)return blueprintFailure(root,change.reason()==null?Reason.STALE_OBSERVATION:change.reason());
            return true;
        }
        return false;
    }
    private boolean blueprintFailure(Jobs.Job root,Reason reason) {
        if(jobs.ready())jobs.failBlueprint(root.id(),root.guard(),reason,root.origin());return true;
    }
    /** Advances owner acknowledgements only. Every effect runs through the ordinary dispatcher. */
    private boolean advanceFacility(){
        if(facilityStages.isEmpty())return false;
        var entry=facilityStages.entrySet().iterator().next();var stage=entry.getValue();
        var root=jobs.snapshot().jobs().stream().filter(j->j.id().equals(entry.getKey())).findFirst().orElse(null);
        if(root==null)return false;
        if(root.state().terminal() || root.state()==Jobs.State.INTERRUPTED){facilityStages.remove(root.id());return false;}
        try{
            if(!jobs.mayControl(root.id(),root.generation(),root.origin()))return facilityFailure(root,Reason.AUTHORITY_DENIED);
            if(clock.millis()>root.allowance().deadlineEpochMillis())return facilityFailure(root,Reason.BUDGET_EXHAUSTED);
            var children=root.children().stream().map(id->jobs.query(id,root.origin())).toList();
            for(var child:children){
                if(child.state()==Jobs.State.FAILED || child.state()==Jobs.State.CANCELLED || child.state()==Jobs.State.INTERRUPTED)
                    return facilityFailure(root,child.reason()==null?Reason.INTERRUPTED:child.reason());
                if(child.state()!=Jobs.State.SUCCEEDED)return false;
            }
            var current=facilities.apply(root.request().request());
            if(current.reason()!=null)return facilityFailure(root,current.reason());
            if(current.existing()){
                facilityStages.remove(root.id());stage(root.id(),stage.oak);return false;
            }
            // A completed placement without a currently usable table cannot satisfy ENSURE or be repeated.
            if(children.stream().anyMatch(j->CraftingTablePlacement.is(j.request().request().capability())))return facilityFailure(root,Reason.FACILITY_MISSING);
            var axe=resolver.resolveForFacilityPrerequisite(new ValidatedRequest(root.request().request(),root.origin(),current.axe().reference()),current.axe());
            var request=FacilityPrerequisites.placement(root.request().request());
            var place=resolver.resolveForPrerequisite(new ValidatedRequest(request,root.origin(),current.placement().reference()),current.placement());
            if(axe.routing().status()!=ResolutionStatus.RESOLVED)return facilityFailure(root,axe.routing().reason()==null?Reason.ARTIFACT_INVALID:axe.routing().reason());
            if(place.routing().status()!=ResolutionStatus.RESOLVED)return facilityFailure(root,place.routing().reason()==null?Reason.ARTIFACT_INVALID:place.routing().reason());
            if(!stage.charged){
                var charged=jobs.charge(root.id(),root.guard(),Map.of(Budgets.Kind.INSTRUCTIONS,(long)(axe.candidateWork()+place.candidateWork()+32),Budgets.Kind.OBSERVATIONS,446L),root.origin());
                if(charged.accepted()){stage.charged=true;return true;}
                return facilityFailure(root,charged.reason()==null?Reason.BUDGET_EXHAUSTED:charged.reason());
            }
            boolean supplied=current.placement().counts().getOrDefault("table_items",0L)>=1;
            if(!children.isEmpty()){
                // Completed MAKE is provenance, never stock: require both its real deposit and fresh physical stock.
                var made=children.getFirst();boolean deposited=made.request().request().equals(FacilityPrerequisites.item(root.request().request()))
                        && made.attempts().stream().filter(a->!a.open()).flatMap(a->a.resourceReceipts().stream()).anyMatch(r->r.stage()==ResourceDelivery.Stage.DEPOSIT && r.item().equals(ResourceDelivery.CRAFTING_TABLE)
                        && r.destination().equals(((ContainerValue)request.arguments().get("source")).value()) && r.quantity()>=1);
                if(!deposited || !supplied)return facilityFailure(root,Reason.RESOURCE_MISSING);
            }
            var observation=supplied?current.placement():observations.apply(FacilityPrerequisites.item(root.request().request()));
            var change=jobs.admitFacilityChild(root.id(),root.guard(),!supplied,observation.reference(),supplied?place.routing().artifact():null,root.origin());
            if(change.accepted()){
                if(!supplied)stage(change.job().id(),stage.oak);
                return true;
            }
            return facilityFailure(root,change.reason()==null?Reason.STALE_OBSERVATION:change.reason());
        }catch(SecurityException denied){return facilityFailure(root,Reason.AUTHORITY_DENIED);}
        catch(RuntimeException stale){return facilityFailure(root,Reason.STALE_OBSERVATION);}
    }
    private boolean facilityFailure(Jobs.Job root,Reason reason){
        if(!jobs.ready())return true;
        var change=jobs.transition(root.id(),root.guard(),Jobs.State.FAILED,reason,root.origin());
        if(change.accepted())facilityStages.remove(root.id());return true;
    }
    private JobLink admitStaged(UUID id,Cuboid source) {
        var root=jobs.snapshot().jobs().stream().filter(j->j.id().equals(id)).findFirst().orElse(null);
        if(root==null)return new JobLink(false);
        if(root.state().terminal()||root.state()==Jobs.State.INTERRUPTED)return new JobLink(true);
        var need=new Need(root.id(),root.guard(),root.request(),RecipeProduction.of(root.request().request().capability()).output().id(),((IntValue)root.request().request().arguments().get("amount")).value(),AllocationPolicy.MATERIAL_ONLY,source);
        var result=resolve(List.of(need),root.origin(),ResourceComposition.RECIPE_KEYS);
        if(result.admission().status()==ResolutionStatus.RESOLVED)return new JobLink(true);
        // Rejected expansion bills first; wait for its asynchronous write before publishing the blocker.
        if(jobs.ready())jobs.transition(root.id(),root.guard(),Jobs.State.FAILED,result.admission().reason()==null?Reason.RESOURCE_MISSING:result.admission().reason(),root.origin());
        else stagedFailures.put(id,result.admission().reason()==null?Reason.RESOURCE_MISSING:result.admission().reason());
        return new JobLink(false);
    }
    private final Map<UUID,Reason> stagedFailures=new HashMap<>();
    private void thread(){if(!server.isSameThread())throw new IllegalStateException("Prerequisites game thread");}
}
