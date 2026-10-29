package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit;
import java.io.*;
import java.util.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;

/** Detached native entity-record facts; no receipt or execution authority. */
public final class EntityChunkRecord {
    public static final String MEMBERSHIP_TAG="cognitivecraft_entity_membership";
    public static final int MAX_ROOT_BYTES=262_144,MAX_ENTITIES=4096,MAX_DEPTH=8;
    private static final List<String> KEYS=List.of(EntityChunkRemovalState.TAG,EntityChunkRemovalState.TAG+"_original",MEMBERSHIP_TAG,MEMBERSHIP_TAG+"_original");
    private EntityChunkRecord(){}
    public static final class Frame {
        private final ChunkPos position;private final Set<UUID> members;private final CompoundTag metadata;
        private final String digest;private final boolean supported,exists;
        private Frame(ChunkPos p,Set<UUID> m,CompoundTag tag,String hash,boolean valid,boolean present) {
            position=p;members=Set.copyOf(m);metadata=tag;digest=hash;supported=valid;exists=present;
        }
        public ChunkPos position(){return position;}public Set<UUID> members(){return members;}public String digest(){return digest;}
        public boolean supported(){return supported;}public boolean exists(){return exists;}
        public CompoundTag metadata(){if(metadata.sizeInBytes()>MAX_ROOT_BYTES)throw new IllegalStateException("Unsupported metadata size");return metadata.copy();}
    }
    public static boolean hasMetadata(CompoundTag root){return root!=null&&KEYS.stream().anyMatch(root::contains);}
    private static CompoundTag feature(CompoundTag root,boolean copy) {
        var out=new CompoundTag();if(root!=null)for(String key:KEYS)if(root.contains(key)){Tag value=Objects.requireNonNull(root.get(key));out.put(key,copy?value.copy():value);}return out;
    }
    private static void append(CompoundTag target,CompoundTag metadata){for(String key:KEYS)if(metadata.contains(key))target.put(key,Objects.requireNonNull(metadata.get(key)));}
    public static CompoundTag empty(ChunkPos position) {
        var root=NbtUtils.addCurrentDataVersion(new CompoundTag());root.put("Entities",new ListTag());root.store("Position",ChunkPos.CODEC,position);return root;
    }
    private static void collect(ListTag entities,Set<UUID> ids,int depth) {
        if(depth>MAX_DEPTH)throw new IllegalArgumentException("Native passenger depth");
        for(Tag value:entities) {
            if(!(value instanceof CompoundTag entity))throw new IllegalArgumentException("Native entity record shape");
            UUID id=entity.read("UUID",UUIDUtil.CODEC).orElseThrow(()->new IllegalArgumentException("Native entity UUID"));
            if(!ids.add(id)||ids.size()>MAX_ENTITIES)throw new IllegalArgumentException("Duplicate or excessive native entities");
            Tag passengers=entity.get("Passengers");
            if(passengers!=null){if(!(passengers instanceof ListTag nested))throw new IllegalArgumentException("Native passenger shape");collect(nested,ids,depth+1);}
        }
    }
    private static Set<UUID> members(CompoundTag root,ChunkPos expected) {
        if(root.sizeInBytes()>MAX_ROOT_BYTES||!expected.equals(root.read("Position",ChunkPos.CODEC).orElse(null)))throw new IllegalArgumentException("Native chunk binding or size");
        if(!(root.get("Entities") instanceof ListTag entities))throw new IllegalArgumentException("Native Entities list required");
        var ids=new HashSet<UUID>();collect(entities,ids,0);return Set.copyOf(ids);
    }
    public static Frame read(CompoundTag root,String dimension,ChunkPos position) {
        if(root==null)return new Frame(position,Set.of(),new CompoundTag(),NativeDeposit.sha("native-empty:"+dimension+":"+position),true,false);
        CompoundTag metadata=feature(root,false);
        try {
            Set<UUID> members=members(root,position);
            var state=EntityChunkRemovalState.load(metadata,true,dimension,position,members);
            if(!state.valid()||!EntityChunkMembershipState.load(metadata,dimension,position,members).valid())return new Frame(position,members,metadata,"",false,true);
            var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){root.write(out);}
            if(bytes.size()>MAX_ROOT_BYTES)throw new IllegalArgumentException("Native byte quota");
            return new Frame(position,members,metadata.copy(),NativeDeposit.sha(bytes.toByteArray()),true,true);
        }catch(RuntimeException|IOException invalid){return new Frame(position,Set.of(),metadata,"",false,true);}
    }
    private static CompoundTag quarantine(CompoundTag metadata) {
        if(!metadata.contains(EntityChunkRemovalState.TAG)||metadata.contains(EntityChunkRemovalState.TAG+"_original"))return metadata;
        var out=feature(metadata,false);out.putString(EntityChunkRemovalState.TAG,"{\"quarantined\":true,\"schema\":0}");
        out.put(EntityChunkRemovalState.TAG+"_original",feature(metadata,false));return out;
    }
    /** Off-thread metadata merge before actual native I/O; vanilla Entities/Position remain unchanged. */
    public static CompoundTag merge(CompoundTag previous,CompoundTag fresh,String dimension,ChunkPos position,List<EntityChunkRemovalState.Removal> desired) {
        return merge(previous,fresh,dimension,position,desired,List.of());
    }
    public static CompoundTag merge(CompoundTag previous,CompoundTag fresh,String dimension,ChunkPos position,List<EntityChunkRemovalState.Removal> desired,
                                    List<EntityChunkMembershipState.Row> membership) {
        Objects.requireNonNull(position);desired=List.copyOf(desired);membership=List.copyOf(membership);
        if(membership.size()>EntityChunkMembershipState.MAX_ROWS)throw new IllegalArgumentException("Native membership write quota");
        if(desired.size()>EntityChunkRemovalState.MAX_REMOVALS)throw new IllegalArgumentException("Native removal write quota");
        if(fresh==null&&!hasMetadata(previous)&&desired.isEmpty()&&membership.isEmpty())return null;
        CompoundTag target=fresh==null?empty(position):fresh,metadata=feature(previous,false);
        Set<UUID> oldMembers=Set.of(),newMembers=Set.of();boolean structural=true;
        try{if(previous!=null)oldMembers=members(previous,position);newMembers=members(target,position);}catch(RuntimeException invalid){structural=false;}
        if(!structural){append(target,quarantine(metadata));return target;}
        var state=EntityChunkRemovalState.load(metadata,true,dimension,position,oldMembers);
        if(state.valid()&&!metadata.contains(MEMBERSHIP_TAG)&&!metadata.contains(MEMBERSHIP_TAG+"_original"))for(var row:desired)state.record(row);
        var captured=state.capture(newMembers);var next=feature(metadata,false);var removal=captured.metadata();
        for(String key:List.of(EntityChunkRemovalState.TAG,EntityChunkRemovalState.TAG+"_original"))if(removal.contains(key))next.put(key,Objects.requireNonNull(removal.get(key)));
        var members=EntityChunkMembershipState.load(metadata,dimension,position,oldMembers);
        if(members.valid())for(var row:membership)members.record(row);
        var capturedMembers=members.capture(newMembers);
        for(String key:List.of(MEMBERSHIP_TAG,MEMBERSHIP_TAG+"_original"))if(capturedMembers.contains(key))next.put(key,Objects.requireNonNull(capturedMembers.get(key)));
        append(target,next);return target;
    }
}
