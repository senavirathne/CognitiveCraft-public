package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.Reason;
import dev.aivillages.core.kernel.runtime.evidence.*;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import dev.aivillages.core.kernel.runtime.evidence.NativeHarvest.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/** Experimental stopped-harvest envelope. It deliberately fences later pickup until native pickup proof exists. */
public final class FabricHarvestProtocol implements HarvestProtocol {
    public enum Point { PREPARED_MARKED,BEFORE_MUTATION,AFTER_MUTATION,RESULT_ACKNOWLEDGED,VERIFICATION_ACKNOWLEDGED }
    private final Thread thread=Thread.currentThread();
    private final ServerLevel level;private final JobLifecycleStore jobs;private final ResourceLeaseService leases;private final Clock clock;
    private final boolean enabled;
    private final HarvestEvidenceStore store;
    private final HarvestPreflightScope preflight=new HarvestPreflightScope();
    private final Set<NativeTicket> tickets=Collections.newSetFromMap(new IdentityHashMap<>());
    private Consumer<Point> observer=point->{ };
    private boolean closed;
    private final Map<String,NativeBirth> membershipBirths=new HashMap<>();
    private final ArrayDeque<Long> preparationNanos=new ArrayDeque<>(),pollNanos=new ArrayDeque<>();
    private final ArrayDeque<Refusal> refusals=new ArrayDeque<>();
    public record Refusal(String phase,String reason,String detail) { }
    private void refused(String phase,String reason,String detail){if(refusals.size()==32)refusals.removeFirst();refusals.addLast(new Refusal(phase,reason,detail==null?"":detail.substring(0,Math.min(128,detail.length()))));}
    public record Metrics(List<Long> preparationNanos,List<Long> pollNanos,List<Long> acknowledgementNanos,List<Refusal> refusals) { }
    public Metrics metrics(){check();return new Metrics(List.copyOf(preparationNanos),List.copyOf(pollNanos),store.acknowledgementNanos(),List.copyOf(refusals));}
    private void measured(ArrayDeque<Long> values,long began){if(values.size()==128)values.removeFirst();values.addLast(System.nanoTime()-began);}
    private final class NativeTicket implements Ticket {
        final Request request;final Action original;final NativeHarvest.Intent intent;final Reason failure;
        boolean forgotten,acknowledged;
        NativeTicket(Request request,Action original,NativeHarvest.Intent intent,Reason failure){this.request=request;this.original=original;this.intent=intent;this.failure=failure;}
    }
    private record Pair(Villager actor,LevelChunk chunk,Participant actorSnapshot,Source source) { }
    private static NativePickup.Chunk membershipChunk(net.minecraft.world.entity.Entity entity) {
        var chunk=entity.chunkPosition();return new NativePickup.Chunk(entity.level().dimension().identifier().toString(),chunk.x(),chunk.z());
    }
    private final class NativeBirth {
        final NativeHarvest.Intent intent;final EntityMembershipTrail actor;final NativePickup.Chunk actorBirth;
        final Map<UUID,BornDrop> drops=new LinkedHashMap<>();boolean complete=true,finished;
        NativeBirth(NativeHarvest.Intent intent,Villager actor){this.intent=intent;actorBirth=membershipChunk(actor);this.actor=((EntityMembershipParticipant)actor).cognitivecraft$beginMembership(intent.actorBefore().incarnation());}
    }
    private record BornDrop(UUID incarnation,NativePickup.Chunk birth,EntityMembershipTrail trail,boolean beforeNativeAdd){}
    public record DropBirth(UUID entity,UUID incarnation,NativePickup.Chunk birth,NativePickup.Trail trail,boolean beforeNativeAdd){}
    public record MembershipBirth(String token,Key key,NativePickup.Chunk actorBirth,NativePickup.Trail actorTrail,List<DropBirth> drops,boolean complete,boolean pickupLineageAvailable){}
    /** Private live H lifetime link; immutable captures do not authorize any pickup effect. */
    public static final class PickupLineage {
        private final FabricHarvestProtocol owner;private final NativeBirth birth;private final HarvestEvidenceStore.Acknowledged original;
        private final NativePickup.Trail actor,drop;
        private PickupLineage(FabricHarvestProtocol owner,NativeBirth birth,HarvestEvidenceStore.Acknowledged original,NativePickup.Trail actor,NativePickup.Trail drop){this.owner=owner;this.birth=birth;this.original=original;this.actor=actor;this.drop=drop;}
        public NativePickup.Trail actorTrail(){return actor;}public NativePickup.Trail dropTrail(){return drop;}
        public NativeHarvest.Entry harvest(){return original.entry();}
    }
    private NativeBirth beginMembershipBirth(NativeHarvest.Intent intent,Villager actor) {
        if(intent.key().ordinal()!=1||!intent.key().root().equals(intent.key().step())||intent.actorBefore().slots().stream().anyMatch(slot->slot.count()!=0))return null;
        if(membershipBirths.size()==NativeHarvest.MAX_ENTRIES||membershipBirths.containsKey(intent.key().token()))throw new IllegalStateException("Original H membership quota/identity");
        var birth=new NativeBirth(intent,actor);membershipBirths.put(intent.key().token(),birth);return birth;
    }
    private void born(NativeBirth birth,ItemEntity drop) {
        check();if(birth==null)return;
        boolean original=drop.level()==level&&!drop.isRemoved()&&level.getEntity(drop.getUUID())==null
                &&drop.blockPosition().equals(pos(birth.intent.source()))&&BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()).toString().equals(birth.intent.item());
        if(!original||birth.drops.size()==64||birth.drops.containsKey(drop.getUUID())){birth.complete=false;return;}
        var state=((DepositParticipant)drop).cognitivecraft$depositParticipant();state.activate();state.track(List.of(drop.getItem()));
        var trail=((EntityMembershipParticipant)drop).cognitivecraft$beginMembership(state.incarnation());
        birth.drops.put(drop.getUUID(),new BornDrop(state.incarnation(),membershipChunk(drop),trail,true));
    }
    public PickupLineage capturePickupLineage(HarvestEvidenceStore.Acknowledged original) {
        check();if(closed||!enabled||!store.owns(original))return null;var e=original.entry();var birth=membershipBirths.get(e.token());
        if(birth==null||!birth.complete||!birth.finished||!birth.intent.equals(e.intent())||e.result()==null||e.result().live()!=NativeHarvest.Live.COMPLETE
                ||e.result().drops().size()!=1||birth.drops.size()!=1)return null;
        var ref=e.result().drops().getFirst();var born=birth.drops.get(ref.entity());if(born==null||!born.incarnation().equals(ref.incarnation())||!born.beforeNativeAdd())return null;
        var expected=new Marker(e.token(),e.intent().digest(),MarkerPhase.RESULT,e.result().digest());
        var actor=actorWitness(e.result().actor());var source=sourceWitness(e.result().source());var drops=dropWitnesses(e.result().drops());
        if(actor==null||source==null||drops==null||drops.size()!=1||!actor.participant().equals(e.result().actor())||!source.source().equals(e.result().source())
                ||!drops.getFirst().drop().equals(ref)||!expected.equals(actor.marker())||!expected.equals(source.marker())||!expected.equals(drops.getFirst().marker()))return null;
        try {
            var actorTrail=birth.actor.capture();var dropTrail=born.trail().capture();
            var nativeActor=level.getEntity(UUID.fromString(e.result().actor().address()));var nativeDrop=level.getEntity(ref.entity());
            if(nativeActor==null||nativeDrop==null||!actorTrail.current().equals(membershipChunk(nativeActor))||!dropTrail.current().equals(membershipChunk(nativeDrop)))return null;
            return new PickupLineage(this,birth,original,actorTrail,dropTrail);
        }catch(RuntimeException incomplete){return null;}
    }
    public boolean currentPickupLineage(PickupLineage lineage) {
        check();if(lineage==null||lineage.owner!=this||membershipBirths.get(lineage.birth.intent.key().token())!=lineage.birth)return false;
        var current=capturePickupLineage(lineage.original);return current!=null&&current.actor.equals(lineage.actor)&&current.drop.equals(lineage.drop);
    }
    public List<MembershipBirth> membershipBirths() {
        check();var views=new ArrayList<MembershipBirth>();
        for(var birth:membershipBirths.values()) {
            NativePickup.Trail actor=null;boolean complete=birth.complete&&birth.finished;
            try{actor=birth.actor.capture();}catch(RuntimeException gap){complete=false;}
            var drops=new ArrayList<DropBirth>();
            for(var e:birth.drops.entrySet()){NativePickup.Trail trail=null;try{trail=e.getValue().trail().capture();}catch(RuntimeException gap){complete=false;}
                drops.add(new DropBirth(e.getKey(),e.getValue().incarnation(),e.getValue().birth(),trail,e.getValue().beforeNativeAdd()));}
            var acknowledged=store.acknowledged(birth.intent.key());boolean linked=acknowledged!=null&&capturePickupLineage(acknowledged)!=null;
            views.add(new MembershipBirth(birth.intent.key().token(),birth.intent.key(),birth.actorBirth,actor,List.copyOf(drops),complete,linked));
        }
        views.sort(Comparator.comparing(MembershipBirth::token));return List.copyOf(views);
    }

    public FabricHarvestProtocol(ServerLevel level,JobLifecycleStore jobs,ResourceLeaseService leases,HarvestJournal journal,
                                 HarvestJournal.Snapshot initial,boolean readOnly,Executor worker,Clock clock,boolean enabled) {
        this.level=Objects.requireNonNull(level);this.jobs=Objects.requireNonNull(jobs);this.leases=Objects.requireNonNull(leases);
        this.clock=Objects.requireNonNull(clock);this.enabled=enabled;
        NativeDeposit.initializeParticipantSchema();DepositStackOwners.initialize();
        store=new HarvestEvidenceStore(initial,(expected,next)->CompletableFuture.supplyAsync(()->{
            if(Thread.currentThread()==thread)throw new IllegalStateException("Harvest I/O executor ran on the game thread");
            try{return journal.replace(expected,next);}catch(java.io.IOException error){throw new CompletionException(error);}
        },worker),new HarvestEvidenceStore.NativePort() {
            public boolean current(NativeHarvest.Intent intent){return nativeCurrent(intent);}
            public NativeHarvest.Result execute(NativeHarvest.Intent intent){return nativeExecute(intent);}
            public Witness restoredActor(Participant expected){return actorWitness(expected);}
            public SourceWitness restoredSource(Source expected){return sourceWitness(expected);}
            public List<DropWitness> restoredDrops(List<Drop> expected){return dropWitnesses(expected);}
        },clock,30_000,readOnly);
    }
    private void check(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Native harvest game-thread owner");}
    public HarvestEvidenceStore evidence(){check();return store;}
    public void observe(Consumer<Point> observer){check();this.observer=Objects.requireNonNull(observer);}
    public void tick(){check();store.tick();}
    public void close(){check();closed=true;for(var ticket:tickets)ticket.forgotten=true;tickets.clear();membershipBirths.clear();}
    @Override public Ticket begin(Request request,Action original) {
        check();long began=System.nanoTime();
        try{return beginNative(Objects.requireNonNull(request),Objects.requireNonNull(original));}
        finally{measured(preparationNanos,began);}
    }
    private Ticket beginNative(Request request,Action original) {
        String phase="authority";
        try {
            if(closed || !enabled)throw new Refused(Reason.UNSUPPORTED_PRIMITIVE);
            if(store.fenced())throw new Refused(Reason.STORAGE_UNAVAILABLE);
            if(tickets.size()>=8)throw new Refused(Reason.STORAGE_LIMIT_REACHED);
            if(!original.current())throw new Refused(Reason.INTERRUPTED);
            phase="owner";
            var owner=new JobLeaseOwners(jobs,clock).byRun(request.bound(),request.correlation());
            var job=jobs.query(owner.job(),request.bound().context());var attempt=job.current();
            if(!jobs.ready() || !leases.ready() || attempt==null || !attempt.open() || jobs.stopPending(job.id(),job.generation(),job.origin()))throw new Refused(Reason.INTERRUPTED);
            phase="participants";
            Pair pair=pair(request,true);if(pair==null)throw new Refused(Reason.TARGET_UNAVAILABLE);
            if(!BuiltInRegistries.BLOCK.getKey(pair.chunk.getBlockState(pos(request.source())).getBlock()).toString().equals(request.item().id()))throw new Refused(Reason.TARGET_INVALID);
            phase="claims";
            var claims=new ArrayList<ResourceLeases.Lease>(4);
            for(var claim:leases.snapshot().leases())if(claim.owner().equals(owner) && claim.origin().equals(job.origin()) && claim.state()==ResourceLeases.State.ACTIVE && claim.expires()>level.getGameTime()) {
                if(claims.size()==4)throw new Refused(Reason.TARGET_UNAVAILABLE);claims.add(claim);
            }
            if(claims.isEmpty())throw new Refused(Reason.TARGET_UNAVAILABLE);
            UUID group=claims.getFirst().group();boolean source=false,facility=false;var references=new ArrayList<Claim>();
            for(var claim:claims) {
                if(!claim.group().equals(group))throw new Refused(Reason.TARGET_UNAVAILABLE);
                var resource=claim.resource();
                if(resource.logBlocks() && resource.area().equals(request.area()) && resource.item().equals(request.item().id()) && claim.remaining()>0)source=true;
                if(resource.equals(ResourceLeases.Resource.facility(job.origin().scope().worldId(),request.destination())))facility=true;
                references.add(new Claim(claim.id(),claim.epoch(),claim.group(),claim.generation()));
            }
            if(!source || !facility)throw new Refused(Reason.TARGET_UNAVAILABLE);
            phase="allowance";
            UUID root=job.id();var ancestor=job;
            for(int n=0;ancestor.parent()!=null;n++) {
                if(n>=8)throw new Refused(Reason.REQUEST_INVALID);ancestor=jobs.query(ancestor.parent(),job.origin());root=ancestor.id();
            }
            long deadline=Math.min(job.allowance().deadlineEpochMillis(),attempt.allowance().deadlineEpochMillis());
            long remaining=attempt.allowance().maximum(Budgets.Kind.COMMITTED_EFFECTS)-request.usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L);
            if(request.deadline()>deadline || !attempt.worker().equals(request.actor()))throw new Refused(Reason.REQUEST_INVALID);
            if(request.effectsRemaining()>remaining)throw new Refused(Reason.BUDGET_EXHAUSTED);
            int reach=(int)Math.ceil(Math.sqrt(pair.actor.distanceToSqr(Vec3.atCenterOf(pos(request.source()))))*1000);
            var conditions=new Conditions(request.actor().citizenId(),job.revision(),deadline,request.deadline(),request.effectsRemaining(),level.getGameTime(),2500,reach,references,request.usage());
            int ordinal=Math.toIntExact(Math.addExact(request.usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L),1));
            phase="intent";
            var intent=new NativeHarvest.Intent(new Key(job.origin().scope().worldId(),root,job.id(),attempt.id(),attempt.generation(),ordinal),job.origin(),request.correlation().runId(),request.operation(),
                    request.actor(),request.area(),request.destination(),request.item().id(),pair.source,conditions,pair.actorSnapshot);
            phase="exclusion";
            if(store.conflicts(request.actor(),request.source().dimension(),request.source().x(),request.source().y(),request.source().z(),""))throw new Refused(Reason.INTERRUPTED);
            var ticket=new NativeTicket(request,original,intent,null);tickets.add(ticket);return ticket;
        }catch(Refused refused){refused(phase,refused.reason.name(),"");return new NativeTicket(request,original,null,refused.reason);}
        catch(RuntimeException invalid){refused(phase,invalid.getClass().getSimpleName(),invalid.getMessage());return new NativeTicket(request,original,null,Reason.TARGET_INVALID);}
    }
    @Override public Progress poll(Ticket value) {
        check();long began=System.nanoTime();try{return pollNative(value);}finally{measured(pollNanos,began);}
    }
    private Progress pollNative(Ticket value) {
        if(!(value instanceof FabricHarvestProtocol.NativeTicket ticket) || ticket.forgotten || closed)return failed(Reason.INTERRUPTED);
        if(ticket.failure!=null)return failed(ticket.failure);
        if(!tickets.contains(ticket))return failed(Reason.INTERRUPTED);
        store.tick();HarvestEvidenceStore.Status status;
        try{status=store.prepare(ticket.intent);if(status.code()==HarvestEvidenceStore.Code.PREPARED)status=store.perform(status.prepared());}
        catch(RuntimeException unknown){refused("publication",unknown.getClass().getSimpleName(),unknown.getMessage());return new Progress(Code.UNRESOLVED,Reason.INTERRUPTED);}
        return switch(status.code()) {
            case PENDING,PREPARED->new Progress(Code.PENDING,null);
            case RESULT_ACKNOWLEDGED->{
                var entry=store.snapshot().entries().stream().filter(e->e.intent().equals(ticket.intent)).findFirst().orElseThrow();
                if(entry.result().live()==NativeHarvest.Live.PARTIAL || entry.result().live()==NativeHarvest.Live.UNRECEIPTED_EFFECT)yield new Progress(Code.UNRESOLVED,Reason.INTERRUPTED);
                if(!ticket.acknowledged){ticket.acknowledged=true;observer.accept(Point.RESULT_ACKNOWLEDGED);}yield new Progress(Code.ACKNOWLEDGED,null);
            }
            case FULL->failed(Reason.STORAGE_LIMIT_REACHED);
            case FENCED->new Progress(Code.UNRESOLVED,Reason.INTERRUPTED);
            default->{refused("journal",status.code().name(),"");yield failed(Reason.INTERRUPTED);}
        };
    }
    private Progress failed(Reason reason){return new Progress(Code.FAILED,reason);}
    @Override public void forget(Ticket value){check();if(value instanceof FabricHarvestProtocol.NativeTicket ticket && tickets.remove(ticket))ticket.forgotten=true;}
    private NativeTicket ticket(NativeHarvest.Intent intent){return tickets.stream().filter(t->t.intent==intent && !t.forgotten).findFirst().orElse(null);}
    private boolean nativeCurrent(NativeHarvest.Intent intent) {
        NativeTicket ticket=ticket(intent);
        if(closed || !enabled || ticket==null || !preflight.test(ticket.request,ticket.original::current) || !jobs.ready() || !leases.ready())return false;
        var job=jobs.query(intent.key().step(),intent.origin());var attempt=job.current();
        if(job.revision()!=intent.conditions().jobRevision() || attempt==null || !attempt.id().equals(intent.key().attempt()) || attempt.generation()!=intent.key().generation()
                || jobs.stopPending(job.id(),job.generation(),job.origin()) || Math.min(job.allowance().deadlineEpochMillis(),attempt.allowance().deadlineEpochMillis())!=intent.conditions().deadlineEpochMillis())return false;
        var owner=new JobLeaseOwners(jobs,clock).byRun(ticket.request.bound(),ticket.request.correlation());
        for(Claim claim:intent.conditions().claims()) {
            var actual=leases.snapshot().leases().stream().filter(l->l.id().equals(claim.id())).findFirst().orElse(null);
            if(actual==null || !actual.owner().equals(owner) || !actual.epoch().equals(claim.epoch()) || !actual.group().equals(claim.group()) || actual.generation()!=claim.generation()
                    || actual.state()!=ResourceLeases.State.ACTIVE || actual.expires()<=level.getGameTime())return false;
        }
        Pair pair=pair(ticket.request,true);
        return pair!=null && pair.actorSnapshot.equals(intent.actorBefore()) && pair.source.equals(intent.source())
                && pair.actor.distanceToSqr(Vec3.atCenterOf(pos(ticket.request.source())))<=6.25;
    }
    private NativeHarvest.Result nativeExecute(NativeHarvest.Intent intent) {
        NativeTicket ticket=Objects.requireNonNull(ticket(intent));Pair pair=Objects.requireNonNull(pair(ticket.request,true));
        mark(pair,new Marker(intent.key().token(),intent.digest(),MarkerPhase.PREPARED,""));observer.accept(Point.PREPARED_MARKED);
        if(!nativeCurrent(intent))throw new IllegalStateException("Harvest changed after prepared marker");
        mark(pair,new Marker(intent.key().token(),intent.digest(),MarkerPhase.MUTATING,""));observer.accept(Point.BEFORE_MUTATION);
        NativeBirth birth=beginMembershipBirth(intent,pair.actor);
        Observed observed=null;RuntimeException failure=null;
        try(var scope=HarvestBirthScope.open(drop->born(birth,drop))){observed=ticket.original.execute();}catch(RuntimeException error){failure=error;}
        observer.accept(Point.AFTER_MUTATION);
        Pair after=Objects.requireNonNull(pair(ticket.request,true));var drops=new ArrayList<Drop>();var nativeDrops=new ArrayList<ItemEntity>();
        if(observed!=null)for(var actual:observed.harvest().drops()) {
            var entity=level.getEntity(actual.entityId());if(!(entity instanceof ItemEntity drop) || drop.isRemoved() || drop.getItem().getCount()!=actual.quantity())throw new IllegalStateException("Actual harvest drop unavailable");
            drops.add(dropSnapshot(drop,true));nativeDrops.add(drop);
        }
        var result=NativeHarvest.observed(intent,after.source,drops,observed==null?null:observed.receipt(),observed==null?ticket.original.usage():observed.usage(),
                failure==null?observed.harvest().reason()==null?"":observed.harvest().reason().name():failure.getClass().getName(),after.actorSnapshot);
        var marker=new Marker(intent.key().token(),intent.digest(),MarkerPhase.RESULT,result.digest());mark(after,marker);
        for(var drop:nativeDrops)((DepositParticipant)drop).cognitivecraft$depositParticipant().mark(marker,()->{ });
        if(birth!=null){birth.finished=result.live()==NativeHarvest.Live.COMPLETE;
            if(!birth.drops.keySet().equals(result.drops().stream().map(Drop::entity).collect(java.util.stream.Collectors.toSet())))birth.complete=false;}
        return result;
    }
    public HarvestEvidenceStore.Reconciled reconcile(Key key){check();return store.reconcile(key);}
    public HarvestEvidenceStore.Code verify(HarvestEvidenceStore.Reconciled proof){
        check();var code=store.verify(proof);if(code==HarvestEvidenceStore.Code.RESOLVED)observer.accept(Point.VERIFICATION_ACKNOWLEDGED);return code;
    }
    public boolean blocked(ValidatedRequest incoming,Effect effect,SurvivalGateway.Cell target) {
        check();if(effect==Effect.MOVE || effect==Effect.OBSERVE)return false;if(store.fenced())return true;
        String allowed="";
        for(var ticket:tickets)if(ticket.intent!=null && !ticket.forgotten && ticket.request.bound().equals(incoming) && (effect==Effect.HARVEST && target.equals(ticket.request.source()) || preflight.destination(ticket.request,incoming,effect,target))){allowed=ticket.intent.key().token();break;}
        var value=incoming.request().arguments().get("actor");
        return value instanceof ActorValue actor && store.conflicts(actor.value(),target.dimension(),target.x(),target.y(),target.z(),allowed);
    }
    private static BlockPos pos(SurvivalGateway.Cell source){return new BlockPos(source.x(),source.y(),source.z());}
    private static BlockPos pos(Source source){return new BlockPos(source.x(),source.y(),source.z());}
    private String dimension(){return level.dimension().identifier().toString();}
    private String blockState(LevelChunk chunk,BlockPos pos){var state=chunk.getBlockState(pos);return state.isAir()?"minecraft:air":state.toString();}
    private Source source(LevelChunk chunk,BlockPos pos,boolean activate) {
        return ((HarvestSourceParticipant)chunk).cognitivecraft$harvestSources().snapshot(dimension(),chunk.getPos(),pos,blockState(chunk,pos),activate);
    }
    private Pair pair(Request request,boolean activate) {
        if(!request.actor().dimension().equals(dimension()) || !request.source().dimension().equals(dimension()))return null;
        var entity=level.getEntity(request.actor().entityId());if(!(entity instanceof Villager actor) || !actor.isAlive())return null;
        if(level.getChunkSource().getChunkNow(actor.blockPosition().getX()>>4,actor.blockPosition().getZ()>>4)==null)return null;
        BlockPos pos=pos(request.source());var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);if(chunk==null)return null;
        Source source=source(chunk,pos,activate);if(source==null)return null;
        return new Pair(actor,chunk,actorSnapshot(actor,activate),source);
    }
    private Participant actorSnapshot(Villager actor,boolean activate) {
        var state=((DepositParticipant)actor).cognitivecraft$depositParticipant();if(activate)state.activate();
        if(!state.active() || !state.valid())throw new IllegalStateException("Invalid native harvest actor");
        var slots=new ArrayList<Slot>();var stacks=new ArrayList<ItemStack>();var inventory=actor.getInventory();
        for(int n=0;n<inventory.getContainerSize();n++) {
            ItemStack stack=inventory.getItem(n);stacks.add(stack);
            if(stack.isEmpty())slots.add(new Slot("minecraft:air",0));
            else {
                if(!ItemStack.isSameItemSameComponents(stack,new ItemStack(stack.getItem())))throw new IllegalArgumentException("Native harvest actor components");
                slots.add(new Slot(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount()));
            }
        }
        if(activate)state.track(stacks);return new Participant(Kind.ACTOR,dimension(),actor.getUUID().toString(),state.incarnation(),state.revision(),slots);
    }
    private Drop dropSnapshot(ItemEntity drop,boolean activate) {
        var state=((DepositParticipant)drop).cognitivecraft$depositParticipant();if(activate)state.activate();
        ItemStack stack=drop.getItem();if(!state.active() || !state.valid() || stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack,new ItemStack(stack.getItem())))throw new IllegalArgumentException("Native harvest drop components");
        if(activate)state.track(List.of(stack));return new Drop(dimension(),drop.getUUID(),state.incarnation(),state.revision(),BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount());
    }
    private void mark(Pair pair,Marker marker) {
        ((DepositParticipant)pair.actor).cognitivecraft$depositParticipant().mark(marker,pair.actor.getInventory()::setChanged);
        ((HarvestSourceParticipant)pair.chunk).cognitivecraft$harvestSources().mark(pos(pair.source),marker,pair.chunk::markUnsaved);
    }
    private Witness actorWitness(Participant expected) {
        if(!expected.dimension().equals(dimension()))return null;var entity=level.getEntity(UUID.fromString(expected.address()));
        if(!(entity instanceof Villager actor) || !actor.isAlive() || level.getChunkSource().getChunkNow(actor.blockPosition().getX()>>4,actor.blockPosition().getZ()>>4)==null)return null;
        var state=((DepositParticipant)actor).cognitivecraft$depositParticipant();
        try{return new Witness(actorSnapshot(actor,false),state.marker(),state.restored());}catch(RuntimeException invalid){return new Witness(expected,null,false);}
    }
    private SourceWitness sourceWitness(Source expected) {
        if(!expected.dimension().equals(dimension()))return null;BlockPos pos=pos(expected);var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);if(chunk==null)return null;
        var state=((HarvestSourceParticipant)chunk).cognitivecraft$harvestSources();
        try{Source actual=source(chunk,pos,false);return actual==null?new SourceWitness(expected,null,false):new SourceWitness(actual,state.marker(pos),state.restored(pos));}
        catch(RuntimeException invalid){return new SourceWitness(expected,null,false);}
    }
    private List<DropWitness> dropWitnesses(List<Drop> expected) {
        var witnesses=new ArrayList<DropWitness>();
        for(Drop ref:expected) {
            if(!ref.dimension().equals(dimension()))return null;var entity=level.getEntity(ref.entity());
            if(!(entity instanceof ItemEntity drop) || drop.isRemoved() || level.getChunkSource().getChunkNow(drop.blockPosition().getX()>>4,drop.blockPosition().getZ()>>4)==null)return null;
            var state=((DepositParticipant)drop).cognitivecraft$depositParticipant();
            try{witnesses.add(new DropWitness(dropSnapshot(drop,false),state.marker(),state.restored()));}
            catch(RuntimeException invalid){witnesses.add(new DropWitness(ref,null,false));}
        }
        return List.copyOf(witnesses);
    }
    private static final class Refused extends RuntimeException {final Reason reason;Refused(Reason reason){this.reason=reason;}}
}
