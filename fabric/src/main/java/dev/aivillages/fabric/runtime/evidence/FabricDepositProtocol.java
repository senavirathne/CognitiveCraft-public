package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.Reason;
import dev.aivillages.core.kernel.runtime.evidence.*;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

/** Opt-in, single-item log envelope. The existing gateway is the only native mutation caller. */
public final class FabricDepositProtocol implements DepositProtocol {
    public enum Point { PREPARED_MARKED, BEFORE_MUTATION, AFTER_MUTATION, RESULT_ACKNOWLEDGED, VERIFICATION_ACKNOWLEDGED }
    private static final int MAX_TICKETS=8;
    private static final Slot EMPTY_SLOT=new Slot("minecraft:air",0);
    private final Thread thread=Thread.currentThread();
    private final ServerLevel level;
    private final JobLifecycleStore jobs;
    private final ResourceLeaseService leases;
    private final Clock clock;
    private final boolean enabled;
    private final DepositEvidenceStore store;
    private final Set<NativeTicket> tickets=Collections.newSetFromMap(new IdentityHashMap<>());
    private Consumer<Point> observer=point->{ };
    private boolean closed;
    private final ArrayDeque<Long> beginNanos=new ArrayDeque<>(),pollNanos=new ArrayDeque<>();
    public static final List<String> PREPARATION_PHASES=List.of("authority","owner","participants","claims","conditions","intent","exclusion");
    private final ArrayDeque<PreparationProfile> preparations=new ArrayDeque<>();
    public record PreparationProfile(List<Long> completedAtNanos,long bodyNanos) {public PreparationProfile{completedAtNanos=List.copyOf(completedAtNanos);}}
    public record Metrics(List<Long> beginNanos,List<Long> pollNanos,List<Long> acknowledgementNanos,List<PreparationProfile> preparations) { }
    public Metrics metrics(){check();return new Metrics(List.copyOf(beginNanos),List.copyOf(pollNanos),store.acknowledgementNanos(),List.copyOf(preparations));}
    private void measured(ArrayDeque<Long> values,long began){if(values.size()==128)values.removeFirst();values.addLast(System.nanoTime()-began);}

    private final class NativeTicket implements Ticket {
        final Request request;
        final Action original;
        final Intent intent;
        Reason failure;
        boolean forgotten,acknowledged;
        NativeTicket(Request request,Action original,Intent intent,Reason failure) {
            this.request=request;this.original=original;this.intent=intent;this.failure=failure;
        }
    }
    private record Pair(Villager actor,ChestBlockEntity chest,Participant actorSnapshot,Participant chestSnapshot) { }

    public FabricDepositProtocol(ServerLevel level,JobLifecycleStore jobs,ResourceLeaseService leases,
                                 DepositJournal journal,DepositJournal.Snapshot initial,boolean readOnly,
                                 Executor worker,Clock clock,boolean enabled) {
        this.level=Objects.requireNonNull(level);this.jobs=Objects.requireNonNull(jobs);this.leases=Objects.requireNonNull(leases);
        this.clock=Objects.requireNonNull(clock);this.enabled=enabled;
        store=new DepositEvidenceStore(initial,(expected,next)->CompletableFuture.supplyAsync(()->{
            if(Thread.currentThread()==thread)throw new IllegalStateException("Deposit I/O executor ran on the game thread");
            try{return journal.replace(expected,next);}catch(java.io.IOException error){throw new CompletionException(error);}
        },worker),new DepositEvidenceStore.NativePort() {
            public boolean current(Intent intent){return nativeCurrent(intent);}
            public Result execute(Intent intent){return nativeExecute(intent);}
            public Witness restored(Participant participant){return witness(participant);}
        },clock,30_000,readOnly);
    }
    private void check(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Native deposit game-thread owner");}
    /** Trusted, non-authorizing host observation hook; absent in ordinary operation. */
    public void observe(Consumer<Point> observer){check();this.observer=Objects.requireNonNull(observer);}
    public DepositEvidenceStore evidence(){check();return store;}
    public void tick() {check();store.tick();}
    public void close(){check();closed=true;for(var ticket:tickets)ticket.forgotten=true;tickets.clear();}

    @Override public Ticket begin(Request request,Action original) {
        check();long began=System.nanoTime();long[] phases=new long[7];
        try{return beginNative(request,original,phases);}finally{
            long finished=System.nanoTime();var elapsed=new ArrayList<Long>(7);
            for(long value:phases)elapsed.add(value==0?0:value-began);
            if(preparations.size()==128)preparations.removeFirst();preparations.addLast(new PreparationProfile(elapsed,finished-began));
            measured(beginNanos,began);
        }
    }
    private Ticket beginNative(Request request,Action original,long[] phases) {
        check();Objects.requireNonNull(request);Objects.requireNonNull(original);
        NativeTicket ticket;
        try {
            if(closed || !enabled)throw new Refused(Reason.UNSUPPORTED_PRIMITIVE);
            if(store.fenced())throw new Refused(Reason.STORAGE_UNAVAILABLE);
            if(tickets.size()>=MAX_TICKETS)throw new Refused(Reason.STORAGE_LIMIT_REACHED);
            if(!original.current())throw new Refused(Reason.INTERRUPTED);
            phases[0]=System.nanoTime();
            var owner=new JobLeaseOwners(jobs,clock).byRun(request.bound(),request.correlation());
            var job=jobs.query(owner.job(),request.bound().context());var attempt=job.current();
            if(!jobs.ready() || !leases.ready() || attempt==null || !attempt.open() || jobs.stopPending(job.id(),job.generation(),job.origin()))throw new Refused(Reason.INTERRUPTED);
            phases[1]=System.nanoTime();
            Pair pair=pair(request.actor(),request.destination(),true);
            if(pair==null)throw new Refused(Reason.TARGET_UNAVAILABLE);
            phases[2]=System.nanoTime();
            var claims=new ArrayList<ResourceLeases.Lease>(4);
            for(var claim:leases.snapshot().leases())if(claim.owner().equals(owner) && claim.origin().equals(job.origin())
                    && claim.state()==ResourceLeases.State.ACTIVE && claim.expires()>level.getGameTime()) {
                if(claims.size()==4)throw new Refused(Reason.TARGET_UNAVAILABLE);claims.add(claim);
            }
            if(claims.isEmpty())throw new Refused(Reason.TARGET_UNAVAILABLE);
            var group=claims.getFirst().group();boolean facilityPresent=false;
            var facility=ResourceLeases.Resource.facility(job.origin().scope().worldId(),request.destination());
            var references=new ArrayList<Claim>(claims.size());
            for(var claim:claims) {
                if(!claim.group().equals(group))throw new Refused(Reason.TARGET_UNAVAILABLE);
                if(claim.resource().equals(facility))facilityPresent=true;
                references.add(new Claim(claim.id(),claim.epoch(),claim.group(),claim.generation()));
            }
            if(!facilityPresent)throw new Refused(Reason.TARGET_UNAVAILABLE);
            int source=sourceSlot(pair.actorSnapshot,request.item().id()),target=targetSlot(pair,source,request.item().id());
            if(source<0)throw new Refused(Reason.RESOURCE_MISSING);
            if(target<0)throw new Refused(Reason.TARGET_UNAVAILABLE);
            phases[3]=System.nanoTime();
            UUID root=job.id();var ancestor=job;
            for(int n=0;ancestor.parent()!=null;n++) {
                if(n>=8)throw new Refused(Reason.REQUEST_INVALID);
                ancestor=jobs.query(ancestor.parent(),job.origin());root=ancestor.id();
            }
            int ordinal=1;
            for(var entry:store.snapshot().entries()) {
                var key=entry.intent().key();
                if(key.root().equals(root) && key.attempt().equals(attempt.id()) && key.generation()==attempt.generation())ordinal=Math.max(ordinal,key.ordinal()+1);
            }
            long deadline=Math.min(job.allowance().deadlineEpochMillis(),attempt.allowance().deadlineEpochMillis());
            if(request.deadline()>deadline)throw new Refused(Reason.REQUEST_INVALID);
            long remaining=attempt.allowance().maximum(Budgets.Kind.COMMITTED_EFFECTS)-request.usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L);
            if(request.effectsRemaining()>remaining)throw new Refused(Reason.BUDGET_EXHAUSTED);
            int reach=(int)Math.ceil(Math.sqrt(pair.actor.distanceToSqr(Vec3.atCenterOf(pair.chest.getBlockPos())))*1000);
            var conditions=new Conditions(request.actor().citizenId(),job.revision(),deadline,request.deadline(),request.effectsRemaining(),
                    level.getGameTime(),2500,reach,references,request.usage());
            phases[4]=System.nanoTime();
            var intent=new Intent(new Key(job.origin().scope().worldId(),root,job.id(),attempt.id(),attempt.generation(),ordinal),job.origin(),request.correlation().runId(),request.operation(),
                    pair.actorSnapshot,pair.chestSnapshot,request.item().id(),source,target,capacity(pair,source,target),conditions);
            phases[5]=System.nanoTime();
            ticket=new NativeTicket(request,original,intent,null);
            if(store.conflicts(Kind.ACTOR,intent.actor().dimension(),intent.actor().address(),"") || store.conflicts(Kind.CHEST,intent.chest().dimension(),intent.chest().address(),""))throw new Refused(Reason.INTERRUPTED);
            phases[6]=System.nanoTime();
        }catch(Refused refused){return new NativeTicket(request,original,null,refused.reason);}
        catch(RuntimeException unsupported){return new NativeTicket(request,original,null,Reason.TARGET_INVALID);}
        tickets.add(ticket);return ticket;
    }
    @Override public Progress poll(Ticket value) {
        check();long began=System.nanoTime();try{return pollNative(value);}finally{measured(pollNanos,began);}
    }
    private Progress pollNative(Ticket value) {
        check();if(!(value instanceof FabricDepositProtocol.NativeTicket ticket) || ticket.forgotten)return failed(Reason.INTERRUPTED);
        if(ticket.failure!=null)return failed(ticket.failure);
        if(!tickets.contains(ticket) || closed)return failed(Reason.INTERRUPTED);
        store.tick();DepositEvidenceStore.Status status;
        try {
            status=store.prepare(ticket.intent);
            if(status.code()==DepositEvidenceStore.Code.PREPARED)status=store.perform(status.prepared());
        }catch(RuntimeException unknown){ticket.failure=Reason.STORAGE_UNAVAILABLE;return failed(ticket.failure);}
        return switch(status.code()) {
            case PENDING,PREPARED -> new Progress(Code.PENDING,null);
            case RESULT_ACKNOWLEDGED -> {
                if(!ticket.acknowledged){ticket.acknowledged=true;observer.accept(Point.RESULT_ACKNOWLEDGED);}
                yield new Progress(Code.ACKNOWLEDGED,null);
            }
            case FULL -> failed(Reason.STORAGE_LIMIT_REACHED);
            case FENCED -> failed(Reason.STORAGE_UNAVAILABLE);
            default -> failed(Reason.INTERRUPTED);
        };
    }
    @Override public void forget(Ticket value) {
        check();if(value instanceof FabricDepositProtocol.NativeTicket ticket && tickets.remove(ticket))ticket.forgotten=true;
        // Removal of a process-local callback never removes a journal row or its exclusion.
    }
    private Progress failed(Reason reason){return new Progress(Code.FAILED,reason);}
    private NativeTicket ticket(Intent intent){return tickets.stream().filter(t->t.intent==intent && !t.forgotten).findFirst().orElse(null);}
    private boolean nativeCurrent(Intent intent) {
        NativeTicket ticket=ticket(intent);
        if(closed || !enabled || ticket==null || !ticket.original.current() || !jobs.ready() || !leases.ready())return false;
        var job=jobs.query(intent.key().step(),intent.origin());var attempt=job.current();
        if(job.revision()!=intent.conditions().jobRevision() || attempt==null || !attempt.id().equals(intent.key().attempt())
                || attempt.generation()!=intent.key().generation() || jobs.stopPending(job.id(),job.generation(),job.origin())
                || Math.min(job.allowance().deadlineEpochMillis(),attempt.allowance().deadlineEpochMillis())!=intent.conditions().deadlineEpochMillis())return false;
        var current=new JobLeaseOwners(jobs,clock).byRun(ticket.request.bound(),ticket.request.correlation());
        for(Claim claim:intent.conditions().claims()) {
            var row=leases.snapshot().leases().stream().filter(l->l.id().equals(claim.id())).findFirst().orElse(null);
            if(row==null || !row.owner().equals(current) || !row.epoch().equals(claim.epoch()) || !row.group().equals(claim.group())
                    || row.generation()!=claim.generation() || row.state()!=ResourceLeases.State.ACTIVE || row.expires()<=level.getGameTime())return false;
        }
        Pair pair=pair(ticket.request.actor(),ticket.request.destination(),true);
        return pair!=null && pair.actorSnapshot.equals(intent.actor()) && pair.chestSnapshot.equals(intent.chest())
                && capacity(pair,intent.actorSlot(),intent.chestSlot())==intent.capacity()
                && pair.actor.distanceToSqr(Vec3.atCenterOf(pair.chest.getBlockPos()))<=6.25;
    }
    private Result nativeExecute(Intent intent) {
        NativeTicket ticket=Objects.requireNonNull(ticket(intent));
        Pair pair=Objects.requireNonNull(pair(ticket.request.actor(),ticket.request.destination(),true));
        mark(pair,new Marker(intent.key().token(),intent.digest(),MarkerPhase.PREPARED,""));observer.accept(Point.PREPARED_MARKED);
        if(!nativeCurrent(intent))throw new IllegalStateException("Deposit changed after prepared marker");
        mark(pair,new Marker(intent.key().token(),intent.digest(),MarkerPhase.MUTATING,""));observer.accept(Point.BEFORE_MUTATION);
        Observed observed=null;RuntimeException failure=null;
        try{observed=ticket.original.execute();}catch(RuntimeException error){failure=error;}
        observer.accept(Point.AFTER_MUTATION);
        Pair after=Objects.requireNonNull(pair(ticket.request.actor(),ticket.request.destination(),true));
        Result result=NativeDeposit.observed(intent,after.actorSnapshot,after.chestSnapshot,
                failure==null?observed.transfer().reason()==null?"":observed.transfer().reason().name():failure.getClass().getName(),
                observed==null?null:observed.receipt(),observed==null?ticket.original.usage():observed.usage());
        mark(after,new Marker(intent.key().token(),intent.digest(),MarkerPhase.RESULT,result.digest()));return result;
    }
    /** Bounded, explicitly requested historical classification; never invokes a gateway action. */
    public DepositEvidenceStore.Reconciled reconcile(Key key){check();return store.reconcile(key);}
    public DepositEvidenceStore.Code verify(DepositEvidenceStore.Reconciled proof){check();return store.verify(proof);}
    public boolean blocked(ValidatedRequest incoming,Effect effect,SurvivalGateway.Cell target) {
        check();if(effect==Effect.MOVE || effect==Effect.OBSERVE)return false;
        if(store.fenced())return true;
        String allowed="";
        for(var ticket:tickets)if(ticket.intent!=null && !ticket.forgotten && ticket.request.bound().equals(incoming)
                && effect==Effect.TRANSFER && target.dimension().equals(ticket.intent.chest().dimension())
                && (target.x()+","+target.y()+","+target.z()).equals(ticket.intent.chest().address())){allowed=ticket.intent.key().token();break;}
        var actor=(ActorValue)incoming.request().arguments().get("actor");
        return actor!=null && store.conflicts(Kind.ACTOR,actor.value().dimension(),actor.value().entityId().toString(),allowed)
                || store.conflicts(Kind.CHEST,target.dimension(),target.x()+","+target.y()+","+target.z(),allowed);
    }
    private Witness witness(Participant expected) {
        check();try {
            Object nativeParticipant;
            if(!expected.dimension().equals(level.dimension().identifier().toString()))return null;
            if(expected.kind()==Kind.ACTOR) {
                var entity=level.getEntity(UUID.fromString(expected.address()));
                if(!(entity instanceof Villager actor) || !actor.isAlive() || level.getChunkSource().getChunkNow(actor.blockPosition().getX()>>4,actor.blockPosition().getZ()>>4)==null)return null;
                nativeParticipant=actor;
            }else {
                String[] c=expected.address().split(",");BlockPos pos=new BlockPos(Integer.parseInt(c[0]),Integer.parseInt(c[1]),Integer.parseInt(c[2]));
                var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);if(chunk==null)return null;
                nativeParticipant=chunk.getBlockEntities().get(pos);if(!(nativeParticipant instanceof ChestBlockEntity))return null;
                if(!chunk.getBlockState(pos).is(Blocks.CHEST) || chunk.getBlockState(pos).getValue(ChestBlock.TYPE)!=ChestType.SINGLE)return null;
            }
            var state=((DepositParticipant)nativeParticipant).cognitivecraft$depositParticipant();
            Container inventory=nativeParticipant instanceof Villager actor?actor.getInventory():(ChestBlockEntity)nativeParticipant;
            if(!state.active() || !state.valid())return new Witness(expected,null,false);
            return new Witness(snapshot(expected.kind(),expected.dimension(),expected.address(),state,inventory,false),state.marker(),state.restored());
        }catch(RuntimeException unsupported){return new Witness(expected,null,false);}
    }
    private Pair pair(ActorRef actorRef,ContainerRef chestRef,boolean activate) {
        String dimension=level.dimension().identifier().toString();
        if(!actorRef.dimension().equals(dimension) || !chestRef.dimension().equals(dimension))return null;
        var entity=level.getEntity(actorRef.entityId());if(!(entity instanceof Villager actor) || !actor.isAlive())return null;
        if(level.getChunkSource().getChunkNow(actor.blockPosition().getX()>>4,actor.blockPosition().getZ()>>4)==null)return null;
        BlockPos pos=new BlockPos(chestRef.x(),chestRef.y(),chestRef.z());var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        if(chunk==null || !chunk.getBlockState(pos).is(Blocks.CHEST) || chunk.getBlockState(pos).getValue(ChestBlock.TYPE)!=ChestType.SINGLE
                || !(chunk.getBlockEntities().get(pos) instanceof ChestBlockEntity chest))return null;
        var actorState=((DepositParticipant)actor).cognitivecraft$depositParticipant();var chestState=((DepositParticipant)chest).cognitivecraft$depositParticipant();
        return new Pair(actor,chest,snapshot(Kind.ACTOR,dimension,actorRef.entityId().toString(),actorState,actor.getInventory(),activate),
                snapshot(Kind.CHEST,dimension,pos.getX()+","+pos.getY()+","+pos.getZ(),chestState,chest,activate));
    }
    private Participant snapshot(Kind kind,String dimension,String address,DepositParticipantState state,Container inventory,boolean activate) {
        if(activate)state.activate();if(!state.valid())throw new IllegalStateException("Native deposit metadata invalid");
        var slots=new ArrayList<Slot>();var stacks=new ArrayList<ItemStack>();
        for(int n=0;n<inventory.getContainerSize();n++) {
            ItemStack stack=inventory.getItem(n);stacks.add(stack);
            if(stack.isEmpty()){slots.add(EMPTY_SLOT);continue;}
            if(!ItemStack.isSameItemSameComponents(stack,new ItemStack(stack.getItem())))throw new IllegalArgumentException("Native deposit component envelope");
            slots.add(new Slot(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount()));
        }
        if(activate)state.track(stacks);
        return new Participant(kind,dimension,address,state.incarnation(),state.revision(),slots);
    }
    private void mark(Pair pair,Marker marker) {
        ((DepositParticipant)pair.actor).cognitivecraft$depositParticipant().mark(marker,pair.actor.getInventory()::setChanged);
        ((DepositParticipant)pair.chest).cognitivecraft$depositParticipant().mark(marker,pair.chest::setChanged);
    }
    private int sourceSlot(Participant actor,String item){for(int n=0;n<actor.slots().size();n++)if(actor.slots().get(n).item().equals(item))return n;return -1;}
    private int targetSlot(Pair pair,int source,String item) {
        if(source<0)return -1;var one=pair.actor.getInventory().getItem(source).copyWithCount(1);
        for(int n=0;n<pair.chestSnapshot.slots().size();n++) {
            var slot=pair.chestSnapshot.slots().get(n);
            if((slot.count()==0 || slot.item().equals(item)) && pair.chest.canPlaceItem(n,one) && slot.count()<capacity(pair,source,n))return n;
        }
        return -1;
    }
    private int capacity(Pair pair,int source,int target) {
        var one=pair.actor.getInventory().getItem(source).copyWithCount(1);var current=pair.chest.getItem(target);
        return Math.min(Math.min(one.getMaxStackSize(),pair.chest.getMaxStackSize(one)),current.isEmpty()?64:current.getMaxStackSize());
    }
    private static final class Refused extends RuntimeException {final Reason reason;Refused(Reason reason){this.reason=reason;}}
}
