package dev.aivillages.core.kernel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static dev.aivillages.core.kernel.KnowledgeAccess.*;

/** Sole off-thread access writer. Unknown, corrupt and uncertain generations stay preserved and inactive. */
final class KnowledgeAccessJournal implements AutoCloseable {
    static final String PATH="data/cognitivecraft/knowledge-access/v1";
    enum Point { BEFORE_STAGE, AFTER_STAGE, AFTER_BACKUP, BEFORE_REPLACE, AFTER_REPLACE }
    @FunctionalInterface interface Faults { void at(Point point)throws IOException; }
    static final class Cancelled extends IOException { }
    private final Path directory,current,previous,staged;
    private final FileChannel channel;
    private final FileLock lock;
    private final Faults faults;
    private byte[] currentBytes,previousBytes;
    private volatile Snapshot state;
    private volatile boolean readOnly;
    private boolean mayHaveSucceeded;
    private KnowledgeAccessJournal(Path directory,FileChannel channel,FileLock lock,UUID world,Faults faults)throws IOException{
        this.directory=directory;this.channel=channel;this.lock=lock;this.faults=faults;
        current=directory.resolve("state.jsonl");previous=directory.resolve("state.prev.jsonl");staged=directory.resolve("state.next.jsonl");
        state=Snapshot.empty(world);
        try(var files=Files.newDirectoryStream(directory)){
            int count=0;for(Path file:files){if(++count>8)throw new IOException("Access file bound");RetentionFileOwner.attributes(file);
                if(!Set.of("state.jsonl","state.prev.jsonl","state.next.jsonl","writer.lock").contains(file.getFileName().toString()))readOnly=true;}
        }
        try{
            currentBytes=readOptional(current);previousBytes=readOptional(previous);
            if(currentBytes!=null)state=KnowledgeAccessCodec.decode(currentBytes,world);
            else if(previousBytes!=null)readOnly=true;
            if(previousBytes!=null)KnowledgeAccessCodec.decode(previousBytes,world);
            if(Files.exists(staged,LinkOption.NOFOLLOW_LINKS)){
                KnowledgeAccessCodec.decode(readOptional(staged),world);readOnly=true;
            }
        }catch(IOException|RuntimeException invalid){readOnly=true;state=Snapshot.empty(world);}
    }
    static KnowledgeAccessJournal open(Path world,UUID id,Faults faults)throws IOException{
        Path directory=world.resolve(PATH);RetentionFileOwner.safePath(directory);Files.createDirectories(directory);
        for(String name:List.of("writer.lock","state.jsonl","state.prev.jsonl","state.next.jsonl"))RetentionFileOwner.safePath(directory.resolve(name));
        FileChannel channel=FileChannel.open(directory.resolve("writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock lock=null;
        try{
            try{lock=channel.tryLock();}catch(OverlappingFileLockException occupied){throw new IOException("Access writer already open",occupied);}
            if(lock==null)throw new IOException("Access writer already open");return new KnowledgeAccessJournal(directory,channel,lock,id,faults);
        }catch(IOException|RuntimeException error){if(lock!=null)lock.release();channel.close();throw error;}
    }
    Snapshot snapshot(){return state;}
    boolean readOnly(){return readOnly;}
    boolean mayHaveSucceeded(){return mayHaveSucceeded;}
    long reservation(int nextBytes)throws IOException{
        long other=0;try(var files=Files.newDirectoryStream(directory)){
            int count=0;for(Path file:files){if(++count>8)throw new IOException("Access file bound");var attrs=RetentionFileOwner.attributes(file);
                if(!file.equals(current)&&!file.equals(previous))other=Math.addExact(other,attrs.size());}
        }
        long now=currentBytes==null?0:currentBytes.length,prior=previousBytes==null?0:previousBytes.length;
        return Math.addExact(other,Math.addExact(nextBytes,Math.max(Math.addExact(now,prior),Math.multiplyExact(now,2))));
    }
    void replace(Snapshot expected,Snapshot next,byte[] bytes,BooleanSupplier cancel)throws IOException{
        if(readOnly||state!=expected||!next.world().equals(state.world())||next.revision()!=Math.addExact(state.revision(),1))throw new IOException("Fenced access publication");
        boolean touched=false;
        try{
            checkpoint(Point.BEFORE_STAGE,cancel);ownership();
            touched=true;writeNew(staged,bytes);checkpoint(Point.AFTER_STAGE,cancel);ownership();
            if(currentBytes!=null){
                Files.copy(current,previous,StandardCopyOption.REPLACE_EXISTING);
                try(var file=FileChannel.open(previous,StandardOpenOption.WRITE)){file.force(true);}
                previousBytes=currentBytes.clone();
            }
            checkpoint(Point.AFTER_BACKUP,cancel);checkpoint(Point.BEFORE_REPLACE,cancel);ownership();
            Files.move(staged,current,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            mayHaveSucceeded=true;
            try(var file=FileChannel.open(directory,StandardOpenOption.READ)){file.force(true);}
            checkpoint(Point.AFTER_REPLACE,cancel);state=next;currentBytes=bytes.clone();mayHaveSucceeded=false;
        }catch(IOException|RuntimeException failure){if(touched||!(failure instanceof Cancelled))readOnly=true;throw failure;}
    }
    private void checkpoint(Point point,BooleanSupplier cancel)throws IOException{
        if(cancel.getAsBoolean())throw new Cancelled();faults.at(point);if(cancel.getAsBoolean())throw new Cancelled();
    }
    private void ownership()throws IOException{
        if(!Arrays.equals(currentBytes,readOptional(current))||!Arrays.equals(previousBytes,readOptional(previous)))throw new IOException("Changed access ownership");
    }
    private static byte[] readOptional(Path file)throws IOException{
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        if(RetentionFileOwner.attributes(file).size()>KnowledgeAccessCodec.MAX_BYTES)throw new IOException("Access byte bound");
        byte[] data=Files.readAllBytes(file);if(data.length>KnowledgeAccessCodec.MAX_BYTES)throw new IOException("Access grew");return data;
    }
    private static void writeNew(Path file,byte[] bytes)throws IOException{
        RetentionFileOwner.safePath(file);try(var channel=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);
        }
    }
    Path directory(){return directory;}
    @Override public void close()throws IOException{readOnly=true;lock.release();channel.close();}
}
