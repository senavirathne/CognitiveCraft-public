package dev.aivillages.core.kernel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

import static dev.aivillages.core.kernel.Blueprint.*;
import static dev.aivillages.core.kernel.BlueprintValidator.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;

/** Sole off-thread structural writer. Immutable bodies and recoverable metadata have separate identities. */
public final class BlueprintStore implements AutoCloseable {
    public static final int SCHEMA=1,MAX_MANIFEST_BYTES=65536,MAX_FILES=100;
    public static final String DOMAIN="blueprint-metadata",WORLD_RELATIVE_PATH="data/cognitivecraft/blueprints/v1";
    public enum Status { STORED, RESOLVED, INVALID_DATA, INCOMPATIBLE, UNSUPPORTED_BLOCK, QUOTA,
        CANCELLED, NOT_FOUND, DENIED, UNAVAILABLE }
    public record Limits(int variants,long bytes,int provenance,int evidence) {
        public Limits {
            if(variants<0 || variants>32 || bytes<0 || bytes>4_194_304 || provenance<0 || provenance>4
                    || evidence<0 || evidence>4)throw new IllegalArgumentException("Blueprint storage limits");
        }
        public static Limits defaults(){return new Limits(32,4_194_304,4,4);}
    }
    public enum Point { BEFORE_BODY, AFTER_BODY, BEFORE_STAGE, AFTER_STAGE, AFTER_BACKUP, BEFORE_REPLACE, AFTER_REPLACE }
    @FunctionalInterface public interface Faults { void at(Point point)throws IOException; }
    public record Provenance(TrustedContext origin,String label) {
        public Provenance {
            Objects.requireNonNull(origin);
            if(label==null || label.length()>64 || label.chars().anyMatch(c->c<32 || c>126))
                throw new IllegalArgumentException("Blueprint label");
        }
    }
    /** Current assessments are advisory; initial requirements are retained independently. */
    public record Evidence(BlueprintValidator.Status status,String target,String fingerprint) {
        public Evidence {
            Objects.requireNonNull(status);
            if(target==null || target.isBlank() || target.length()>64 || fingerprint==null
                    || !fingerprint.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Blueprint evidence");
        }
    }
    public record Record(Ref ref,Map<String,Long> materials,List<Requirement> requirements,String target,
                         List<Provenance> provenance,List<Evidence> evidence) {
        public Record {
            Objects.requireNonNull(ref);
            if(materials.isEmpty() || materials.size()>64 || requirements.isEmpty() || requirements.size()>64
                    || provenance.isEmpty() || provenance.size()>4 || evidence.size()>4 || target==null
                    || target.isBlank() || target.length()>64)throw new IllegalArgumentException("Blueprint record bounds");
            materials.forEach((id,n)->{identifier(id);if(n<1 || n>1024)throw new IllegalArgumentException("Material amount");});
            if(requirements.stream().map(Requirement::block).distinct().count()!=requirements.size()
                    || provenance.stream().map(Provenance::origin).distinct().count()!=provenance.size())
                throw new IllegalArgumentException("Duplicate blueprint metadata");
            materials=Map.copyOf(materials);requirements=List.copyOf(requirements);
            provenance=List.copyOf(provenance);evidence=List.copyOf(evidence);
        }
    }
    public record Snapshot(UUID world,long revision,List<Record> records) {
        public Snapshot {
            Objects.requireNonNull(world);
            if(revision<0 || records.size()>32 || records.stream().map(Record::ref).distinct().count()!=records.size()
                    || records.stream().flatMap(r->r.provenance().stream()).anyMatch(p->!p.origin().scope().worldId().equals(world)))
                throw new IllegalArgumentException("Blueprint snapshot");
            records=List.copyOf(records);
        }
    }
    public record Publication(Status status,Ref ref,int inputBytes,int validationWork,boolean publicationMayHaveSucceeded) { }
    public record Inspection(Status status,Ref ref,Body body,Map<String,Long> materials,List<Provenance> provenance,
                             List<Evidence> evidence,Evidence current) {
        public Inspection {
            materials=Map.copyOf(materials);provenance=List.copyOf(provenance);evidence=List.copyOf(evidence);
        }
        public boolean compatible(){return status==Status.RESOLVED;}
    }
    private final Path directory,current,previous,stage;
    private final UUID world;
    private final Limits limits;
    private final Faults faults;
    private final BiPredicate<TrustedContext,TrustedContext> access;
    private final FileChannel channel;
    private final FileLock lock;
    private Snapshot state;
    private byte[] ownedCurrent;
    private Set<Ref> rollback=Set.of();
    private long epoch;
    private boolean readOnly;
    private WorldRetentionManager manager;
    private BlueprintStore(Path directory,UUID world,Limits limits,Faults faults,
                           BiPredicate<TrustedContext,TrustedContext> access,FileChannel channel,FileLock lock) {
        this.directory=directory;this.world=world;this.limits=limits;this.faults=faults;this.access=access;
        this.channel=channel;this.lock=lock;current=directory.resolve("manifest.jsonl");
        previous=directory.resolve("manifest.prev.jsonl");stage=directory.resolve("manifest.next.jsonl");
        state=new Snapshot(world,0,List.of());
    }
    public static BlueprintStore open(Path world,UUID id)throws IOException {
        return open(world,id,Limits.defaults(),TrustedContext::equals,p->{});
    }
    public static BlueprintStore open(Path world,UUID id,Limits limits,
                                     BiPredicate<TrustedContext,TrustedContext> access,Faults faults)throws IOException {
        Objects.requireNonNull(id);Objects.requireNonNull(limits);Objects.requireNonNull(access);Objects.requireNonNull(faults);
        Path dir=world.resolve(WORLD_RELATIVE_PATH);RetentionFileOwner.safePath(dir);Files.createDirectories(dir);
        Path lockPath=dir.resolve("writer.lock");RetentionFileOwner.safePath(lockPath);
        var channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=null;
        try {
            try{lock=channel.tryLock();}catch(OverlappingFileLockException occupied){throw new IOException("Blueprint writer occupied",occupied);}
            if(lock==null)throw new IOException("Blueprint writer occupied");
            var store=new BlueprintStore(dir,id,limits,faults,access,channel,lock);
            if(Files.exists(store.stage))try {
                decode(read(store.stage,MAX_MANIFEST_BYTES),id);
            }catch(IOException invalid){store.readOnly=true;}
            if(Files.exists(store.previous))try {
                store.rollback=refs(decode(read(store.previous,MAX_MANIFEST_BYTES),id));
            }catch(IOException invalid){store.readOnly=true;}
            if(Files.exists(store.current))try {
                store.ownedCurrent=read(store.current,MAX_MANIFEST_BYTES);
                store.state=decode(store.ownedCurrent,id);
                for(Record record:store.state.records())store.body(record.ref());
            }catch(IOException invalid){store.readOnly=true;}
            else if(Files.exists(store.previous))store.readOnly=true;
            return store;
        }catch(IOException|RuntimeException failed){if(lock!=null)lock.release();channel.close();throw failed;}
    }
    public synchronized boolean readOnly(){return readOnly;}
    public synchronized long revision(){return state.revision();}
    public synchronized int variants(){return state.records().size();}
    private static Set<Ref> refs(Snapshot snapshot) {
        return snapshot.records().stream().map(Record::ref).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    /** Candidate strings remain untrusted even when a caller already ran the public validator. */
    public synchronized Publication store(String candidate,Registry registry,Provenance provenance,BooleanSupplier cancel) {
        if(!provenance.origin().scope().worldId().equals(world))return publication(Status.DENIED,null,0,0,false);
        Result result=BlueprintValidator.validate(candidate,registry,BlueprintValidator.Limits.defaults(),cancel);
        if(!result.valid())return publication(status(result.status()),null,result.inputBytes(),result.work(),false);
        if(readOnly)return publication(Status.UNAVAILABLE,null,result.inputBytes(),result.work(),false);
        Ref ref=result.body().ref();Record old=find(ref);
        if(old!=null && (!old.target().equals(result.target()) || !old.requirements().equals(result.requirements())
                || !old.materials().equals(result.materials())))
            return publication(Status.INCOMPATIBLE,ref,result.inputBytes(),result.work(),false);
        if(old==null && state.records().size()>=limits.variants())
            return publication(Status.QUOTA,ref,result.inputBytes(),result.work(),false);
        var provenanceRows=new ArrayList<>(old==null?List.<Provenance>of():old.provenance());
        int at=-1;for(int i=0;i<provenanceRows.size();i++)if(provenanceRows.get(i).origin().equals(provenance.origin()))at=i;
        if(at<0)provenanceRows.add(provenance);else provenanceRows.set(at,provenance);
        if(provenanceRows.size()>limits.provenance())return publication(Status.QUOTA,ref,result.inputBytes(),result.work(),false);
        Record record=new Record(ref,result.materials(),result.requirements(),result.target(),provenanceRows,
                old==null?List.of():old.evidence());
        if(record.equals(old))return publication(Status.STORED,ref,result.inputBytes(),result.work(),false);
        var rows=new ArrayList<>(state.records());rows.removeIf(r->r.ref().equals(ref));rows.add(record);
        rows.sort(Comparator.comparing(r->r.ref().sha256()));
        try {
            var next=new Snapshot(world,Math.addExact(state.revision(),1),rows);byte[] metadata=encode(next);
            byte[] geometry=result.body().canonical().getBytes(StandardCharsets.UTF_8);
            Amount usage=usage();
            long additional=metadata.length+(ownedCurrent==null?0:ownedCurrent.length);
            if(!Files.exists(bodyPath(ref)))additional=Math.addExact(additional,geometry.length);
            long projected=Math.addExact(usage.bytes(),additional);
            if(projected>limits.bytes() || usage.records()+3>MAX_FILES)
                return publication(Status.QUOTA,ref,result.inputBytes(),result.work(),false);
            if(manager!=null) {
                var capacity=manager.assessReplacement("blueprints",Category.ARTIFACTS,
                        new Amount(projected,usage.records()+3));
                if(capacity.status()!=Capacity.AVAILABLE)return publication(capacity.status()==Capacity.STORAGE_LIMIT_REACHED
                        ?Status.QUOTA:Status.UNAVAILABLE,ref,result.inputBytes(),result.work(),false);
                var pins=new HashSet<>(refs(state));pins.add(ref);
                if(!pin(pins))return publication(Status.UNAVAILABLE,ref,result.inputBytes(),result.work(),false);
            }
            return commit(next,metadata,ref,geometry,cancel,result.inputBytes(),result.work());
        }catch(MetadataQuota quota) {
            return publication(Status.QUOTA,ref,result.inputBytes(),result.work(),false);
        }catch(IOException|RuntimeException unavailable) {
            return publication(Status.UNAVAILABLE,ref,result.inputBytes(),result.work(),false);
        }
    }
    public synchronized Inspection inspect(Ref ref,TrustedContext viewer,Registry registry,BooleanSupplier cancel) {
        Record record=find(ref);
        if(!viewer.scope().worldId().equals(world))return empty(Status.DENIED,ref);
        if(record==null)return empty(Status.NOT_FOUND,ref);
        List<Provenance> visible=record.provenance().stream().filter(p->access.test(p.origin(),viewer)).toList();
        if(visible.isEmpty())return empty(Status.DENIED,ref);
        if(cancel.getAsBoolean())return empty(Status.CANCELLED,ref);
        try {
            Body body=body(ref);
            Result result=BlueprintValidator.validate(body.canonical(),registry,BlueprintValidator.Limits.defaults(),cancel);
            var assessment=assessment(record,result);
            Status outcome=readOnly?Status.UNAVAILABLE:status(assessment.status());
            if(outcome==Status.STORED)outcome=Status.RESOLVED;
            return new Inspection(outcome,ref,body,record.materials(),visible,record.evidence(),assessment);
        }catch(IOException corrupt){return empty(Status.UNAVAILABLE,ref);}
    }
    /** Explicit advisory evidence update; neither re-admits nor substitutes an incompatible body. */
    public synchronized Publication recordCompatibility(Ref ref,TrustedContext viewer,Registry registry,BooleanSupplier cancel) {
        Inspection inspection=inspect(ref,viewer,registry,cancel);
        if(inspection.current()==null || readOnly)return publication(inspection.status(),ref,0,0,false);
        if(limits.evidence()==0)return publication(Status.QUOTA,ref,0,0,false);
        Record record=find(ref);var evidence=new ArrayList<>(record.evidence());
        if(!evidence.isEmpty() && evidence.getLast().equals(inspection.current()))
            return publication(inspection.status(),ref,0,0,false);
        evidence.add(inspection.current());while(evidence.size()>limits.evidence())evidence.removeFirst();
        var rows=new ArrayList<>(state.records());rows.remove(record);
        rows.add(new Record(record.ref(),record.materials(),record.requirements(),record.target(),record.provenance(),evidence));
        rows.sort(Comparator.comparing(r->r.ref().sha256()));
        try {
            var next=new Snapshot(world,Math.addExact(state.revision(),1),rows);byte[] metadata=encode(next);
            Amount usage=usage();long projected=Math.addExact(usage.bytes(),metadata.length+(ownedCurrent==null?0:ownedCurrent.length));
            if(projected>limits.bytes())return publication(Status.QUOTA,ref,0,0,false);
            if(manager!=null) {
                var capacity=manager.assessReplacement("blueprints",Category.ARTIFACTS,new Amount(projected,usage.records()+2));
                if(capacity.status()!=Capacity.AVAILABLE)return publication(capacity.status()==Capacity.STORAGE_LIMIT_REACHED
                        ?Status.QUOTA:Status.UNAVAILABLE,ref,0,0,false);
            }
            Publication committed=commit(next,metadata,ref,null,cancel,0,0);
            return committed.status()==Status.STORED?publication(inspection.status(),ref,0,0,false):committed;
        }catch(MetadataQuota quota){return publication(Status.QUOTA,ref,0,0,false);}
        catch(IOException|RuntimeException unavailable){return publication(Status.UNAVAILABLE,ref,0,0,false);}
    }
    private static Evidence assessment(Record record,Result current) {
        BlueprintValidator.Status status=current.status();
        if(status!=BlueprintValidator.Status.CANCELLED && status!=BlueprintValidator.Status.QUOTA
                && (!current.valid() || !record.target().equals(current.target())
                    || !record.requirements().equals(current.requirements()) || !record.materials().equals(current.materials())))
            status=BlueprintValidator.Status.INCOMPATIBLE;
        return new Evidence(status,current.target(),digest(StrictJson.canonical(Map.of("target",current.target(),
                "requirements",requirements(current.requirements()),"status",status.name(),"reason",current.reason()))));
    }
    private Publication commit(Snapshot next,byte[] metadata,Ref ref,byte[] geometry,
                               BooleanSupplier cancel,int inputBytes,int work) {
        boolean replaced=false;
        try {
            checkpoint(Point.BEFORE_BODY,cancel);verifyOwned();
            if(geometry!=null) {
                Path body=bodyPath(ref);
                if(Files.exists(body)) {
                    if(!Arrays.equals(read(body,65536),geometry))throw new IOException("Immutable blueprint body changed");
                } else {epoch=Math.addExact(epoch,1);writeNew(body,geometry);}
            }
            checkpoint(Point.AFTER_BODY,cancel);checkpoint(Point.BEFORE_STAGE,cancel);
            epoch=Math.addExact(epoch,1);write(stage,metadata);checkpoint(Point.AFTER_STAGE,cancel);
            if(ownedCurrent!=null)write(previous,ownedCurrent);
            checkpoint(Point.AFTER_BACKUP,cancel);checkpoint(Point.BEFORE_REPLACE,cancel);verifyOwned();
            if(!Arrays.equals(read(stage,MAX_MANIFEST_BYTES),metadata))throw new IOException("Blueprint stage changed");
            Files.move(stage,current,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);replaced=true;
            forceDirectory();checkpoint(Point.AFTER_REPLACE,cancel);
            rollback=refs(state);state=next;ownedCurrent=metadata;epoch=Math.addExact(epoch,1);
            if(manager!=null)pin(refs(state));
            return publication(Status.STORED,ref,inputBytes,work,false);
        }catch(Cancelled cancelled){readOnly=true;return publication(Status.CANCELLED,ref,inputBytes,work,replaced);}
        catch(IOException|RuntimeException failed){readOnly=true;return publication(Status.UNAVAILABLE,ref,inputBytes,work,replaced);}
        // Preserve an uncertain stage/body for diagnosis and conservative, manager-approved collection.
    }
    private static final class Cancelled extends IOException { private static final long serialVersionUID=1L; }
    private void checkpoint(Point point,BooleanSupplier cancel)throws IOException {
        if(cancel.getAsBoolean())throw new Cancelled();faults.at(point);if(cancel.getAsBoolean())throw new Cancelled();
    }
    private boolean pin(Set<Ref> refs) {
        var update=manager.references().publishNext("blueprint-publication",refs.stream().map(Blueprint::retentionKey)
                .collect(java.util.stream.Collectors.toSet()),true);
        return update==RetentionRoots.Update.APPLIED || update==RetentionRoots.Update.UNCHANGED;
    }
    private int verifyOwned()throws IOException {
        if(ownedCurrent==null) {
            if(Files.exists(current))throw new IOException("Blueprint manifest changed");
            return 0;
        }
        byte[] actual=read(current,MAX_MANIFEST_BYTES);
        if(!Arrays.equals(actual,ownedCurrent))throw new IOException("Blueprint manifest changed");
        return actual.length;
    }
    private Record find(Ref ref){return state.records().stream().filter(r->r.ref().equals(ref)).findFirst().orElse(null);}
    private Path bodyPath(Ref ref){return directory.resolve(ref.sha256()+".body.json");}
    private Body body(Ref ref)throws IOException {
        try {
            byte[] bytes=read(bodyPath(ref),65536);
            if(!ref.sha256().equals(digest(bytes)))throw new IOException("Blueprint body checksum");
            String json=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            Body body=BlueprintValidator.readCanonical(json);
            if(!body.ref().equals(ref))throw new IOException("Blueprint body identity");
            return body;
        }catch(StrictJson.Invalid|RuntimeException invalid){throw new IOException("Invalid blueprint body",invalid);}
    }
    private Amount usage()throws IOException {
        long bytes=0,files=0;
        try(var paths=Files.newDirectoryStream(directory)) {
            for(Path path:paths) {
                if(++files>MAX_FILES)throw new IOException("Blueprint file limit");
                bytes=Math.addExact(bytes,RetentionFileOwner.attributes(path).size());
            }
        }
        return new Amount(bytes,files);
    }
    public Owner retentionOwner() {
        return new Owner() {
            @Override public String id(){return "blueprints";}
            @Override public long revision(){synchronized(BlueprintStore.this){return epoch;}}
            @Override public void bindPolicy(WorldRetentionManager policy) {
                synchronized(BlueprintStore.this) {
                    if(manager!=null)throw new IllegalStateException("Blueprint policy already bound");
                    manager=policy;
                    if(!pin(refs(state)))throw new IllegalStateException("Blueprint roots unavailable");
                }
            }
            @Override public Cursor open() {
                long captured=revision();
                return new Cursor() {
                    DirectoryStream<Path> stream;Iterator<Path> paths;int seen;boolean started,finished;
                    @Override public long revision(){return captured;}
                    @Override public Page next(int maximum,int allowance)throws IOException {
                        synchronized(BlueprintStore.this) {
                            var entries=new ArrayList<Entry>();int work=0,bytes=0;
                            if(finished)return new Page(entries,true,true,0,0);
                            if(!started){started=true;bytes=verifyOwned();work++;stream=Files.newDirectoryStream(directory);paths=stream.iterator();}
                            while(work<maximum && paths.hasNext()) {
                                // Each file is at most one bounded envelope/body; no unmetered recursive traversal.
                                if(allowance-bytes<65536)break;
                                Path path=paths.next();if(++seen>MAX_FILES)throw new IOException("Blueprint file limit");
                                var attributes=RetentionFileOwner.attributes(path);String name=path.getFileName().toString();
                                if(attributes.size()>65536)throw new IOException("Blueprint file byte limit");
                                boolean isBody=name.matches("[0-9a-f]{64}\\.body\\.json");Ref ref=isBody?new Ref(1,name.substring(0,64)):null;
                                boolean protectedBody=ref!=null && (find(ref)!=null || rollback.contains(ref));
                                String identity=RetentionFileOwner.identity(attributes);
                                if(isBody) {
                                    byte[] content=read(path,65536);bytes+=content.length;
                                    if(!digest(content).equals(ref.sha256()))throw new IOException("Blueprint retention checksum");
                                    try{BlueprintValidator.readCanonical(new String(content,StandardCharsets.UTF_8));}
                                    catch(StrictJson.Invalid|RuntimeException invalid){throw new IOException("Opaque blueprint body",invalid);}
                                }
                                work++;
                                Key key=isBody?retentionKey(ref):new Key("blueprints","file:"+name);
                                Set<TrustedContext> viewers=ref!=null && find(ref)!=null?find(ref).provenance().stream()
                                        .map(Provenance::origin).collect(java.util.stream.Collectors.toSet()):Set.of();
                                entries.add(new Entry(key,Category.ARTIFACTS,new Amount(attributes.size(),isBody?1:0),
                                        List.of(),!isBody || protectedBody,isBody && !protectedBody,viewers,identity));
                            }
                            finished=!paths.hasNext();if(finished)close();
                            return new Page(entries,finished,!readOnly && BlueprintStore.this.epoch==captured,work,bytes);
                        }
                    }
                    @Override public void close()throws IOException{if(stream!=null){stream.close();stream=null;}}
                };
            }
            @Override public OwnerResult collect(Entry entry,long expectedRevision,BooleanSupplier cancel) {
                synchronized(BlueprintStore.this) {
                    if(cancel.getAsBoolean())return OwnerResult.of(OwnerStatus.CANCELLED);
                    if(readOnly)return OwnerResult.of(OwnerStatus.UNAVAILABLE);
                    if(epoch!=expectedRevision)return OwnerResult.of(OwnerStatus.STALE);
                    if(!entry.key().owner().equals("blueprints") || !entry.key().id().matches("[0-9a-f]{64}"))
                        return OwnerResult.of(OwnerStatus.PROTECTED);
                    Ref ref=new Ref(1,entry.key().id());
                    if(find(ref)!=null || rollback.contains(ref))return OwnerResult.of(OwnerStatus.PROTECTED);
                    try {
                        verifyOwned();
                        Path path=bodyPath(ref);
                        if(!RetentionFileOwner.identity(RetentionFileOwner.attributes(path)).equals(entry.identity()))
                            return OwnerResult.of(OwnerStatus.STALE);
                        body(ref);if(cancel.getAsBoolean())return OwnerResult.of(OwnerStatus.CANCELLED);
                        epoch=Math.addExact(epoch,1);Files.delete(path);forceDirectory();
                        return OwnerResult.of(OwnerStatus.COLLECTED);
                    }catch(IOException|RuntimeException failed){return OwnerResult.of(OwnerStatus.UNAVAILABLE);}
                }
            }
        };
    }
    private static List<Object> requirements(List<Requirement> requirements) {
        return requirements.stream().map(r->(Object)Map.of("block",r.block(),"item",r.item(),
                "kind",r.kind().name(),"fingerprint",r.fingerprint())).toList();
    }
    private static Map<String,Object> context(TrustedContext c) {
        return Map.of("principal",c.principal().id().toString(),"world",c.scope().worldId().toString(),"domain",c.scope().domainId().toString());
    }
    private static byte[] encode(Snapshot snapshot)throws IOException {
        var lines=new ArrayList<String>();
        for(Record record:snapshot.records())lines.add(StrictJson.canonical(Map.of("ref",record.ref().sha256(),
                "materials",record.materials(),"requirements",requirements(record.requirements()),"target",record.target(),
                "provenance",record.provenance().stream().map(p->Map.of("origin",context(p.origin()),"label",p.label())).toList(),
                "evidence",record.evidence().stream().map(e->Map.of("status",e.status().name(),"target",e.target(),"fingerprint",e.fingerprint())).toList())));
        String body=lines.isEmpty()?"":String.join("\n",lines)+"\n";
        byte[] bytes=(StrictJson.canonical(Map.of("schema",SCHEMA,"world",snapshot.world().toString(),
                "revision",snapshot.revision(),"records",lines.size(),"sha256",digest(body)))+"\n"+body).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_MANIFEST_BYTES)throw new MetadataQuota();
        decode(bytes,snapshot.world());return bytes;
    }
    private static final class MetadataQuota extends IOException { private static final long serialVersionUID=1L; }
    @SuppressWarnings("unchecked") private static Snapshot decode(byte[] bytes,UUID world)throws IOException {
        try {
            String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();int split=text.indexOf('\n');
            if(split<0)throw new IOException("Blueprint header missing");
            var header=StrictJson.object(text.substring(0,split));Outcomes.exact(header,"schema","world","revision","records","sha256");
            if(Outcomes.number(header,"schema","$")!=SCHEMA || !world.toString().equals(header.get("world")))
                throw new IOException("Unsupported blueprint metadata");
            String body=text.substring(split+1);if(!digest(body).equals(header.get("sha256")))throw new IOException("Blueprint metadata checksum");
            var records=new ArrayList<Record>();
            for(String line:body.split("\n")) {
                if(line.isEmpty())continue;if(records.size()>=32)throw new IOException("Blueprint variant quota");
                var row=StrictJson.object(line);Outcomes.exact(row,"ref","materials","requirements","target","provenance","evidence");
                var materials=new TreeMap<String,Long>();
                ((Map<String,Object>)row.get("materials")).forEach((id,n)->materials.put(id,(Long)n));
                var requirements=new ArrayList<Requirement>();
                for(Object value:(List<?>)row.get("requirements")) {
                    var r=(Map<String,Object>)value;Outcomes.exact(r,"block","item","kind","fingerprint");
                    requirements.add(new Requirement((String)r.get("block"),(String)r.get("item"),Kind.valueOf((String)r.get("kind")),(String)r.get("fingerprint")));
                }
                var provenance=new ArrayList<Provenance>();
                for(Object value:(List<?>)row.get("provenance")) {
                    var p=(Map<String,Object>)value;Outcomes.exact(p,"origin","label");var c=(Map<String,Object>)p.get("origin");
                    Outcomes.exact(c,"principal","world","domain");
                    provenance.add(new Provenance(new TrustedContext(new PrincipalRef(UUID.fromString((String)c.get("principal"))),
                            new ScopeRef(UUID.fromString((String)c.get("world")),UUID.fromString((String)c.get("domain")))),(String)p.get("label")));
                }
                var evidence=new ArrayList<Evidence>();
                for(Object value:(List<?>)row.get("evidence")) {
                    var e=(Map<String,Object>)value;Outcomes.exact(e,"status","target","fingerprint");
                    evidence.add(new Evidence(BlueprintValidator.Status.valueOf((String)e.get("status")),(String)e.get("target"),(String)e.get("fingerprint")));
                }
                records.add(new Record(new Ref(1,(String)row.get("ref")),materials,requirements,(String)row.get("target"),provenance,evidence));
            }
            if(records.size()!=Outcomes.number(header,"records","$"))throw new IOException("Blueprint record count");
            return new Snapshot(world,Outcomes.number(header,"revision","$"),records);
        }catch(StrictJson.Invalid|RuntimeException invalid){throw new IOException("Invalid blueprint metadata",invalid);}
    }
    private static byte[] read(Path path,int maximum)throws IOException {
        RetentionFileOwner.safePath(path);if(RetentionFileOwner.attributes(path).size()>maximum)throw new IOException("Blueprint byte limit");
        try(var input=Files.newInputStream(path)){byte[] bytes=input.readNBytes(maximum+1);if(bytes.length>maximum)throw new IOException("Blueprint grew");return bytes;}
    }
    private static void writeNew(Path path,byte[] bytes)throws IOException {
        RetentionFileOwner.safePath(path);
        try(var output=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){write(output,bytes);}
    }
    private static void write(Path path,byte[] bytes)throws IOException {
        RetentionFileOwner.safePath(path);
        try(var output=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){write(output,bytes);}
    }
    private static void write(FileChannel output,byte[] bytes)throws IOException {
        var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);
    }
    private void forceDirectory()throws IOException{try(var directoryChannel=FileChannel.open(directory,StandardOpenOption.READ)){directoryChannel.force(true);}}
    private static Status status(BlueprintValidator.Status status) {
        return switch(status) {
            case VALID->Status.STORED;case INVALID_DATA->Status.INVALID_DATA;case INCOMPATIBLE->Status.INCOMPATIBLE;
            case UNSUPPORTED_BLOCK->Status.UNSUPPORTED_BLOCK;case QUOTA->Status.QUOTA;case CANCELLED->Status.CANCELLED;
        };
    }
    private static Publication publication(Status status,Ref ref,int bytes,int work,boolean uncertain){return new Publication(status,ref,bytes,work,uncertain);}
    private static Inspection empty(Status status,Ref ref){return new Inspection(status,ref,null,Map.of(),List.of(),List.of(),null);}
    @Override public synchronized void close()throws IOException {
        readOnly=true;
        if(manager!=null)manager.references().publishNext("blueprint-publication",Set.of(),true);
        lock.release();channel.close();
    }
}
