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
    // NativeTicket has identity equality. The fixed eight-ticket limit permits a
    // preallocated identity list without invoking VM identity hashing in capture.
    private final List<NativeTicket> tickets=new ArrayList<>(8);
    private EntityStorageEvidence nativeStorage;
    private final Map<ChunkPos,CompletableFuture<EntityChunkRecord.Frame>> frames=new HashMap<>();
    private final Map<ChunkPos,Long> frameRevisions=new HashMap<>();
    private Consumer<Point> observer=p->{};
    private record LivePickup(FabricHarvestProtocol.PickupLineage lineage,NativePickup.Intent intent,
                              NativePickup.Result result,Marker marker) { }
    private final Map<String,LivePickup> live=new HashMap<>();
    private String descendant="";
    private static final List<String> CAPTURE_PHASES=List.of("owner","original-harvest","native-lineage","allowance","intent","storage-and-ticket");
    private final CaptureThreadTime captureThreadTime=new CaptureThreadTime();
    private final ArrayDeque<Long> captureNanos=new ArrayDeque<>();
    private final class NativeTicket implements Ticket {
        final Request request;final Action action;final FabricHarvestProtocol.PickupLineage lineage;final NativePickup.Intent intent;final Reason failure;
        final Jobs.Job authority;
        final List<NativePickup.Chunk> chunks;
        boolean acknowledged,forgotten,joinedRoomChecked;Marker actorPredecessor;
        NativeTicket(Request r,Action a,FabricHarvestProtocol.PickupLineage l,NativePickup.Intent i,Reason f,Jobs.Job authority,List<NativePickup.Chunk> chunks){
            request=r;action=a;lineage=l;intent=i;failure=f;this.authority=authority;this.chunks=chunks;
        }
    }
    public FabricPickupProtocol(ServerLevel level,JobLifecycleStore jobs,ResourceLeaseService leases,FabricHarvestProtocol harvest,
                                PickupJournal journal,PickupJournal.Snapshot initial,boolean readOnly,Executor worker,Clock clock,boolean enabled) {
        this.level=Objects.requireNonNull(level);this.jobs=Objects.requireNonNull(jobs);this.leases=Objects.requireNonNull(leases);
        this.harvest=Objects.requireNonNull(harvest);this.clock=Objects.requireNonNull(clock);this.enabled=enabled;
        // Registration is stable for this exact ServerLevel. Bind during owner
        // startup; do not acquire the global registry monitor in normal captures.
        nativeStorage=NativeEntityStorage.get(level);
        NativePickup.initializeCaptureSchema();
        NativeCaptureSchema.link(NativeTicket.class,DepositLineage.class);
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
    @Override public boolean waitingOnPublication() {
        check();
        return jobs.productionPlanningReady() && !store.fenced() && !harvest.evidence().fenced()
                && (store.pending() || harvest.evidence().pending() || jobs.unavailableReason()==Reason.BUDGET_EXHAUSTED
                    || leases.failure()==null && !leases.ready() || deposits!=null && !deposits.fenced() && deposits.pending());
    }
    private EntityStorageEvidence nativeStorage(){
        // Preserve late registration/unavailability behavior without caching availability.
        if(nativeStorage==null)nativeStorage=NativeEntityStorage.get(level);
        return nativeStorage;
    }
    public PickupEvidenceStore evidence(){check();return store;}
    public void observe(Consumer<Point> observer){check();this.observer=Objects.requireNonNull(observer);}
    public List<Long> captureNanos(){check();return List.copyOf(captureNanos);}
    public List<Long> captureCpuNanos(){check();return captureThreadTime.nanos();}
    public List<String> capturePhaseNames(){return CAPTURE_PHASES;}
    public List<List<Long>> capturePhaseNanos(){check();return captureThreadTime.phaseNanos();}
    public void tick(){check();store.tick();}
    public void close(){check();closed=true;tickets.clear();frames.clear();live.clear();restoredPickups.clear();}
    @Override public Ticket begin(Request request,Action action) {
        return begin(request,action,action::current);
    }
    @Override public Ticket begin(Request request,Action action,BooleanSupplier preflight) {
        check();long cpuStarted=captureThreadTime.start();long began=System.nanoTime();
        long[] phases=new long[CAPTURE_PHASES.size()];
        try {
            if(closed || !enabled || store.fenced() || store.pending() || tickets.size()==8)throw new IllegalStateException("Pickup unavailable");
            var owner=new JobLeaseOwners(jobs,clock).byRun(request.bound(),request.correlation());
            var job=jobs.query(owner.job(),request.bound().context());var attempt=job.current();
            if(!jobs.ready() || !leases.ready() || attempt==null || !attempt.open() || jobs.stopPending(job.id(),job.generation(),job.origin()))throw new IllegalStateException("Original owner stopping");
            phases[0]=System.nanoTime();
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
            phases[1]=System.nanoTime();
            var lineage=harvest.preparePickupLineage(harvest.evidence().acknowledged(h.intent().key()),preflight);
            if(lineage==null)throw new IllegalStateException("Original pickup lineage");
            phases[2]=System.nanoTime();
            var claims=new ArrayList<Claim>();
            for(var claim:leases.snapshot().leases())if(claim.owner().equals(owner) && claim.origin().equals(job.origin()) && claim.state()==ResourceLeases.State.ACTIVE && claim.expires()>level.getGameTime())
                claims.add(new Claim(claim.id(),claim.epoch(),claim.group(),claim.generation()));
            long deadline=Math.min(job.allowance().deadlineEpochMillis(),attempt.allowance().deadlineEpochMillis());
            if(request.deadline()>deadline || request.effectsRemaining()>attempt.allowance().maximum(Budgets.Kind.COMMITTED_EFFECTS)-request.usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L))throw new IllegalStateException("Pickup allowance");
            var conditions=new Conditions(request.actor().citizenId(),job.revision(),deadline,request.deadline(),request.effectsRemaining(),level.getGameTime(),2500,
                    lineage.reachMilli(),claims,request.usage());
            phases[3]=System.nanoTime();
            var k=h.intent().key();
            var intent=new NativePickup.Intent(new Key(k.world(),k.root(),k.step(),k.attempt(),k.generation(),2),h,request.operation(),request.expected(),conditions,lineage.actorTrail(),lineage.dropTrail());
            phases[4]=System.nanoTime();
            var port=nativeStorage();if(port==null || !port.available())throw new IllegalStateException("Native entity storage unavailable");
            var touched=new LinkedHashSet<>(lineage.actorTrail().chunks());touched.addAll(lineage.dropTrail().chunks());
            var chunks=List.copyOf(touched);
            var ticket=new NativeTicket(request,action,lineage,intent,null,job,chunks);tickets.add(ticket);
            phases[5]=System.nanoTime();
            return ticket;
        }catch(RuntimeException refused){
            traceRefusal(request,"capture",refused.getMessage());
            return new NativeTicket(request,action,null,null,Reason.INTERRUPTED,null,List.of());
        }
        finally{
            if(captureNanos.size()==128)captureNanos.removeFirst();
            captureNanos.addLast(System.nanoTime()-began);
            captureThreadTime.record(cpuStarted,began,phases);
        }
    }
    public Progress poll(Ticket value) {
        check();if(!(value instanceof NativeTicket ticket) || ticket.forgotten || closed)return failed();
        if(ticket.failure!=null || !tickets.contains(ticket))return failed();
        // Start each required read once the capture has produced the full chunk set.
        boolean loading=false;
        for(var chunk:ticket.chunks) {
            var frame=frame(chunk);
            if(frame==null)loading=true;
            else if(!frame.supported()){
                traceRefusal(ticket.request,"membership", "Actual native chunk frame is unsupported");
                return failed();
            }
        }
        if(loading)return new Progress(Code.PENDING,null);
        try {
            ticket.actorPredecessor=actorPredecessor(ticket);
            if(!ticket.joinedRoomChecked && ticket.authority.activity()!=null && ticket.authority.activity().production()!=null) {
                var actorTrail=ticket.intent.actorTrail();
                var port=Objects.requireNonNull(nativeStorage());
                for(var chunk:actorTrail.chunks()) {
                    var pos=new ChunkPos(chunk.x(),chunk.z());
                    var saved=frame(chunk);
                    if(saved==null || !port.roomForJoinedEffects(pos,actorTrail.entity(),saved,frameRevisions.get(pos)))
                        return new Progress(Code.PENDING,null);
                }
                ticket.joinedRoomChecked=true;
            }
        } catch(RuntimeException unprovedSuccessor){
            traceRefusal(ticket.request,"predecessor",unprovedSuccessor.getMessage());
            return failed();
        }
        store.tick();PickupEvidenceStore.Status status;
        try {
            status=store.prepare(ticket.intent,ticket.lineage.acknowledged());
            if(status.code()==PickupEvidenceStore.Code.PREPARED) {
                if(!jobs.ready() || !leases.ready())return new Progress(Code.PENDING,null);
                status=store.perform(status.prepared());
            }
        }catch(RuntimeException unknown){
            traceRefusal(ticket.request,"publication",unknown.getMessage());
            return new Progress(Code.UNRESOLVED,Reason.INTERRUPTED);
        }
        return switch(status.code()) {
            case PENDING,PREPARED->new Progress(Code.PENDING,null);
            case RESULT_ACKNOWLEDGED->{
                var entry=store.snapshot().entries().stream().filter(e->e.intent().equals(ticket.intent)).findFirst().orElseThrow();
                if(entry.result().live()!=NativePickup.Live.COMPLETE && entry.result().live()!=NativePickup.Live.NO_EFFECT)yield new Progress(Code.UNRESOLVED,Reason.INTERRUPTED);
                if(!ticket.acknowledged){ticket.acknowledged=true;observer.accept(Point.RESULT_ACKNOWLEDGED);}yield new Progress(Code.ACKNOWLEDGED,null);
            }
            default->{traceRefusal(ticket.request,"journal",status.code().name());yield failed();}
        };
    }
    private static void traceRefusal(Request request,String phase,String reason) {
        if(Boolean.getBoolean("cognitivecraft.navigation.trace"))
            System.out.println("CognitiveCraft PICKUP refusal run="+request.correlation().runId()+" stage="+phase+" reason="+reason);
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
        live.put(intent.key().token(),new LivePickup(ticket.lineage,intent,result,marker));
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
        var port=Objects.requireNonNull(NativeEntityStorage.get(level));
        for(var c:intent.actorTrail().chunks()) {
            var f=Objects.requireNonNull(frame(c));
            if(!f.supported())throw new IllegalStateException("Unsupported prior membership");
            // A completed native deposit can still be staged ahead of the saved frame.
            // Select that actual live declaration, then retain every original-delivery guard below.
            var staged=port.stagedMembership(new ChunkPos(c.x(),c.z()),intent.actorTrail().entity());
            var row=staged==null?f.membership(intent.actorTrail().entity()):staged;if(row==null)continue;
            if(row.marker().effect().equals(intent.key().token()))continue;
            if(deposits==null || deposits.fenced() || deposits.pending())throw new IllegalStateException("Unproved prior actor custody");
            var match=deposits.snapshot().entries().stream().filter(e->e.intent().pickupPrefix()!=null && deposits.settled(e)
                    && (e.verification()==null || e.verification().outcome()==Reconciliation.SAVED_COMPLETE) && e.result()!=null && e.result().live()==NativeDeposit.Live.COMPLETE
                    && (e.intent().key().step().equals(intent.key().step()) && e.intent().key().generation()+1==intent.key().generation()
                        && e.result().actor().equals(before) || closedSavedPredecessor(e,intent,before))
                    && row.role()==NativePickup.Role.ACTOR && row.revision()==e.result().actor().revision()
                    && row.trail().incarnation().equals(before.incarnation()) && intent.actorTrail().chunks().containsAll(row.trail().chunks())
                    && row.marker().equals(new Marker(e.token(),e.intent().digest(),MarkerPhase.RESULT,e.result().digest()))).findFirst();
            if(match.isEmpty() || predecessor!=null && !predecessor.equals(row.marker()))throw new IllegalStateException("Unproved prior actor delivery");predecessor=row.marker();
        }
        return predecessor;
    }
    private boolean closedSavedPredecessor(NativeDeposit.Entry prior, NativePickup.Intent next, Participant before) {
        if (!jobs.productionPlanningReady() || prior.verification() == null
                || prior.verification().outcome() != Reconciliation.SAVED_COMPLETE
                || !prior.intent().origin().equals(next.harvest().intent().origin())
                || !prior.result().actor().incarnation().equals(before.incarnation())
                || prior.result().actor().revision() > before.revision()) return false;
        try {
            var current = jobs.query(next.key().root(), prior.intent().origin());
            if (current.activity() == null || current.activity().production() == null) return false;
            var job = jobs.query(prior.intent().key().root(), prior.intent().origin());
            var attempt = job.attempts().stream().filter(a -> a.id().equals(prior.intent().key().attempt())
                    && a.generation() == prior.intent().key().generation()).findFirst().orElse(null);
            return job.state() == Jobs.State.SUCCEEDED && attempt != null && !attempt.open() && !attempt.uncertain()
                    && attempt.resourceReceipts().equals(List.of(prior.intent().pickupPrefix().pickup().intent().harvest().result().receipt(),
                            prior.intent().pickupPrefix().pickup().result().receipt(), prior.result().receipt()));
        } catch (RuntimeException unavailable) {
            return false;
        }
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
        private final PickupEvidenceStore.SavedPickup saved;
        private DepositLineage(FabricPickupProtocol owner,PickupEvidenceStore.Acknowledged pickup,FabricHarvestProtocol.PickupLineage harvest,NativeJoinedDeposit.Prefix prefix) {
            this.owner=owner;this.pickup=pickup;this.harvest=harvest;this.prefix=prefix;this.saved=null;
        }
        private DepositLineage(FabricPickupProtocol owner, PickupEvidenceStore.SavedPickup saved, NativeJoinedDeposit.Prefix prefix) {
            this.owner=owner;this.saved=saved;this.prefix=prefix;this.pickup=null;this.harvest=null;
        }
        NativeJoinedDeposit.Prefix prefix(){return prefix;}
    }
    private final Map<UUID, PickupEvidenceStore.SavedPickup> restoredPickups = new HashMap<>();

    private Key originalPickupKey(Jobs.Job job) {
        var attempt = job.current();
        var step = job.activity() == null ? null : job.activity().current();
        if (!jobs.productionPlanningReady() || attempt == null || !attempt.open() || step == null || !step.integrated()
                || !LogAcquisition.is(attempt.bound().request().capability()) || step.quantity() != 1
                || step.assignmentExpiresTick() == null || level.getGameTime() >= step.assignmentExpiresTick()
                || clock.millis() >= attempt.allowance().deadlineEpochMillis() || job.cancellationOutcome() != null
                || attempt.executions().size() != 1 || !attempt.executions().getFirst().runId().equals(attempt.id())) return null;
        return new Key(job.origin().scope().worldId(), job.id(), job.id(), attempt.id(), job.generation(), 2);
    }

    /** Classify the original H/P save before offering the existing Jobs owner a way to deliver its unit. */
    public Jobs.StoppedObservation savedContinuation(Jobs.StoppedContext original) {
        check();
        var job = jobs.query(original.job(), original.origin());
        var key = originalPickupKey(job);
        if (key == null || job.state() != Jobs.State.INTERRUPTED || !original.equals(Jobs.StoppedContext.of(job))) return null;
        var view = store.reconcile(key);
        if (view == null) return null;
        if (view.result() != NativePickup.Reconciliation.SAVED_COMPLETE)
            return Jobs.StoppedObservation.ambiguous("Original pickup has no complete saved actor, source and membership cohort");
        if (view.entry().verification() == null && verify(view) != PickupEvidenceStore.Code.RESOLVED)
            return Jobs.StoppedObservation.ambiguous("Waiting for the original saved pickup acknowledgement");
        var saved = store.savedPickup(key);
        if (saved == null) return Jobs.StoppedObservation.ambiguous("Original saved pickup is no longer current");
        var prefix = prefixReceipts(saved.entry());
        var usage = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class);
        usage.putAll(original.original().usage());
        saved.entry().result().usage().forEach((kind, count) -> usage.merge(kind, count, Math::max));
        return new Jobs.StoppedObservation(Jobs.StoppedKind.CONTINUABLE,
                new Jobs.Report(original.attempt(), original.generation(), 2, usage, List.of(), null, prefix),
                "Original saved harvest and pickup agree. Only their original delivery may continue.");
    }

    private static List<ResourceDelivery.Receipt> prefixReceipts(NativePickup.Entry entry) {
        return List.of(entry.intent().harvest().result().receipt(), entry.result().receipt());
    }

    /** Retained terrain and drop tombstones of an earlier delivery in the same original activity. */
    public boolean restoredDeliveryRemainders(NativeDeposit.Entry delivery) {
        check();
        var prefix = delivery.intent().pickupPrefix();
        if (prefix == null || delivery.result() == null || delivery.result().live() != NativeDeposit.Live.COMPLETE
                || store.fenced() || store.pending() || harvest.evidence().fenced() || harvest.evidence().pending()) return false;
        var pickup = prefix.pickup();
        if (!store.snapshot().entries().contains(pickup) || !harvest.evidence().snapshot().entries().contains(pickup.intent().harvest())) return false;
        var expected = new Marker(delivery.token(), delivery.intent().digest(), MarkerPhase.RESULT, delivery.result().digest());
        var source = harvest.sourceWitness(pickup.result().source());
        var trail = pickup.intent().dropTrail();
        if (source == null || !source.restoredFromNativeSave() || !source.source().equals(pickup.result().source())
                || !expected.equals(source.marker()) || level.getEntity(trail.entity()) != null) return false;
        var drop = pickup.intent().harvest().result().drops().getFirst();
        for (var section : trail.chunks()) {
            if (level.getChunkSource().getChunkNow(section.x(), section.z()) == null) return false;
            var frame = frame(section);
            if (frame == null || !frame.supported() || frame.members().contains(trail.entity())) return false;
            var row = frame.membership(trail.entity());
            if (row == null || row.role() != NativePickup.Role.DROP || row.present() || !row.trail().equals(trail)
                    || row.revision() != drop.revision() || !expected.equals(row.marker())) return false;
        }
        return true;
    }

    public boolean savedPickupFollows(Jobs.Job job, NativeDeposit.Entry predecessor) {
        check();
        var key = originalPickupKey(job);
        if (key == null) return false;
        var proof = store.savedPickup(key);
        return proof != null && proof.entry().intent().harvest().intent().actorBefore().equals(predecessor.result().actor())
                && key.generation() == predecessor.intent().key().generation() + 1;
    }

    @Override public List<ResourceDelivery.Receipt> restoreForDeposit(ValidatedRequest bound, RunCorrelation correlation) {
        check();
        try {
            if (closed || !enabled || restoredPickups.size() >= 8 || restoredPickups.containsKey(correlation.runId())) return List.of();
            var job = new JobLeaseOwners(jobs, clock).execution(bound, correlation);
            var key = originalPickupKey(job);
            if (key == null || job.state() != Jobs.State.ACTIVE || job.current().uncertain()
                    || !Integer.valueOf(2).equals(job.activity().current().savedPrefixEffects())) return List.of();
            var saved = store.savedPickup(key);
            if (saved == null) return List.of();
            var prefix = prefixReceipts(saved.entry());
            if (!job.current().resourceReceipts().equals(prefix)) return List.of();
            restoredPickups.put(correlation.runId(), saved);
            return prefix;
        } catch (RuntimeException unavailable) { return List.of(); }
    }
    @Override public void releaseRestoredPickup(UUID run) { check(); restoredPickups.remove(run); }

    DepositLineage prepareDeposit(DepositProtocol.Request request,BooleanSupplier preflight) {
        check();if(closed || !enabled || store.fenced() || store.pending() || !descendant.isEmpty())return null;
        NativePickup.Entry p=null;
        for(var entry:store.snapshot().entries()) {
            if(!entry.intent().harvest().intent().run().equals(request.correlation().runId()))continue;
            if(p!=null)return null;
            p=entry;
        }
        if(p==null)return null;
        String token=p.token();
        var saved = restoredPickups.get(request.correlation().runId());
        if (saved != null) {
            if (!saved.entry().equals(p) || !store.ownsSavedPickup(saved) || !request.receiptPrefix().equals(prefixReceipts(p))) return null;
            var trail = p.verification().observation().actorTrail().trail();
            var actor = actor(request.actor());
            var source = harvest.sourceWitness(p.result().source());
            if (actor == null || !present(actor.getUUID(), trail.current()) || source == null || !source.source().equals(p.result().source())
                    || !FabricHarvestProtocol.matchesResultMarker(source.marker(), token, p.intent().digest(), p.result().digest())
                    || level.getEntity(p.intent().dropTrail().entity()) != null
                    || !harvest.actorMatches(actor, p.result().actor(), source.marker(), true) || !preflight.getAsBoolean()) return null;
            return new DepositLineage(this, saved, new NativeJoinedDeposit.Prefix(p, trail));
        }
        var committed=live.get(token);var acknowledged=store.acknowledged(p.intent().key());
        if(committed==null || !committed.intent().equals(p.intent()) || !committed.result().equals(p.result())
                || acknowledged==null || !p.intent().harvest().intent().actor().equals(request.actor())
                || !request.receiptPrefix().equals(List.of(p.intent().harvest().result().receipt(),p.result().receipt())))return null;
        var entity=level.getEntity(request.actor().entityId());var source=harvest.sourceWitness(p.result().source());
        if(!(entity instanceof Villager actor) || source==null || !source.source().equals(p.result().source())
                || !committed.marker().equals(source.marker())
                || level.getEntity(p.intent().dropTrail().entity())!=null
                || !harvest.actorMatches(actor,p.result().actor(),source.marker(),true))return null;
        descendant=token;
        try {
            var link=committed.lineage();
            var prefix=harvest.prepareDepositLineage(link,p,preflight);
            return prefix==null?null:new DepositLineage(this,acknowledged,link,prefix);
        }finally{descendant="";}
    }
    boolean currentDeposit(DepositLineage lineage) {
        check();
        if (closed || lineage == null || lineage.owner != this) return false;
        if (lineage.saved != null) {
            var entry = lineage.saved.entry();
            var source = harvest.sourceWitness(entry.result().source());
            return restoredPickups.get(entry.intent().harvest().intent().run()) == lineage.saved && store.ownsSavedPickup(lineage.saved)
                    && present(lineage.prefix.actorTrail().entity(), lineage.prefix.actorTrail().current())
                    && source != null && source.source().equals(entry.result().source())
                    && FabricHarvestProtocol.matchesResultMarker(source.marker(), entry.token(), entry.intent().digest(), entry.result().digest())
                    && level.getEntity(entry.intent().dropTrail().entity()) == null;
        }
        return store.owns(lineage.pickup) && NativePickup.extendsTrail(harvest.actorTrail(lineage.harvest),lineage.prefix.actorTrail());
    }
    <T> T depositScope(DepositLineage lineage,java.util.function.Supplier<T> action) {
        check();if(!currentDeposit(lineage) || !descendant.isEmpty())throw new IllegalStateException("Original P/D scope");
        if (lineage.saved != null) return action.get();
        descendant=lineage.pickup.entry().token();try{return harvest.descendantScope(lineage.harvest,action);}finally{descendant="";}
    }
    void deposited(DepositLineage lineage,NativeDeposit.Intent intent,NativeDeposit.Result result) {
        check();if(!currentDeposit(lineage) || !lineage.prefix.equals(intent.pickupPrefix()))throw new IllegalStateException("Original P/D lineage changed");
        var p=lineage.prefix.pickup();var actor=Objects.requireNonNull(actor(p.intent().harvest().intent().actor()));
        var marker=new Marker(intent.key().token(),intent.digest(),MarkerPhase.RESULT,result.digest());
        var previous=new Marker(p.token(),p.intent().digest(),MarkerPhase.RESULT,p.result().digest());
        mark(p.intent(),actor,null,marker);
        var trail = lineage.saved == null ? harvest.actorTrail(lineage.harvest) : lineage.prefix.actorTrail();
        stage(trail,NativePickup.Role.ACTOR,result.actor().revision(),marker,true,previous);
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
        for(var e:store.snapshot().entries()) {
            if(!e.intent().harvest().intent().actor().equals(actor.value())
                    || !descendant.isEmpty() && e.token().equals(descendant) || store.settled(e))continue;
            boolean own=false;
            if(effect==Effect.PICKUP)for(var ticket:tickets) {
                if(ticket.intent==e.intent() && !ticket.forgotten && ticket.request.bound().equals(request) && ticket.request.target().equals(target)){
                    own=true;
                    break;
                }
            }
            if(!own)return true;
        }
        return false;
    }
}
