package dev.aivillages.core.kernel.storage;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.aivillages.core.kernel.storage.contract.StorageCodec;
import dev.aivillages.core.kernel.storage.contract.StorageWire;
import dev.aivillages.core.kernel.storage.StorageRegistry.Record;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;

/** Versioned metadata snapshots only. Quantities, Jobs, credits and inferred ACLs are forbidden. */
public final class StorageMetadataCodec {
    public static final String VERSION = "cc.storage-metadata/1";
    public static byte[] encode(Snapshot snapshot, StorageRegistryLimits limits) {
        bounds(snapshot,limits);
        var records = snapshot.records().values().stream().sorted(Comparator.comparing(r -> r.id().toString())).map(StorageMetadataCodec::recordBody).toList();
        var roles = snapshot.assignments().values().stream().sorted(Comparator.comparing(a -> roleSort(a.key()))).map(StorageMetadataCodec::assignmentBody).toList();
        var refs = snapshot.bindings().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString))).map(e -> e.getValue().body()).toList();
        var payload = Map.of("schema",VERSION,"world_id",snapshot.world().toString(),"revision",snapshot.revision(),"records",records,"assignments",roles,"bindings",refs);
        byte[] result = canonical(Map.of("checksum_sha256",digest(canonical(payload)),"payload",payload));
        if (result.length > limits.snapshotBytes()) throw new IllegalArgumentException("metadata byte limit");
        return result;
    }
    public static Snapshot decode(byte[] bytes, UUID expectedWorld, StorageRegistryLimits limits) {
        if (bytes == null || bytes.length > limits.snapshotBytes()) throw new IllegalArgumentException("metadata byte limit");
        Map<String,Object> root = object(parse(bytes)); fields(root,"checksum_sha256","payload");
        if (!Arrays.equals(bytes,canonical(root))) throw new IllegalArgumentException("noncanonical metadata");
        Map<String,Object> p = object(root.get("payload")); fields(p,"schema","world_id","revision","records","assignments","bindings");
        if (!VERSION.equals(p.get("schema"))) throw new IllegalArgumentException("unsupported metadata schema");
        if (!digest(canonical(p)).equals(root.get("checksum_sha256"))) throw new IllegalArgumentException("metadata checksum mismatch");
        UUID world = uuid(p.get("world_id")); if (!world.equals(expectedWorld)) throw new IllegalArgumentException("metadata world mismatch");
        long revision = integer(p.get("revision"),0);
        List<Object> recordRows = list(p.get("records")), roleRows = list(p.get("assignments")), bindingRows = list(p.get("bindings"));
        if (recordRows.size() > limits.records() || roleRows.size() > limits.assignments() || bindingRows.size() > recordRows.size())
            throw new IllegalArgumentException("metadata record/assignment bound");
        var records = new HashMap<UUID,Record>(); var assignments = new HashMap<RoleKey,Assignment>(); var bindings = new HashMap<UUID,StorageWire.Message>();
        for (Object value : recordRows) {
            var b = validate("StorageRecord",object(value)).body(); var stamp = object(b.get("stamp"));
            var grants = new ArrayList<Grant>();
            for (Object entry : list(b.get("acl"))) {
                var grant = object(entry); var permissions = EnumSet.noneOf(Permission.class);
                for (Object permission : list(grant.get("permissions"))) permissions.add(Permission.valueOf((String)permission));
                grants.add(new Grant(owner(object(grant.get("subject"))),permissions));
            }
            var purposes = EnumSet.noneOf(Role.class); for (Object role : list(b.get("roles"))) purposes.add(Role.valueOf((String)role));
            Record record = new Record(uuid(b.get("world_id")),uuid(b.get("storage_id")),(String)b.get("label"),owner(object(b.get("owner"))),
                    purposes,grants,(Long)stamp.get("revision"),(Long)stamp.get("generation"),(Long)b.get("acl_revision"),State.valueOf((String)b.get("state")),(String)b.get("adapter_id"));
            if (records.put(record.id(),record) != null) throw new IllegalArgumentException("duplicate metadata identity");
        }
        for (Object value : roleRows) {
            var b = validate("StorageRoleAssignment",object(value)).body();
            Assignment assignment = new Assignment(uuid(b.get("world_id")),new RoleKey(owner(object(b.get("beneficiary"))),Role.valueOf((String)b.get("role"))),
                    uuid(b.get("storage_id")),(Long)b.get("storage_generation"),(Long)b.get("revision"));
            if (assignments.put(assignment.key(),assignment) != null) throw new IllegalArgumentException("duplicate beneficiary/role key");
        }
        for (Object value : bindingRows) {
            var binding = validate("PhysicalStorageBinding",object(value));
            if (bindings.put(uuid(binding.body().get("storage_id")),binding) != null) throw new IllegalArgumentException("duplicate binding reference");
        }
        Snapshot snapshot = new Snapshot(world,revision,records,assignments,bindings); bounds(snapshot,limits);
        // Row order is deterministic too. Do not silently rewrite a corrupt or legacy file.
        if (!Arrays.equals(bytes,encode(snapshot,limits))) throw new IllegalArgumentException("noncanonical metadata row order");
        return snapshot;
    }
    static Map<String,Object> recordBody(Record r) {
        var grants = r.acl().stream().map(g -> Map.of("subject",ownerBody(g.subject()),"permissions",g.permissions().stream().map(Enum::name).sorted().toList())).toList();
        var b = new TreeMap<String,Object>();
        b.put("storage_id",r.id().toString()); b.put("world_id",r.world().toString()); b.put("label",r.label()); b.put("owner",ownerBody(r.owner()));
        b.put("roles",r.purposes().stream().map(Enum::name).sorted().toList()); b.put("acl",grants);
        b.put("stamp",Map.of("schema_version",1L,"revision",r.revision(),"generation",r.generation())); b.put("acl_revision",r.aclRevision());
        b.put("state",r.state().name()); b.put("adapter_id",r.adapterId()); validate("StorageRecord",b); return b;
    }
    static Map<String,Object> assignmentBody(Assignment a) {
        var b = Map.<String,Object>of("world_id",a.world().toString(),"beneficiary",ownerBody(a.key().beneficiary()),"role",a.key().role().name(),
                "storage_id",a.storage().toString(),"storage_generation",a.storageGeneration(),"revision",a.revision());
        validate("StorageRoleAssignment",b); return b;
    }
    static Map<String,Object> ownerBody(Owner owner) {
        return owner.kind() == OwnerKind.PUBLIC ? Map.of("kind",owner.kind().name()) : Map.of("kind",owner.kind().name(),"id",owner.id().toString());
    }
    private static Owner owner(Map<String,Object> value) {
        OwnerKind kind = OwnerKind.valueOf((String)value.get("kind")); return new Owner(kind,kind == OwnerKind.PUBLIC ? null : uuid(value.get("id")));
    }
    private static String roleSort(RoleKey key) { return key.beneficiary().kind().name()+":"+key.beneficiary().id()+":"+key.role().name(); }
    private static StorageWire.Message validate(String kind, Map<String,Object> body) {
        return StorageCodec.decode(canonical(Map.of("schema",StorageWire.VERSION,"kind",kind,"body",body)));
    }
    private static void bounds(Snapshot snapshot, StorageRegistryLimits limits) {
        if (snapshot.records().size() > limits.records() || snapshot.assignments().size() > limits.assignments()) throw new IllegalArgumentException("metadata record/assignment bound");
    }
    private static Object parse(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (var reader = new JsonReader(new StringReader(text))) {
                reader.setLenient(false); Object result = value(reader,0,new int[]{0});
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("trailing metadata"); return result;
            }
        } catch (IOException | IllegalStateException e) { throw new IllegalArgumentException("invalid metadata JSON",e); }
    }
    private static Object value(JsonReader reader, int depth, int[] tokens) throws IOException {
        if (depth > 32 || ++tokens[0] > 1_500_000) throw new IllegalArgumentException("metadata parser limit");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); var map = new TreeMap<String,Object>();
                while (reader.hasNext()) {
                    String key = string(reader.nextName()); if (map.containsKey(key) || key.length() > 128) throw new IllegalArgumentException("duplicate/oversized metadata field");
                    map.put(key,value(reader,depth+1,tokens));
                }
                reader.endObject(); yield map;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); var list = new ArrayList<Object>();
                while (reader.hasNext()) { if (list.size() >= 4097) throw new IllegalArgumentException("metadata array bound"); list.add(value(reader,depth+1,tokens)); }
                reader.endArray(); yield list;
            }
            case STRING -> string(reader.nextString());
            case NUMBER -> {
                String n = reader.nextString(); if (!n.matches("-?(0|[1-9][0-9]*)")) throw new IllegalArgumentException("integer metadata only");
                long result = Long.parseLong(n); if (result < -StorageRegistryLimits.MAX_INTEGER || result > StorageRegistryLimits.MAX_INTEGER) throw new IllegalArgumentException("metadata integer range"); yield result;
            }
            case NULL -> { reader.nextNull(); yield null; }
            case BOOLEAN -> reader.nextBoolean();
            default -> throw new IllegalArgumentException("metadata JSON value");
        };
    }
    private static String string(String text) {
        if (text.length() > 1024 || !Normalizer.isNormalized(text,Normalizer.Form.NFC)) throw new IllegalArgumentException("metadata NFC/string bound");
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i==text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("metadata surrogate"); }
            else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("metadata surrogate");
        }
        return text;
    }
    static byte[] canonical(Object value) { var out = new StringBuilder(); append(value,out); return out.append('\n').toString().getBytes(StandardCharsets.UTF_8); }
    private static void append(Object value, StringBuilder out) {
        if (value instanceof Map<?,?> map) {
            out.append('{'); var keys = new TreeSet<String>(); map.keySet().forEach(k -> keys.add((String)k)); boolean first=true;
            for (String key : keys) { if (!first) out.append(','); first=false; quote(key,out); out.append(':'); append(map.get(key),out); } out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('['); for (int i=0;i<list.size();i++) { if (i!=0) out.append(','); append(list.get(i),out); } out.append(']');
        } else if (value instanceof String s) quote(s,out);
        else if (value == null) out.append("null");
        else if (value instanceof Long || value instanceof Integer || value instanceof Boolean) out.append(value);
        else throw new IllegalArgumentException("metadata JSON type");
    }
    private static void quote(String value, StringBuilder out) {
        string(value); out.append('"');
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t"); case '\b' -> out.append("\\b"); case '\f' -> out.append("\\f");
                default -> { if (c<32) out.append(String.format("\\u%04x",(int)c)); else out.append(c); }
            }
        }
        out.append('"');
    }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static UUID uuid(Object value) {
        if (!(value instanceof String s) || !s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IllegalArgumentException("metadata UUID");
        return UUID.fromString(s);
    }
    private static long integer(Object value, long minimum) {
        if (!(value instanceof Long n) || n < minimum || n > StorageRegistryLimits.MAX_INTEGER) throw new IllegalArgumentException("metadata revision"); return n;
    }
    private static void fields(Map<String,Object> value, String... names) { if (!value.keySet().equals(Set.of(names))) throw new IllegalArgumentException("missing or unknown metadata fields"); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { if (!(value instanceof Map<?,?>)) throw new IllegalArgumentException("metadata object required"); return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { if (!(value instanceof List<?>)) throw new IllegalArgumentException("metadata array required"); return (List<Object>)value; }
    private StorageMetadataCodec() { }
}
