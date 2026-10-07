package dev.aivillages.fabric;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded runtime object witnesses; replacing a block entity invalidates the old observation. */
final class LeaseTargetWitnesses {
    private record Entry(Object target,String token) { }
    private final int cap;
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    LeaseTargetWitnesses(int cap) {
        if(cap<1 || cap>128)throw new IllegalArgumentException("Witness limit");this.cap=cap;
    }
    String identity(String key,Object target) {
        if(key==null || key.length()>128 || target==null)throw new IllegalArgumentException("Target witness");
        Entry previous=entries.get(key);
        if(previous!=null && previous.target()==target)return previous.token();
        if(previous==null && entries.size()==cap)throw new IllegalStateException("Witness capacity");
        Entry next=new Entry(target,UUID.randomUUID().toString());entries.put(key,next);return next.token();
    }
    int retained(){return entries.size();}
}
