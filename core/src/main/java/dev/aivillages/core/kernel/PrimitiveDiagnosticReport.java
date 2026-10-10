package dev.aivillages.core.kernel;

import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

import static dev.aivillages.core.kernel.PrimitiveDiagnostics.*;
import static dev.aivillages.core.kernel.PrimitiveDiagnosticCodec.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Explicit offline host-only report. It has no generation, gameplay, or repository dependency. */
public final class PrimitiveDiagnosticReport {
    private PrimitiveDiagnosticReport() { }
    record Result(byte[] json,long expiry,int rows,int omitted) { }
    private record Key(CapabilityId primitive,String catalog) { }
    private static final class Group {
        final Key key;long attempts,lastSeen,expiry=Long.MAX_VALUE;
        boolean saturated;
        final Set<CapabilityId> registered=new HashSet<>();
        final SortedSet<String> families=new TreeSet<>();
        Group(Key key){this.key=key;}
    }
    static Result render(List<Snapshot> snapshots,Policy policy,Map<CapabilityId,String> families,long now) {
        if(snapshots.size()>512 || families.size()>128 || now<0)throw invalid();
        for(String name:families.values())if(!name.matches("[a-zA-Z0-9][a-zA-Z0-9 _-]{0,63}"))throw invalid();
        Map<Key,Group> groups=new HashMap<>();
        var modes=new TreeSet<String>();
        long earliest=Long.MAX_VALUE;
        for(Snapshot row:snapshots) {
            long expiry=Math.addExact(row.bucketStart(),policy.limit(Limit.RETENTION_MILLIS));
            if(expiry<=now)continue;
            modes.add(row.representative().requestProjection().isEmpty()&&row.representative().candidateExcerpt().isEmpty()
                    && !row.representative().projectionsTruncated()?"METADATA":"EXCERPTS");
            earliest=Math.min(earliest,expiry);
            var key=new Key(row.primitive(),row.catalog());var g=groups.computeIfAbsent(key,Group::new);
            g.saturated|=row.saturated() || row.occurrences()>Long.MAX_VALUE-g.attempts;
            g.attempts=row.occurrences()>Long.MAX_VALUE-g.attempts?Long.MAX_VALUE:g.attempts+row.occurrences();
            g.lastSeen=Math.max(g.lastSeen,row.lastSeen());g.expiry=Math.min(g.expiry,expiry);
            String family=families.get(row.capability());
            if(family!=null){g.registered.add(row.capability());g.families.add(family);}
            else g.families.add("Unmapped");
        }
        List<Group> sorted=groups.values().stream().sorted(
                Comparator.comparingLong((Group g)->g.attempts).reversed()
                        .thenComparing(Comparator.comparingInt((Group g)->g.registered.size()).reversed())
                        .thenComparing(Comparator.comparingLong((Group g)->g.lastSeen).reversed())
                        .thenComparing(g->g.key.primitive.name()).thenComparingInt(g->g.key.primitive.version())
                        .thenComparing(g->g.key.catalog)).toList();
        int included=Math.min(sorted.size(),policy.limit(Limit.REPORT_ROWS));
        long expiry=earliest==Long.MAX_VALUE?now:earliest;
        byte[] bytes;
        while(true) {
            List<Object> rows=new ArrayList<>();
            for(Group g:sorted.subList(0,included))rows.add(Map.of(
                    "primitive",Map.of("id",g.key.primitive.name(),"version",(long)g.key.primitive.version()),
                    "catalogFingerprint",g.key.catalog,"observedRejectedAttempts",g.attempts,
                    "distinctRegisteredCapabilities",(long)g.registered.size(),"families",List.copyOf(g.families),
                    "lastSeen",g.lastSeen,"saturated",g.saturated,
                    "runtimeSupport","requested_id_version_absent_in_observed_catalog",
                    "reason","UNSUPPORTED_PRIMITIVE / UNKNOWN_PRIMITIVE",
                    "status","requires_human_implementation_review"));
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("schema",1L);report.put("kind","primitive_diagnostic_report");report.put("generatedAt",now);
            report.put("expiresAt",expiry);report.put("windowStart",Math.max(0,now-policy.limit(Limit.RETENTION_MILLIS)));
            report.put("windowEnd",now);report.put("collectionModes",List.copyOf(modes));
            report.put("authority","advisory_diagnostic_evidence_only");report.put("potentiallyIncomplete",true);
            report.put("lossCountsKnown",false);report.put("lossCounts","unknown_across_crash_or_missing_final_snapshot");
            report.put("omittedRows",(long)(sorted.size()-included));report.put("rows",rows);
            report.put("countMeaning","observed_rejected_attempts_not_unique_inferences_or_humans");
            report.put("supportMeaning","observed_catalog_and_window_not_current_support_or_necessity");
            bytes=PrimitiveDiagnosticCodec.bytes(report);
            if(bytes.length<=policy.limit(Limit.REPORT_BYTES))break;
            if(included==0)return new Result(new byte[0],expiry,0,sorted.size());
            included--;
        }
        return new Result(bytes,expiry,included,sorted.size()-included);
    }
    /** java -cp <production-jar>:<gson-jar> ...PrimitiveDiagnosticReport <world-root> */
    public static void main(String[] args) {
        if(args.length!=1&&args.length!=3){System.err.println("Usage: PrimitiveDiagnosticReport <world-root> [rows bytes]");System.exit(2);}
        Clock clock=Clock.systemUTC();
        try {
            Policy policy=args.length==1?Policy.metadata():new Policy(Mode.METADATA,Map.of(
                    Limit.REPORT_ROWS,Long.parseLong(args[1]),Limit.REPORT_BYTES,Long.parseLong(args[2])),Set.of());
            try(PrimitiveDiagnosticStore store=PrimitiveDiagnosticStore.existing(Path.of(args[0]),policy)) {
            Map<String,Snapshot> rows=new HashMap<>();
            long utc=clock.millis(),now=utc;
            boolean done=false;
            while(!done) {
                var slice=store.replay(32,131072);
                for(Snapshot row:slice.rows()) {
                    if(row.lastSeen()>Math.addExact(utc,300000L))throw invalid();
                    now=Math.max(now,row.lastSeen());
                    if(Math.addExact(row.bucketStart(),policy.limit(Limit.RETENTION_MILLIS))<=now)continue;
                    Snapshot old=rows.get(row.aggregateId());
                    if(old!=null&&old.revision()>=row.revision())continue;
                    if(old==null&&rows.size()>=512)continue;
                    rows.put(row.aggregateId(),row);
                }
                done=slice.complete();
                if(!done)Thread.sleep(1000);
            }
            Result report=render(List.copyOf(rows.values()),policy,Map.of(CropDelivery.ID,"Farming"),now);
            if(report.json.length==0)throw invalid();
            store.report(report.json,report.expiry);
            System.out.println("Private primitive diagnostic report created; counts may be incomplete.");
            }
        } catch(Exception failure) {
            // Fixed text only: even host errors never echo paths, fields, payloads or exception detail.
            System.err.println("Primitive diagnostic report unavailable; verify private storage and writer shutdown.");
            System.exit(1);
        }
    }
}
