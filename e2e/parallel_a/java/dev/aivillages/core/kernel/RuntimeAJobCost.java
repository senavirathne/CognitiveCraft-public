package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.persistence.*;
import dev.aivillages.fabric.KernelSession;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.runtime.persistence.ArchiveFormat.sha256;

/** Test-only actual-metadata cost fixtures. Each copy has zero native effects and is never a gameplay Job. */
public final class RuntimeAJobCost {
    private RuntimeAJobCost() { }
    /** Diagnostic metadata-only CLI; never counts a fixture copy as native execution. */
    public static void main(String[] args)throws IOException {
        if(args.length!=1)throw new IllegalArgumentException("Exact extracted metadata root required");
        Path target=Path.of(args[0]);var result=run(target,true);
        Files.writeString(target.resolve("profile-result.json"),StrictJson.canonical(result));
        System.out.println("A04_METADATA_PROFILE "+StrictJson.canonical(Map.of("fixture",true,"nativeEffects",0,"samples",64)));
    }
    public static Map<String,Object> freeze(Path target,Path nativeWorld,KernelSession.RuntimeStorageView view)throws IOException {
        if(!view.ready() || view.jobs().jobs().size()!=64)throw new IOException("Freeze the actual quiescent old Job wall");
        Path template=target.resolve("template");Files.createDirectories(template.resolve(JobJournal.WORLD_RELATIVE_PATH));
        byte[] jobs=JobJournal.encode(view.jobs());Files.write(template.resolve(JobJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl"),jobs);
        copy(nativeWorld.resolve(ArchiveHistory.RELATIVE_PATH),template.resolve(ArchiveHistory.RELATIVE_PATH));
        Path required=template.resolve(ArchiveRequirement.PATH);Files.createDirectories(required.getParent());
        Files.copy(nativeWorld.resolve(ArchiveRequirement.PATH),required);
        var details=map("world",view.jobs().worldId().toString(),"revision",view.jobs().revision(),"retentionGeneration",view.retentionGeneration(),
                "sourceBytes",jobs.length,"sourceSha256",sha256(jobs),"hotJobs",64L,"fixture",true,"nativeEffects",0L);
        Files.writeString(target.resolve("frozen.json"),StrictJson.canonical(details));return details;
    }
    public static Map<String,Object> run(Path target,boolean candidate)throws IOException {
        Map<String,Object> frozen;
        try{frozen=StrictJson.object(Files.readString(target.resolve("frozen.json")));}
        catch(StrictJson.Invalid invalid){throw new IOException("Invalid frozen metadata cost input",invalid);}
        UUID world=UUID.fromString((String)frozen.get("world"));
        Snapshot source;try(var journal=JobJournal.open(target.resolve("template"),world)){source=journal.snapshot();}
        byte[] exact=JobJournal.encode(source);if(!sha256(exact).equals(frozen.get("sourceSha256")))throw new IOException("Benchmark source changed");
        var components=JobHistory.components(world,source.jobs(),source.allocations());var selected=new LinkedHashSet<UUID>();
        var seen=Collections.newSetFromMap(new IdentityHashMap<Snapshot,Boolean>());var rows=new ArrayList<ArchiveFormat.Row>();int bytes=81;
        for(Job job:source.jobs()) {
            Snapshot component=components.get(job.id());if(!seen.add(component) || selected.size()+component.jobs().size()>16
                    || component.jobs().stream().anyMatch(j->JobHistoryCodec.custody(j,component)==null))continue;
            byte[] payload=JobHistoryCodec.encode(component);if(payload.length>PersistencePolicy.RECORD_BYTES || bytes+21+payload.length>PersistencePolicy.SEGMENT_BYTES)continue;
            rows.add(new ArchiveFormat.Row(ArchiveFormat.Kind.HISTORY,JobHistoryCodec.id(payload),payload));bytes+=21+payload.length;
            component.jobs().forEach(j->selected.add(j.id()));
        }
        if(selected.size()!=16)throw new IOException("Actual old wall lacks a bounded 16-Job closed selection");
        Snapshot retained=new Snapshot(world,source.revision()+1,source.jobs().stream().filter(j->!selected.contains(j.id())).toList(),
                source.allocations().stream().filter(a->!selected.contains(a.producer()) && !selected.contains(a.demand())).toList());
        var guard=new ArchiveFormat.Guard(source.revision(),(Long)frozen.get("retentionGeneration"));var samples=new ArrayList<Long>();
        for(int n=0;n<64;n++) {
            Path fixture=target.resolve((candidate?"candidate-":"baseline-")+n);copy(target.resolve("template"),fixture);
            try(var journal=JobJournal.open(fixture,world)) {
                if(candidate)try(var writer=ArchiveWriter.open(fixture,world,PersistencePolicy.defaults())) {
                    long start=System.nanoTime();var result=writer.append(writer.snapshot(),ArchiveFormat.Owner.JOBS,guard,rows,owner->guard);
                    if(!result.acknowledged() || !writer.authentic(result.acknowledgement(),ArchiveFormat.Owner.JOBS,guard))throw new IOException("Cost fixture archive not acknowledged");
                    var history=new JobHistory(journal::snapshot,writer);history.refresh(source);journal.replace(source,retained);history.refresh(retained);
                    if(history.read().jobs().size()!=64 || history.size()!=16 || journal.snapshot().jobs().size()!=48)throw new IOException("Cost fixture lost original rows");
                    samples.add(System.nanoTime()-start);
                }else {
                    long start=System.nanoTime();journal.replace(source,new Snapshot(world,source.revision()+1,source.jobs(),source.allocations()));samples.add(System.nanoTime()-start);
                }
            }
        }
        return map("status","PASS","mode",candidate?"archive-ack-domain-verify-and-hot-ack":"legacy-full-hot-snapshot-ack", "fixture",true,"nativeEffects",0,
                "samplesNanos",samples,"sourceBytes",exact.length,"sourceSha256",sha256(exact),"logicalJobs",64,"selectedJobs",selected.stream().map(UUID::toString).toList(),
                "archiveSegmentBytes",bytes,"hotAfter",candidate?48:64,"samples",64,"setupOutsideTimer",true);
    }
    private static void copy(Path source,Path destination)throws IOException {
        if(!Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS))throw new IOException("Bounded fixture directory missing");
        try(var stream=Files.walk(source)) {
            int count=0;for(Path path:stream.toList()) {
                if(++count>160 || Files.isSymbolicLink(path))throw new IOException("Bounded fixture copy");
                Path target=destination.resolve(source.relativize(path));
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectories(target);else Files.copy(path,target);
            }
        }
    }
    private static Map<String,Object> map(Object... pairs) {
        var result=new LinkedHashMap<String,Object>();for(int n=0;n<pairs.length;n+=2)result.put((String)pairs[n],pairs[n+1]);return result;
    }
}
