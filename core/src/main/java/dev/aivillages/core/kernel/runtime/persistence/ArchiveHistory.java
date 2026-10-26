package dev.aivillages.core.kernel.runtime.persistence;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static dev.aivillages.core.kernel.runtime.persistence.ArchiveFormat.*;

/** Off-thread, read-only bounded archive view. A fenced view never proves UUID absence. */
public final class ArchiveHistory {
    public static final String RELATIVE_PATH="data/cognitivecraft/runtime-history/v1";
    public static final String CURRENT="manifest.jsonl", PREVIOUS="manifest.prev.jsonl", PENDING="manifest.next.jsonl";
    public enum State { READY, FENCED }
    private final State state;
    private final Manifest manifest;
    private final Map<Key,Row> current;
    private final Set<String> references, redundant;
    private final long bytes;
    private final List<String> diagnostics;
    private ArchiveHistory(State state,Manifest manifest,Map<Key,Row> current,Set<String> references,
            Set<String> redundant,long bytes,List<String> diagnostics) {
        this.state=state;this.manifest=manifest;this.current=Map.copyOf(current);
        this.references=Set.copyOf(references);this.redundant=Set.copyOf(redundant);this.bytes=bytes;this.diagnostics=List.copyOf(diagnostics);
    }
    public State state(){return state;}
    public Manifest manifest(){return manifest;}
    public long bytes(){return bytes;}
    public int records(){return current.size();}
    public Set<String> protectedSegments(){return references;}
    public Set<String> redundantSegments(){return redundant;}
    public List<String> diagnostics(){return diagnostics;}
    public Optional<Row> lookup(Key key)throws IOException {
        if(state!=State.READY)throw new IOException("Fenced archive cannot prove presence or absence");
        return Optional.ofNullable(current.get(Objects.requireNonNull(key)));
    }
    public static ArchiveHistory read(Path world,UUID worldId,PersistencePolicy policy)throws IOException {
        Objects.requireNonNull(world);Objects.requireNonNull(worldId);Objects.requireNonNull(policy);
        Path root=world.resolve(RELATIVE_PATH);safe(root);
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))
            return new ArchiveHistory(State.READY,Manifest.empty(worldId),Map.of(),Set.of(),Set.of(),0,List.of());
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Archive directory required");
        var diagnostics=new ArrayList<String>();var inventory=new LinkedHashMap<String,Long>();long physical=0;int segmentFiles=0;
        try(var stream=Files.newDirectoryStream(root)) {
            for(Path file:stream) {
                if(inventory.size()>=policy.files())throw new IOException("Archive file quota");safe(file);
                var attr=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!attr.isRegularFile())throw new IOException("Unexpected archive entry");
                physical=Math.addExact(physical,attr.size());if(physical>policy.archiveBytes())throw new IOException("Archive byte quota");
                String name=file.getFileName().toString();inventory.put(name,attr.size());
                if(name.matches("[0-9a-f]{64}\\.seg") && ++segmentFiles>policy.segments())throw new IOException("Archive segment quota");
                if(!Set.of(CURRENT,PREVIOUS,PENDING,"writer.lock").contains(name) && !name.matches("[0-9a-f]{64}\\.seg"))
                    diagnostic(diagnostics,"UNKNOWN_FILE");
            }
        } catch(ArithmeticException overflow){throw new IOException("Archive usage overflow",overflow);}
        Manifest chosen=Manifest.empty(worldId);var manifests=new LinkedHashMap<String,Manifest>();var captured=new HashMap<String,byte[]>();
        for(String name:List.of(CURRENT,PREVIOUS,PENDING)) if(inventory.containsKey(name)) {
            try {
                byte[] content=read(root.resolve(name),PersistencePolicy.MANIFEST_BYTES);captured.put(name,content);
                manifests.put(name,ArchiveFormat.manifest(content,worldId));
            } catch(IOException invalid){diagnostic(diagnostics,"INVALID_"+name);}
        }
        if(manifests.containsKey(CURRENT))chosen=manifests.get(CURRENT);
        else if(manifests.containsKey(PREVIOUS)) {chosen=manifests.get(PREVIOUS);diagnostic(diagnostics,"BACKUP_INSPECTION_ONLY");}
        else if(inventory.size()>(inventory.containsKey("writer.lock")?1:0))diagnostic(diagnostics,"NO_CURRENT_MANIFEST");
        if(inventory.containsKey(PENDING))diagnostic(diagnostics,"PENDING_PUBLICATION");
        if(manifests.containsKey(CURRENT) && manifests.containsKey(PREVIOUS)) {
            var previous=manifests.get(PREVIOUS);
            if(previous.revision()>=chosen.revision() || previous.retentionGeneration()>chosen.retentionGeneration()
                    || Arrays.stream(Owner.values()).anyMatch(owner->previous.ownerRevisions().get(owner)>manifests.get(CURRENT).ownerRevisions().get(owner)))
                diagnostic(diagnostics,"NONMONOTONIC_MANIFEST_FENCE");
        }
        var segments=new HashMap<String,Segment>();var segmentBytes=new HashMap<String,Integer>();var references=new HashSet<String>();
        var rows=new LinkedHashMap<Key,Row>();
        for(var m:manifests.values()) for(var ref:m.segments()) {
            references.add(ref.digest());
            try {
                Segment segment=segments.get(ref.digest());
                if(segment==null) {
                    byte[] content=read(root.resolve(ref.digest()+".seg"),PersistencePolicy.SEGMENT_BYTES);
                    if(content.length!=ref.bytes() || !sha256(content).equals(ref.digest()))throw new IOException("Archive reference checksum");
                    segment=ArchiveFormat.segment(content,worldId);segments.put(ref.digest(),segment);segmentBytes.put(ref.digest(),content.length);
                }
                if(segmentBytes.get(ref.digest()).longValue()!=ref.bytes() || segment.owner()!=ref.owner() || segment.rows().size()!=ref.records()
                        || segment.guard().ownerRevision()>m.ownerRevisions().get(ref.owner())
                        || segment.guard().retentionGeneration()>m.retentionGeneration())throw new IOException("Archive owner fence");
                if(m==chosen)for(var row:segment.rows())merge(rows,new Key(segment.owner(),row.kind(),row.id()),row);
            } catch(IOException invalid){diagnostic(diagnostics,"INVALID_REFERENCED_SEGMENT");}
        }
        // Repacking may change segment names, never the exact immutable UUID history.
        for(var m:manifests.values())for(var ref:m.segments()) {
            var segment=segments.get(ref.digest());if(segment==null)continue;
            for(var row:segment.rows())if(!row.equals(rows.get(new Key(segment.owner(),row.kind(),row.id()))))
                diagnostic(diagnostics,"LOST_OR_CONFLICTING_UUID");
        }
        var redundant=new HashSet<String>();
        for(String name:inventory.keySet())if(name.matches("[0-9a-f]{64}\\.seg") && !references.contains(name.substring(0,64))) {
            try {
                byte[] content=read(root.resolve(name),PersistencePolicy.SEGMENT_BYTES);
                if(!sha256(content).equals(name.substring(0,64)))throw new IOException("Orphan checksum");
                var segment=ArchiveFormat.segment(content,worldId);
                if(segment.rows().stream().allMatch(row->row.equals(rows.get(new Key(segment.owner(),row.kind(),row.id())))))
                    redundant.add(name.substring(0,64));
                else diagnostic(diagnostics,"UNACKNOWLEDGED_UUID_HISTORY");
            }catch(IOException invalid){diagnostic(diagnostics,"INVALID_UNREFERENCED_SEGMENT");}
        }
        for(var e:captured.entrySet())if(!Arrays.equals(e.getValue(),read(root.resolve(e.getKey()),PersistencePolicy.MANIFEST_BYTES)))
            diagnostic(diagnostics,"MANIFEST_CHANGED_DURING_READ");
        return new ArchiveHistory(diagnostics.isEmpty()?State.READY:State.FENCED,chosen,rows,references,redundant,physical,diagnostics);
    }
    private static void merge(Map<Key,Row> rows,Key key,Row value)throws IOException {
        Row previous=rows.putIfAbsent(key,value);
        if(previous!=null && !previous.equals(value))throw new IOException("Conflicting exact UUID payload");
    }
    static byte[] read(Path path,int limit)throws IOException {
        safe(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>limit)throw new IOException("Bounded regular archive file required");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Archive input quota");return bytes;
        }
    }
    static void safe(Path path)throws IOException {
        for(Path part=path.toAbsolutePath();part!=null;part=part.getParent())
            if(Files.isSymbolicLink(part))throw new IOException("Symbolic archive path");
    }
    private static void diagnostic(List<String> values,String message) {
        if(values.size()<16 && !values.contains(message))values.add(message);
    }
}
