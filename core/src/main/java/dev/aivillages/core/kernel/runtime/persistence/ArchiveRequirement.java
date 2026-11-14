package dev.aivillages.core.kernel.runtime.persistence;

import dev.aivillages.core.kernel.StrictJson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Durable world-level reader requirement, outside the replaceable version directory. */
public final class ArchiveRequirement {
    public static final String PATH="data/cognitivecraft/runtime-history/required.json";
    public static final String PENDING=PATH+".next";
    public static final int MAX_BYTES=256;
    private ArchiveRequirement() { }
    record Inspection(boolean required,boolean valid,int files,long bytes) { }
    public static boolean shouldRead(Path world) {
        return Files.exists(world.resolve(ArchiveHistory.RELATIVE_PATH),LinkOption.NOFOLLOW_LINKS)
                || Files.exists(world.resolve(PATH),LinkOption.NOFOLLOW_LINKS)
                || Files.exists(world.resolve(PENDING),LinkOption.NOFOLLOW_LINKS);
    }
    private static byte[] body(UUID world) {
        return StrictJson.canonical(Map.of("schema",1L,"world",world.toString())).getBytes(StandardCharsets.UTF_8);
    }
    static Inspection inspect(Path world,UUID id)throws IOException {
        int files=0;long bytes=0;boolean valid=true,required=false;
        for(String name:List.of(PATH,PENDING)) {
            Path path=world.resolve(name);ArchiveHistory.safe(path);
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))continue;
            required=true;files++;
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Regular history requirement required");
            bytes=Math.addExact(bytes,Files.size(path));
            if(name.equals(PENDING) || Files.size(path)>MAX_BYTES || !Arrays.equals(ArchiveHistory.read(path,MAX_BYTES),body(id)))valid=false;
        }
        return new Inspection(required,valid,files,bytes);
    }
    static void ensure(Path world,UUID id)throws IOException {
        Inspection before=inspect(world,id);
        if(!before.valid())throw new IOException("Invalid or pending history reader requirement");
        if(before.required())return;
        Path path=world.resolve(PATH),pending=world.resolve(PENDING);ArchiveHistory.safe(path);Files.createDirectories(path.getParent());
        try(var channel=FileChannel.open(pending,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes=ByteBuffer.wrap(body(id));while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
        }
        Files.move(pending,path,StandardCopyOption.ATOMIC_MOVE);
        for(Path directory:List.of(path.getParent(),path.getParent().getParent()))
            try(var channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}
    }
}
