package dev.aivillages.fabric;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** A10 plain observer transport: a stopped JVM never shares its unfinished tail with its successor. */
public final class RuntimeAObservationEpoch {
    private static final UUID PROCESS=UUID.randomUUID();
    private static final long START=System.currentTimeMillis(), PID=ProcessHandle.current().pid();
    private static final boolean ENABLED=Set.of("A00","A01","A02","A03")
            .contains(System.getenv().getOrDefault("COGNITIVECRAFT_A10_PROFILE",""));
    private static final Map<Path,Path> PATHS=new ConcurrentHashMap<>();
    private RuntimeAObservationEpoch() { }
    public static void append(Path legacy,String exactFrame)throws IOException {
        Path target=legacy;
        if(ENABLED) {
            String name=legacy.getFileName().toString();
            if(!name.equals("observations.jsonl") && !name.equals("secondary-observations.jsonl"))throw new IOException("Known observation stream required");
            if(PATHS.size()>=2 && !PATHS.containsKey(legacy))throw new IOException("Bounded native observer streams");
            target=PATHS.computeIfAbsent(legacy,p->p.resolveSibling(name.substring(0,name.length()-6)+".epoch-"
                    +String.format(Locale.ROOT,"%019d",START)+"-"+PID+"-"+PROCESS+".jsonl"));
        }
        Files.writeString(target,exactFrame+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
}
