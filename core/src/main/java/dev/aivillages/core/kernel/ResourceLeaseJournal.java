package dev.aivillages.core.kernel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;

/** Sole off-tick durable lease writer. Unknown/corrupt formats are preserved inactive. */
public final class ResourceLeaseJournal implements AutoCloseable {
    public static final int SCHEMA=1, MAX_BYTES=1_048_576, MAX_RECORDS=128;
    public static final String WORLD_RELATIVE_PATH="data/cognitivecraft/leases/v1";
    public enum Point { BEFORE_STAGE, AFTER_STAGE, AFTER_BACKUP, BEFORE_REPLACE, AFTER_REPLACE }
    @FunctionalInterface public interface FaultInjector {
        void at(Point point) throws IOException;
        static FaultInjector none(){return point->{ };}
    }
    private final Path directory,current,previous;
    private final FileChannel channel;
    private final FileLock lock;
    private final FaultInjector faults;
    private Snapshot state;
    private boolean readOnly;
    private ResourceLeaseJournal(Path directory,FileChannel channel,FileLock lock,Snapshot state,
                                 boolean readOnly,FaultInjector faults) {
        this.directory=directory;this.channel=channel;this.lock=lock;this.state=state;
        this.readOnly=readOnly;this.faults=faults;
        current=directory.resolve("state.jsonl");previous=directory.resolve("state.prev.jsonl");
    }
    public static ResourceLeaseJournal open(Path world,UUID worldId) throws IOException {
        return open(world,worldId,FaultInjector.none());
    }
    public static ResourceLeaseJournal open(Path world,UUID worldId,FaultInjector faults) throws IOException {
        Path directory=world.resolve(WORLD_RELATIVE_PATH);Files.createDirectories(directory);
        for(Path path:List.of(directory,directory.resolve("state.jsonl"),directory.resolve("state.prev.jsonl"),
                directory.resolve("state.next.jsonl"),directory.resolve("writer.lock")))
            if(Files.isSymbolicLink(path))throw new IOException("Symbolic lease path");
        FileChannel channel=FileChannel.open(directory.resolve("writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock lock=null;
        try {
            try{lock=channel.tryLock();}catch(OverlappingFileLockException occupied){throw new IOException("Lease writer already open",occupied);}
            if(lock==null)throw new IOException("Lease writer already open");
            Path current=directory.resolve("state.jsonl"),previous=directory.resolve("state.prev.jsonl");
            Snapshot state;boolean readOnly=false;
            if(!Files.exists(current,LinkOption.NOFOLLOW_LINKS)&&!Files.exists(previous,LinkOption.NOFOLLOW_LINKS))
                state=Snapshot.empty(worldId);
            else try{state=read(current,worldId);}
            catch(IOException invalid) {
                readOnly=true;
                try{state=read(previous,worldId);}catch(IOException absent){state=Snapshot.empty(worldId);}
            }
            return new ResourceLeaseJournal(directory,channel,lock,state,readOnly,faults);
        }catch(IOException|RuntimeException failed){if(lock!=null)lock.release();channel.close();throw failed;}
    }
    public synchronized Snapshot snapshot(){return state;}
    public synchronized boolean readOnly(){return readOnly;}
    public synchronized Snapshot replace(Snapshot expected,Snapshot next) throws IOException {
        if(readOnly || !state.equals(expected) || !next.world().equals(state.world())
                || next.revision()!=Math.addExact(state.revision(),1))throw new IOException("Lease publication fenced");
        byte[] bytes=encode(next);Path staged=directory.resolve("state.next.jsonl");
        try {
            faults.at(Point.BEFORE_STAGE);write(staged,bytes);faults.at(Point.AFTER_STAGE);
            if(Files.exists(current,LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(current,previous,StandardCopyOption.REPLACE_EXISTING);
                try(var backup=FileChannel.open(previous,StandardOpenOption.WRITE)){backup.force(true);}
            }
            faults.at(Point.AFTER_BACKUP);faults.at(Point.BEFORE_REPLACE);
            Files.move(staged,current,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(var dir=FileChannel.open(directory,StandardOpenOption.READ)){dir.force(true);}
            faults.at(Point.AFTER_REPLACE);state=next;return next;
        }catch(IOException uncertain){readOnly=true;throw uncertain;}
        finally{Files.deleteIfExists(staged);}
    }
    public static byte[] encode(Snapshot state) throws IOException {
        ResourceLeaseService.validateSnapshot(state,Settings.production());
        List<String> rows=new ArrayList<>();
        for(Lease lease:state.leases()) {
            String row=ResourceLeaseCodec.encode(lease);
            try{StrictJson.object(row);}catch(StrictJson.Invalid invalid){throw new IOException("Lease row limit",invalid);}
            rows.add(row);
        }
        String payload=rows.isEmpty()?"":String.join("\n",rows)+"\n";
        String header=StrictJson.canonical(Map.of("schema",1L,"world",state.world().toString(),
                "epoch",state.epoch().toString(),"revision",state.revision(),"generation",state.generation(),
                "records",(long)rows.size(),"sha256",sha(payload.getBytes(StandardCharsets.UTF_8))));
        byte[] bytes=(header+"\n"+payload).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES || rows.size()>MAX_RECORDS)throw new IOException("Lease storage quota");
        return bytes;
    }
    private static Snapshot read(Path path,UUID worldId) throws IOException {
        byte[] bytes;
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(MAX_BYTES+1);}
        if(bytes.length>MAX_BYTES)throw new IOException("Lease input quota");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            int split=text.indexOf('\n');if(split<0)throw new IOException("Lease header");
            Map<String,Object> header=StrictJson.object(text.substring(0,split));
            Outcomes.exact(header,"schema","world","epoch","revision","generation","records","sha256");
            if(Outcomes.number(header,"schema","$")!=1)throw new IOException("Unknown lease schema");
            UUID world=UUID.fromString(Outcomes.string(header,"world","$"));
            if(!world.equals(worldId))throw new IOException("Lease world mismatch");
            String payload=text.substring(split+1);
            if(!sha(payload.getBytes(StandardCharsets.UTF_8)).equals(Outcomes.string(header,"sha256","$")))
                throw new IOException("Lease digest");
            String[] rows=payload.isEmpty()?new String[0]:payload.split("\n",-1);
            int count=rows.length==0?0:rows.length-1;
            if(count>MAX_RECORDS || count!=Outcomes.number(header,"records","$")
                    || rows.length>0&&!rows[rows.length-1].isEmpty())throw new IOException("Lease rows");
            List<Lease> leases=new ArrayList<>();
            for(int n=0;n<count;n++)leases.add(ResourceLeaseCodec.decode(StrictJson.object(rows[n])));
            Snapshot state=new Snapshot(world,UUID.fromString(Outcomes.string(header,"epoch","$")),
                    Outcomes.number(header,"revision","$"),Outcomes.number(header,"generation","$"),leases);
            ResourceLeaseService.validateSnapshot(state,Settings.production());return state;
        }catch(StrictJson.Invalid|RuntimeException|CharacterCodingException invalid){throw new IOException("Invalid lease snapshot",invalid);}
    }
    private static void write(Path path,byte[] bytes) throws IOException {
        try(var output=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);
        }
    }
    private static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    @Override public synchronized void close() throws IOException {try{lock.release();}finally{channel.close();}}
}
