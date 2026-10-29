package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.StrictJson;
import dev.aivillages.core.kernel.WorldRetentionManager;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativePickup.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.sha;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.keys;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.number;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.id;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.string;

/** Single off-thread writer. Invalid current/pending/required bytes are preserved and fenced. */
public final class PickupJournal implements AutoCloseable {
    public static final String BASE="data/cognitivecraft/pickup-evidence", PATH=BASE+"/v1";
    public static final String REQUIRED="data/cognitivecraft/pickup-evidence-required.json";
    public static final int MAX_BYTES=4_194_304;
    public enum Point { BEFORE_STAGE, AFTER_STAGE, AFTER_BACKUP, AFTER_REPLACE, AFTER_FORCE }
    @FunctionalInterface public interface Faults {void at(Point point)throws IOException;}
    public record Snapshot(UUID world,UUID epoch,long revision,List<Entry> entries) {
        public Snapshot {
            Objects.requireNonNull(world);Objects.requireNonNull(epoch);entries=List.copyOf(entries);
            if(revision<0 || entries.size()>MAX_ENTRIES)throw new IllegalArgumentException("Pickup journal quota/revision");
            var keys=new HashSet<String>();var ordinals=new HashSet<String>();var operations=new HashSet<String>();
            for(Entry entry:entries) {
                var key=entry.intent().key();
                if(!key.world().equals(world) || !keys.add(entry.token())
                        || !ordinals.add(key.root()+"/"+key.attempt()+"/"+key.generation()+"/"+key.ordinal())
                        || !operations.add(entry.intent().harvest().intent().run()+"/"+entry.intent().operation()))
                    throw new IllegalArgumentException("Pickup journal identity collision");
            }
        }
    }
    private final Path directory,base;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Faults faults;
    private Snapshot state;
    private boolean readOnly;
    private volatile long retentionRevision;
    private java.util.function.Predicate<WorldRetentionManager.Amount> capacity=amount->true;
    private volatile Thread serializedWriter;
    private PickupJournal(Path world,FileChannel channel,FileLock lock,Snapshot state,boolean readOnly,Faults faults) {
        directory=world.resolve(PATH);base=world.resolve(BASE);lockChannel=channel;this.lock=lock;this.state=state;this.readOnly=readOnly;this.faults=faults;
    }
    public static boolean exists(Path world) {return Files.exists(world.resolve(BASE),LinkOption.NOFOLLOW_LINKS)
            || Files.exists(world.resolve(REQUIRED),LinkOption.NOFOLLOW_LINKS) || Files.exists(world.resolve(REQUIRED+".next"),LinkOption.NOFOLLOW_LINKS);}
    public static PickupJournal open(Path world,UUID id)throws IOException{return open(world,id,point->{});}
    public static PickupJournal open(Path world,UUID id,Faults faults)throws IOException {
        Objects.requireNonNull(world);Objects.requireNonNull(id);Objects.requireNonNull(faults);
        Path base=world.resolve(BASE),directory=world.resolve(PATH);boolean fresh=!exists(world);
        safe(world.resolve(REQUIRED));safe(world.resolve(REQUIRED+".next"));
        for(Path p=directory;p!=null && p.startsWith(world);p=p.getParent())safe(p);
        for(String file:List.of("required.json","required.next.json","v1/state.jsonl","v1/state.prev.jsonl","v1/state.next.jsonl","v1/writer.lock"))safe(base.resolve(file));
        Files.createDirectories(directory);FileChannel channel=FileChannel.open(directory.resolve("writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock lock=null;
        try {
            try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new IOException("Pickup writer already open",busy);}
            if(lock==null)throw new IOException("Pickup writer already open");
            Snapshot loaded;boolean fenced=false;
            if(fresh) {
                loaded=new Snapshot(id,UUID.randomUUID(),0,List.of());write(directory.resolve("state.jsonl"),encode(loaded));force(directory);
                write(base.resolve("required.next.json"),requirement(loaded));Files.move(base.resolve("required.next.json"),base.resolve("required.json"),StandardCopyOption.ATOMIC_MOVE);
                force(base);force(base.getParent());
                Path required=world.resolve(REQUIRED),staged=world.resolve(REQUIRED+".next");write(staged,requirement(loaded));Files.move(staged,required,StandardCopyOption.ATOMIC_MOVE);force(required.getParent());
            }else {
                try{loaded=read(directory.resolve("state.jsonl"),id);}
                catch(IOException invalid) {
                    fenced=true;
                    try{loaded=read(directory.resolve("state.prev.jsonl"),id);}
                    catch(IOException unavailable){loaded=new Snapshot(id,UUID.randomUUID(),0,List.of());}
                }
                try{if(!Arrays.equals(bytes(base.resolve("required.json"),1024),requirement(loaded)))fenced=true;}
                catch(IOException invalid){fenced=true;}
                try{if(!Arrays.equals(bytes(world.resolve(REQUIRED),1024),requirement(loaded)))fenced=true;}catch(IOException invalid){fenced=true;}
                if(Files.exists(base.resolve("required.next.json"),LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(directory.resolve("state.next.jsonl"),LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(world.resolve(REQUIRED+".next"),LinkOption.NOFOLLOW_LINKS))fenced=true;
                if(!layout(base,directory))fenced=true;
                Path previous=directory.resolve("state.prev.jsonl");
                if(Files.exists(previous,LinkOption.NOFOLLOW_LINKS))try {
                    Snapshot old=read(previous,id);if(!old.epoch.equals(loaded.epoch) || old.revision>loaded.revision || loaded.revision-old.revision>1)fenced=true;
                }catch(IOException invalid){fenced=true;}
            }
            return new PickupJournal(world,channel,lock,loaded,fenced,faults);
        }catch(IOException|RuntimeException failure){if(lock!=null)lock.release();channel.close();throw failure;}
    }
    public synchronized Snapshot snapshot(){return state;}
    public synchronized boolean readOnly(){return readOnly;}
    /** Worker-owned storage quota gate; the game-thread protocol never invokes it. */
    public synchronized void capacity(java.util.function.Predicate<WorldRetentionManager.Amount> capacity){this.capacity=Objects.requireNonNull(capacity);}
    public synchronized Snapshot replace(Snapshot expected,Snapshot next)throws IOException {
        if(serializedWriter!=null && Thread.currentThread()!=serializedWriter)throw new IOException("Pickup journal requires its bound serialized writer");
        if(readOnly || state!=expected || !state.world.equals(next.world) || !state.epoch.equals(next.epoch)
                || next.revision!=Math.addExact(state.revision,1) || next.entries.size()<state.entries.size())throw new IOException("Stale/fenced pickup publication");
        for(int n=0;n<state.entries.size();n++) {
            Entry before=state.entries.get(n),after=next.entries.get(n);
            if(!before.intent().equals(after.intent()) || before.result()!=null && !before.result().equals(after.result())
                    || before.verification()!=null && !before.verification().equals(after.verification()))
                throw new IOException("Changed or removed original pickup evidence");
        }
        // Reserve the maximum complete result for every row before PREPARED can acknowledge.
        if(1024L+next.entries.size()*(MAX_RECORD_BYTES+1L)>MAX_BYTES)throw new IOException("Pickup result reservation quota");
        long reserved=3L*(1024L+next.entries.size()*(MAX_RECORD_BYTES+1L))+4096;
        if(!capacity.test(new WorldRetentionManager.Amount(reserved,next.entries.size())))throw new IOException("World pickup evidence capacity unavailable");
        byte[] raw=encode(next);Path pending=directory.resolve("state.next.jsonl");
        retentionRevision++;
        try {
            if(!layout(base,directory) || !Arrays.equals(bytes(base.resolve("required.json"),1024),requirement(state))
                    || !Arrays.equals(bytes(base.getParent().resolve("pickup-evidence-required.json"),1024),requirement(state))
                    || Files.exists(base.getParent().resolve("pickup-evidence-required.json.next"),LinkOption.NOFOLLOW_LINKS)
                    || !Arrays.equals(bytes(directory.resolve("state.jsonl"),MAX_BYTES),encode(state))
                    || Files.exists(pending,LinkOption.NOFOLLOW_LINKS))throw new IOException("Pickup disk authority changed before publication");
            faults.at(Point.BEFORE_STAGE);write(pending,raw);faults.at(Point.AFTER_STAGE);
            write(directory.resolve("state.prev.jsonl"),encode(state));
            faults.at(Point.AFTER_BACKUP);Files.move(pending,directory.resolve("state.jsonl"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            faults.at(Point.AFTER_REPLACE);force(directory);faults.at(Point.AFTER_FORCE);state=next;return next;
        }catch(IOException failure){readOnly=true;throw failure;}
        finally{retentionRevision++;}
    }
    /** Bounded accounting for every current/previous/pending and required file; none is collectible. */
    public WorldRetentionManager.Owner retentionOwner() {return retentionOwner(null);}
    /** Select only for journals sharing the exact same serialized storage thread. */
    public synchronized WorldRetentionManager.Owner retentionOwnerForCurrentWriter() {
        Thread current=Thread.currentThread();
        if(serializedWriter!=null && serializedWriter!=current)throw new IllegalStateException("Pickup serialized writer already bound");
        serializedWriter=current;return retentionOwner(current);
    }
    private WorldRetentionManager.Owner retentionOwner(Thread writer) {
        var owner=this;
        var paths=List.of(base.resolve("required.json"),base.resolve("required.next.json"),
                directory.resolve("state.jsonl"),directory.resolve("state.prev.jsonl"),directory.resolve("state.next.jsonl"),directory.resolve("writer.lock"),
                base.getParent().resolve("pickup-evidence-required.json"),base.getParent().resolve("pickup-evidence-required.json.next"));
        return new WorldRetentionManager.Owner() {
            public String id(){return "pickups";}
            public long revision(){return retentionRevision;}
            public Optional<Thread> serializedWriter(){return Optional.ofNullable(writer);}
            public Optional<WorldRetentionManager.Amount> admissionCeiling(){
                int rows=writer==null?MAX_ENTRIES:owner.snapshot().entries().size();
                // Every acknowledged PREPARED row already reserves its complete maximum result.
                // A bound single writer admits any additional row before exposing its capability.
                long reserved=writer==null?3L*MAX_BYTES+4096:3L*(1024L+rows*(MAX_RECORD_BYTES+1L))+4096;
                return Optional.of(new WorldRetentionManager.Amount(reserved,rows));
            }
            public WorldRetentionManager.Cursor open() {
                long captured=revision();
                return new WorldRetentionManager.Cursor() {
                    int cursor;boolean first=true;
                    public long revision(){return captured;}
                    public WorldRetentionManager.Page next(int maximum,int bytes)throws IOException {
                        var entries=new ArrayList<WorldRetentionManager.Entry>();int inspected=0;
                        if(first && maximum>0) {
                            first=false;inspected++;
                            entries.add(new WorldRetentionManager.Entry(new WorldRetentionManager.Key(id(),"records"),WorldRetentionManager.Category.EVIDENCE,
                                    new WorldRetentionManager.Amount(0,owner.snapshot().entries().size()),List.of(),true,false,Set.of(),""));
                        }
                        while(inspected<maximum && cursor<paths.size()) {
                            int n=cursor++;Path path=paths.get(n);inspected++;safe(path);
                            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))continue;
                            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid pickup accounting file");
                            long size=Files.size(path);if(size>MAX_BYTES)throw new IOException("Oversized pickup accounting file");
                            entries.add(new WorldRetentionManager.Entry(new WorldRetentionManager.Key(id(),"file:"+n),WorldRetentionManager.Category.EVIDENCE,
                                    new WorldRetentionManager.Amount(size,0),List.of(),true,false,Set.of(),Long.toString(size)));
                        }
                        return new WorldRetentionManager.Page(entries,!first && cursor==paths.size(),captured==retentionRevision && (captured&1)==0,inspected,0);
                    }
                };
            }
            public WorldRetentionManager.OwnerResult collect(WorldRetentionManager.Entry entry,long expectedRevision,java.util.function.BooleanSupplier cancel) {
                return WorldRetentionManager.OwnerResult.of(WorldRetentionManager.OwnerStatus.PROTECTED);
            }
        };
    }
    public static byte[] encode(Snapshot state)throws IOException {
        var output=new ByteArrayOutputStream();for(Entry entry:state.entries){output.write(NativePickup.encode(entry));output.write('\n');}
        byte[] payload=output.toByteArray();byte[] header=(StrictJson.canonical(Map.of("schema",1L,"world",state.world.toString(),"epoch",state.epoch.toString(),
                "revision",state.revision,"records",state.entries.size(),"sha256",sha(payload)))+"\n").getBytes(StandardCharsets.UTF_8);
        if(header.length>1024 || header.length+(long)payload.length>MAX_BYTES)throw new IOException("Pickup journal byte quota");
        output.reset();output.write(header);output.write(payload);return output.toByteArray();
    }
    public static Snapshot read(Path path,UUID world)throws IOException {
        byte[] raw=bytes(path,MAX_BYTES);int end=0;while(end<raw.length && raw[end]!='\n')end++;
        if(end==raw.length || end>1024)throw new IOException("Pickup journal header");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw,0,end)).toString();
            var header=StrictJson.object(text);keys(header,Set.of("schema","world","epoch","revision","records","sha256"));
            if(number(header,"schema")!=1 || !id(header,"world").equals(world))throw new IOException("Unknown/cross-world pickup journal");
            byte[] payload=Arrays.copyOfRange(raw,end+1,raw.length);if(!sha(payload).equals(string(header,"sha256")))throw new IOException("Pickup journal checksum");
            var entries=new ArrayList<Entry>();int start=end+1;
            for(int n=start;n<raw.length;n++)if(raw[n]=='\n') {
                if(entries.size()==MAX_ENTRIES || n==start)throw new IOException("Pickup journal record quota/framing");
                entries.add(NativePickup.decode(Arrays.copyOfRange(raw,start,n)));start=n+1;
            }
            if(start!=raw.length || entries.size()!=number(header,"records"))throw new IOException("Pickup journal framing/count");
            Snapshot result=new Snapshot(world,id(header,"epoch"),number(header,"revision"),entries);
            if(!Arrays.equals(raw,encode(result)))throw new IOException("Noncanonical pickup journal");return result;
        }catch(StrictJson.Invalid|RuntimeException|CharacterCodingException invalid){throw new IOException("Invalid pickup journal",invalid);}
    }
    private static byte[] requirement(Snapshot state){return StrictJson.canonical(Map.of("schema",1L,"world",state.world.toString(),"epoch",state.epoch.toString())).getBytes(StandardCharsets.UTF_8);}
    private static boolean layout(Path base,Path directory)throws IOException {
        try(var entries=Files.newDirectoryStream(base)) {
            for(Path path:entries) {
                safe(path);String name=path.getFileName().toString();
                if(name.equals("v1")){if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))return false;}
                else if(!Set.of("required.json","required.next.json").contains(name) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>1024)return false;
            }
        }
        try(var entries=Files.newDirectoryStream(directory)) {
            int files=0;long total=0;
            for(Path path:entries) {
                safe(path);String name=path.getFileName().toString();
                if(!Set.of("state.jsonl","state.prev.jsonl","state.next.jsonl","writer.lock").contains(name) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))return false;
                long size=Files.size(path);if(size>(name.equals("writer.lock")?0:MAX_BYTES))return false;total=Math.addExact(total,size);files++;
            }
            return files<=4 && total<=3L*MAX_BYTES;
        }
    }
    private static void safe(Path path)throws IOException {if(Files.isSymbolicLink(path))throw new IOException("Symbolic pickup path");}
    private static byte[] bytes(Path path,int maximum)throws IOException {
        safe(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>maximum)throw new IOException("Pickup regular file/byte limit");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] raw=input.readNBytes(maximum+1);if(raw.length>maximum)throw new IOException("Pickup read quota");return raw;}
    }
    private static void write(Path path,byte[] raw)throws IOException {
        safe(path);try(var channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer=ByteBuffer.wrap(raw);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
    }
    private static void force(Path path)throws IOException {try(var channel=FileChannel.open(path,StandardOpenOption.READ)){channel.force(true);}}
    @Override public synchronized void close()throws IOException {try{lock.release();}finally{lockChannel.close();}}
}
