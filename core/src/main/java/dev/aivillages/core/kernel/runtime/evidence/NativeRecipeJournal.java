package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.ResourceDelivery.Stage;
import dev.aivillages.core.kernel.StrictJson;
import dev.aivillages.core.kernel.WorldRetentionManager.Amount;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import static dev.aivillages.core.kernel.RecipeTransaction.fingerprint;
import static dev.aivillages.core.kernel.RecipeTransaction.hash;

/** The A write-ahead evidence journal. It never grants a job, claim, or native save acknowledgement. */
public final class NativeRecipeJournal implements AutoCloseable {
    public static final String BASE = "data/cognitivecraft/recipe-evidence";
    private static final String REQUIRED = BASE + "-required.json";
    private static final int MAX_ROW = 2_048;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_EFFECTS = 3_072;

    public record Participant(String address, UUID incarnation, long revision, String slots) {
        public Participant {
            Objects.requireNonNull(incarnation);
            if (address == null || address.length() > 192 || address.isBlank() || revision < 0 || !hash(slots))
                throw new IllegalArgumentException("Invalid exact-choice participant");
        }
        private Map<String,Object> value() {
            return Map.of("address",address,"incarnation",incarnation.toString(),"revision",revision,"slots",slots);
        }
    }

    public record Intent(UUID token, UUID root, UUID step, UUID attempt, long generation, UUID operation,
                         String method, String choice, int batch, Stage phase, long deadline,
                         List<Participant> before) {
        public Intent {
            Objects.requireNonNull(token); Objects.requireNonNull(root); Objects.requireNonNull(step);
            Objects.requireNonNull(attempt); Objects.requireNonNull(operation);
            before = List.copyOf(before);
            if (generation < 1 || !hash(method) || !hash(choice) || batch < 0 || batch > 15 || deadline < 1
                    || !List.of(Stage.WITHDRAW, Stage.CRAFT, Stage.DEPOSIT).contains(phase)
                    || before.isEmpty() || before.size() > 4 || !before.getFirst().address().startsWith("actor:")
                    || before.stream().map(Participant::address).distinct().count() != before.size())
                throw new IllegalArgumentException("Invalid original recipe effect");
        }
        private Map<String,Object> value() {
            return Map.ofEntries(Map.entry("kind","prepare"),Map.entry("token",token.toString()),
                    Map.entry("root",root.toString()),Map.entry("step",step.toString()),Map.entry("attempt",attempt.toString()),
                    Map.entry("generation",generation),Map.entry("operation",operation.toString()),Map.entry("method",method),
                    Map.entry("choice",choice),Map.entry("batch",batch),Map.entry("phase",phase.name()),Map.entry("deadline",deadline),
                    Map.entry("before",before.stream().map(Participant::value).toList()));
        }
        public String digest() { return fingerprint(StrictJson.canonical(value())); }
    }

    public enum Status { APPLIED, NO_EFFECT, UNCERTAIN }

    public record Result(UUID token, String intent, Status status, List<Participant> after) {
        public Result {
            Objects.requireNonNull(token); Objects.requireNonNull(status);
            after = List.copyOf(after);
            if (!hash(intent) || after.size() > 4 || status != Status.UNCERTAIN && after.isEmpty())
                throw new IllegalArgumentException("Invalid exact-choice result");
        }
        private Map<String,Object> value() {
            return Map.of("kind","result","token",token.toString(),"intent",intent,"status",status.name(),
                    "after",after.stream().map(Participant::value).toList());
        }
        public String digest() { return fingerprint(StrictJson.canonical(value())); }
    }

    public record Entry(Intent intent, Result result) {
        public Entry {
            Objects.requireNonNull(intent);
            if (result != null && (!result.token().equals(intent.token()) || !result.intent().equals(intent.digest())))
                throw new IllegalArgumentException("Result does not match prepared recipe effect");
            if (result != null && result.status() != Status.UNCERTAIN) {
                if (result.after().size() != intent.before().size()) throw new IllegalArgumentException("Missing native participant");
                for (int index=0; index<result.after().size(); index++) {
                    var before=intent.before().get(index);
                    var after=result.after().get(index);
                    if (!before.address().equals(after.address()) || !before.incarnation().equals(after.incarnation())
                            || before.revision()>after.revision()) throw new IllegalArgumentException("Participant identity changed");
                }
                if (result.status()==Status.NO_EFFECT && !intent.before().equals(result.after()))
                    throw new IllegalArgumentException("No-effect participants changed");
            }
        }
    }

    private final UUID world;
    private final Path data, required, next;
    private final Thread writer = Thread.currentThread();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<UUID,Entry> rows = new LinkedHashMap<>();
    private final HashSet<UUID> operations = new HashSet<>();
    private volatile Map<UUID,List<Entry>> attempts = Map.of();
    private volatile List<Entry> entries = List.of();
    private volatile boolean fenced;
    private volatile long bytes, revision;
    private String head;
    private BasicFileAttributes attributes;
    private int pending;
    private Predicate<Amount> capacity = ignored -> false;

    private NativeRecipeJournal(Path data, Path required, UUID world, FileChannel channel, FileLock lock) {
        this.data=data; this.required=required; this.next=required.resolveSibling(required.getFileName()+".next");
        this.world=world; lockChannel=channel; this.lock=lock; head=fingerprint(world.toString());
    }

    public static boolean exists(Path world) { return present(world.resolve(BASE)) || present(world.resolve(REQUIRED)) || present(world.resolve(REQUIRED+".next")); }

    public static NativeRecipeJournal open(Path worldDirectory, UUID world) throws IOException {
        var directory=worldDirectory.resolve(BASE);
        var data=directory.resolve("phases.jsonl");
        var required=worldDirectory.resolve(REQUIRED);
        var lockPath=directory.resolve("writer.lock");
        for (var path:List.of(data,required,lockPath,worldDirectory.resolve(REQUIRED+".next"))) safe(path);
        boolean fresh=!exists(worldDirectory);
        Files.createDirectories(directory);
        var channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try {
            var lock=channel.tryLock();
            if (lock==null) throw new IOException("Native recipe evidence writer already open");
            var journal=new NativeRecipeJournal(data,required,world,channel,lock);
            if (fresh) {
                writeNew(data,new byte[0]); force(directory);
                journal.publishRequirement(0,journal.head);
            }
            try { journal.load(); }
            catch (IOException | RuntimeException invalid) { journal.fenced=true; }
            return journal;
        } catch (IOException | RuntimeException failure) { channel.close(); throw failure; }
    }

    public boolean fenced() { return fenced; }
    public long revision() { return revision; }
    public long bytes() { return bytes; }
    public List<Entry> entries() { return entries; }
    public List<Entry> entries(UUID attempt) { return attempts.getOrDefault(attempt,List.of()); }
    public void capacity(Predicate<Amount> capacity) { thread(); this.capacity=Objects.requireNonNull(capacity); }

    public Entry prepare(Intent intent) throws IOException {
        thread();
        if (rows.containsKey(intent.token()) || operations.contains(intent.operation())) throw new IOException("Repeated recipe operation");
        long reserved = bytes + (pending+2L)*(MAX_ROW+1) + 2048;
        if (rows.size()>=MAX_EFFECTS || reserved>MAX_BYTES || !capacity.test(new Amount(reserved,rows.size()+1)))
            throw new IOException("Native recipe evidence capacity unavailable");
        return publish(new Entry(intent,null),intent.value());
    }

    public Entry result(Result result) throws IOException {
        thread();
        var prepared=rows.get(result.token());
        if (prepared==null || prepared.result()!=null) throw new IOException("No original prepared recipe operation");
        return publish(new Entry(prepared.intent(),result),result.value());
    }

    private Entry publish(Entry entry, Map<String,Object> value) throws IOException {
        revision++;
        try {
            var current=attributes(data);
            if (current.size()!=bytes || !current.lastModifiedTime().equals(attributes.lastModifiedTime())
                    || !Objects.equals(current.fileKey(),attributes.fileKey()) || present(next)
                    || !Arrays.equals(read(required,1024),requirement(bytes,head))) throw new IOException("Recipe journal authority changed");
            byte[] line=(frame(value,head)+"\n").getBytes(StandardCharsets.UTF_8);
            if (line.length>MAX_ROW+1 || bytes+line.length>MAX_BYTES) throw new IOException("Recipe journal byte limit");
            try (var file=FileChannel.open(data,StandardOpenOption.APPEND,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                var buffer=ByteBuffer.wrap(line);
                while(buffer.hasRemaining())file.write(buffer);
                file.force(true);
            }
            long nextLength=bytes+line.length;
            String nextHead=fingerprint(new String(line,StandardCharsets.UTF_8));
            publishRequirement(nextLength,nextHead);
            attributes=attributes(data); bytes=nextLength; head=nextHead;
            rows.put(entry.intent().token(),entry);
            if(entry.result()==null){operations.add(entry.intent().operation());pending++;}else pending--;
            snapshot();
            return entry;
        } catch (IOException failure) { fenced=true; throw failure; }
        finally { revision++; }
    }

    private void load() throws IOException {
        if (present(next)) throw new IOException("Unacknowledged recipe journal publication");
        byte[] raw=read(data,MAX_BYTES);
        int start=0;
        while(start<raw.length) {
            int end=start;
            while(end<raw.length && raw[end]!='\n' && end-start<=MAX_ROW)end++;
            if(end==raw.length || raw[end]!='\n' || end-start>MAX_ROW)throw new IOException("Recipe journal framing");
            String line=new String(raw,start,end-start,StandardCharsets.UTF_8);
            try { loadFrame(line); }
            catch(StrictJson.Invalid | RuntimeException invalid){throw new IOException("Malformed exact-choice evidence",invalid);}
            head=fingerprint(line+"\n"); start=end+1;
        }
        if(!Arrays.equals(read(required,1024),requirement(raw.length,head)))throw new IOException("Recipe journal commitment mismatch");
        attributes=attributes(data); bytes=raw.length; snapshot();
    }

    private void loadFrame(String line) throws StrictJson.Invalid {
        var row=new LinkedHashMap<>(StrictJson.object(line));
        if(!head.equals(row.remove("previous")))throw new IllegalArgumentException("Foreign recipe journal prefix");
        if("prepare".equals(row.get("kind"))) {
            var intent=new Intent(uuid(row,"token"),uuid(row,"root"),uuid(row,"step"),uuid(row,"attempt"),
                    (Long)row.get("generation"),uuid(row,"operation"),(String)row.get("method"),(String)row.get("choice"),
                    Math.toIntExact((Long)row.get("batch")),Stage.valueOf((String)row.get("phase")),(Long)row.get("deadline"),participants(row.get("before")));
            if(!line.equals(frame(intent.value(),head)) || rows.size()>=MAX_EFFECTS || !operations.add(intent.operation())
                    || rows.putIfAbsent(intent.token(),new Entry(intent,null))!=null)throw new IllegalArgumentException("Repeated recipe intent");
            pending++;
        } else if("result".equals(row.get("kind"))) {
            var result=new Result(uuid(row,"token"),(String)row.get("intent"),Status.valueOf((String)row.get("status")),participants(row.get("after")));
            var old=rows.get(result.token());
            if(!line.equals(frame(result.value(),head)) || old==null || old.result()!=null)throw new IllegalArgumentException("Missing or repeated recipe result");
            rows.put(old.intent().token(),new Entry(old.intent(),result)); pending--;
        } else throw new IllegalArgumentException("Unknown recipe evidence frame");
    }

    private void snapshot() {
        var listed=List.copyOf(rows.values());
        var grouped=new HashMap<UUID,List<Entry>>();
        for(var entry:listed)grouped.computeIfAbsent(entry.intent().attempt(),ignored->new ArrayList<>()).add(entry);
        grouped.replaceAll((ignored,group)->List.copyOf(group));
        entries=listed; attempts=Map.copyOf(grouped);
    }
    private void publishRequirement(long size,String digest) throws IOException {
        writeNew(next,requirement(size,digest));
        Files.move(next,required,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        force(required.getParent());
    }
    private byte[] requirement(long size,String digest) {
        return StrictJson.canonical(Map.of("schema",1,"world",world.toString(),"bytes",size,"head",digest)).getBytes(StandardCharsets.UTF_8);
    }
    private static String frame(Map<String,Object> row,String previous) {
        var envelope=new LinkedHashMap<>(row); envelope.put("previous",previous); return StrictJson.canonical(envelope);
    }
    private static List<Participant> participants(Object raw) {
        return ((List<?>)raw).stream().map(o->{var row=(Map<?,?>)o;
            return new Participant((String)row.get("address"),uuid(row,"incarnation"),(Long)row.get("revision"),(String)row.get("slots"));}).toList();
    }
    private static UUID uuid(Map<?,?> row,String key){return UUID.fromString((String)row.get(key));}
    private void thread(){if(Thread.currentThread()!=writer || fenced)throw new IllegalStateException("Recipe evidence writer unavailable");}
    private static boolean present(Path path){return Files.exists(path,LinkOption.NOFOLLOW_LINKS);}
    private static void safe(Path path) throws IOException {
        for(var component=path.toAbsolutePath();component!=null;component=component.getParent())
            if(Files.isSymbolicLink(component))throw new IOException("Symbolic recipe journal path");
    }
    private static BasicFileAttributes attributes(Path path) throws IOException {
        safe(path); var actual=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!actual.isRegularFile())throw new IOException("Recipe evidence file expected"); return actual;
    }
    private static byte[] read(Path path,int limit) throws IOException {
        if(attributes(path).size()>limit)throw new IOException("Recipe evidence byte limit");
        try(var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
            var data=stream.readNBytes(limit+1);
            if(data.length>limit)throw new IOException("Recipe evidence read limit"); return data;
        }
    }
    private static void writeNew(Path path,byte[] bytes) throws IOException {
        try(var file=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer); file.force(true);
        }
    }
    private static void force(Path path) throws IOException {
        try(var file=FileChannel.open(path,StandardOpenOption.READ)){file.force(true);}
    }
    @Override public void close() throws IOException {try{lock.close();}finally{lockChannel.close();}}
}
