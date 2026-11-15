package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.RecipeChoice;
import dev.aivillages.core.kernel.runtime.activity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import java.time.Clock;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.Kind.STOCK;
import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;

/** Read-only native projection and bounded coordination of existing Jobs-owned activities. */
final class FabricProductionActivities {
    private final ServerLevel level;
    private final FabricGatewayWorld world;
    private final JobLifecycleStore jobs;
    private final ResourceLeaseService leases;
    private final VersionedSkillRepository repository;
    private final KnowledgeAccessStore access;
    private final AuthorityPolicy authority;
    private final Clock clock;
    private final ProductionActivityPlanner planner=new ProductionActivityPlanner();
    private int cursor;

    FabricProductionActivities(ServerLevel level,FabricGatewayWorld world,JobLifecycleStore jobs,
                               ResourceLeaseService leases,VersionedSkillRepository repository,
                               KnowledgeAccessStore access,AuthorityPolicy authority,Clock clock) {
        this.level=level;this.world=world;this.jobs=jobs;this.leases=leases;this.repository=repository;
        this.access=access;this.authority=authority;this.clock=clock;
    }

    void step(java.util.function.Predicate<Jobs.Job> previousCheckpointSettled) {
        if(!jobs.ready() || !leases.ready() || level.getGameTime()%4!=0)return;
        var rows=jobs.snapshot().jobs();
        for(int visited=0;visited<Math.min(4,rows.size());visited++) {
            cursor%=rows.size();
            Jobs.Job job=rows.get(cursor++);
            var activity=job.activity();
            if(activity==null || activity.production()==null || activity.current()!=null || job.state().terminal()
                    || job.current()!=null && job.current().open() || job.cancellationOutcome()!=null)continue;
            var control=activity.production();
            if(control.decisions()==ActivityPayload.ProductionControl.MAX_DECISIONS || clock.millis()<control.nextCheckMillis()
                    || clock.millis()>=job.allowance().deadlineEpochMillis() || !previousCheckpointSettled.test(job)
                    || !authority.currentlyAllows(job.responsible(),Effect.OBSERVE,job.origin()))continue;
            try {
                var frame=observe(job);
                var decision=planner.propose(job,frame,control.memory(),jobs.remainingAllowance(job.id(),job.origin()),
                        offer->world.validateActivityChoice(offer.request(),offer.choice(),
                                choiceAuthority(job,offer.request()),offer.sources())==null);
                var model=decision.model();
                var next=control.next(model.memory(),model.blocker(),model.nextCheckMillis());
                if(decision.proposal()==null)jobs.recordActivityPlanning(job.id(),job.guard(),job.origin(),next);
                else {
                    var prepared=jobs.prepareActivity(decision.proposal(),job.origin(),next);
                    if(!prepared.accepted() && jobs.ready()) {
                        var refused=control.next(control.memory(),prepared.reason()==null?"ACTIVITY_PREPARATION_REFUSED":prepared.reason().name(),
                                Math.min(job.allowance().deadlineEpochMillis(),clock.millis()+1000));
                        jobs.recordActivityPlanning(job.id(),job.guard(),job.origin(),refused);
                    }
                }
            } catch(IllegalArgumentException | IllegalStateException unavailable) {
                var next=control.next(control.memory(),"CURRENT_NATIVE_PLAN_UNAVAILABLE",
                        Math.min(job.allowance().deadlineEpochMillis(),clock.millis()+1000));
                jobs.recordActivityPlanning(job.id(),job.guard(),job.origin(),next);
            }
            return; // At most one decision publication through the current Jobs writer.
        }
    }

    RecipeTransaction.ChoiceAuthority choiceAuthority(Jobs.Job root,ValidatedRequest request) {
        var actor=root.responsible();
        var originalSource=((ContainerValue)root.request().request().arguments().get("source")).value();
        var destination=((ContainerValue)request.request().arguments().get("destination")).value();
        return new RecipeTransaction.ChoiceAuthority() {
            public boolean permits(TrustedContext caller,ActorRef worker,ContainerRef target,RecipeTransaction.ChoicePhase phase) {
                if(!root.origin().equals(caller) || !actor.equals(worker))return false;
                return (target.equals(originalSource) || target.equals(destination))
                        && (!target.equals(originalSource) || allowed(request,Effect.WITHDRAW,target))
                        && (!target.equals(destination) || allowed(request,Effect.TRANSFER,target));
            }
            public boolean permitsFacility(TrustedContext caller,ActorRef worker,FacilityRef table,RecipeTransaction.ChoicePhase phase) {
                return root.origin().equals(caller) && actor.equals(worker)
                        && new FacilityValue(table).equals(root.request().request().arguments().get("table"))
                        && allowed(request,Effect.CRAFT,new ContainerRef(table.dimension(),table.x(),table.y(),table.z()));
            }
        };
    }

    ActivityRuntime.Validation validate(Jobs.Job root,ValidatedRequest request,ActivityPayload.Choice choice,
                                        ActivityPayload.Quantities quantities) {
        if(root.activity()==null || root.activity().production()==null
                || !authority.currentlyAllows(root.responsible(),Effect.OBSERVE,root.origin()))return ActivityRuntime.Validation.INVALID;
        if(choice==null) {
            var op=request.request().capability();
            if(LogAcquisition.is(op)) {
                var available=acquisition(root);
                if(!available.isEmpty() && available.getFirst().method()!=null
                        && available.getFirst().method().capability().equals(op)
                        && available.getFirst().availableUnits()>=quantities.demand())return ActivityRuntime.Validation.VALID;
            }
            return ActivityRuntime.Validation.INVALID;
        }
        var v2=ActivityRecipeEvidence.exactInvocation(root,request,choice,quantities);
        var recipe=choice.recipe();
        return world.validateActivityChoice(v2,recipe,choiceAuthority(root,v2),ActivityRecipeEvidence.sources(v2,recipe))==null?
                ActivityRuntime.Validation.VALID:ActivityRuntime.Validation.INVALID;
    }

    ObservationSnapshot routingObservation(Jobs.Job job,ValidatedRequest invocation,ObservationSnapshot original) {
        var step=job.activity().current();
        if(validate(job,invocation,step.choice(),step.quantities())!=ActivityRuntime.Validation.VALID)return original;
        var counts=new HashMap<>(original.counts());
        counts.put("unknown_cells",0L);counts.put("recipe_supported",1L);
        var output=RecipeProduction.of(invocation.request().capability()).output();
        counts.put("output_capacity",(long)world.inspectRecipeContainer(ResourceDelivery.destinationAddress(invocation.request()),output).capacity());
        return new ObservationSnapshot(original.reference(),ObservationStatus.PRESENT,original.targetIdentity(),
                original.observedTick(),original.scannedCells(),counts);
    }

    private Snapshot observe(Jobs.Job job) {
        var bound=job.request();
        var request=bound.request();
        var actor=job.responsible();
        var nativeActor=world.recipeActor(actor);
        if(nativeActor==null)throw new IllegalStateException("Current original actor unavailable");
        var capability=request.capability();
        var production=RecipeProduction.of(capability);
        var source=((ContainerValue)request.arguments().get("source")).value();
        var destination=ResourceDelivery.destinationAddress(request);
        var facility=production.axe()?facility(job,source,nativeActor):null;
        var allowedArea=job.activity().production().acquisition();
        var policy=new Policy(actor,Set.of(actor),Set.of(source),Set.of(destination),
                allowedArea==null?Set.of():Set.of(allowedArea),source,facility==null?null:facility.reference(),
                facility!=null,false,0,16);
        var goal=new Goal(Mode.MAKE_NEW,defaultKey(production.output()),((IntValue)request.arguments().get("amount")).value(),
                destination,policy);
        var profiles=new ArrayList<RecipeProfile>();
        var capabilities=production.axe()?List.of(OakPlankProduction.ID,BirchPlankProduction.ID,StickProduction.ID,
                CraftingTableProduction.ID,WoodenAxeProduction.ID):List.of(capability);
        for(var id:capabilities) {
            var target=id.equals(capability)?destination:source;
            long travel=travel(nativeActor,source,target,id.equals(WoodenAxeProduction.ID)?facility:null);
            var profile=world.describeChoice(new CapabilityId(id.name(),2),new Cost(travel,4,100+6*travel));
            var method=pin(job,id,profile.semanticFingerprint(),2);
            profiles.add(new RecipeProfile(profile.recipe(),profile.capability(),profile.cells(),profile.result(),
                    profile.semanticFingerprint(),method,profile.allowance(),profile.reloadEpoch()));
        }
        var box=world.inspectRecipeContainer(destination,production.output());
        boolean writable=allowed(bound,Effect.TRANSFER,destination) && box.status()==ObservationStatus.PRESENT
                && box.capacity()>=goal.demand()-job.fulfilled();
        var stock=stock(job,source,nativeActor);
        var acquisition=acquisition(job);
        long revision=job.generation(); // Native values below carry their own current observation data.
        return new Snapshot(SCHEMA,job.origin().scope().worldId(),job.id(),job.revision(),job.origin(),
                new Owner(job.origin().principal().id(),actor,job.generation(),writable,false,false,false),
                goal,destination,job.fulfilled(),ProductionActivityPlanner.budget(job),clock.millis(),revision,
                stock,acquisition,profiles,facility,List.of(),Set.of(Kind.PRODUCE,Kind.ACQUIRE),
                progress(job),true,false,0);
    }

    private List<Stock> stock(Jobs.Job job,ContainerRef source,Villager actor) {
        var box=world.recipeContainer(source);
        if(box==null || !allowed(job.request(),Effect.WITHDRAW,source))
            throw new IllegalStateException("Current authorized stock unavailable");
        var totals=new HashMap<String,Long>();
        for(int slot=0;slot<box.getContainerSize();slot++) {
            var stack=box.getItem(slot);
            if(!stack.isEmpty())totals.merge(FabricConcreteRecipeChoices.itemKey(level,stack).id(),(long)stack.getCount(),Math::addExact);
        }
        var free=new HashMap<String,Long>();
        for(var entry:totals.entrySet()) {
            var key=new ResourceLeases.Resource(job.origin().scope().worldId(),STOCK,ResourceLeases.point(source),entry.getKey(),-1);
            var stock=leases.availableStock(key,entry.getValue(),job.origin());
            if(!stock.usable())throw new IllegalStateException("Current free stock unavailable");
            free.put(entry.getKey(),stock.available());
        }
        var projected=new ArrayList<Stock>();
        long travel=travel(actor,source,source,null);
        for(int slot=0;slot<box.getContainerSize();slot++) {
            var stack=box.getItem(slot);
            if(stack.isEmpty())continue;
            var item=FabricConcreteRecipeChoices.itemKey(level,stack);
            long available=Math.min(stack.getCount(),free.get(item.id()));
            free.compute(item.id(),(id,count)->count-available);
            projected.add(new Stock("source:"+slot,new Location(source,null,slot),item,available,stack.getCount(),0,
                    null,List.of(),Eligibility.NONE,Availability.PRESENT,true,true,travel,job.generation()));
        }
        return List.copyOf(projected);
    }

    private Facility facility(Jobs.Job job,ContainerRef staging,Villager actor) {
        var reference=((FacilityValue)job.request().request().arguments().get("table")).value();
        var cell=new ContainerRef(reference.dimension(),reference.x(),reference.y(),reference.z());
        var p=pos(cell);
        boolean present=world.loadedForDiscovery(p) && level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
        var placement=new CapabilityRequest(CraftingTablePlacement.ID,Map.of("actor",new ActorValue(job.responsible()),"amount",new IntValue(1),
                "source",new ContainerValue(staging),"target",new FacilityValue(reference)));
        var marked=new ValidatedRequest(placement,job.origin(),job.request().observation());
        var preflight=present?null:world.placementPreflight(marked);
        boolean free=preflight!=null && preflight.reason()==null && !preflight.pending() && allowed(marked,Effect.PLACE,cell);
        var id=present?FabricFacilityIdentity.capture(level,p):null;
        long travel=travel(actor,staging,cell,null);
        return new Facility(reference,id!=null?Availability.PRESENT:free?Availability.ABSENT:Availability.UNKNOWN,
                allowed(job.request(),Effect.CRAFT,cell),true,id==null?"":id,free,job.generation(),
                pin(job,CraftingTablePlacement.ID,ActivityPayload.digest("crafting-table-placement:v1"),1),
                new Cost(travel,3,100+6*travel));
    }

    private List<Acquisition> acquisition(Jobs.Job job) {
        var area=job.activity().production().acquisition();
        var actor=world.recipeActor(job.responsible());
        if(area==null || actor==null)return List.of();
        long oak=0,birch=0;
        boolean complete=true;
        for(int x=area.minX();x<=area.maxX();x++)for(int y=area.minY();y<=area.maxY();y++)for(int z=area.minZ();z<=area.maxZ();z++) {
            var position=new ContainerRef(area.dimension(),x,y,z);
            if(!world.loadedForDiscovery(pos(position)) || !allowed(job.request(),Effect.HARVEST,position)) {complete=false;continue;}
            var target=new SurvivalGateway.Cell(area.dimension(),x,y,z);
            if(world.resourceBlock(target,ResourceDelivery.OAK_LOG).eligible())oak++;
            if(world.resourceBlock(target,ResourceDelivery.BIRCH_LOG).eligible())birch++;
        }
        // Overlapping acquisition options are forbidden by the original B contract.
        if(oak>0 && birch>0 || !complete || oak==0 && birch==0)return List.of();
        var capability=oak>0?OakLogDelivery.ID:BirchLogDelivery.ID;
        var item=oak>0?ResourceDelivery.OAK_LOG:ResourceDelivery.BIRCH_LOG;
        var staging=((ContainerValue)job.request().request().arguments().get("source")).value();
        var corner=new ContainerRef(area.dimension(),area.minX(),area.minY(),area.minZ());
        long travel=travel(actor,corner,staging,null)+Math.abs((long)area.maxX()-area.minX())
                +Math.abs((long)area.maxY()-area.minY())+Math.abs((long)area.maxZ()-area.minZ());
        return List.of(new Acquisition("original-authorized-logs",area,defaultKey(item),Math.min(64,oak+birch),
                Availability.PRESENT,true,true,pin(job,capability,ActivityPayload.digest(capability.toString()),1),
                new Cost(travel,4,120+6*travel)));
    }

    private RecipeChoice.ItemKey defaultKey(ResourceDelivery.ItemIdentity item) {
        return FabricConcreteRecipeChoices.itemKey(level,new net.minecraft.world.item.ItemStack(FabricResourceItems.item(item)));
    }

    private MethodPin pin(Jobs.Job job,CapabilityId id,String semantics,int version) {
        var page=repository.page(id,null,16);
        if(!page.complete())return null;
        var capability=new CapabilityId(id.name(),version);
        return page.candidates().stream().filter(c->c.admission()==AdmissionStatus.ADMITTED
                && c.compatibility().status()==CompatibilityStatus.COMPATIBLE
                && access.mayUse(job.responsible(),c.ref(),job.origin()))
                .map(c->new MethodPin(capability,c.ref().sha256(),semantics,true)).findFirst().orElse(null);
    }

    private List<SettledProgress> progress(Jobs.Job job) {
        var progress=new ArrayList<SettledProgress>();
        long confirmed=0;
        for(var checkpoint:job.activity().checkpoints()) {
            var step=job.activity().forAttempt(checkpoint.attempt());
            var attempt=job.attempts().stream().filter(a->a.id().equals(checkpoint.attempt())).findFirst().orElseThrow();
            confirmed=Math.addExact(confirmed,step.creditedToGoal(attempt));
            var refs=checkpoint.receipts().stream().limit(16).map(UUID::toString).toList();
            var effect=attempt.resourceReceipts().isEmpty()?refs.getLast():attempt.resourceReceipts().getLast().operationId().toString();
            progress.add(new SettledProgress(job.id(),step.token(),step.id().toString(),attempt.id(),effect,refs,
                    List.of("jobs:"+step.preparedRevision()+":"+checkpoint.attempt()),SettlementKind.ACCEPTED_EFFECT,
                    job.revision(),attempt.generation(),confirmed));
        }
        return List.copyOf(progress);
    }

    private boolean allowed(ValidatedRequest request,Effect effect,ContainerRef address) {
        var actor=((ActorValue)request.request().arguments().get("actor")).value();
        return authority.currentlyAllows(actor,effect,request.context())
                && world.permit(request,effect,new SurvivalGateway.Cell(address.dimension(),address.x(),address.y(),address.z()))==null;
    }
    private static BlockPos pos(ContainerRef address){return new BlockPos(address.x(),address.y(),address.z());}
    private static long travel(Villager actor,ContainerRef source,ContainerRef destination,Facility facility) {
        var start=actor.blockPosition();
        var from=pos(source);
        var end=pos(destination);
        long path=start.distManhattan(from)+from.distManhattan(end);
        if(facility!=null) {
            var f=facility.reference();
            var at=new BlockPos(f.x(),f.y(),f.z());
            path=Math.max(path,(long)start.distManhattan(from)+from.distManhattan(at)+at.distManhattan(end));
        }
        return 2*path+4;
    }
}
