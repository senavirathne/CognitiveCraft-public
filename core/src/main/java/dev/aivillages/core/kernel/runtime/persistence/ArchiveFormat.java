package dev.aivillages.core.kernel.runtime.persistence;

import dev.aivillages.core.kernel.StrictJson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import static dev.aivillages.core.kernel.runtime.persistence.PersistencePolicy.*;

/** Reader-first format. No encoder, mutation, domain admission or physical proof authority. */
public final class ArchiveFormat {
    private ArchiveFormat() { }
    public static final long MAGIC = 0x4343414849535431L; // CCAHIST1
    public static final int SCHEMA = 1;
    public enum Owner { JOBS, LEASES }
    public enum Kind { HISTORY, INTENT, RESULT, CANCELLATION, SETTLEMENT }
    public enum Phase { RESERVED, RESULT, CANCELLING, SETTLED }
    public record Guard(long ownerRevision, long retentionGeneration) {
        public Guard { if(ownerRevision<0 || retentionGeneration<0)throw new IllegalArgumentException("Archive fence"); }
    }
    public record Key(Owner owner, Kind kind, UUID id) {
        public Key { Objects.requireNonNull(owner);Objects.requireNonNull(kind);Objects.requireNonNull(id); }
    }
    public record Row(Kind kind, UUID id, byte[] payload) {
        public Row {
            Objects.requireNonNull(kind);Objects.requireNonNull(id);Objects.requireNonNull(payload);
            if(payload.length<1 || payload.length>RECORD_BYTES)throw new IllegalArgumentException("Archive record bound");
            payload=payload.clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
        @Override public boolean equals(Object value) {
            return value instanceof Row r && kind==r.kind && id.equals(r.id) && Arrays.equals(payload,r.payload);
        }
        @Override public int hashCode() { return Objects.hash(kind,id,Arrays.hashCode(payload)); }
    }
    public record Segment(UUID world, Owner owner, Guard guard, List<Row> rows) {
        public Segment {
            Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(guard);Objects.requireNonNull(rows);
            if(rows.isEmpty() || rows.size()>SEGMENT_ROWS)throw new IllegalArgumentException("Segment row count");
            rows=List.copyOf(rows);
            var keys=new HashSet<Key>();long bytes=8+4+16+1+8+8+4+32;
            for(var r:rows) {
                if(!keys.add(new Key(owner,r.kind(),r.id())))throw new IllegalArgumentException("Duplicate segment UUID");
                bytes=Math.addExact(bytes,21L+r.payload.length);
            }
            if(bytes>SEGMENT_BYTES)throw new IllegalArgumentException("Segment byte bound");
        }
    }
    public record Ref(String digest, Owner owner, long bytes, int records, long createdEpochMillis) {
        public Ref {
            hash(digest);Objects.requireNonNull(owner);
            if(bytes<90 || bytes>SEGMENT_BYTES || records<1 || records>SEGMENT_ROWS || createdEpochMillis<0)
                throw new IllegalArgumentException("Segment reference bounds");
        }
    }
    public record Slot(UUID id, Owner owner, UUID root, UUID attempt, long generation,
                       long reservedBytes, long remainingBytes, Phase phase) {
        public Slot {
            Objects.requireNonNull(id);Objects.requireNonNull(owner);Objects.requireNonNull(root);
            Objects.requireNonNull(attempt);Objects.requireNonNull(phase);
            if(generation<1 || reservedBytes<1 || reservedBytes>RECORD_BYTES || remainingBytes<0 || remainingBytes>reservedBytes)
                throw new IllegalArgumentException("Original-attempt reservation bound");
        }
    }
    public record Migration(UUID id, Owner owner, int sourceSchema, String sourceDigest,
                            long targetRevision, String targetDigest) {
        public Migration {
            Objects.requireNonNull(id);Objects.requireNonNull(owner);hash(sourceDigest);hash(targetDigest);
            if(sourceSchema<0 || sourceSchema>3 || owner==Owner.LEASES && sourceSchema!=1 || targetRevision<1)
                throw new IllegalArgumentException("Compatible migration identity");
        }
    }
    public record Manifest(UUID world, long revision, Map<Owner,Long> ownerRevisions,
            long retentionGeneration, List<Ref> segments, List<Slot> slots, List<Migration> migrations) {
        public Manifest {
            Objects.requireNonNull(world);ownerRevisions=Map.copyOf(ownerRevisions);
            segments=List.copyOf(segments);slots=List.copyOf(slots);migrations=List.copyOf(migrations);
            if(revision<0 || retentionGeneration<0 || ownerRevisions.size()!=Owner.values().length
                    || !ownerRevisions.keySet().containsAll(List.of(Owner.values()))
                    || ownerRevisions.values().stream().anyMatch(v->v<0) || segments.size()>MAX_SEGMENTS
                    || slots.size()>8 || migrations.size()>8)
                throw new IllegalArgumentException("Manifest bounds");
            if(segments.stream().map(Ref::digest).distinct().count()!=segments.size()
                    || slots.stream().map(Slot::id).distinct().count()!=slots.size()
                    || migrations.stream().map(Migration::id).distinct().count()!=migrations.size()
                    || migrations.stream().anyMatch(m->m.targetRevision()>=revision))
                throw new IllegalArgumentException("Conflicting manifest identities");
        }
        public static Manifest empty(UUID world) {
            return new Manifest(world,0,Map.of(Owner.JOBS,0L,Owner.LEASES,0L),0,List.of(),List.of(),List.of());
        }
    }
    static void hash(String value) {
        if(value==null || !value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("SHA-256 identity");
    }
    public static String sha256(byte[] value) { return HexFormat.of().formatHex(checksum(value)); }
    static byte[] checksum(byte[] value) {
        try{return MessageDigest.getInstance("SHA-256").digest(value);}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static Segment segment(byte[] bytes, UUID expectedWorld) throws IOException {
        if(bytes.length<90 || bytes.length>SEGMENT_BYTES)throw new IOException("Segment size");
        try {
            var data=ByteBuffer.wrap(bytes,0,bytes.length-32);
            if(!MessageDigest.isEqual(checksum(Arrays.copyOf(bytes,bytes.length-32)),Arrays.copyOfRange(bytes,bytes.length-32,bytes.length)))
                throw new IOException("Segment checksum");
            if(data.getLong()!=MAGIC || data.getInt()!=SCHEMA)throw new IOException("Unknown segment schema");
            UUID world=new UUID(data.getLong(),data.getLong());if(!world.equals(expectedWorld))throw new IOException("Foreign archive world");
            Owner owner=Owner.values()[Byte.toUnsignedInt(data.get())];var guard=new Guard(data.getLong(),data.getLong());
            int count=data.getInt();if(count<1 || count>SEGMENT_ROWS)throw new IOException("Segment row count");
            var rows=new ArrayList<Row>();
            for(int n=0;n<count;n++) {
                Kind kind=Kind.values()[Byte.toUnsignedInt(data.get())];UUID id=new UUID(data.getLong(),data.getLong());
                int size=data.getInt();if(size<1 || size>RECORD_BYTES || size>data.remaining())throw new IOException("Record size");
                byte[] payload=new byte[size];data.get(payload);rows.add(new Row(kind,id,payload));
            }
            if(data.hasRemaining())throw new IOException("Trailing segment data");
            return new Segment(world,owner,guard,rows);
        } catch(IllegalArgumentException | IndexOutOfBoundsException | java.nio.BufferUnderflowException invalid) {
            throw new IOException("Invalid bounded segment",invalid);
        }
    }
    public static Manifest manifest(byte[] bytes, UUID expectedWorld) throws IOException {
        if(bytes.length<2 || bytes.length>MANIFEST_BYTES)throw new IOException("Manifest size");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            int line=text.indexOf('\n');if(line<1)throw new IOException("Manifest framing");
            var header=StrictJson.object(text.substring(0,line));var payload=StrictJson.object(text.substring(line+1));
            exact(header,"schema","world","revision","owners","retentionGeneration","records","sha256");
            if(number(header,"schema")!=SCHEMA)throw new IOException("Unknown manifest schema");
            String worldText=string(header,"world");UUID world=UUID.fromString(worldText);
            if(!world.toString().equals(worldText) || !world.equals(expectedWorld))throw new IOException("Foreign archive world");
            String digest=string(header,"sha256");hash(digest);
            if(!sha256(text.substring(line+1).getBytes(StandardCharsets.UTF_8)).equals(digest))throw new IOException("Manifest checksum");
            var owners=object(header.get("owners"));exact(owners,"JOBS","LEASES");
            exact(payload,"segments","slots","migrations");var refs=new ArrayList<Ref>();
            for(var value:list(payload,"segments",MAX_SEGMENTS)) {
                var r=object(value);exact(r,"digest","owner","bytes","records","createdEpochMillis");
                refs.add(new Ref(string(r,"digest"),Owner.valueOf(string(r,"owner")),number(r,"bytes"),
                        Math.toIntExact(number(r,"records")),number(r,"createdEpochMillis")));
            }
            if(number(header,"records")!=refs.size())throw new IOException("Manifest reference count");
            var slots=new ArrayList<Slot>();
            for(var value:list(payload,"slots",8)) {
                var r=object(value);exact(r,"id","owner","root","attempt","generation","reservedBytes","remainingBytes","phase");
                slots.add(new Slot(uuid(r,"id"),Owner.valueOf(string(r,"owner")),uuid(r,"root"),uuid(r,"attempt"),
                        number(r,"generation"),number(r,"reservedBytes"),number(r,"remainingBytes"),Phase.valueOf(string(r,"phase"))));
            }
            var migrations=new ArrayList<Migration>();
            for(var value:list(payload,"migrations",8)) {
                var r=object(value);exact(r,"id","owner","sourceSchema","sourceDigest","targetRevision","targetDigest");
                migrations.add(new Migration(uuid(r,"id"),Owner.valueOf(string(r,"owner")),Math.toIntExact(number(r,"sourceSchema")),
                        string(r,"sourceDigest"),number(r,"targetRevision"),string(r,"targetDigest")));
            }
            return new Manifest(world,number(header,"revision"),Map.of(Owner.JOBS,number(owners,"JOBS"),Owner.LEASES,number(owners,"LEASES")),
                    number(header,"retentionGeneration"),refs,slots,migrations);
        } catch(StrictJson.Invalid | IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("Invalid bounded manifest",invalid);
        }
    }
    private static UUID uuid(Map<String,Object> row,String key)throws IOException {
        String value=string(row,key);UUID id=UUID.fromString(value);
        if(!id.toString().equals(value))throw new IOException("Canonical UUID required");return id;
    }
    private static void exact(Map<String,Object> row,String... keys)throws IOException {
        if(!row.keySet().equals(Set.of(keys)))throw new IOException("Unknown or missing archive field");
    }
    private static long number(Map<String,Object> row,String key)throws IOException {
        if(!(row.get(key) instanceof Long n) || n<0)throw new IOException("Nonnegative signed-64-bit integer required");return n;
    }
    private static String string(Map<String,Object> row,String key)throws IOException {
        if(!(row.get(key) instanceof String text))throw new IOException("String required");return text;
    }
    private static Map<String,Object> object(Object value)throws IOException {
        if(!(value instanceof Map<?,?> values))throw new IOException("Object required");
        var result=new LinkedHashMap<String,Object>();
        for(var e:values.entrySet()) {if(!(e.getKey() instanceof String key))throw new IOException("Object key");result.put(key,e.getValue());}
        return result;
    }
    private static List<?> list(Map<String,Object> row,String key,int maximum)throws IOException {
        if(!(row.get(key) instanceof List<?> values) || values.size()>maximum)throw new IOException("Bounded archive array required");return values;
    }
}
