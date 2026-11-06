package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.Reason;
import dev.aivillages.core.kernel.runtime.evidence.*;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
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
    private final PickupEvidenceStore store;private DepositEvidenceStore deposits;private boolean closed;
    public void attachDeposit(DepositEvidenceStore deposits){check();if(this.deposits!=null)throw new IllegalStateException("Deposit successor already attached");this.deposits=Objects.requireNonNull(deposits);}
    private final Set<NativeTicket> tickets=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ChunkPos,CompletableFuture<EntityChunkRecord.Frame>> frames=new HashMap<>();
    private final Map<ChunkPos,Long> frameRevisions=new HashMap<>();
    private Consumer<Point> observer=p->{};
    private final Map<String,FabricHarvestProtocol.PickupLineage> live=new HashMap<>();
    private String descendant="";
    private final CaptureThreadTime captureThreadTime=new CaptureThreadTime();
    private final ArrayDeque<Long> captureNanos=new ArrayDeque<>();
    private final class NativeTicket implements Ticket {
        final Request request;final Action action;final FabricHarvestProtocol.PickupLineage lineage;final NativePickup.Intent intent;final Reason failure;
        final Jobs.Job authority;
        final List<NativePickup.Chunk> chunks;
        boolean acknowledged,forgotten;Marker actorPredecessor;
        NativeTicket(Request r,Action a,FabricHarvestProtocol.PickupLineage l,NativePickup.Intent i,Reason f,Jobs.Job authority,List<NativePickup.Chunk> chunks){
            request=r;action=a;lineage=l;intent=i;failure=f;this.authority=authority;this.chunks=chunks;
        }
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
    public List<Long> captureCpuNanos(){check();return captureThreadTime.nanos();}
    public void tick(){check();store.tick();}
    public void close(){check();closed=true;tickets.clear();frames.clear();live.clear();}
    @Override public Ticket begin(Request request,Action action) {
        return begin(request,action,action::current);
    }
    @Override public Ticket begin(Request request,Action action,BooleanSupplier preflight) {
        check();long cpuStarted=captureThreadTime.start();long began=System.nanoTime();
        try {
            if(closed || !enabled || store.fenced() || store.pending() || tickets.size()==8)throw new IllegalStateException("Pickup unavailable");
            var owner=new JobLeaseOwners(jobs,clock).byRun(request.bound(),request.correlation());
            var job=jobs.query(owner.job(),request.bound().context());var attempt=job.current();
            if(!jobs.ready() || !leases.ready() || attempt==null || !attempt.open() || jobs.stopPending(job.id(),job.generation(),job.origin()))throw new IllegalStateException("Original owner stopping");
            NativeHarvest.Entry h=null;
            for(var entry:harvest.evidence().snapshot().entries()) {
                var key=entry.intent().key();
                if(!key.step().equals(job.id()) || !key.attempt().equals(attempt.id()) || key.generation()!=attempt.generation())continue;
                if(h!=null)throw new IllegalStateException("Original first-H required");
                h=entry;
            }
            if(h==null)throw new IllegalStateException("Original first-H required");
            if(!h.intent().run().equals(request.correlation().runId()) || !h.intent().actor().equals(request.actor())
                    || !h.intent().item().equals(request.item().id()) || h.result()==null || h.result().drops().size()!=1)
                throw new IllegalStateException("Original pickup custody");
            var output=h.result().drops().getFirst();
            if(!output.entity().equals(request.drop()) || output.quantity()!=request.expected())
                throw new IllegalStateException("Original pickup output");
            var lineage=harvest.preparePickupLineage(harvest.evidence().acknowledged(h.intent().key()),preflight);
            if(lineage==null)throw new IllegalStateException("Original pickup lineage");
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
            var touched=new LinkedHashSet<>(lineage.actorTrail().chunks());touched.addAll(lineage.dropTrail().chunks());
            var chunks=List.copyOf(touched);
            for(var chunk:chunks)frame(chunk);
            var ticket=new NativeTicket(request,action,lineage,intent,null,job,chunks);tickets.add(ticket);return ticket;
        }catch(RuntimeException refused){return new NativeTicket(request,action,null,null,Reason.INTERRUPTED,null,List.of());}
        finally{
            if(captureNanos.size()==128)captureNanos.removeFirst();
            captureNanos.addLast(System.nanoTime()-began);
            captureThreadTime.record(cpuStarted);
        }
    }
    public Progress poll(Ticket value) {
        check();if(!(value instanceof NativeTicket ticket) || ticket.forgotten || closed)return failed();
        if(ticket.failure!=null || !tickets.contains(ticket))return failed();
        for(var chunk:ticket.chunks) {
            var frame=frame(chunk);if(frame==null)return new Progress(Code.PENDING,null);
            if(!frame.supported())return failed();
        }
        try{ticket.actorPredecessor=actorPredecessor(ticket);}catch(RuntimeException unprovedSuccessor){return failed();}
        store.tick();PickupEvidenceStore.Status status;
        try {
            status=store.prepare(ticket.intent,ticket.lineage.acknowledged());
            if(status.code()==PickupEvidenceStore.Code.PREPARED) {
                if(!jobs.ready() || !leases.ready())return new Progress(Code.PENDING,null);
                status=store.perform(status.prepared());
            }
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
        if(!LiveAttemptAuthority.unchanged(ticket.authority,job) || attempt==null || !attempt.open() || !attempt.id().equals(intent.key().attempt())
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
        stage(intent.actorTrail(),NativePickup.Role.ACTOR,result.actor().revision(),marker,true,ticket.actorPredecessor);
        stage(intent.dropTrail(),NativePickup.Role.DROP,result.remaining()==null?h.result().drops().getFirst().revision():result.remaining().revision(),marker,result.remaining()!=null,null);
        live.put(intent.key().token(),ticket.lineage);
        ((EntityMembershipParticipant)actor).cognitivecraft$membershipData().result(marker,result.actor().revision(),true);
        if(remaining!=null)((EntityMembershipParticipant)remaining).cognitivecraft$membershipData().result(marker,result.remaining().revision(),true);
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
        long revision=port.revision(pos);if(!Objects.equals(frameRevisions.get(pos),revision)){frames.remove(pos);frameRevisions.put(pos,revision);}
        var pending=frames.computeIfAbsent(pos,port::readFrame);return pending.isDone()&&!pending.isCompletedExceptionally()?pending.getNow(null):null;
    }
    /** A later bounded attempt may replace only its own acknowledged delivered-unit membership. */
    private Marker actorPredecessor(NativeTicket ticket) {
        Marker predecessor=null;var intent=ticket.intent;var before=intent.harvest().intent().actorBefore();
        for(var c:intent.actorTrail().chunks()) {
            var f=Objects.requireNonNull(frame(c));
            if(!f.supported())throw new IllegalStateException("Unsupported prior membership");var row=f.membership(intent.actorTrail().entity());if(row==null)continue;
            if(row.marker().effect().equals(intent.key().token()))continue;
            if(deposits==null || deposits.fenced() || deposits.pending())throw new IllegalStateException("Unproved prior actor custody");
            var match=deposits.snapshot().entries().stream().filter(e->e.intent().pickupPrefix()!=null && e.verification()!=null
                    && e.verification().outcome()==Reconciliation.SAVED_COMPLETE && e.result()!=null && e.result().live()==NativeDeposit.Live.COMPLETE
                    && e.intent().key().step().equals(intent.key().step()) && e.intent().key().generation()+1==intent.key().generation()
                    && e.result().actor().equals(before) && row.role()==NativePickup.Role.ACTOR && row.revision()==before.revision()
                    && row.trail().incarnation().equals(before.incarnation()) && intent.actorTrail().chunks().containsAll(row.trail().chunks())
                    && row.marker().equals(new Marker(e.token(),e.intent().digest(),MarkerPhase.RESULT,e.result().digest()))).findFirst();
            if(match.isEmpty() || predecessor!=null && !predecessor.equals(row.marker()))throw new IllegalStateException("Unproved prior actor delivery");predecessor=row.marker();
        }
        return predecessor;
    }
    private boolean present(UUID id,NativePickup.Chunk chunk) {
        var e=level.getEntity(id);return e!=null && !e.isRemoved() && e.chunkPosition().x()==chunk.x() && e.chunkPosition().z()==chunk.z();
    }
    private NativePickup.Observation observeSaved(NativePickup.Entry entry) {
        check();if(entry.result()==null)return null;
        return observeCohort(entry.result().actor(),entry.result().source(),entry.intent().actorTrail(),entry.intent().dropTrail());
    }
    private NativePickup.Trail savedTrail(NativePickup.Trail original) {
        var f=frame(original.chunks().getFirst());if(f==null || !f.supported())return null;
        var row=f.membership(original.entity());
        return row==null?null:row.trail();
    }
    private NativePickup.Observation observeCohort(Participant expectedActor,NativeHarvest.Source expectedSource,NativePickup.Trail actorTrail,NativePickup.Trail dropTrail) {
        var actor=harvest.actorWitness(expectedActor);var source=harvest.sourceWitness(expectedSource);
        var drop=drop(dropTrail.entity());NativeHarvest.DropWitness dw=null;
        if(drop!=null) {
            var state=((DepositParticipant)drop).cognitivecraft$depositParticipant();
            try{dw=new NativeHarvest.DropWitness(harvest.dropSnapshot(drop,false),state.marker(),state.restored());}catch(RuntimeException invalid){return null;}
        }
        actorTrail=savedTrail(actorTrail);dropTrail=savedTrail(dropTrail);if(actorTrail==null || dropTrail==null)return null;
        var refs=new ArrayList<NativePickup.Member>();
        for(var c:actorTrail.chunks())refs.add(new NativePickup.Member(NativePickup.Role.ACTOR,actorTrail.entity(),c));
        for(var c:dropTrail.chunks())refs.add(new NativePickup.Member(NativePickup.Role.DROP,dropTrail.entity(),c));
        var members=new ArrayList<NativePickup.MembershipWitness>();NativePickup.TrailWitness at=null,dt=null;
        for(var member:refs) {
            var c=member.chunk();if(level.getChunkSource().getChunkNow(c.x(),c.z())==null)return null;
            var f=frame(c);if(f==null || !f.supported())return null;
            var row=f.membership(member.entity());if(row==null)return null;
            var witness=new NativePickup.TrailWitness(row.trail(),row.marker(),true);
            if(member.role()==NativePickup.Role.ACTOR){if(at!=null&&!at.equals(witness))return null;at=witness;}else{if(dt!=null&&!dt.equals(witness))return null;dt=witness;}
            members.add(new NativePickup.MembershipWitness(member,row.trail().incarnation(),row.revision(),row.marker(),true,f.members().contains(member.entity()),present(member.entity(),c),f.digest()));
        }
        return new NativePickup.Observation(actor,source,dw,at,dt,members);
    }
    public static final class DepositLineage {
        private final FabricPickupProtocol owner;private final PickupEvidenceStore.Acknowledged pickup;
        private final FabricHarvestProtocol.PickupLineage harvest;private final NativeJoinedDeposit.Prefix prefix;
        private DepositLineage(FabricPickupProtocol owner,PickupEvidenceStore.Acknowledged pickup,FabricHarvestProtocol.PickupLineage harvest,NativeJoinedDeposit.Prefix prefix) {
            this.owner=owner;this.pickup=pickup;this.harvest=harvest;this.prefix=prefix;
        }
        NativeJoinedDeposit.Prefix prefix(){return prefix;}
    }
    DepositLineage captureDeposit(DepositProtocol.Request request) {
        check();if(closed || !enabled || store.fenced() || store.pending())return null;
        NativePickup.Entry p=null;
        for(var entry:store.snapshot().entries()) {
            if(!entry.intent().harvest().intent().run().equals(request.correlation().runId()))continue;
            if(p!=null)return null;
            p=entry;
        }
        if(p==null)return null;
        var link=live.get(p.token());var acknowledged=store.acknowledged(p.intent().key());
        if(link==null || acknowledged==null || !p.intent().harvest().intent().actor().equals(request.actor())
                || !request.receiptPrefix().equals(List.of(p.intent().harvest().result().receipt(),p.result().receipt())))return null;
        var actor=actor(request.actor());var source=harvest.sourceWitness(p.result().source());
        if(actor==null || source==null || !source.source().equals(p.result().source())
                || !FabricHarvestProtocol.matchesResultMarker(source.marker(),p.token(),p.intent().digest(),p.result().digest())
                || level.getEntity(p.intent().dropTrail().entity())!=null
                || !harvest.actorMatches(actor,p.result().actor(),source.marker(),true))return null;
        try{return new DepositLineage(this,acknowledged,link,new NativeJoinedDeposit.Prefix(p,harvest.actorTrail(link)));}catch(RuntimeException gap){return null;}
    }
    boolean currentDeposit(DepositLineage lineage) {
        check();return !closed && lineage!=null && lineage.owner==this && store.owns(lineage.pickup)
                && NativePickup.extendsTrail(harvest.actorTrail(lineage.harvest),lineage.prefix.actorTrail());
    }
    <T> T depositScope(DepositLineage lineage,java.util.function.Supplier<T> action) {
        check();if(!currentDeposit(lineage) || !descendant.isEmpty())throw new IllegalStateException("Original P/D scope");
        descendant=lineage.pickup.entry().token();try{return harvest.descendantScope(lineage.harvest,action);}finally{descendant="";}
    }
    void deposited(DepositLineage lineage,NativeDeposit.Intent intent,NativeDeposit.Result result) {
        check();if(!currentDeposit(lineage) || !lineage.prefix.equals(intent.pickupPrefix()))throw new IllegalStateException("Original P/D lineage changed");
        var p=lineage.pickup.entry();var actor=Objects.requireNonNull(actor(p.intent().harvest().intent().actor()));
        var marker=new Marker(intent.key().token(),intent.digest(),MarkerPhase.RESULT,result.digest());
        var previous=new Marker(p.token(),p.intent().digest(),MarkerPhase.RESULT,p.result().digest());
        mark(p.intent(),actor,null,marker);
        stage(harvest.actorTrail(lineage.harvest),NativePickup.Role.ACTOR,result.actor().revision(),marker,true,previous);
        stage(p.intent().dropTrail(),NativePickup.Role.DROP,p.intent().harvest().result().drops().getFirst().revision(),marker,false,previous);
        ((EntityMembershipParticipant)actor).cognitivecraft$membershipData().result(marker,result.actor().revision(),true);frames.clear();
    }
    NativePickup.Observation observeDeposit(NativeDeposit.Entry entry) {
        check();var prefix=entry.intent().pickupPrefix();if(prefix==null || store.fenced() || store.pending())return null;
        if(store.snapshot().entries().stream().noneMatch(e->e.equals(prefix.pickup())) || harvest.evidence().fenced() || harvest.evidence().pending()
                || harvest.evidence().snapshot().entries().stream().noneMatch(e->e.equals(prefix.pickup().intent().harvest())))return null;
        return observeCohort(entry.result()==null?entry.intent().actor():entry.result().actor(),prefix.pickup().result().source(),prefix.actorTrail(),prefix.pickup().intent().dropTrail());
    }
    public PickupEvidenceStore.Code verify(PickupEvidenceStore.Reconciled proof){check();var code=store.verify(proof);if(code==PickupEvidenceStore.Code.RESOLVED)observer.accept(Point.VERIFICATION_ACKNOWLEDGED);return code;}
    public boolean blocked(ValidatedRequest request,Effect effect,SurvivalGateway.Cell target) {
        check();if(effect==Effect.MOVE || effect==Effect.OBSERVE)return false;if(store.fenced())return true;
        var value=request.request().arguments().get("actor");if(!(value instanceof ActorValue actor))return false;
        for(var e:store.snapshot().entries())if(!store.settled(e) && !e.token().equals(descendant) && e.intent().harvest().intent().actor().equals(actor.value())) {
            boolean own=tickets.stream().anyMatch(t->t.intent==e.intent() && !t.forgotten && t.request.bound().equals(request) && effect==Effect.PICKUP && t.request.target().equals(target));
            if(!own)return true;
        }
        return false;
    }
}
