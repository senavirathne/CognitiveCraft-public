package dev.aivillages.core.kernel;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Stream;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;
import static dev.aivillages.core.kernel.PrimitiveDiagnostics.*;
import static dev.aivillages.core.kernel.PrimitiveDiagnosticCodec.*;

/** Disposable diagnostic-only I/O. Every method is worker/host-only, never called by gameplay. */
public final class PrimitiveDiagnosticStore implements PrimitiveDiagnosticStorePort {
    public static final Path RELATIVE_ROOT=Path.of("data","cognitivecraft","diagnostics","primitive","v1");
    private static final Set<PosixFilePermission> DIRECTORY_PERMS=PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS=PosixFilePermissions.fromString("rw-------");
    private final Path root;
    private final Policy policy;
    private final UserPrincipal account;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final byte[] salt;
    private final Faults faults;
    private final Set<String> inactiveReports=new HashSet<>();
    private Segment activeSegment;
    private final List<Segment> segments=new ArrayList<>();
    private int replayIndex,maintenanceCursor;
    private BufferedInputStream replay;
    private final ByteArrayOutputStream line=new ByteArrayOutputStream(8192);
    private boolean oversized,closed;
    private long replayBytes,records,peakBytes,reportExpiry=-1;
    private int peakFiles,maxMaintenance;
    private static final class Segment {
        final Path path; long bucket=-1,bytes,lastSeen; boolean unknown;
        Segment(Path path,long bytes) { this.path=path;this.bytes=bytes; }
    }

    public enum Point { OPEN, DIRECTORY, READ, WRITE, DELETE, LOCK, CLOSE }
    @FunctionalInterface public interface Faults {
        void check(Point point) throws IOException;
        default int write(FileChannel channel,ByteBuffer bytes) throws IOException { return channel.write(bytes); }
        static Faults none(){return point->{};}
    }
    private PrimitiveDiagnosticStore(Path world,Policy policy,boolean create,Faults faults) throws IOException {
        this.faults=Objects.requireNonNull(faults);faults.check(Point.OPEN);
        if(!policy.enabled())throw failure();
        this.policy=policy;
        Path normalized=world.toAbsolutePath().normalize();
        account=normalized.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        root=normalized.resolve(RELATIVE_ROOT).normalize();
        if(!root.startsWith(normalized))throw failure();
        rejectSymlinkAncestors(root);
        if(create) privateDirectories(root); else if(!Files.isDirectory(root,NOFOLLOW_LINKS))throw failure();
        for(Path p=root;p!=null&&p.startsWith(normalized);p=p.getParent())
            if(!Files.getOwner(p,NOFOLLOW_LINKS).equals(account))throw failure();
        for(Path p=root;p.startsWith(root.getParent().getParent());p=p.getParent())verifyPrivate(p,true);
        Inventory inventory=inventory();
        boolean history=inventory.paths.stream().anyMatch(p->!p.getFileName().toString().equals("writer.lock"));
        Path lockPath=root.resolve("writer.lock");
        if(!Files.exists(lockPath,NOFOLLOW_LINKS)) {
            if(!create)throw failure();
            createPrivate(lockPath);
        }
        verifyPrivate(lockPath,false);
        FileChannel channel=FileChannel.open(lockPath,READ,WRITE,NOFOLLOW_LINKS);
        FileLock acquired=null;
        try {
            faults.check(Point.LOCK);acquired=channel.tryLock();
            if(acquired==null)throw failure();
            Path saltPath=root.resolve("salt.bin");
            if(!Files.exists(saltPath,NOFOLLOW_LINKS)) {
                if(!create||history)throw failure();
                reserve(32,1);
                byte[] fresh=new byte[32];new SecureRandom().nextBytes(fresh);
                createPrivate(saltPath);
                try(FileChannel out=FileChannel.open(saltPath,WRITE,NOFOLLOW_LINKS)) {
                    if(out.write(ByteBuffer.wrap(fresh))!=32)throw failure();
                }
            }
            verifyPrivate(saltPath,false);
            if(Files.size(saltPath)!=32)throw failure();
            salt=Files.readAllBytes(saltPath);
            for(Path path:inventory().paths) {
                String name=path.getFileName().toString();
                if(name.matches("segment-[0-7]\\.jsonl")) {
                    long size=Files.size(path);
                    if(size>policy.limit(Limit.SEGMENT_BYTES))throw failure();
                    segments.add(new Segment(path,size));
                }
                if(name.equals("report.json")||name.equals("report.json.tmp")) {
                    if(Files.size(path)>65536)throw failure();
                    try {
                        var m=PrimitiveDiagnosticCodec.parse(Files.readAllBytes(path),65536);
                        if(num(m,"schema")==1 && str(m,"kind").equals("primitive_diagnostic_report")) {
                            long expiry=num(m,"expiresAt");
                            if(expiry<0)throw failure();
                            reportExpiry=reportExpiry<0?expiry:Math.min(reportExpiry,expiry);
                        } else inactiveReports.add(name);
                    } catch(IllegalArgumentException ignored) {
                        // An unrecognized report is inactive and consumes quota; never quarantine/copy it.
                        inactiveReports.add(name);
                    }
                }
            }
            if(segments.size()>policy.limit(Limit.SEGMENTS))throw failure();
            long total=0;for(Segment segment:segments)total=Math.addExact(total,segment.bytes);
            if(total>policy.limit(Limit.REPLAY_BYTES))throw failure();
            segments.sort(Comparator.comparing(s->s.path.getFileName().toString()));
            lockChannel=channel;lock=acquired;
        } catch(Throwable e) {
            if(acquired!=null)try{acquired.release();}catch(IOException ignored){}
            try{channel.close();}catch(IOException ignored){}
            if(e instanceof Error error)throw error;
            throw failure();
        }
    }
    public static PrimitiveDiagnosticStore open(Path world,Policy policy) throws IOException {
        return open(world,policy,Faults.none());
    }
    public static PrimitiveDiagnosticStore open(Path world,Policy policy,Faults faults) throws IOException {
        return new PrimitiveDiagnosticStore(world,policy,true,faults);
    }
    static PrimitiveDiagnosticStore existing(Path world,Policy policy) throws IOException {
        return new PrimitiveDiagnosticStore(world,policy,false,Faults.none());
    }
    @Override public byte[] salt() { return salt.clone(); }
    @Override public ReplaySlice replay(int maxRecords,int maxBytes) throws IOException {
        check();faults.check(Point.READ);
        List<Snapshot> rows=new ArrayList<>();int inspected=0,used=0,invalid=0;
        while(replayIndex<segments.size() && inspected<maxRecords && used<maxBytes) {
            Segment segment=segments.get(replayIndex);
            if(replay==null) {
                verifyPrivate(segment.path,false);
                replay=new BufferedInputStream(Channels.newInputStream(
                        FileChannel.open(segment.path,READ,NOFOLLOW_LINKS)),8192);
            }
            int next=replay.read();
            if(next<0) {
                if(line.size()>0||oversized){invalid++;inspected++;record();}
                replay.close();replay=null;replayIndex++;line.reset();oversized=false;continue;
            }
            used++;replayBytes=Math.addExact(replayBytes,1);
            if(replayBytes>policy.limit(Limit.REPLAY_BYTES))throw failure();
            if(next=='\n') {
                inspected++;record();
                if(oversized||line.size()==0||line.size()+1>8192)invalid++;
                else {
                    byte[] bytes=line.toByteArray();
                    try {
                        Map<String,Object> header=PrimitiveDiagnosticCodec.parse(bytes,8192);
                        long schema=num(header,"schema");
                        if(schema>1){segment.unknown=true;throw new IOException("Future diagnostic schema inactive");}
                        else {
                            Snapshot row=decode(bytes);
                            if(segment.bucket<0)segment.bucket=row.bucketStart();
                            else if(segment.bucket!=row.bucketStart())throw failure();
                            segment.lastSeen=Math.max(segment.lastSeen,row.lastSeen());
                            rows.add(row);
                        }
                    } catch(IllegalArgumentException bad){invalid++;}
                }
                line.reset();oversized=false;
            } else if(line.size()<8192)line.write(next);else oversized=true;
        }
        if(replayIndex>=segments.size())activeSegment=segments.stream().filter(s->!s.unknown&&s.bucket>=0)
                .max(Comparator.comparingLong((Segment s)->s.bucket).thenComparingLong(s->s.lastSeen)
                        .thenComparing(s->s.path.getFileName().toString())).orElse(null);
        return new ReplaySlice(List.copyOf(rows),inspected,used,invalid,replayIndex>=segments.size());
    }
    private void record() throws IOException {
        records=Math.addExact(records,1);
        if(records>policy.limit(Limit.REPLAY_RECORDS))throw failure();
    }
    @Override public int maintain(long now,int allowance) throws IOException {
        check();int inspected=0;
        // Segments are day-bounded, including the last active segment.
        while(inspected<allowance && !segments.isEmpty()) {
            maintenanceCursor%=segments.size();
            Segment s=segments.get(maintenanceCursor);
            inspected++;
            if(!s.unknown && s.bucket>=0 && expired(s.bucket,now)) {
                if(replay!=null && replayIndex==maintenanceCursor)break;
                try{faults.check(Point.DELETE);Files.delete(s.path);}catch(IOException e){throw new RetentionFailure();}
                if(activeSegment==s)activeSegment=null;
                segments.remove(maintenanceCursor);
                if(replayIndex>maintenanceCursor)replayIndex--;
            } else maintenanceCursor++;
            if(inspected>=segments.size()+1)break;
        }
        if(inspected<allowance && reportExpiry>=0 && now>=reportExpiry) {
            inspected++;
            for(String name:List.of("report.json","report.json.tmp")) {
                Path path=root.resolve(name);
                if(!inactiveReports.contains(name)&&Files.exists(path,NOFOLLOW_LINKS)) {
                    verifyPrivate(path,false);
                    try{faults.check(Point.DELETE);Files.delete(path);}catch(IOException e){throw new RetentionFailure();}
                }
            }
            reportExpiry=-1;
        }
        maxMaintenance=Math.max(maxMaintenance,inspected);
        return inspected;
    }
    boolean expired(long bucket,long now) { return now>=Math.addExact(bucket,policy.limit(Limit.RETENTION_MILLIS)); }
    @Override public void append(byte[] bytes,long bucket,long now) throws IOException {
        check();
        if(bytes.length==0||bytes.length>policy.limit(Limit.RECORD_BYTES)
                ||bytes.length>policy.limit(Limit.SEGMENT_BYTES)||expired(bucket,now))throw failure();
        Segment active=activeSegment;
        if(active!=null&&(active.bucket!=bucket||active.bytes+bytes.length>policy.limit(Limit.SEGMENT_BYTES)))active=null;
        if(active==null) {
            activeSegment=null;
            while(segments.size()>=policy.limit(Limit.SEGMENTS))pruneOldest(null);
            while(true) {
                try{reserve(bytes.length,1);break;}
                catch(IOException quota){pruneOldest(null);}
            }
            Set<String> used=new HashSet<>();for(Segment s:segments)used.add(s.path.getFileName().toString());
            Path path=null;
            for(int i=0;i<8;i++)if(!used.contains("segment-"+i+".jsonl")){path=root.resolve("segment-"+i+".jsonl");break;}
            if(path==null)throw failure();
            createPrivate(path);active=new Segment(path,0);active.bucket=bucket;segments.add(active);
            activeSegment=active;
        }
        try{reserve(bytes.length,0);}catch(IOException full) {
            // Prune only other closed, known owned segments; never remove future schemas.
            while(true) {
                pruneOldest(active);
                try{reserve(bytes.length,0);break;}catch(IOException stillFull){if(segments.size()<=1)throw failure();}
            }
        }
        verifyPrivate(active.path,false);
        if(Files.size(active.path)!=active.bytes)throw failure();
        try(FileChannel out=FileChannel.open(active.path,WRITE,APPEND,NOFOLLOW_LINKS)) {
            faults.check(Point.WRITE);int written=faults.write(out,ByteBuffer.wrap(bytes));
            if(written!=bytes.length)throw failure();
        }
        active.bytes=Math.addExact(active.bytes,bytes.length);
        inventory();
    }
    private void pruneOldest(Segment protectedActive) throws IOException {
        Segment oldest=segments.stream().filter(s->s!=protectedActive&&!s.unknown && s.bucket>=0)
                .min(Comparator.comparingLong(s->s.bucket)).orElse(null);
        if(oldest==null)throw failure();
        try{faults.check(Point.DELETE);Files.delete(oldest.path);}catch(IOException e){throw new RetentionFailure();}
        segments.remove(oldest);
    }
    @Override public void report(byte[] bytes,long expiry) throws IOException {
        check();
        if(bytes.length>policy.limit(Limit.REPORT_BYTES)||policy.limit(Limit.REPORT_ROWS)==0||expiry<0
                || !inactiveReports.isEmpty())throw failure();
        Path temp=root.resolve("report.json.tmp"),target=root.resolve("report.json");
        if(Files.exists(temp,NOFOLLOW_LINKS))throw failure();
        reserve(bytes.length,1);
        createPrivate(temp);
        reportExpiry=reportExpiry<0?expiry:Math.min(reportExpiry,expiry);
        try(FileChannel out=FileChannel.open(temp,WRITE,NOFOLLOW_LINKS)) {
            faults.check(Point.WRITE);if(faults.write(out,ByteBuffer.wrap(bytes))!=bytes.length)throw failure();
        }
        // Both temp and final are charged in reserve; move does not increase footprint.
        if(Files.exists(target,NOFOLLOW_LINKS))verifyPrivate(target,false);
        Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        verifyPrivate(target,false);inventory();
    }
    private record Inventory(List<Path> paths,long bytes) { }
    private Inventory inventory() throws IOException {
        List<Path> paths=new ArrayList<>();long total=0;
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(root)) {
            for(Path path:entries) {
                if(paths.size()>=policy.limit(Limit.FILES))throw failure();
                verifyPrivate(path,false);
                total=Math.addExact(total,Files.size(path));
                if(total>policy.limit(Limit.DIRECTORY_BYTES))throw failure();
                paths.add(path);
            }
        }
        peakFiles=Math.max(peakFiles,paths.size());peakBytes=Math.max(peakBytes,total);
        return new Inventory(List.copyOf(paths),total);
    }
    private void reserve(long bytes,int files) throws IOException {
        Inventory current=inventory();
        if(Math.addExact(current.bytes,bytes)>policy.limit(Limit.DIRECTORY_BYTES)
                ||Math.addExact(current.paths.size(),files)>policy.limit(Limit.FILES))throw failure();
        peakBytes=Math.max(peakBytes,current.bytes+bytes);peakFiles=Math.max(peakFiles,current.paths.size()+files);
    }
    @Override public Usage usage() { return new Usage(peakFiles,peakBytes,segments.size(),
            segments.stream().mapToLong(s->s.bytes).sum(),records,replayBytes,maxMaintenance); }
    private void check() throws IOException {
        if(closed||!lock.isValid())throw failure();verifyPrivate(root,true);
    }
    private void rejectSymlinkAncestors(Path path) throws IOException {
        for(Path p=path;p!=null;p=p.getParent())
            if(Files.isSymbolicLink(p))throw failure();
    }
    private void privateDirectories(Path path) throws IOException {
        if(Files.exists(path,NOFOLLOW_LINKS)) {
            if(path.startsWith(root.getParent().getParent()))verifyPrivate(path,true);
            return;
        }
        Path parent=path.getParent();
        if(parent!=null&&!Files.exists(parent,NOFOLLOW_LINKS))privateDirectories(parent);
        faults.check(Point.DIRECTORY);
        try {
            if(Files.getFileAttributeView(parent,PosixFileAttributeView.class,NOFOLLOW_LINKS)!=null)
                Files.createDirectory(path,PosixFilePermissions.asFileAttribute(DIRECTORY_PERMS));
            else { Files.createDirectory(path);secureAcl(path,true); }
        } catch(FileAlreadyExistsException race) { if(!Files.isDirectory(path,NOFOLLOW_LINKS))throw failure(); }
        // Minecraft and the authoritative stores may concurrently create shared world/data
        // ancestors. Only our diagnostic subtree requires exclusive private permissions.
        if(path.startsWith(root.getParent().getParent()))verifyPrivate(path,true);
        else if(!Files.isDirectory(path,NOFOLLOW_LINKS)||!Files.getOwner(path,NOFOLLOW_LINKS).equals(account))throw failure();
    }
    private void createPrivate(Path path) throws IOException {
        if(Files.getFileAttributeView(root,PosixFileAttributeView.class,NOFOLLOW_LINKS)!=null)
            Files.createFile(path,PosixFilePermissions.asFileAttribute(FILE_PERMS));
        else { Files.createFile(path);secureAcl(path,false); }
        verifyPrivate(path,false);
    }
    private void secureAcl(Path path,boolean directory) throws IOException {
        AclFileAttributeView view=Files.getFileAttributeView(path,AclFileAttributeView.class,NOFOLLOW_LINKS);
        if(view==null)throw failure();
        var builder=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(account)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if(directory)builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT);
        view.setAcl(List.of(builder.build()));
    }
    private void verifyPrivate(Path path,boolean directory) throws IOException {
        BasicFileAttributes basic=Files.readAttributes(path,BasicFileAttributes.class,NOFOLLOW_LINKS);
        if(basic.isSymbolicLink() || (directory?!basic.isDirectory():!basic.isRegularFile()))throw failure();
        if(!Files.getOwner(path,NOFOLLOW_LINKS).equals(account))throw failure();
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,NOFOLLOW_LINKS);
        if(posix!=null) {
            if(!posix.readAttributes().permissions().equals(directory?DIRECTORY_PERMS:FILE_PERMS))throw failure();
        } else {
            var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,NOFOLLOW_LINKS);
            if(acl==null || acl.getAcl().isEmpty() || acl.getAcl().stream().anyMatch(e->
                    e.type()==AclEntryType.ALLOW&&!e.principal().equals(account)))throw failure();
        }
    }
    @Override public void close() throws IOException {
        if(closed)return;closed=true;
        try{faults.check(Point.CLOSE);if(replay!=null)replay.close();}finally{try{lock.release();}finally{lockChannel.close();}}
        Arrays.fill(salt,(byte)0);
    }
    private static IOException failure() { return new IOException("Diagnostic storage unavailable"); }
    public static final class RetentionFailure extends IOException {
        public RetentionFailure() { super("Diagnostic retention unavailable"); }
    }
}
