package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.RecipeChoice;
import dev.aivillages.core.kernel.runtime.activity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import java.time.Clock;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.Kind.STOCK;
import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;

/** Read-only native projection and bounded coordination of existing Jobs-owned activities. */
final class FabricProductionActivities {
    private static final System.Logger LOGGER=System.getLogger(FabricProductionActivities.class.getName());
    private final ServerLevel level;
    private final FabricGatewayWorld world;
    private final JobLifecycleStore jobs;
    private final ResourceLeaseService leases;
    private final VersionedSkillRepository repository;
    private final KnowledgeAccessStore access;
    private final AuthorityPolicy authority;
    private final Clock clock;
    private final ProductionActivityPlanner planner=new ProductionActivityPlanner();
    private final Map<UUID,String> waitingObservations=new HashMap<>();
    private int cursor;

    FabricProductionActivities(ServerLevel level,FabricGatewayWorld world,JobLifecycleStore jobs,
                               ResourceLeaseService leases,VersionedSkillRepository repository,
                               KnowledgeAccessStore access,AuthorityPolicy authority,Clock clock) {
        this.level=level;this.world=world;this.jobs=jobs;this.leases=leases;this.repository=repository;
        this.access=access;this.authority=authority;this.clock=clock;
    }

    /** Reject known unsupported component inputs before admitting a new simple plank goal. */
    Outcomes.Reason intakeFailure(CapabilityRequest request,boolean gather,ContainerRef additionalSource) {
        var definition=RecipeProduction.of(request.capability());
        var family=RecipeProduction.PlankMaterial.forOutput(definition.output().id());
        if(gather || family.isEmpty())return null;
        var source=((ContainerValue)request.arguments().get("source")).value();
        long plain=0,components=0;
        for(var address:additionalSource==null || additionalSource.equals(source)?List.of(source):List.of(source,additionalSource)) {
            var container=world.recipeContainer(address);
            if(container==null)continue;
            for(int slot=0;slot<container.getContainerSize();slot++) {
                var stack=container.getItem(slot);
                if(stack.isEmpty() || !family.get().inputs().contains(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()))continue;
                if(FabricRecipeTransactions.plain(stack,stack.getItem()))plain+=stack.getCount();
                else components+=stack.getCount();
            }
        }
        return components>0 && plain<definition.inputs(((IntValue)request.arguments().get("amount")).value())
                ?Outcomes.Reason.TARGET_INVALID:null;
    }

    /** The explicit legacy area has already passed command authority and size checks. */
    boolean hasExplicitLogs(CapabilityId capability,Cuboid area) {
        var item=LogAcquisition.item(ResourceComposition.explicitLogMethod(capability));
        for(long x=area.minX();x<=area.maxX();x++)for(long y=area.minY();y<=area.maxY();y++)for(long z=area.minZ();z<=area.maxZ();z++) {
            var cell=new SurvivalGateway.Cell(area.dimension(),(int)x,(int)y,(int)z);
            if(world.resourceBlock(cell,item).eligible())return true;
        }
        return false;
    }

    void step(java.util.function.Predicate<Jobs.Job> previousCheckpointSettled) {
        if(!jobs.ready() || !leases.ready() || level.getServer().tickRateManager().isFrozen()
                || level.getGameTime()%4!=0)return;
        var rows=jobs.snapshot().jobs();
        waitingObservations.keySet().removeIf(id->rows.stream().noneMatch(j->j.id().equals(id)&&!j.state().terminal()));
        for(int visited=0;visited<Math.min(4,rows.size());visited++) {
            cursor%=rows.size();
            Jobs.Job job=rows.get(cursor++);
            var activity=job.activity();
            if(activity==null || activity.production()==null || activity.current()!=null || job.state().terminal()
                    || job.current()!=null && job.current().open() || job.cancellationOutcome()!=null)continue;
            var control=activity.production();
            if(control.observedDestination() && activity.steps().isEmpty() && job.attempts().isEmpty()
                    && clock.millis()<job.allowance().deadlineEpochMillis()
                    && originalDestinationRemoved(job)) {
                jobs.transition(job.id(),job.guard(),Jobs.State.FAILED,Outcomes.Reason.TARGET_UNAVAILABLE,job.origin());
                return;
            }
            if(control.decisions()==ActivityPayload.ProductionControl.MAX_DECISIONS
                    || clock.millis()<control.nextCheckMillis() && control.nextCheckMillis()!=Long.MAX_VALUE
                    || clock.millis()>=job.allowance().deadlineEpochMillis() || !previousCheckpointSettled.test(job)
                    || !authority.currentlyAllows(job.responsible(),Effect.OBSERVE,job.origin()))continue;
            try {
                var frame=observe(job);
                var observed=dev.aivillages.core.kernel.production.planning.PlanningCoordinator.observationStamp(frame);
                if(control.nextCheckMillis()==Long.MAX_VALUE && observed.equals(waitingObservations.get(job.id())))continue;
                var decision=planner.propose(job,frame,control.memory(),jobs.remainingAllowance(job.id(),job.origin()),
                        offer->world.validateActivityChoice(offer.request(),offer.choice(),
                                choiceAuthority(job,offer.request()),offer.sources())==null);
                var model=decision.model();
                if(decision.proposal()==null) {
                    if(Boolean.getBoolean("cognitivecraft.gametest.trace") && (control.decisions()==0
                            || control.decisions()==ActivityPayload.ProductionControl.MAX_DECISIONS-1)) {
                        System.out.println("I00_PLAN_BLOCK root="+job.id()+" blocker="+model.blocker()
                                +" ticksRemaining="+frame.budget().remaining(Counter.ELAPSED_TICKS)+" facility="+frame.facility()
                                +" acquisition="+frame.acquisition().stream().map(a->a.availableUnits()+"/"+a.allowance()
                                        +"/"+(a.method()!=null&&a.method().usable())).toList()
                                +" methods="+frame.recipes().stream().map(r->r.recipe()+"/"+r.allowance()
                                        +"/"+(r.method()!=null&&r.method().usable())).toList());
                    }
                    if(model.nextCheckMillis()==Long.MAX_VALUE && (waitingObservations.containsKey(job.id()) || waitingObservations.size()<32))
                        waitingObservations.put(job.id(),observed);
                    else waitingObservations.remove(job.id());
                    if(!model.memory().equals(control.memory()) || !model.blocker().equals(control.blocker())
                            || model.nextCheckMillis()!=control.nextCheckMillis())
                        jobs.recordActivityPlanning(job.id(),job.guard(),job.origin(),
                                control.next(model.memory(),model.blocker(),model.nextCheckMillis()));
                }
                else {
                    waitingObservations.remove(job.id());
                    var next=control.prepared(model.memory(),decision.plan(),decision.proposal().id(),decision.coveredActions());
                    var prepared=jobs.prepareActivity(decision.proposal(),job.origin(),next);
                    if(!prepared.accepted() && jobs.ready()) {
                        var refused=control.next(control.memory(),prepared.reason()==null?"ACTIVITY_PREPARATION_REFUSED":prepared.reason().name(),
                                Math.min(job.allowance().deadlineEpochMillis(),clock.millis()+1000));
                        jobs.recordActivityPlanning(job.id(),job.guard(),job.origin(),refused);
                    }
                }
            } catch(IllegalArgumentException | IllegalStateException unavailable) {
                if(control.decisions()==0)LOGGER.log(System.Logger.Level.WARNING,
                        "I00 planning projection unavailable for root "+job.id(),unavailable);
                var next=control.next(control.memory(),"CURRENT_NATIVE_PLAN_UNAVAILABLE",
                        Math.min(job.allowance().deadlineEpochMillis(),clock.millis()+1000));
                jobs.recordActivityPlanning(job.id(),job.guard(),job.origin(),next);
            }
            return; // At most one decision publication through the current Jobs writer.
        }
    }

    private boolean originalDestinationRemoved(Jobs.Job job) {
        var request=job.request().request();
        var target=ResourceDelivery.destinationAddress(request);
        var block=pos(target);
        return allowed(job.request(),Effect.TRANSFER,target) && world.loadedForDiscovery(block)
                && level.getBlockState(block).isAir();
    }

    RecipeTransaction.ChoiceAuthority choiceAuthority(Jobs.Job root,ValidatedRequest request) {
        var actor=root.responsible();
        var sources=ActivityRecipeEvidence.authorizedSources(root);
        var destination=((ContainerValue)request.request().arguments().get("destination")).value();
        return new RecipeTransaction.ChoiceAuthority() {
            public boolean permits(TrustedContext caller,ActorRef worker,ContainerRef target,RecipeTransaction.ChoicePhase phase) {
                if(!root.origin().equals(caller) || !actor.equals(worker))return false;
                boolean source=sources.contains(target);
                return (source || target.equals(destination))
                        && (!source || allowed(request,Effect.WITHDRAW,target))
                        && (!target.equals(destination) || allowed(request,Effect.TRANSFER,target));
            }
            public boolean permitsFacility(TrustedContext caller,ActorRef worker,FacilityRef table,RecipeTransaction.ChoicePhase phase) {
                return root.origin().equals(caller) && actor.equals(worker)
                        && new FacilityValue(table).equals(root.request().request().arguments().get("table"))
                        && allowed(request,Effect.CRAFT,new ContainerRef(table.dimension(),table.x(),table.y(),table.z()));
            }
            public boolean permitsHeldSticks(TrustedContext caller,ActorRef worker,RecipeTransaction.ChoicePhase phase) {
                if(!root.activity().production().allowsHeldSticks() || !root.origin().equals(caller) || !actor.equals(worker))return false;
                var nativeActor=world.recipeActor(worker);
                return nativeActor!=null && allowed(request,Effect.OBSERVE,position(nativeActor));
            }
        };
    }

    ActivityRuntime.Validation validate(Jobs.Job root,ValidatedRequest request,ActivityPayload.Choice choice,
                                        ActivityPayload.Quantities quantities) {
        if(root.activity()==null || root.activity().production()==null
                || !authority.currentlyAllows(root.responsible(),Effect.OBSERVE,root.origin()))return ActivityRuntime.Validation.INVALID;
        if(choice==null) {
            var op=request.request().capability();
            if(CraftingTablePlacement.is(op)) {
                var source=((ContainerValue)request.request().arguments().get("source")).value();
                var site=CraftingTablePlacement.address(request.request());
                if(!allowed(request,Effect.WITHDRAW,source) || !allowed(request,Effect.PLACE,site))
                    return ActivityRuntime.Validation.INVALID;
                var prepared=world.placementPreflight(request);
                return prepared.status()==ObservationStatus.PRESENT && prepared.reason()==null
                        && !prepared.pending() && prepared.availableItems()>=1 && quantities.equals(ActivityPayload.Quantities.exact(1))
                        ?ActivityRuntime.Validation.VALID:ActivityRuntime.Validation.INVALID;
            }
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
        if(validate(job,invocation,step.choice(),step.quantities())!=ActivityRuntime.Validation.VALID)
            return new ObservationSnapshot(original.reference(),ObservationStatus.UNKNOWN,original.targetIdentity(),
                    original.observedTick(),original.scannedCells(),original.counts());
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
        var authorizedSources=ActivityRecipeEvidence.authorizedSources(job);
        var policy=new Policy(actor,Set.of(actor),Set.copyOf(authorizedSources),Set.of(destination),
                allowedArea==null?Set.of():Set.of(allowedArea),source,facility==null?null:facility.reference(),
                facility!=null,false,0,16,job.activity().production().allowsHeldSticks(),job.activity().production().groupedOriginalPlanks());
        var goal=new Goal(Mode.MAKE_NEW,defaultKey(production.output()),((IntValue)request.arguments().get("amount")).value(),
                destination,policy);
        var profiles=new ArrayList<RecipeProfile>();
        boolean exactLegacy=job.activity().production().exactLegacyMaterials();
        boolean separateSticks=job.activity().production().allowsSeparateSticks();
        boolean singleBatchPlanks=allowedArea==null && goal.demand()<=production.outputPerBatch()
                && RecipeProduction.PlankMaterial.forOutput(production.output().id()).isPresent();
        var capabilities=production.axe()?List.of(OakPlankProduction.ID,BirchPlankProduction.ID,StickProduction.ID,
                CraftingTableProduction.ID,WoodenAxeProduction.ID)
                : exactLegacy && production.table()?List.of(OakPlankProduction.ID,capability):List.of(capability);
        for(var id:capabilities) {
            if(exactLegacy && production.axe() && id.equals(BirchPlankProduction.ID))continue;
            var target=id.equals(capability)?destination:source;
            var start=position(nativeActor);
            long travel=singleBatchPlanks?FabricProductionTravel.singleBatchInputAndDelivery(start,source,target)
                    :FabricProductionTravel.recipe(start,source,target,
                    id.equals(WoodenAxeProduction.ID) && facility!=null?facility.reference():null,
                    facility==null?null:facility.reference());
            if(id.equals(WoodenAxeProduction.ID) && facility!=null && facility.availability()==Availability.PRESENT
                    && !separateSticks && goal.demand()>=4 && clearTableFloor(nativeActor,actor,source,target,facility.reference()))
                travel=FabricProductionTravel.existingTableOnClearFloor(start,source,target,facility.reference());
            if(id.equals(WoodenAxeProduction.ID) && facility!=null && separateSticks)
                travel=Math.addExact(travel,FabricProductionTravel.separateStickLeg(source,authorizedSources.getLast(),facility.reference()));
            RecipeProfile profile;
            try {
                profile=world.describeChoice(new CapabilityId(id.name(),2),new Cost(travel,4,100+6*travel));
            } catch(FabricConcreteRecipeChoices.Refused unsupported) {
                if(unsupported.reason!=Outcomes.Reason.UNSUPPORTED_PRIMITIVE)throw unsupported;
                // The installed recipe is outside this host's current native envelope.
                // Keep the other legal recipes and let the planner publish its scoped blocker.
                continue;
            }
            var method=pin(job,id,profile.semanticFingerprint(),2);
            var cells=exactLegacy?profile.cells().stream().map(cell->cell.stream()
                    .filter(input->ResourceComposition.explicitRecipeInput(id,input.id())).toList()).toList():profile.cells();
            if(cells.stream().anyMatch(List::isEmpty))continue;
            profiles.add(new RecipeProfile(profile.recipe(),profile.capability(),cells,profile.result(),
                    profile.semanticFingerprint(),method,profile.allowance(),profile.reloadEpoch()));
        }
        var box=world.inspectRecipeContainer(destination,production.output());
        boolean writable=allowed(bound,Effect.TRANSFER,destination) && box.status()==ObservationStatus.PRESENT
                && box.capacity()>=goal.demand()-job.fulfilled();
        var stock=new ArrayList<>(stock(job,source,nativeActor,"source:",false));
        if(authorizedSources.size()>1)stock.addAll(stock(job,authorizedSources.getLast(),nativeActor,"additional-source:",separateSticks));
        if(policy.useHeldSticks() && allowed(bound,Effect.OBSERVE,position(nativeActor)))stock.addAll(heldSticks(job,nativeActor));
        var acquisition=acquisition(job);
        long revision=job.generation(); // Native values below carry their own current observation data.
        return new Snapshot(SCHEMA,job.origin().scope().worldId(),job.id(),job.revision(),job.origin(),
                new Owner(job.origin().principal().id(),actor,job.generation(),writable,false,false,false),
                goal,destination,job.fulfilled(),ProductionActivityPlanner.budget(job),clock.millis(),revision,
                stock,acquisition,profiles,facility,List.of(),Set.of(Kind.PRODUCE,Kind.ACQUIRE,Kind.PLACE_TABLE),
                progress(job),true,false,0);
    }

    private boolean clearTableFloor(Villager actor,ActorRef worker,ContainerRef source,ContainerRef target,FacilityRef facility) {
        var points=List.of(actor.blockPosition(),pos(source),pos(target),new BlockPos(facility.x(),facility.y(),facility.z()));
        int y=points.getFirst().getY();
        if(points.stream().anyMatch(p->p.getY()!=y))return false;
        int minX=points.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX=points.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minZ=points.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxZ=points.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        if((maxX-minX+1L)*(maxZ-minZ+1L)>128)return false;
        var participants=points.subList(1,4);
        for(var cell:BlockPos.betweenClosed(minX,y,minZ,maxX,y,maxZ)) {
            var floor=cell.below();
            if(!world.discoverable(worker,floor,Effect.OBSERVE) || !world.discoverable(worker,cell.above(),Effect.OBSERVE)
                    || !level.getBlockState(cell.above()).isAir())return false;
            var ground=level.getBlockState(floor);
            if(!ground.isCollisionShapeFullBlock(level,floor) || !ground.getFluidState().isEmpty()
                    || ground.is(Blocks.MAGMA_BLOCK) || ground.is(Blocks.POWDER_SNOW))return false;
            if(!participants.contains(cell) && !level.getBlockState(cell).isAir())return false;
        }
        return true;
    }

    private List<Stock> heldSticks(Jobs.Job job,Villager actor) {
        var inventory=actor.getInventory();
        if(inventory.getContainerSize()>8)throw new IllegalStateException("Bounded original actor inventory required");
        var projected=new ArrayList<Stock>();
        for(int slot=0;slot<inventory.getContainerSize();slot++) {
            var stack=inventory.getItem(slot);
            if(FabricRecipeTransactions.plain(stack,net.minecraft.world.item.Items.STICK))
                projected.add(new Stock("held-stick:"+slot,new Location(null,job.responsible(),slot),
                        FabricConcreteRecipeChoices.itemKey(level,stack),stack.getCount(),stack.getCount(),0,
                        null,List.of(),Eligibility.NONE,Availability.PRESENT,true,true,0,job.generation()));
        }
        return List.copyOf(projected);
    }

    private List<Stock> stock(Jobs.Job job,ContainerRef source,Villager actor,String slotPrefix,boolean sticksOnly) {
        var box=world.recipeContainer(source);
        if(box==null || !allowed(job.request(),Effect.WITHDRAW,source))
            throw new IllegalStateException("Current authorized stock unavailable");
        var totals=new HashMap<String,Long>();
        for(int slot=0;slot<box.getContainerSize();slot++) {
            var stack=box.getItem(slot);
            if(sticksOnly && !stack.is(net.minecraft.world.item.Items.STICK))continue;
            if(!stack.isEmpty() && ResourceLeases.supportedStockItem(FabricConcreteRecipeChoices.itemKey(level,stack).id()))
                totals.merge(FabricConcreteRecipeChoices.itemKey(level,stack).id(),(long)stack.getCount(),Math::addExact);
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
            if(stack.isEmpty() || sticksOnly && !stack.is(net.minecraft.world.item.Items.STICK))continue;
            var item=FabricConcreteRecipeChoices.itemKey(level,stack);
            if(!ResourceLeases.supportedStockItem(item.id()))continue;
            long available=Math.min(stack.getCount(),free.get(item.id()));
            free.compute(item.id(),(id,count)->count-available);
            projected.add(new Stock(slotPrefix+slot,new Location(source,null,slot),item,available,stack.getCount(),0,
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
        long travel=FabricProductionTravel.placement(position(actor),staging,reference);
        return new Facility(reference,id!=null?Availability.PRESENT:free?Availability.ABSENT:Availability.UNKNOWN,
                allowed(job.request(),Effect.CRAFT,cell),true,id==null?"":id,free,job.generation(),
                pin(job,CraftingTablePlacement.ID,ActivityPayload.digest("crafting-table-placement:v1"),1),
                new Cost(travel,3,100+6*travel));
    }

    private List<Acquisition> acquisition(Jobs.Job job) {
        var area=job.activity().production().acquisition();
        var actor=world.recipeActor(job.responsible());
        if(area==null || actor==null)return List.of();
        var exact=job.activity().production().exactLegacyMaterials()
                ? ResourceComposition.explicitLogMethod(job.request().request().capability()):null;
        long oak=0,birch=0;
        var targets=new ArrayList<ContainerRef>();
        boolean complete=true;
        for(int x=area.minX();x<=area.maxX();x++)for(int y=area.minY();y<=area.maxY();y++)for(int z=area.minZ();z<=area.maxZ();z++) {
            var position=new ContainerRef(area.dimension(),x,y,z);
            if(!world.loadedForDiscovery(pos(position)) || !allowed(job.request(),Effect.HARVEST,position)) {complete=false;continue;}
            var target=new SurvivalGateway.Cell(area.dimension(),x,y,z);
            if((exact==null || exact.equals(OakLogDelivery.ID)) && world.resourceBlock(target,ResourceDelivery.OAK_LOG).eligible()){oak++;targets.add(position);}
            if((exact==null || exact.equals(BirchLogDelivery.ID)) && world.resourceBlock(target,ResourceDelivery.BIRCH_LOG).eligible()){birch++;targets.add(position);}
        }
        // Overlapping acquisition options are forbidden by the original B contract.
        if(oak>0 && birch>0 || !complete || oak==0 && birch==0)return List.of();
        var capability=oak>0?OakLogDelivery.ID:BirchLogDelivery.ID;
        var item=oak>0?ResourceDelivery.OAK_LOG:ResourceDelivery.BIRCH_LOG;
        var staging=((ContainerValue)job.request().request().arguments().get("source")).value();
        long travel=FabricProductionTravel.acquisition(position(actor),staging,targets);
        // Include the final native deposit acknowledgement even when the harvest
        // and pickup used most of their shared tick quote.
        return List.of(new Acquisition("original-authorized-logs",area,defaultKey(item),Math.min(64,oak+birch),
                Availability.PRESENT,true,true,pin(job,capability,ActivityPayload.digest(capability.toString()),1),
                new Cost(travel,4,144+6*travel)));
    }

    private RecipeChoice.ItemKey defaultKey(ResourceDelivery.ItemIdentity item) {
        return FabricConcreteRecipeChoices.itemKey(level,new net.minecraft.world.item.ItemStack(FabricResourceItems.item(item)));
    }

    private MethodPin pin(Jobs.Job job,CapabilityId id,String semantics,int version) {
        var page=repository.page(id,"",16);
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
            var tokens=job.activity().production().coveredActions().getOrDefault(step.proposal(),List.of(step.token()));
            for(var token:tokens)progress.add(new SettledProgress(job.id(),token,step.id().toString(),attempt.id(),effect,refs,
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
    private static ContainerRef position(Villager actor){var p=actor.blockPosition();return new ContainerRef(
            actor.level().dimension().identifier().toString(),p.getX(),p.getY(),p.getZ());}
    private static long travel(Villager actor,ContainerRef source,ContainerRef destination,Facility facility) {
        return FabricProductionTravel.recipe(position(actor),source,destination,facility==null?null:facility.reference(),null);
    }
}
