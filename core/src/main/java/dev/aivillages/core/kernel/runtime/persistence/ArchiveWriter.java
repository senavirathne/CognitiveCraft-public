package dev.aivillages.core.kernel.runtime.persistence;

import dev.aivillages.core.kernel.StrictJson;
import dev.aivillages.core.kernel.WorldRetentionManager;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Clock;
import java.util.*;
import java.util.function.BooleanSupplier;
import static dev.aivillages.core.kernel.runtime.persistence.ArchiveFormat.*;
import static dev.aivillages.core.kernel.runtime.persistence.PersistencePolicy.*;

/** Off-thread storage only. An acknowledged archive is not save-confirmed physical evidence. */
public final class ArchiveWriter implements AutoCloseable {
    public static final String RETENTION_OWNER="runtime-history";
    public static final int DEFAULT_RECORD_QUOTA=2048;
    public enum Point { BEFORE_SEGMENT, AFTER_SEGMENT_ACK, BEFORE_MANIFEST_STAGE, AFTER_MANIFEST_STAGE,
        AFTER_BACKUP, AFTER_MANIFEST_REPLACE, AFTER_DIRECTORY_ACK, BEFORE_CLEANUP, AFTER_CLEANUP }
    @FunctionalInterface public interface Faults { void at(Point point)throws IOException; static Faults none(){return point->{};} }
    @FunctionalInterface public interface Fences { Guard current(Owner owner); }
    public enum Status { ACKNOWLEDGED, DUPLICATE, ALREADY_SETTLED, BACKPRESSURE, STALE, FENCED }
    public static final class Acknowledgement {
        private final ArchiveWriter writer; private final Manifest manifest; private final String digest;
        private final Owner owner; private final Guard guard;
        private Acknowledgement(ArchiveWriter writer,Manifest manifest,String digest,Owner owner,Guard guard) {
            this.writer=writer;this.manifest=manifest;this.digest=digest;this.owner=owner;this.guard=guard;
        }
        public long revision(){return manifest.revision();} public String digest(){return digest;}
        public Owner owner(){return owner;} public Guard guard(){return guard;}
    }
    public record Result(Status status,Acknowledgement acknowledgement) {
        public boolean acknowledged(){return status==Status.ACKNOWLEDGED || status==Status.DUPLICATE;}
    }
    public static final class CleanupCandidate {
        private final ArchiveWriter writer; private final String digest,identity; private final long revision;
        private CleanupCandidate(ArchiveWriter writer,String digest,String identity,long revision) {
            this.writer=writer;this.digest=digest;this.identity=identity;this.revision=revision;
        }
        public WorldRetentionManager.Key key(){return key(digest);}
        private static WorldRetentionManager.Key key(String digest){return new WorldRetentionManager.Key(RETENTION_OWNER,"segment:"+digest);}
    }
    private final Path world,root;private final UUID worldId;private final PersistencePolicy policy;
    private final FileChannel channel;private final FileLock lock;private final Faults faults;private final Clock clock;
    private volatile Faults observer=Faults.none();private boolean observing;
    private volatile ArchiveHistory history;private volatile boolean fenced,closed;private volatile long epoch;
    private volatile String currentDigest;
    private WorldRetentionManager.Owner accountingOwner;
    private ArchiveWriter(Path world,UUID worldId,PersistencePolicy policy,FileChannel channel,FileLock lock,Faults faults,Clock clock,ArchiveHistory history)throws IOException {
        this.world=world;this.root=world.resolve(ArchiveHistory.RELATIVE_PATH);this.worldId=worldId;this.policy=policy;
        this.channel=channel;this.lock=lock;this.faults=faults;this.clock=clock;this.history=history;fenced=history.state()!=ArchiveHistory.State.READY;
        currentDigest=Files.exists(root.resolve(ArchiveHistory.CURRENT))?sha256(ArchiveHistory.read(root.resolve(ArchiveHistory.CURRENT),MANIFEST_BYTES)):"";
    }
    public static ArchiveWriter open(Path world,UUID worldId,PersistencePolicy policy)throws IOException {
        return open(world,worldId,policy,Faults.none(),Clock.systemUTC());
    }
    public static ArchiveWriter open(Path world,UUID worldId,PersistencePolicy policy,Faults faults,Clock clock)throws IOException {
        Objects.requireNonNull(worldId);Objects.requireNonNull(policy);Objects.requireNonNull(faults);Objects.requireNonNull(clock);
        Path root=world.resolve(ArchiveHistory.RELATIVE_PATH);ArchiveHistory.safe(root);Files.createDirectories(root);ArchiveHistory.safe(root);
        var channel=FileChannel.open(root.resolve("writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock lock=null;
        try {
            try{lock=channel.tryLock();}catch(OverlappingFileLockException occupied){throw new IOException("Archive writer already open",occupied);}
            if(lock==null)throw new IOException("Archive writer already open");
            ArchiveHistory initial=ArchiveHistory.read(world,worldId,policy);
            if(initial.state()==ArchiveHistory.State.READY && initial.manifest().revision()>0) {
                ArchiveRequirement.ensure(world,worldId);initial=ArchiveHistory.read(world,worldId,policy);
            }
            return new ArchiveWriter(world,worldId,policy,channel,lock,faults,clock,initial);
        }catch(IOException | RuntimeException failure){if(lock!=null)lock.release();channel.close();throw failure;}
    }
    /** O(1), no I/O monitor. The manifest alone is never permission to evict a hot row. */
    public Manifest snapshot(){return history.manifest();}
    public ArchiveHistory historyView(){return history;}
    /** Trusted host instrumentation; defaults to no-op and cannot create an acknowledgement. */
    public synchronized void observePublication(Faults observer) {
        if(observing)throw new IllegalStateException("Publication observer already bound");
        this.observer=Objects.requireNonNull(observer);observing=true;
    }
    private void at(Point point)throws IOException {faults.at(point);observer.at(point);}
    public boolean readOnly(){return fenced || closed;}
    public long retentionRevision(){return epoch;}
    public Optional<Row> lookup(Key key)throws IOException {if(readOnly())throw new IOException("Fenced archive");return history.lookup(key);}
    public boolean authentic(Acknowledgement ack,Owner owner,Guard guard) {
        return !readOnly() && ack!=null && ack.writer==this && ack.owner==owner && ack.guard.equals(guard)
                && ack.manifest.equals(snapshot()) && ack.digest.equals(currentDigest);
    }
    private Result result(Status status,Owner owner,Guard guard) {
        return new Result(status,status==Status.ACKNOWLEDGED || status==Status.DUPLICATE
                ?new Acknowledgement(this,snapshot(),currentDigest,owner,guard):null);
    }
    private Status preflight(Manifest expected,Owner owner,Guard guard,Fences fences) {
        if(readOnly())return Status.FENCED;
        if(!snapshot().equals(expected) || !Objects.equals(guard,fences.current(owner))
                || guard.ownerRevision()<expected.ownerRevisions().get(owner) || guard.retentionGeneration()<expected.retentionGeneration())return Status.STALE;
        return null;
    }
    public synchronized Result append(Manifest expected,Owner owner,Guard guard,List<Row> input,Fences fences)throws IOException {
        Objects.requireNonNull(input);Status denied=preflight(expected,owner,guard,fences);if(denied!=null)return result(denied,owner,guard);
        if(input.isEmpty() || input.size()>SEGMENT_ROWS || input.stream().anyMatch(r->r.kind()!=Kind.HISTORY))throw new IllegalArgumentException("Bounded immutable owner history only");
        var rows=new ArrayList<Row>();var ids=new HashSet<UUID>();
        for(var row:input) {
            if(!ids.add(row.id()))throw new IllegalArgumentException("Duplicate input UUID");
            var old=history.lookup(new Key(owner,row.kind(),row.id()));
            if(old.isPresent() && !old.get().equals(row))throw new IOException("Conflicting exact history UUID");
            if(old.isEmpty())rows.add(row);
        }
        if(rows.isEmpty())return result(Status.DUPLICATE,owner,guard);
        byte[] bytes=encode(new Segment(worldId,owner,guard,rows));
        int ordinarySegments=Math.min(segmentCapacity()*policy.pressurePercent()/100,segmentCapacity()-4*policy.reservations());
        if(snapshot().segments().size()>=ordinarySegments || history.records()+rows.size()>DEFAULT_RECORD_QUOTA*policy.pressurePercent()/100
                || history.bytes()+bytes.length+3L*MANIFEST_BYTES>policy.ordinaryBytes()
                || !capacity(bytes.length,reserved(snapshot().slots()),1,futureFiles(snapshot().slots(),null,null)))return result(Status.BACKPRESSURE,owner,guard);
        return publish(expected,owner,guard,rows,snapshot().slots(),snapshot().migrations(),fences);
    }
    public synchronized Result reserve(Manifest expected,Slot requested,Guard guard,Fences fences)throws IOException {
        Owner owner=requested.owner();Status denied=preflight(expected,owner,guard,fences);if(denied!=null)return result(denied,owner,guard);
        if(requested.phase()!=Phase.RESERVED || requested.remainingBytes()!=requested.reservedBytes())throw new IllegalArgumentException("Fresh original-attempt reservation required");
        for(var old:snapshot().slots())if(old.id().equals(requested.id())) {
            if(!sameIdentity(old,requested) || old.reservedBytes()!=requested.reservedBytes())throw new IOException("Conflicting reservation identity");
            return result(old.phase()==Phase.SETTLED?Status.ALREADY_SETTLED:Status.DUPLICATE,owner,guard);
        }
        var prior=history.lookup(new Key(owner,Kind.INTENT,requested.id()));
        if(prior.isPresent()) {
            if(!samePayloadIdentity(requested,prior.get()))throw new IOException("Conflicting legacy reservation replay");
            return result(Status.ALREADY_SETTLED,owner,guard);
        }
        var slots=new ArrayList<>(snapshot().slots().stream().filter(s->s.phase()!=Phase.SETTLED).toList());
        slots.add(requested);
        if(slots.size()>policy.reservations() || reserved(slots)>policy.reservedBytes() || history.bytes()>=policy.ordinaryBytes()
                || !capacity(0,reserved(slots),0,futureFiles(slots,null,null)))return result(Status.BACKPRESSURE,owner,guard);
        return publish(expected,owner,guard,List.of(),slots,snapshot().migrations(),fences);
    }
    /** One bounded record per phase, all bytes charged before issuing a new physical effect by its caller. */
    public synchronized Result record(Manifest expected,UUID reservation,Kind kind,byte[] opaque,Guard guard,Fences fences)throws IOException {
        if(kind==Kind.HISTORY)throw new IllegalArgumentException("Reservation metadata kind");
        Slot slot=snapshot().slots().stream().filter(s->s.id().equals(reservation)).findFirst().orElseThrow(()->new IllegalArgumentException("Original reservation missing"));
        Owner owner=slot.owner();Status denied=preflight(expected,owner,guard,fences);if(denied!=null)return result(denied,owner,guard);
        byte[] identity=ByteBuffer.allocate(40).putLong(slot.root().getMostSignificantBits()).putLong(slot.root().getLeastSignificantBits())
                .putLong(slot.attempt().getMostSignificantBits()).putLong(slot.attempt().getLeastSignificantBits()).putLong(slot.generation()).array();
        if(opaque==null || opaque.length<1 || opaque.length>RECORD_BYTES-40)throw new IllegalArgumentException("Bounded native metadata");
        byte[] payload=new byte[40+opaque.length];System.arraycopy(identity,0,payload,0,40);System.arraycopy(opaque,0,payload,40,opaque.length);
        Row row=new Row(kind,reservation,payload);var prior=history.lookup(new Key(owner,kind,reservation));
        if(prior.isPresent()) {
            if(!prior.get().equals(row))throw new IOException("Conflicting reservation phase replay");
            return result(Status.DUPLICATE,owner,guard);
        }
        if(slot.phase()==Phase.SETTLED || kind!=Kind.INTENT && history.lookup(new Key(owner,Kind.INTENT,reservation)).isEmpty()
                || kind==Kind.SETTLEMENT && history.lookup(new Key(owner,Kind.RESULT,reservation)).isEmpty())return result(Status.STALE,owner,guard);
        byte[] bytes=encode(new Segment(worldId,owner,guard,List.of(row)));long cost=Math.addExact(bytes.length,512);
        long maximum=switch(kind){case INTENT->INTENT_BYTES;case RESULT->RESULT_BYTES;case CANCELLATION->CANCELLATION_BYTES;case SETTLEMENT->SETTLEMENT_BYTES;default->throw new IllegalArgumentException("Metadata kind");};
        if(cost>maximum || cost>slot.remainingBytes())return result(Status.BACKPRESSURE,owner,guard);
        long remaining=kind==Kind.SETTLEMENT?0:slot.remainingBytes()-cost;
        Phase phase=kind==Kind.SETTLEMENT?Phase.SETTLED:kind==Kind.CANCELLATION || slot.phase()==Phase.CANCELLING?Phase.CANCELLING:kind==Kind.RESULT?Phase.RESULT:Phase.RESERVED;
        var replacement=new Slot(slot.id(),owner,slot.root(),slot.attempt(),slot.generation(),slot.reservedBytes(),remaining,phase);
        var slots=snapshot().slots().stream().map(s->s.id().equals(reservation)?replacement:s).toList();
        if(!capacity(bytes.length,reserved(slots),1,futureFiles(slots,reservation,kind)))return result(Status.BACKPRESSURE,owner,guard);
        return publish(expected,owner,guard,List.of(row),slots,snapshot().migrations(),fences);
    }
    public synchronized Result checkpoint(Manifest expected,Owner owner,Guard guard,Fences fences)throws IOException {
        Status denied=preflight(expected,owner,guard,fences);if(denied!=null)return result(denied,owner,guard);
        return publish(expected,owner,guard,List.of(),snapshot().slots(),snapshot().migrations(),fences);
    }
    public synchronized Result migration(Manifest expected,Migration receipt,byte[] original,Guard guard,Fences fences)throws IOException {
        Owner owner=receipt.owner();Status denied=preflight(expected,owner,guard,fences);if(denied!=null)return result(denied,owner,guard);
        if(original==null || original.length>2_097_152 || !sha256(original).equals(receipt.sourceDigest()))throw new IOException("Migration source identity mismatch");
        for(var old:snapshot().migrations())if(old.id().equals(receipt.id()) || old.owner()==owner && old.sourceDigest().equals(receipt.sourceDigest())) {
            if(!old.equals(receipt))throw new IOException("Conflicting migration identity");return result(Status.DUPLICATE,owner,guard);
        }
        if(receipt.targetRevision()!=expected.revision() || !receipt.targetDigest().equals(currentDigest))throw new IOException("Unacknowledged migration target");
        if(snapshot().migrations().size()==8 || !capacity(0,reserved(snapshot().slots()),0,futureFiles(snapshot().slots(),null,null)))return result(Status.BACKPRESSURE,owner,guard);
        var migrations=new ArrayList<>(snapshot().migrations());migrations.add(receipt);
        return publish(expected,owner,guard,List.of(),snapshot().slots(),migrations,fences);
    }
    private boolean capacity(long nextBytes,long reserved,int newFiles,int futureFiles) {
        return history.bytes()+nextBytes+reserved+SEGMENT_BYTES+3L*MANIFEST_BYTES+2L*ArchiveRequirement.MAX_BYTES<=policy.archiveBytes()
                && snapshot().segments().size()+newFiles+futureFiles<=segmentCapacity();
    }
    // Preserve the existing physical-file quota, including both requirement publication cuts.
    private int segmentCapacity(){return Math.min(policy.segments(),policy.files()-6);}
    private int futureFiles(List<Slot> slots,UUID publishing,Kind kind)throws IOException {
        int total=0;for(var slot:slots)if(slot.phase()!=Phase.SETTLED)
            for(var phase:List.of(Kind.INTENT,Kind.RESULT,Kind.CANCELLATION,Kind.SETTLEMENT))
                if(!(slot.id().equals(publishing) && phase==kind) && history.lookup(new Key(slot.owner(),phase,slot.id())).isEmpty())total++;
        return total;
    }
    private static long reserved(List<Slot> slots){long total=0;for(var s:slots)if(s.phase()!=Phase.SETTLED)total=Math.addExact(total,s.remainingBytes());return total;}
    private static boolean sameIdentity(Slot a,Slot b){return a.id().equals(b.id()) && a.owner()==b.owner() && a.root().equals(b.root()) && a.attempt().equals(b.attempt()) && a.generation()==b.generation();}
    private static boolean samePayloadIdentity(Slot slot,Row row) {
        byte[] p=row.payload();if(p.length<40)return false;var b=ByteBuffer.wrap(p);
        return new UUID(b.getLong(),b.getLong()).equals(slot.root()) && new UUID(b.getLong(),b.getLong()).equals(slot.attempt()) && b.getLong()==slot.generation();
    }
    private Result publish(Manifest expected,Owner owner,Guard guard,List<Row> rows,List<Slot> slots,List<Migration> migrations,Fences fences)throws IOException {
        try {
            var refs=new ArrayList<>(expected.segments());byte[] segment=null;
            if(!rows.isEmpty()) {
                segment=encode(new Segment(worldId,owner,guard,rows));String digest=sha256(segment);
                if(refs.stream().noneMatch(r->r.digest().equals(digest)))refs.add(new Ref(digest,owner,segment.length,rows.size(),clock.millis()));
                at(Point.BEFORE_SEGMENT);Path path=root.resolve(digest+".seg");
                if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
                    if(!Arrays.equals(segment,ArchiveHistory.read(path,SEGMENT_BYTES)))throw new IOException("Conflicting immutable segment");
                }else writeNew(path,segment);
                forceDirectory();at(Point.AFTER_SEGMENT_ACK);
            }
            var revisions=new EnumMap<Owner,Long>(Owner.class);revisions.putAll(expected.ownerRevisions());revisions.put(owner,guard.ownerRevision());
            var next=new Manifest(worldId,Math.addExact(expected.revision(),1),revisions,guard.retentionGeneration(),refs,slots,migrations);byte[] bytes=encode(next);
            if(!ArchiveFormat.manifest(bytes,worldId).equals(next))throw new IOException("Manifest validation");
            if(!Objects.equals(fences.current(owner),guard))throw new IOException("Owner or retention fence changed before publication");
            ArchiveRequirement.ensure(world,worldId);
            at(Point.BEFORE_MANIFEST_STAGE);Path pending=root.resolve(ArchiveHistory.PENDING);
            writeNew(pending,bytes);at(Point.AFTER_MANIFEST_STAGE);
            Path current=root.resolve(ArchiveHistory.CURRENT),previous=root.resolve(ArchiveHistory.PREVIOUS);
            if(Files.exists(current,LinkOption.NOFOLLOW_LINKS)) {
                ArchiveHistory.safe(previous);Files.copy(current,previous,StandardCopyOption.REPLACE_EXISTING);
                try(var backup=FileChannel.open(previous,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){backup.force(true);}
            }
            at(Point.AFTER_BACKUP);
            if(!Objects.equals(fences.current(owner),guard))throw new IOException("Owner or retention fence changed at publication");
            Files.move(pending,current,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);at(Point.AFTER_MANIFEST_REPLACE);
            forceDirectory();at(Point.AFTER_DIRECTORY_ACK);
            if(!Objects.equals(fences.current(owner),guard))throw new IOException("Owner or retention fence changed after possible publication");
            ArchiveHistory verified=ArchiveHistory.read(world,worldId,policy);if(verified.state()!=ArchiveHistory.State.READY || !verified.manifest().equals(next))throw new IOException("Published history validation");
            history=verified;currentDigest=sha256(bytes);epoch=Math.addExact(epoch,1);return result(Status.ACKNOWLEDGED,owner,guard);
        }catch(IOException | RuntimeException uncertain){fenced=true;throw uncertain;}
    }
    private void forceDirectory()throws IOException {try(var dir=FileChannel.open(root,StandardOpenOption.READ)){dir.force(true);}}
    private static void writeNew(Path path,byte[] bytes)throws IOException {
        ArchiveHistory.safe(path);try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            var data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);
        }
    }
    public synchronized Optional<CleanupCandidate> candidate(String digest)throws IOException {
        ArchiveFormat.hash(digest);if(policy.deleteFiles()==0 || readOnly() || !history.redundantSegments().contains(digest) || history.protectedSegments().contains(digest))return Optional.empty();
        var attributes=Files.readAttributes(root.resolve(digest+".seg"),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(clock.millis()-attributes.lastModifiedTime().toMillis()<policy.eligibleAgeMillis())return Optional.empty();
        return Optional.of(new CleanupCandidate(this,digest,identity(attributes),epoch));
    }
    public synchronized boolean cleanup(CleanupCandidate candidate,WorldRetentionManager.DiscardLease lease,BooleanSupplier cancel)throws IOException {
        Objects.requireNonNull(cancel);
        if(candidate==null || candidate.writer!=this || readOnly() || candidate.revision!=epoch || lease==null
                || !lease.permits(candidate.key(),candidate.revision,retentionOwner()) || cancel.getAsBoolean())return false;
        var fresh=ArchiveHistory.read(world,worldId,policy);
        if(fresh.state()!=ArchiveHistory.State.READY || !fresh.manifest().equals(snapshot())){fenced=true;return false;}
        if(fresh.protectedSegments().contains(candidate.digest) || !fresh.redundantSegments().contains(candidate.digest))return false;
        Path path=root.resolve(candidate.digest+".seg");ArchiveHistory.safe(path);
        if(!identity(Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS)).equals(candidate.identity))return false;
        try {
            at(Point.BEFORE_CLEANUP);
            if(!lease.permits(candidate.key(),candidate.revision,retentionOwner()) || cancel.getAsBoolean())return false;
            Files.delete(path);forceDirectory();at(Point.AFTER_CLEANUP);
            history=ArchiveHistory.read(world,worldId,policy);epoch=Math.addExact(epoch,1);return true;
        }catch(IOException | RuntimeException failure){fenced=true;throw failure;}
    }
    private static String identity(BasicFileAttributes a){return a.size()+":"+a.lastModifiedTime()+":"+a.fileKey();}
    /** Automatic domain collection stays protected; explicit cleanup additionally requires a scoped manager lease. */
    public synchronized WorldRetentionManager.Owner retentionOwner() {
        if(accountingOwner!=null)return accountingOwner;
        accountingOwner=new WorldRetentionManager.Owner() {
            public String id(){return RETENTION_OWNER;}public long revision(){return epoch;}
            public Optional<WorldRetentionManager.Amount> admissionCeiling(){return Optional.of(new WorldRetentionManager.Amount(policy.archiveBytes(),DEFAULT_RECORD_QUOTA));}
            public WorldRetentionManager.Cursor open() {
                long captured=epoch;ArchiveHistory view=history;
                return new WorldRetentionManager.Cursor() {
                    java.nio.file.DirectoryStream<Path> stream;Iterator<Path> paths;boolean started,done;int count;
                    public long revision(){return captured;}
                    public WorldRetentionManager.Page next(int maximum,int bytes)throws IOException {
                        var entries=new ArrayList<WorldRetentionManager.Entry>();int work=0;
                        if(done)return new WorldRetentionManager.Page(entries,true,captured==epoch,0,0);
                        if(!started) {started=true;stream=Files.newDirectoryStream(root);
                            var metadata=new ArrayList<Path>();
                            for(String name:List.of(ArchiveRequirement.PATH,ArchiveRequirement.PENDING)) {
                                Path path=world.resolve(name);if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))metadata.add(path);
                            }
                            paths=java.util.stream.Stream.concat(metadata.stream(),
                                    java.util.stream.StreamSupport.stream(stream.spliterator(),false)).iterator();
                            entries.add(new WorldRetentionManager.Entry(new WorldRetentionManager.Key(id(),"records"),WorldRetentionManager.Category.JOBS,
                                    new WorldRetentionManager.Amount(0,view.records()),List.of(),true,false,Set.of(),""));work++;}
                        while(work<maximum && paths.hasNext()) {
                            Path path=paths.next();if(++count>policy.files())throw new IOException("Archive inventory bound");ArchiveHistory.safe(path);
                            var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!a.isRegularFile())throw new IOException("Archive inventory entry");
                            String name=path.getFileName().toString();boolean segment=name.matches("[0-9a-f]{64}\\.seg");String digest=segment?name.substring(0,64):"";
                            boolean safeCopy=segment && view.redundantSegments().contains(digest) && !view.protectedSegments().contains(digest) && !readOnly();
                            var key=segment?CleanupCandidate.key(digest):new WorldRetentionManager.Key(id(),
                                    (path.getParent().equals(root)?"file:":"requirement:")+name);
                            entries.add(new WorldRetentionManager.Entry(key,WorldRetentionManager.Category.JOBS,new WorldRetentionManager.Amount(a.size(),0),List.of(),
                                    !safeCopy,safeCopy && clock.millis()-a.lastModifiedTime().toMillis()>=policy.eligibleAgeMillis(),Set.of(),identity(a)));work++;
                        }
                        done=!paths.hasNext();if(done)close();return new WorldRetentionManager.Page(entries,done,captured==epoch && !readOnly(),work,0);
                    }
                    public void close()throws IOException {if(stream!=null){stream.close();stream=null;}}
                };
            }
            public WorldRetentionManager.OwnerResult collect(WorldRetentionManager.Entry entry,long revision,BooleanSupplier cancel) {
                return WorldRetentionManager.OwnerResult.of(WorldRetentionManager.OwnerStatus.PROTECTED);
            }
        };
        return accountingOwner;
    }
    static byte[] encode(Segment segment)throws IOException {
        var output=new ByteArrayOutputStream();var data=new DataOutputStream(output);
        data.writeLong(MAGIC);data.writeInt(SCHEMA);data.writeLong(segment.world().getMostSignificantBits());data.writeLong(segment.world().getLeastSignificantBits());
        data.writeByte(segment.owner().ordinal());data.writeLong(segment.guard().ownerRevision());data.writeLong(segment.guard().retentionGeneration());data.writeInt(segment.rows().size());
        for(var row:segment.rows()){data.writeByte(row.kind().ordinal());data.writeLong(row.id().getMostSignificantBits());data.writeLong(row.id().getLeastSignificantBits());byte[] p=row.payload();data.writeInt(p.length);data.write(p);}
        data.write(HexFormat.of().parseHex(sha256(output.toByteArray())));return output.toByteArray();
    }
    static byte[] encode(Manifest value)throws IOException {
        var body=new LinkedHashMap<String,Object>();
        body.put("segments",value.segments().stream().map(r->Map.of("digest",r.digest(),"owner",r.owner().name(),"bytes",r.bytes(),"records",(long)r.records(),"createdEpochMillis",r.createdEpochMillis())).toList());
        body.put("slots",value.slots().stream().map(s->Map.of("id",s.id().toString(),"owner",s.owner().name(),"root",s.root().toString(),"attempt",s.attempt().toString(),"generation",s.generation(),"reservedBytes",s.reservedBytes(),"remainingBytes",s.remainingBytes(),"phase",s.phase().name())).toList());
        body.put("migrations",value.migrations().stream().map(m->Map.of("id",m.id().toString(),"owner",m.owner().name(),"sourceSchema",(long)m.sourceSchema(),"sourceDigest",m.sourceDigest(),"targetRevision",m.targetRevision(),"targetDigest",m.targetDigest())).toList());
        String payload=StrictJson.canonical(body);String header=StrictJson.canonical(Map.of("schema",(long)SCHEMA,"world",value.world().toString(),"revision",value.revision(),"owners",Map.of("JOBS",value.ownerRevisions().get(Owner.JOBS),"LEASES",value.ownerRevisions().get(Owner.LEASES)),"retentionGeneration",value.retentionGeneration(),"records",(long)value.segments().size(),"sha256",sha256(payload.getBytes(StandardCharsets.UTF_8))));
        byte[] bytes=(header+"\n"+payload).getBytes(StandardCharsets.UTF_8);ArchiveFormat.manifest(bytes,value.world());return bytes;
    }
    @Override public synchronized void close()throws IOException {if(!closed){closed=true;try{lock.release();}finally{channel.close();}}}
}
