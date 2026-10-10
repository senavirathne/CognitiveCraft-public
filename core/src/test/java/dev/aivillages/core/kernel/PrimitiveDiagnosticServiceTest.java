package dev.aivillages.core.kernel;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.PrimitiveDiagnostics.*;
import static dev.aivillages.core.kernel.PrimitiveDiagnosticCodec.*;
import static org.junit.jupiter.api.Assertions.*;

class PrimitiveDiagnosticServiceTest {
    @TempDir Path temporary;
    static final class Time extends Clock {
        long wall=1_800_000_000_000L,nano;
        @Override public long millis(){return wall;}
        @Override public Instant instant(){return Instant.ofEpochMilli(wall);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        void advance(){nano+=1_000_000_000L;}
    }
    static final TrustedContext OWNER=new TrustedContext(new PrincipalRef(new UUID(10,11)),
            new ScopeRef(new UUID(12,13),new UUID(14,15)));
    static Observation observation(long n) { return observation(n,OWNER,"$.body[0].id","fixture-model",CropDelivery.ID,"b".repeat(64),1); }
    static Observation observation(long n,TrustedContext owner,String path,String model,CapabilityId capability,String catalog,int version) {
        return new Observation(owner,new UUID(20,n),new UUID(21,n),Generation.Role.INITIAL,capability,
                new ProviderMetadata("controlled-v1",model,null,false,false),
                new SkillCompiler.UnsupportedPrimitiveReference(new CapabilityId("cognitivecraft:smelt_item",version),"0".repeat(64),
                        new SkillCompiler.Diagnostic(path,"UNKNOWN_PRIMITIVE"),
                        List.of(new SkillCompiler.ArgumentObservation(0,Type.INT)),catalog),"a".repeat(64),
                requestShape(CropDelivery.SPEC,Generation.Role.INITIAL));
    }
    static Policy policy(Map<Limit,Long> lower){return new Policy(Mode.METADATA,lower,Set.of());}
    static final class Memory implements PrimitiveDiagnosticStorePort {
        final List<byte[]> written=new ArrayList<>(); final List<Snapshot> replayed=new ArrayList<>();
        final byte[] salt=new byte[32];int cursor,attempts,inspected; boolean fail,closed;
        @Override public byte[] salt(){return salt.clone();}
        @Override public ReplaySlice replay(int records,int bytes) {
            var rows=new ArrayList<Snapshot>();int used=0;
            while(cursor<replayed.size()&&rows.size()<records) {
                Snapshot s=replayed.get(cursor);byte[] b=encode(s,8192);
                if(used+b.length>bytes)break;rows.add(s);used+=b.length;cursor++;
            }
            return new ReplaySlice(List.copyOf(rows),rows.size(),used,0,cursor==replayed.size());
        }
        @Override public int maintain(long now,int allowance){inspected=Math.min(allowance,1);return inspected;}
        @Override public void append(byte[] bytes,long bucket,long now)throws IOException {
            attempts++;if(fail)throw new IOException("secret-payload-must-not-log");written.add(bytes.clone());
        }
        @Override public void report(byte[] bytes,long expiry){throw new AssertionError("No implicit report writes");}
        @Override public Usage usage(){return new Usage(0,0,0,0,cursor,0,inspected);}
        @Override public void close(){closed=true;}
    }
    static PrimitiveDiagnosticService service(Policy policy,Time time,Memory memory) {
        var s=new PrimitiveDiagnosticService(policy,time,()->time.nano,()->memory,ignored->{},new UUID(30,31),false);
        s.cycle();return s;
    }
    static void step(PrimitiveDiagnosticService service,Time time){time.advance();service.cycle();}
    static long count(PrimitiveDiagnosticService s,Counter c){return s.status().counters().get(c);}

    @ParameterizedTest @EnumSource(Limit.class)
    void everyNumericAllowanceRejectsNegativeNPlusOneAndLongMaxAndAcceptsZeroAndExactN(Limit limit) {
        assertThrows(IllegalArgumentException.class,()->policy(Map.of(limit,-1L)));
        assertThrows(IllegalArgumentException.class,()->policy(Map.of(limit,(long)limit.maximum+1)));
        assertThrows(IllegalArgumentException.class,()->policy(Map.of(limit,Long.MAX_VALUE)));
        assertEquals(limit.maximum,policy(Map.of(limit,(long)limit.maximum)).limit(limit));
        assertEquals(0,policy(Map.of(limit,0L)).limit(limit));
    }
    @Test void reservationsAndExplicitScopeBoundariesAreFiniteAndConsistent() {
        assertEquals(16_252_928,Policy.metadata().workingReservationBytes());
        assertTrue(Policy.metadata().workingReservationBytes()<=16L*1024*1024);
        assertThrows(IllegalArgumentException.class,()->policy(Map.of(Limit.QUEUE_BYTES,1048575L)));
        assertThrows(IllegalArgumentException.class,()->policy(Map.of(Limit.SEGMENT_BYTES,8191L)));
        assertThrows(IllegalArgumentException.class,()->policy(Map.of(Limit.WRITE_BYTES,8191L)));
        assertThrows(IllegalArgumentException.class,()->policy(Map.of(Limit.DIRECTORY_BYTES,1048576L)));
        assertDoesNotThrow(()->policy(Map.of(Limit.QUEUE,127L,Limit.QUEUE_BYTES,127L*8192)));
        assertThrows(IllegalArgumentException.class,()->new Policy(Mode.EXCERPTS,Map.of(),Set.of()));
        Set<ScopeRef> scopes=new HashSet<>();for(int i=0;i<64;i++)scopes.add(new ScopeRef(new UUID(1,i),new UUID(2,i)));
        assertDoesNotThrow(()->new Policy(Mode.EXCERPTS,Map.of(),scopes));
        scopes.add(new ScopeRef(UUID.randomUUID(),UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,()->new Policy(Mode.EXCERPTS,Map.of(),scopes));
    }
    @Test void offAndZeroFacilitiesNeverOpenStorage() {
        for(Policy p:List.of(Policy.off(),policy(Map.of(Limit.QUEUE,0L)),policy(Map.of(Limit.WRITES,0L)),
                policy(Map.of(Limit.DIRECTORY_BYTES,0L)),policy(Map.of(Limit.AGGREGATES,0L)))) {
            var s=new PrimitiveDiagnosticService(p,new Time(),()->0,()->{throw new AssertionError("off opened");},
                    ignored->{},UUID.randomUUID(),false);
            assertEquals(Offer.DISABLED,s.offer(observation(1)));s.cycle();assertEquals(0,s.status().queued());
            assertTrue(s.closeAsync().isDone());
        }
    }
    @Test void globalTickQueueAndCycleCapsDropNewWithoutCatchUpBurst() {
        var time=new Time();var memory=new Memory();var s=service(Policy.metadata(),time,memory);
        for(int tick=0;tick<16;tick++) {
            s.beginTick();for(int n=0;n<8;n++)assertEquals(Offer.ACCEPTED,s.offer(observation(tick*8L+n+1)));
            assertEquals(Offer.FULL,s.offer(observation(10000+tick)));
        }
        s.beginTick();assertEquals(Offer.FULL,s.offer(observation(99999)));s.cycle();
        assertEquals(0,memory.written.size(),"same-cycle work burst");
        step(s,time);
        assertEquals(96,s.status().queued());assertEquals(128,s.status().maxQueued());
        assertEquals(32,s.status().maxDequeued());assertTrue(s.status().maxWritten()<=16);
        assertTrue(s.status().maxWriteBytes()<=131072);assertTrue(s.status().maxInspected()<=32);
        assertEquals(16,count(s,Counter.TICK_DROP));assertEquals(1,count(s,Counter.QUEUE_DROP));
        time.nano+=10_000_000_000L;s.cycle();assertEquals(64,s.status().queued(),"no catch-up drain");
    }
    @Test void finalReplaySliceDoesNotAlsoSpendNormalMaintenanceBudget() {
        var time=new Time();var memory=new Memory();
        for(int n=1;n<=32;n++)memory.replayed.add(initial(observation(n),
                "00000000-0000-0000-0000-000000000001:"+n,time.wall,memory.salt,Policy.metadata(),Map.of()));
        var s=service(Policy.metadata(),time,memory);
        assertEquals(PrimitiveDiagnosticService.State.READY,s.status().state());
        assertEquals(32,s.status().maxInspected());assertEquals(0,memory.inspected);
        step(s,time);assertTrue(s.status().maxInspected()<=32);
    }
    @Test void dedupAggregationTupleAndFirstRepresentativeArePreserved() {
        var time=new Time();var memory=new Memory();var s=service(Policy.metadata(),time,memory);
        var first=observation(1);s.offer(first);s.offer(first);
        s.offer(observation(2,OWNER,"$.body[9].id","different-model",CropDelivery.ID,"b".repeat(64),1));
        step(s,time);
        assertEquals(1,s.snapshot().size());var row=s.snapshot().getFirst();
        assertEquals(2,row.occurrences());assertEquals(2,row.revision());assertEquals(1,count(s,Counter.DUPLICATE));
        assertEquals("fixture-model",row.representative().provider().model());
        assertEquals("$.body[0].id",row.representative().diagnostic().path());
        assertTrue(row.latestEventId().endsWith(":3"));
        var foreign=new TrustedContext(new PrincipalRef(new UUID(40,41)),OWNER.scope());
        s.beginTick();s.offer(observation(3,foreign,"$.body[0].id","fixture",CropDelivery.ID,"b".repeat(64),1));
        s.offer(observation(4,OWNER,"$.body[0].id","fixture",CropDelivery.ID,"c".repeat(64),1));
        s.offer(observation(5,OWNER,"$.body[0].id","fixture",CropDelivery.ID,"b".repeat(64),2));
        s.offer(observation(6,OWNER,"$.body[0].id","fixture",new CapabilityId("test:capability",2),"b".repeat(64),1));
        step(s,time);assertEquals(5,s.snapshot().size());
        time.wall+=DAY;s.beginTick();s.offer(observation(7));step(s,time);assertEquals(6,s.snapshot().size());
    }
    @Test void aggregateAndRecentDedupMaximumsStayBoundedUnderUniqueFlood() {
        var time=new Time();var memory=new Memory();
        var s=service(policy(Map.of(Limit.AGGREGATES,2L,Limit.DEDUP,3L)),time,memory);
        for(int i=1;i<=4;i++)s.offer(observation(i,OWNER,"$.body[0].id","fixture",CropDelivery.ID,"b".repeat(64),i));
        step(s,time);assertEquals(2,s.snapshot().size());assertEquals(2,count(s,Counter.AGGREGATE_DROP));
        s.beginTick();s.offer(observation(1,OWNER,"$.body[0].id","fixture",CropDelivery.ID,"b".repeat(64),1));
        step(s,time);assertEquals(2,s.snapshot().stream().filter(r->r.primitive().version()==1).findFirst().orElseThrow().occurrences(),
                "oldest dedup entry was evicted");
    }
    @Test void futureClockDisablesAndBackwardsWallClockCannotExtendRetention() {
        var time=new Time();var memory=new Memory();
        memory.replayed.add(initial(observation(1),"00000000-0000-0000-0000-000000000001:1",
                time.wall+300001,memory.salt,Policy.metadata(),Map.of()));
        var anomalous=service(Policy.metadata(),time,memory);
        assertEquals(PrimitiveDiagnosticService.State.DISABLED,anomalous.status().state());
        assertEquals(1,count(anomalous,Counter.CLOCK_ANOMALY));assertTrue(anomalous.snapshot().isEmpty());
        time=new Time();memory=new Memory();var s=service(Policy.metadata(),time,memory);
        s.offer(observation(1));step(s,time);assertEquals(1,s.snapshot().size());
        time.wall-=DAY*20;time.nano+=DAY*7*1_000_000L;s.cycle();assertTrue(s.snapshot().isEmpty());
    }
    @Test void ioFailureDoesNotReopenRetryOrSpawnAndAggregationRemainsBounded() {
        var time=new Time();var memory=new Memory();memory.fail=true;var warnings=new ArrayList<String>();
        var s=new PrimitiveDiagnosticService(Policy.metadata(),time,()->time.nano,()->memory,warnings::add,UUID.randomUUID(),false);
        s.cycle();s.offer(observation(1));step(s,time);
        assertEquals(PrimitiveDiagnosticService.State.MEMORY_ONLY,s.status().state());assertEquals(1,memory.attempts);
        s.beginTick();s.offer(observation(2));step(s,time);
        assertEquals(2,s.snapshot().getFirst().occurrences());assertEquals(1,memory.attempts);
        assertEquals(List.of("CognitiveCraft primitive diagnostics unavailable; gameplay continues."),warnings);
        assertFalse(warnings.toString().contains("secret"));
    }
    @Test void sequenceRevisionAndOccurrenceExhaustionNeverWrapOrReuseIds() {
        var time=new Time();var memory=new Memory();var s=service(Policy.metadata(),time,memory);
        s.sequenceForTest(Long.MAX_VALUE-1);assertEquals(Offer.ACCEPTED,s.offer(observation(1)));
        assertEquals(Offer.UNAVAILABLE,s.offer(observation(2)));step(s,time);
        assertTrue(s.snapshot().getFirst().latestEventId().endsWith(Long.toString(Long.MAX_VALUE)));
        var base=s.snapshot().getFirst();
        var sealed=new Snapshot(base.aggregateId(),Long.MAX_VALUE,Long.MAX_VALUE,base.firstSeen(),base.lastSeen(),
                base.bucketStart(),base.worldRef(),base.scopeRef(),base.principalRef(),base.capability(),base.catalog(),
                base.primitive(),base.fingerprint(),base.latestEventId(),base.representative(),true,true,Map.of());
        var restart=new Memory();restart.replayed.add(sealed);var second=service(Policy.metadata(),time,restart);
        second.offer(observation(3));step(second,time);
        assertEquals(Long.MAX_VALUE,second.snapshot().getFirst().revision());
        assertEquals(Long.MAX_VALUE,second.snapshot().getFirst().occurrences());assertEquals(1,count(second,Counter.SATURATED));
        assertTrue(restart.written.isEmpty());
    }
    @Test void serializedEnvelopeOmissionAndExcerptOptInNeverCaptureSourceValues() {
        var time=new Time();var memory=new Memory();
        var p=new Policy(Mode.EXCERPTS,Map.of(),Set.of(OWNER.scope()));var s=service(p,time,memory);
        s.offer(observation(1));step(s,time);
        String encoded=new String(memory.written.getFirst(),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(encoded.contains("[redacted]"));assertFalse(encoded.contains(OWNER.principal().id().toString()));
        assertFalse(encoded.contains(observation(1).attemptId().toString()));assertTrue(encoded.length()<=8192);
        assertEquals(s.snapshot().getFirst(),decode(memory.written.getFirst()));
        var projected=s.snapshot().getFirst().representative();
        assertTrue(projected.requestProjection().contains("ACTOR"));assertTrue(projected.candidateExcerpt().contains("arg0"));
        assertNull(projected.provider().digest());
        assertTrue(encoded.contains("\"digest\":null"));
        var foreign=new TrustedContext(OWNER.principal(),new ScopeRef(OWNER.scope().worldId(),new UUID(99,99)));
        s.beginTick();s.offer(observation(2,foreign,"$.body[0].id","fixture",CropDelivery.ID,"b".repeat(64),1));step(s,time);
        assertTrue(s.snapshot().stream().anyMatch(r->r.representative().candidateExcerpt().isEmpty()));
        var tiny=service(policy(Map.of(Limit.RECORD_BYTES,128L)),time,new Memory());tiny.offer(observation(3));step(tiny,time);
        assertTrue(tiny.snapshot().isEmpty());assertEquals(1,count(tiny,Counter.OVERSIZE));
    }
    @Test void asynchronousCloseStopsOffersAndBoundsDrainWithoutServerJoin() {
        var time=new Time();var memory=new Memory();var s=service(Policy.metadata(),time,memory);
        for(int tick=0;tick<16;tick++){s.beginTick();for(int i=0;i<8;i++)s.offer(observation(tick*8L+i+1));}
        var closing=s.closeAsync();assertEquals(Offer.UNAVAILABLE,s.offer(observation(1000)));
        s.cycle();assertTrue(closing.isDone());assertTrue(memory.closed);
        assertTrue(s.status().maxDequeued()<=32);assertTrue(s.status().maxWriteBytes()<=131072);
        assertTrue(count(s,Counter.SHUTDOWN_DROP)>=96);assertEquals(0,s.status().queued());
    }
    @Test void hardMaximumLoadHasMeasuredQueueMapDedupWorkAndSerializedReservations() throws Exception {
        var time=new Time();var memory=new Memory();var s=service(Policy.metadata(),time,memory);
        for(int offset=0;offset<1024;offset+=32) {
            for(int tick=0;tick<4;tick++) {
                s.beginTick();
                for(int j=0;j<8;j++) {
                    int n=offset+tick*8+j+1;
                    assertEquals(Offer.ACCEPTED,s.offer(observation(n,OWNER,"$.body[0].id","fixture",CropDelivery.ID,"b".repeat(64),n)));
                }
            }
            step(s,time);
        }
        assertEquals(512,s.status().aggregateCount());assertEquals(1024,s.status().dedupCount());
        assertEquals(512,count(s,Counter.AGGREGATE_DROP));
        assertTrue(s.status().maxDequeued()<=32&&s.status().maxInspected()<=32&&s.status().maxWritten()<=16);
        assertTrue(s.status().maxWriteBytes()<=131072&&s.status().maxRecordBytes()<=8192);
        assertEquals(16_252_928,s.status().reservedWorkingBytes());
        var evidence=Map.ofEntries(Map.entry("schema",1L),Map.entry("offered",1024L),Map.entry("aggregates",512L),Map.entry("dedup",1024L),
                Map.entry("droppedNewAggregate",count(s,Counter.AGGREGATE_DROP)),Map.entry("workingReservation",s.status().reservedWorkingBytes()),
                Map.entry("maxDequeued",(long)s.status().maxDequeued()),Map.entry("maxInspected",(long)s.status().maxInspected()),
                Map.entry("maxWritten",(long)s.status().maxWritten()),Map.entry("maxWriteBytes",(long)s.status().maxWriteBytes()),
                Map.entry("maxRecordBytes",(long)s.status().maxRecordBytes()));
        Path output=Path.of("build/primitive-diagnostic-evidence");java.nio.file.Files.createDirectories(output);
        java.nio.file.Files.write(output.resolve("load.json"),PrimitiveDiagnosticCodec.bytes(evidence));
    }
    @Test void fixedCountersAndCheckedTimeArithmeticCannotWrap() {
        var time=new Time();var memory=new Memory();var s=service(Policy.metadata(),time,memory);
        s.counterForTest(Counter.CATALOG_OMITTED,Long.MAX_VALUE-1);
        s.catalogUnavailable();s.catalogUnavailable();step(s,time);
        assertEquals(Long.MAX_VALUE,count(s,Counter.CATALOG_OMITTED));
        var overflowTime=new Time();overflowTime.wall=Long.MAX_VALUE;
        var overflow=service(Policy.metadata(),overflowTime,new Memory());
        assertEquals(PrimitiveDiagnosticService.State.READY,overflow.status().state());
        overflow.offer(observation(1));step(overflow,overflowTime);
        assertEquals(PrimitiveDiagnosticService.State.DISABLED,overflow.status().state());
        assertTrue(overflow.snapshot().isEmpty());
    }
}
