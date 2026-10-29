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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;

/** Original first-P producer. Native I/O is asynchronous; Jobs remains the execution owner. */
public final class FabricPickupProtocol implements PickupProtocol {
    public enum Point { PREPARED_MARKED,BEFORE_MUTATION,AFTER_MUTATION,RESULT_ACKNOWLEDGED,VERIFICATION_ACKNOWLEDGED }
    private final Thread thread=Thread.currentThread();
    private final ServerLevel level;private final JobLifecycleStore jobs;private final ResourceLeaseService leases;
    private final FabricHarvestProtocol harvest;private final Clock clock;private final boolean enabled;
    private final PickupEvidenceStore store;private boolean closed;
    private final Set<NativeTicket> tickets=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ChunkPos,CompletableFuture<EntityChunkRecord.Frame>> frames=new HashMap<>();
    private Consumer<Point> observer=p->{};
    private final ArrayDeque<Long> captureNanos=new ArrayDeque<>();
    private final class NativeTicket implements Ticket {
        final Request request;final Action action;final FabricHarvestProtocol.PickupLineage lineage;final NativePickup.Intent intent;final Reason failure;
        boolean acknowledged,forgotten;
        NativeTicket(Request r,Action a,FabricHarvestProtocol.PickupLineage l,NativePickup.Intent i,Reason f){request=r;action=a;lineage=l;intent=i;failure=f;}
    }
    public FabricPickupProtocol(ServerLevel level,JobLifecycleStore jobs,ResourceLeaseService leases,FabricHarvestProtocol harvest,
                                PickupJournal journal,PickupJournal.Snapshot initial,boolean readOnly,Executor worker,Clock clock,boolean enabled) {
        this.level=Objects.requireNonNull(level);this.jobs=Objects.requireNonNull(jobs);this.leases=Objects.requireNonNull(leases);
        this.harvest=Objects.requireNonNull(harvest);this.clock=Objects.requireNonNull(clock);this.enabled=enabled;
        store=new PickupEvidenceStore(initial,harvest.evidence(),(expected,next)->CompletableFuture.supplyAsync(()->{
            if(Thread.currentThread()==thread)throw new IllegalStateException("Pickup I/O on game thread");
            try{return journal.replace(expected,next);}catch(java.io.IOException error){throw new CompletionException(error);}
        },worker),new PickupEvidenceStore.NativePort() {
            public boolean current(NativePickup.Intent intent){return nativeCurrent(intent);}
            public NativePickup.Result execute(NativePickup.Intent intent){return nativeExecute(intent);}
            public NativePickup.Observation restored(NativePickup.Entry entry){return observeSaved(entry);}
        },clock,30_000,readOnly);
    }
    private void check(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Pickup game-thread owner");}
    public PickupEvidenceStore evidence(){check();return store;}
    public void observe(Consumer<Point> observer){check();this.observer=Objects.requireNonNull(observer);}
    public List<Long> captureNanos(){check();return List.copyOf(captureNanos);}
    public void tick(){check();store.tick();}
    public void close(){check();closed=true;tickets.clear();frames.clear();}
    public Ticket begin(Request request,Action action) {
        check();long began=System.nanoTime();
        try {
            if(closed || !enabled || store.fenced() || store.pending() || tickets.size()==8)throw new IllegalStateException("Pickup unavailable");
            var owner=new JobLeaseOwners(jobs,clock).byRun(request.bound(),request.correlation());
            var job=jobs.query(owner.job(),request.bound().context());var attempt=job.current();
            if(!jobs.ready() || !leases.ready() || attempt==null || !attempt.open() || jobs.stopPending(job.id(),job.generation(),job.origin()))throw new IllegalStateException("Original owner stopping");
            var ancestors=harvest.evidence().snapshot().entries().stream().filter(e->e.intent().key().step().equals(job.id())
                    && e.intent().key().attempt().equals(attempt.id()) && e.intent().key().generation()==attempt.generation()).toList();
            if(ancestors.size()!=1)throw new IllegalStateException("Original first-H required");
            var h=ancestors.getFirst();var acknowledged=harvest.evidence().acknowledged(h.intent().key());
            var lineage=harvest.capturePickupLineage(acknowledged);
            if(lineage==null || !h.intent().run().equals(request.correlation().runId()) || !h.intent().actor().equals(request.actor())
                    || !h.result().drops().getFirst().entity().equals(request.drop()) || h.result().drops().getFirst().quantity()!=request.expected()
                    || !h.intent().item().equals(request.item().id()) || !harvest.pickupScope(lineage,action::current))throw new IllegalStateException("Original pickup custody");
            var claims=new ArrayList<Claim>();
            for(var claim:leases.snapshot().leases())if(claim.owner().equals(owner) && claim.origin().equals(job.origin()) && claim.state()==ResourceLeases.State.ACTIVE && claim.expires()>level.getGameTime())
                claims.add(new Claim(claim.id(),claim.epoch(),claim.group(),claim.generation()));
            var actor=actor(request.actor());var drop=drop(request.drop());if(actor==null || drop==null)throw new IllegalStateException("Pickup participants unavailable");
            long deadline=Math.min(job.allowance().deadlineEpochMillis(),attempt.allowance().deadlineEpochMillis());
            if(request.deadline()>deadline || request.effectsRemaining()>attempt.allowance().maximum(Budgets.Kind.COMMITTED_EFFECTS)-request.usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L))throw new IllegalStateException("Pickup allowance");
            var conditions=new Conditions(request.actor().citizenId(),job.revision(),deadline,request.deadline(),request.effectsRemaining(),level.getGameTime(),2500,
                    (int)Math.ceil(Math.sqrt(actor.distanceToSqr(drop))*1000),claims,request.usage());
            var k=h.intent().key();
            var intent=new NativePickup.Intent(new Key(k.world(),k.root(),k.step(),k.attempt(),k.generation(),2),h,request.operation(),request.expected(),conditions,lineage.actorTrail(),lineage.dropTrail());
            var port=NativeEntityStorage.get(level);if(port==null || !port.available())throw new IllegalStateException("Native entity storage unavailable");
            for(var member:NativePickup.members(intent))frame(member.chunk());
            var ticket=new NativeTicket(request,action,lineage,intent,null);tickets.add(ticket);return ticket;
        }catch(RuntimeException refused){return new NativeTicket(request,action,null,null,Reason.INTERRUPTED);}
        finally{if(captureNanos.size()==128)captureNanos.removeFirst();captureNanos.addLast(System.nanoTime()-began);}
    }
    public Progress poll(Ticket value) {
        check();if(!(value instanceof NativeTicket ticket) || ticket.forgotten || closed)return failed();
        if(ticket.failure!=null || !tickets.contains(ticket))return failed();
        for(var member:NativePickup.members(ticket.intent)) {
            var frame=frame(member.chunk());if(frame==null)return new Progress(Code.PENDING,null);
            if(!frame.supported())return failed();
        }
        store.tick();PickupEvidenceStore.Status status;
        try {
            status=store.prepare(ticket.intent,ticket.lineage.acknowledged());
            if(status.code()==PickupEvidenceStore.Code.PREPARED)status=store.perform(status.prepared());
        }catch(RuntimeException unknown){return new Progress(Code.UNRESOLVED,Reason.INTERRUPTED);}
        return switch(status.code()) {
            case PENDING,PREPARED->new Progress(Code.PENDING,null);
            case RESULT_ACKNOWLEDGED->{
                var entry=store.snapshot().entries().stream().filter(e->e.intent().equals(ticket.intent)).findFirst().orElseThrow();
                if(entry.result().live()!=NativePickup.Live.COMPLETE && entry.result().live()!=NativePickup.Live.NO_EFFECT)yield new Progress(Code.UNRESOLVED,Reason.INTERRUPTED);
                if(!ticket.acknowledged){ticket.acknowledged=true;observer.accept(Point.RESULT_ACKNOWLEDGED);}yield new Progress(Code.ACKNOWLEDGED,null);
            }
            default->failed();
        };
    }
    private Progress failed(){return new Progress(Code.FAILED,Reason.INTERRUPTED);}
    public void forget(Ticket value){check();if(value instanceof NativeTicket t){tickets.remove(t);t.forgotten=true;}}
    private NativeTicket ticket(NativePickup.Intent intent){return tickets.stream().filter(t->t.intent==intent && !t.forgotten).findFirst().orElse(null);}
    private Villager actor(ActorRef ref){var e=level.getEntity(ref.entityId());return e instanceof Villager v && v.isAlive() && loaded(v)?v:null;}
    private ItemEntity drop(UUID id){var e=level.getEntity(id);return e instanceof ItemEntity d && d.isAlive() && loaded(d)?d:null;}
    private boolean loaded(Entity e){return level.getChunkSource().getChunkNow(e.chunkPosition().x(),e.chunkPosition().z())!=null;}
    private boolean nativeCurrent(NativePickup.Intent intent) {
        var ticket=ticket(intent);if(closed || !enabled || ticket==null || !jobs.ready() || !leases.ready() || !harvest.pickupTrailsCurrent(ticket.lineage))return false;
        var h=intent.harvest();var job=jobs.query(intent.key().step(),h.intent().origin());var attempt=job.current();
        if(job.revision()!=intent.conditions().jobRevision() || attempt==null || !attempt.open() || !attempt.id().equals(intent.key().attempt())
                || attempt.generation()!=intent.key().generation() || jobs.stopPending(job.id(),job.generation(),job.origin())
                || Math.min(job.allowance().deadlineEpochMillis(),attempt.allowance().deadlineEpochMillis())!=intent.conditions().deadlineEpochMillis())return false;
        var owner=new JobLeaseOwners(jobs,clock).byRun(ticket.request.bound(),ticket.request.correlation());
        for(var c:intent.conditions().claims()) {
            var actual=leases.snapshot().leases().stream().filter(l->l.id().equals(c.id())).findFirst().orElse(null);
            if(actual==null || !actual.owner().equals(owner) || !actual.epoch().equals(c.epoch()) || !actual.group().equals(c.group())
                    || actual.generation()!=c.generation() || actual.state()!=ResourceLeases.State.ACTIVE || actual.expires()<=level.getGameTime())return false;
        }
        var actor=actor(ticket.request.actor());var drop=drop(ticket.request.drop());var source=harvest.sourceWitness(h.result().source());
        return actor!=null && drop!=null && actor.distanceToSqr(drop)<=6.25 && source!=null && source.source().equals(h.result().source())
                && harvest.actorSnapshot(actor,false).equals(h.result().actor()) && harvest.dropSnapshot(drop,false).equals(h.result().drops().getFirst())
                && harvest.pickupScope(ticket.lineage,ticket.action::current);
    }
    private NativePickup.Result nativeExecute(NativePickup.Intent intent) {
        var ticket=Objects.requireNonNull(ticket(intent));var h=intent.harvest();var actor=Objects.requireNonNull(actor(ticket.request.actor()));var drop=Objects.requireNonNull(drop(ticket.request.drop()));
        mark(intent,actor,drop,new Marker(intent.key().token(),intent.digest(),MarkerPhase.PREPARED,""));observer.accept(Point.PREPARED_MARKED);
        if(!nativeCurrent(intent))throw new IllegalStateException("Pickup changed after PREPARED");
        mark(intent,actor,drop,new Marker(intent.key().token(),intent.digest(),MarkerPhase.MUTATING,""));observer.accept(Point.BEFORE_MUTATION);
        Observed observed=null;RuntimeException failure=null;
        try{observed=harvest.pickupScope(ticket.lineage,ticket.action::execute);}catch(RuntimeException error){failure=error;}
        observer.accept(Point.AFTER_MUTATION);
        var source=Objects.requireNonNull(harvest.sourceWitness(h.result().source())).source();var remaining=drop(ticket.request.drop());
        var result=NativePickup.observed(intent,source,harvest.actorSnapshot(actor,true),remaining==null?null:harvest.dropSnapshot(remaining,true),
                observed==null?null:observed.receipt(),observed==null?ticket.action.usage():observed.usage(),failure==null?"":failure.getClass().getName());
        var marker=new Marker(intent.key().token(),intent.digest(),MarkerPhase.RESULT,result.digest());mark(intent,actor,remaining,marker);
        stage(intent.actorTrail(),NativePickup.Role.ACTOR,result.actor().revision(),marker,true,null);
        stage(intent.dropTrail(),NativePickup.Role.DROP,result.remaining()==null?h.result().drops().getFirst().revision():result.remaining().revision(),marker,result.remaining()!=null,null);
        frames.clear();return result;
    }
    private void mark(NativePickup.Intent intent,Villager actor,ItemEntity drop,Marker marker) {
        ((DepositParticipant)actor).cognitivecraft$depositParticipant().mark(marker,actor.getInventory()::setChanged);
        if(drop!=null)((DepositParticipant)drop).cognitivecraft$depositParticipant().mark(marker,()->{});
        var s=intent.harvest().result().source();var chunk=level.getChunkSource().getChunkNow(s.x()>>4,s.z()>>4);
        if(chunk==null)throw new IllegalStateException("Pickup source unloaded");
        ((HarvestSourceParticipant)chunk).cognitivecraft$harvestSources().mark(new BlockPos(s.x(),s.y(),s.z()),marker,chunk::markUnsaved);
    }
    void stage(NativePickup.Trail trail,NativePickup.Role role,long revision,Marker marker,boolean present,Marker predecessor) {
        var port=Objects.requireNonNull(NativeEntityStorage.get(level));
        for(var chunk:trail.chunks())if(!port.stageMembership(new ChunkPos(chunk.x(),chunk.z()),new EntityChunkMembershipState.Row(role,trail,revision,marker,present&&chunk.equals(trail.current()),predecessor)))
            throw new IllegalStateException("Native membership capacity unavailable");
    }
    private EntityChunkRecord.Frame frame(NativePickup.Chunk chunk) {
        var port=NativeEntityStorage.get(level);if(port==null || !port.available() || !chunk.dimension().equals(level.dimension().identifier().toString()))return null;
        var pos=new ChunkPos(chunk.x(),chunk.z());
        if(frames.size()==EntityStorageEvidence.MAX_CHUNKS && !frames.containsKey(pos))return null;
        var pending=frames.computeIfAbsent(pos,port::readFrame);return pending.isDone()&&!pending.isCompletedExceptionally()?pending.getNow(null):null;
    }
    private boolean present(UUID id,NativePickup.Chunk chunk) {
        var e=level.getEntity(id);return e!=null && !e.isRemoved() && e.chunkPosition().x()==chunk.x() && e.chunkPosition().z()==chunk.z();
    }
    private NativePickup.Observation observeSaved(NativePickup.Entry entry) {
        check();if(entry.result()==null)return null;var r=entry.result();var intent=entry.intent();
        var actor=harvest.actorWitness(r.actor());var source=harvest.sourceWitness(r.source());
        var drop=drop(intent.dropTrail().entity());NativeHarvest.DropWitness dw=null;
        if(drop!=null) {
            var state=((DepositParticipant)drop).cognitivecraft$depositParticipant();
            try{dw=new NativeHarvest.DropWitness(harvest.dropSnapshot(drop,false),state.marker(),state.restored());}catch(RuntimeException invalid){return null;}
        }
        var members=new ArrayList<NativePickup.MembershipWitness>();NativePickup.TrailWitness at=null,dt=null;
        for(var member:NativePickup.members(intent)) {
            var c=member.chunk();if(level.getChunkSource().getChunkNow(c.x(),c.z())==null)return null;
            var f=frame(c);if(f==null || !f.supported())return null;
            var state=EntityChunkMembershipState.load(f.metadata(),c.dimension(),f.position(),f.members());var row=state.row(member.entity());if(row==null)return null;
            var witness=new NativePickup.TrailWitness(row.trail(),row.marker(),true);
            if(member.role()==NativePickup.Role.ACTOR){if(at!=null&&!at.equals(witness))return null;at=witness;}else{if(dt!=null&&!dt.equals(witness))return null;dt=witness;}
            members.add(new NativePickup.MembershipWitness(member,row.trail().incarnation(),row.revision(),row.marker(),true,f.members().contains(member.entity()),present(member.entity(),c),f.digest()));
        }
        return new NativePickup.Observation(actor,source,dw,at,dt,members);
    }
    public PickupEvidenceStore.Code verify(PickupEvidenceStore.Reconciled proof){check();var code=store.verify(proof);if(code==PickupEvidenceStore.Code.RESOLVED)observer.accept(Point.VERIFICATION_ACKNOWLEDGED);return code;}
    public boolean blocked(ValidatedRequest request,Effect effect,SurvivalGateway.Cell target) {
        check();if(effect==Effect.MOVE || effect==Effect.OBSERVE)return false;if(store.fenced())return true;
        var value=request.request().arguments().get("actor");if(!(value instanceof ActorValue actor))return false;
        for(var e:store.snapshot().entries())if(e.verification()==null && e.intent().harvest().intent().actor().equals(actor.value())) {
            boolean own=tickets.stream().anyMatch(t->t.intent==e.intent() && !t.forgotten && t.request.bound().equals(request) && effect==Effect.PICKUP && t.request.target().equals(target));
            if(!own)return true;
        }
        return false;
    }
}
