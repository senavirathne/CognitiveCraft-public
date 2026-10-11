package dev.aivillages.core.kernel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Sole off-tick Plan writer. No jobs, assignments, receipts, budgets or executable frames are stored here. */
public final class PlanJournal implements AutoCloseable {
    public static final int SCHEMA=1,MAX_BYTES=262144;
    public static final String DOMAIN="goal-plans",WORLD_RELATIVE_PATH="data/cognitivecraft/plans/v1";
    public enum Point {BEFORE_STAGE,AFTER_STAGE,AFTER_BACKUP,BEFORE_REPLACE,AFTER_REPLACE}
    @FunctionalInterface public interface Faults {void at(Point point)throws IOException;}
    public record Link(UUID id,UUID parent,TrustedContext origin,List<UUID> children,List<ArtifactRef> methods) {
        public Link {children=List.copyOf(children);methods=List.copyOf(methods);
            if(!Plan.id(parent).equals(id) || children.size()<2 || children.size()>8 || methods.size()>8
                    || new HashSet<>(children).size()!=children.size())throw new IllegalArgumentException("Plan linkage");}
    }
    /** Only topology survives. Quantities are normalized placeholders; source observations/bindings/methods are omitted. */
    public record Template(UUID id,TrustedContext owner,Plan.Proposal topology) {
        public Template {Objects.requireNonNull(id);Objects.requireNonNull(owner);Objects.requireNonNull(topology);
            if(topology.nodes().size()!=2 || topology.nodes().stream().anyMatch(n->n.amount()!=1)
                    || topology.nodes().stream().map(Plan.Node::slot).distinct().count()!=2
                    || topology.nodes().stream().anyMatch(n->n.slot()<0 || n.slot()>1))throw new IllegalArgumentException("Template bounds");}
    }
    public record Snapshot(UUID world,long revision,List<Link> links,List<Template> templates) {
        public Snapshot {Objects.requireNonNull(world);links=List.copyOf(links);templates=List.copyOf(templates);
            if(revision<0 || links.size()>16 || templates.size()>8 || links.stream().map(Link::id).distinct().count()!=links.size()
                    || templates.stream().map(Template::id).distinct().count()!=templates.size()
                    || links.stream().anyMatch(l->!l.origin().scope().worldId().equals(world))
                    || templates.stream().anyMatch(t->!t.owner().scope().worldId().equals(world)))throw new IllegalArgumentException("Plan snapshot bounds");}
        public static Snapshot empty(UUID world){return new Snapshot(world,0,List.of(),List.of());}
    }
    private final Path directory,current,previous;
    private final FileChannel channel;private final FileLock lock;private final Faults faults;
    private Snapshot state;private boolean readOnly;private byte[] ownedCurrent;
    private java.util.function.IntPredicate capacity=bytes->true;
    private PlanJournal(Path directory,FileChannel channel,FileLock lock,Faults faults,Snapshot state,boolean readOnly,byte[] owned) {
        this.directory=directory;this.channel=channel;this.lock=lock;this.faults=faults;this.state=state;this.readOnly=readOnly;
        current=directory.resolve("state.jsonl");previous=directory.resolve("state.prev.jsonl");ownedCurrent=owned;
    }
    public static PlanJournal open(Path world,UUID id)throws IOException{return open(world,id,p->{});}
    public static PlanJournal open(Path world,UUID id,Faults faults)throws IOException {
        Path dir=world.resolve(WORLD_RELATIVE_PATH);RetentionFileOwner.safePath(dir);Files.createDirectories(dir);
        for(String name:List.of("state.jsonl","state.prev.jsonl","state.next.jsonl","writer.lock"))RetentionFileOwner.safePath(dir.resolve(name));
        var channel=FileChannel.open(dir.resolve("writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=null;
        try {
            try {lock=channel.tryLock();}catch(OverlappingFileLockException occupied){throw new IOException("Plan writer occupied",occupied);}
            if(lock==null)throw new IOException("Plan writer occupied");
            Path current=dir.resolve("state.jsonl"),previous=dir.resolve("state.prev.jsonl");
            Snapshot state=Snapshot.empty(id);boolean inactive=false;byte[] owned=null;
            if(Files.exists(current))try{owned=bytes(current);state=decode(owned,id);}catch(IOException invalid){inactive=true;}
            else if(Files.exists(previous))inactive=true;
            if(inactive)try{state=decode(bytes(previous),id);}catch(IOException ignored){state=Snapshot.empty(id);}
            return new PlanJournal(dir,channel,lock,faults,state,inactive,owned);
        }catch(IOException|RuntimeException failure){if(lock!=null)lock.release();channel.close();throw failure;}
    }
    public synchronized Snapshot snapshot(){return state;}
    public synchronized boolean readOnly(){return readOnly;}
    public synchronized void capacity(java.util.function.IntPredicate check){capacity=Objects.requireNonNull(check);}
    public synchronized Snapshot replace(Snapshot expected,Snapshot next)throws IOException {
        if(readOnly || !state.equals(expected) || !next.world().equals(state.world()) || next.revision()!=Math.addExact(state.revision(),1))
            throw new IOException("Inactive or stale Plan writer");
        byte[] encoded=encode(next);if(!capacity.test(encoded.length))throw new IOException("Plan retention capacity");
        Path stage=directory.resolve("state.next.jsonl");
        try {
            verifyOwned();faults.at(Point.BEFORE_STAGE);write(stage,encoded);faults.at(Point.AFTER_STAGE);
            if(ownedCurrent!=null)write(previous,ownedCurrent);
            faults.at(Point.AFTER_BACKUP);faults.at(Point.BEFORE_REPLACE);verifyOwned();
            if(!Arrays.equals(encoded,bytes(stage)))throw new IOException("Plan stage changed");
            Files.move(stage,current,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(var dir=FileChannel.open(directory,StandardOpenOption.READ)){dir.force(true);}
            faults.at(Point.AFTER_REPLACE);state=next;ownedCurrent=encoded;return next;
        }catch(IOException uncertain){readOnly=true;throw uncertain;}
        finally{Files.deleteIfExists(stage);}
    }
    private void verifyOwned()throws IOException {
        if(ownedCurrent==null?Files.exists(current):!Arrays.equals(ownedCurrent,bytes(current)))throw new IOException("Plan current changed");
    }
    public WorldRetentionManager.Owner retentionOwner() {
        return new RetentionFileOwner("plans",directory,WorldRetentionManager.Category.EVIDENCE,
                ()->snapshot().revision(),()->snapshot().links().size()+snapshot().templates().size(),4,MAX_BYTES*3L);
    }
    public static byte[] encode(Snapshot state)throws IOException {
        var rows=new ArrayList<String>();
        for(var link:state.links())rows.add(StrictJson.canonical(Map.of("kind","link","id",link.id().toString(),"parent",link.parent().toString(),
                "origin",scope(link.origin()),"children",link.children().stream().map(UUID::toString).toList(),"methods",link.methods().stream().map(
                        m->Map.of("capability",m.capability().name(),"version",m.capability().version(),"sha256",m.sha256())).toList())));
        for(var template:state.templates())try{rows.add(StrictJson.canonical(Map.of("kind","template","id",template.id().toString(),
                "owner",scope(template.owner()),"topology",StrictJson.object(Plan.encode(template.topology())))));}catch(StrictJson.Invalid invalid){throw new IOException(invalid);}
        String body=rows.isEmpty()?"":String.join("\n",rows)+"\n";
        var header=StrictJson.canonical(Map.of("schema",SCHEMA,"world",state.world().toString(),"revision",state.revision(),"records",rows.size(),"sha256",sha(body.getBytes(StandardCharsets.UTF_8))));
        byte[] out=(header+"\n"+body).getBytes(StandardCharsets.UTF_8);if(out.length>MAX_BYTES)throw new IOException("Plan byte quota");return out;
    }
    private static Snapshot decode(byte[] bytes,UUID world)throws IOException {
        try {
            String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();int split=text.indexOf('\n');
            if(split<0)throw new IOException("Plan header missing");var header=StrictJson.object(text.substring(0,split));
            Outcomes.exact(header,"schema","world","revision","records","sha256");
            if(Outcomes.number(header,"schema","$")!=SCHEMA || !world.toString().equals(header.get("world")))throw new IOException("Plan schema/world unavailable");
            String body=text.substring(split+1);if(!sha(body.getBytes(StandardCharsets.UTF_8)).equals(header.get("sha256")))throw new IOException("Plan checksum");
            var links=new ArrayList<Link>();var templates=new ArrayList<Template>();int records=0;
            for(String line:body.split("\n")) {
                if(line.isEmpty())continue;if(++records>24)throw new IOException("Plan record quota");var row=StrictJson.object(line);
                if("link".equals(row.get("kind"))) {
                    Outcomes.exact(row,"kind","id","parent","origin","children","methods");var children=new ArrayList<UUID>();var methods=new ArrayList<ArtifactRef>();
                    for(Object id:(List<?>)row.get("children"))children.add(UUID.fromString((String)id));
                    for(Object value:(List<?>)row.get("methods")) {
                        @SuppressWarnings("unchecked") var m=(Map<String,Object>)value;Outcomes.exact(m,"capability","version","sha256");
                        methods.add(new ArtifactRef(new CapabilityId(Outcomes.string(m,"capability","$"),Math.toIntExact(Outcomes.number(m,"version","$"))),Outcomes.string(m,"sha256","$")));
                    }
                    links.add(new Link(UUID.fromString((String)row.get("id")),UUID.fromString((String)row.get("parent")),scope(Outcomes.object(row,"origin","$")),children,methods));
                }else if("template".equals(row.get("kind"))) {
                    Outcomes.exact(row,"kind","id","owner","topology");templates.add(new Template(UUID.fromString((String)row.get("id")),
                            scope(Outcomes.object(row,"owner","$")),Plan.decode(StrictJson.canonical(row.get("topology")),8192)));
                }else throw new IOException("Unknown Plan record");
            }
            if(records!=Outcomes.number(header,"records","$"))throw new IOException("Plan count");
            return new Snapshot(world,Outcomes.number(header,"revision","$"),links,templates);
        }catch(StrictJson.Invalid|RuntimeException invalid){throw new IOException("Invalid Plan snapshot",invalid);}
    }
    private static Map<String,Object> scope(TrustedContext c){return Map.of("principal",c.principal().id().toString(),"world",c.scope().worldId().toString(),"domain",c.scope().domainId().toString());}
    private static TrustedContext scope(Map<String,Object> m)throws StrictJson.Invalid{
        Outcomes.exact(m,"principal","world","domain");return new TrustedContext(new PrincipalRef(UUID.fromString((String)m.get("principal"))),
                new ScopeRef(UUID.fromString((String)m.get("world")),UUID.fromString((String)m.get("domain"))));}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static byte[] bytes(Path path)throws IOException{RetentionFileOwner.safePath(path);if(Files.size(path)>MAX_BYTES)throw new IOException("Plan byte quota");return Files.readAllBytes(path);}
    private static void write(Path path,byte[] bytes)throws IOException{RetentionFileOwner.safePath(path);try(var output=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
        var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);}}
    @Override public synchronized void close()throws IOException{lock.release();channel.close();}
}
