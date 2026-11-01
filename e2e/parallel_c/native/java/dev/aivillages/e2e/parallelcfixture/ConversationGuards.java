package dev.aivillages.e2e.parallelcfixture;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Observers only. Active native mixins count actual calls without cancelling or altering them. */
public final class ConversationGuards {
    private static final Set<String> READY=ConcurrentHashMap.newKeySet();
    private static final AtomicLong JOBS=new AtomicLong(),WORLD=new AtomicLong();
    private ConversationGuards() { }
    public static void ready(String hook){READY.add(hook);CConversationServer.event("guard-ready",Map.of("hook",hook));}
    public static void job(){JOBS.incrementAndGet();CConversationServer.event("job-call",snapshot());}
    public static void world(){WORLD.incrementAndGet();CConversationServer.event("world-operation-call",snapshot());}
    public static Map<String,Object> snapshot(){return Map.of("job_calls",JOBS.get(),"world_operation_calls",WORLD.get(),"active_hooks",READY.stream().sorted().toList());}
}
