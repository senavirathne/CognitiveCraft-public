package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.CapabilityRetrievalIndex.*;
import dev.aivillages.core.kernel.CapabilityRetrievalIndex.Limits;
import dev.aivillages.core.kernel.CapabilityResolver.CandidateSource;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.*;
import dev.aivillages.core.kernel.VersionedSkillRepository.Candidate;
import dev.aivillages.core.kernel.VersionedSkillRepository.Integrity;
import dev.aivillages.core.kernel.VersionedSkillRepository.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** GP-12: bounded metadata retrieval, owner integration, honest gaps, recovery and private cursors. */
final class CapabilityRetrievalIndexTest {
    @TempDir Path temp;
    private static final String DIM="minecraft:overworld";
    private static final UUID WORLD=new UUID(0,1), INSTANCE=new UUID(0,2);
    private static final Identity IDENTITY=new Identity(WORLD,INSTANCE);
    private static final TrustedContext OWNER=new TrustedContext(new PrincipalRef(new UUID(0,3)),new ScopeRef(WORLD,new UUID(0,4)));
    private static final TrustedContext FOREIGN=new TrustedContext(new PrincipalRef(new UUID(0,5)),new ScopeRef(WORLD,new UUID(0,6)));
    private static final CapabilityCatalog CAPABILITIES=id->id.equals(CropDelivery.ID)?Optional.of(CropDelivery.SPEC):Optional.empty();
    private static LookupRegistry registry() {
        return new LookupRegistry(Map.of("deliver wheat",CropDelivery.ID),Map.of(CropDelivery.ID,Set.of("crop")),CAPABILITIES);
    }
    private static ArtifactRef ref(int number) { return new ArtifactRef(CropDelivery.ID,String.format("%064x",number)); }
    private static Metadata row(int number,AdmissionStatus admitted,CompatibilityStatus compatible) {
        var ref=ref(number);
        return new Metadata(new Candidate(ref,admitted,new Compatibility(ref,compatible,
                compatible==CompatibilityStatus.COMPATIBLE?List.of():List.of(Reason.ARTIFACT_INCOMPATIBLE),"minecraft-26.3"),
                Integrity.VERIFIED),Set.of("crop"));
    }
    private static Metadata row(int number) { return row(number,AdmissionStatus.ADMITTED,CompatibilityStatus.COMPATIBLE); }
    private static ArtifactDescriptor descriptor(ArtifactRef ref) {
        return new ArtifactDescriptor(ref,1,CropDelivery.SPEC.parameters(),Type.INT,Set.of(Effect.HARVEST),List.of(),List.of());
    }
    private static final class Catalog implements Source {
        List<Metadata> rows=List.of();
        final Map<CapabilityId,NavigableMap<String,Metadata>> exactRows=new HashMap<>();
        long revision=1,runtime=1,visibility;
        int metadataReads,maximumBatch,descriptors,exactPages,snapshots;
        boolean unavailable,truncatedSnapshot,oversizedBatch;
        Runnable descriptorHook=()->{};
        void replace(List<Metadata> replacement) {
            rows=List.copyOf(replacement); exactRows.clear();
            for (Metadata row:rows) exactRows.computeIfAbsent(row.candidate().ref().capability(),ignored->new TreeMap<>())
                    .put(row.candidate().ref().sha256(),row);
        }
        @Override public Epoch epoch() { return new Epoch(revision,runtime,visibility,true); }
        @Override public CatalogSnapshot snapshot() {
            snapshots++; if (unavailable) throw new IllegalStateException("catalog unavailable");
            List<Metadata> captured=rows; Epoch stamp=epoch();
            return new CatalogSnapshot() {
                int position;
                @Override public Epoch epoch() { return stamp; }
                @Override public int size() { return captured.size(); }
                @Override public boolean complete() { return position==captured.size() || truncatedSnapshot && position>0; }
                @Override public List<Metadata> next(int maximum) {
                    int take=Math.min(captured.size()-position,oversizedBatch?maximum+1:maximum);
                    if (truncatedSnapshot) take=Math.min(take,1);
                    List<Metadata> batch=captured.subList(position,position+take); position+=take;
                    metadataReads+=batch.size(); maximumBatch=Math.max(maximumBatch,batch.size()); return batch;
                }
            };
        }
        @Override public CandidateSource exact() {
            return new CandidateSource() {
                @Override public Page page(CapabilityId id,String after,int size) {
                    exactPages++; if (unavailable) throw new IllegalStateException("exact catalog unavailable");
                    var table=exactRows.get(id); var selected=new ArrayList<Candidate>(); boolean complete=true;
                    if (table!=null) {
                        var it=table.tailMap(after,false).values().iterator();
                        while (it.hasNext() && selected.size()<size) selected.add(it.next().candidate());
                        complete=!it.hasNext();
                    }
                    return new Page(selected,complete?"":selected.getLast().ref().sha256(),complete,revision);
                }
                @Override public Optional<ArtifactDescriptor> descriptor(ArtifactRef ref) {
                    descriptors++; descriptorHook.run(); return Optional.of(CapabilityRetrievalIndexTest.descriptor(ref));
                }
                @Override public long revision() { return revision; }
            };
        }
    }
    private CapabilityRetrievalIndex index(Catalog source,String name) {
        return index(source,name,Limits.defaults(),IDENTITY,context->true,FaultInjector.none(),new AtomicLong());
    }
    private CapabilityRetrievalIndex index(Catalog source,String name,Limits limits,Identity identity,
                                          Predicate<TrustedContext> visibility,FaultInjector faults,AtomicLong clock) {
        return new CapabilityRetrievalIndex(temp.resolve(name),identity,source,registry(),CAPABILITIES,limits,clock::get,visibility,faults);
    }
    private static Progress finish(CapabilityRetrievalIndex.Rebuild work) {
        Progress progress=null; int calls=0;
        do { progress=work.advance(); assertTrue(++calls<=20_000,"finite rebuild/recovery"); } while (!progress.terminal());
        return progress;
    }
    private static void ready(CapabilityRetrievalIndex index) { assertEquals(State.READY,finish(index.beginRebuild()).state()); }
    private static List<ArtifactRef> all(CapabilityRetrievalIndex index,Query query,TrustedContext context) {
        var refs=new ArrayList<ArtifactRef>(); Result page; int pages=0;
        do {
            page=index.query(query,context); assertEquals(State.READY,page.state());
            assertTrue(page.examined()<=64); assertTrue(page.responseBytes()<=65536);
            refs.addAll(page.entries().stream().map(r->r.candidate().ref()).toList());
            assertTrue(++pages<=4097); query=query.continueWith(page.nextCursor());
        } while (!page.complete());
        return refs;
    }
    private static ValidatedRequest request(TrustedContext owner,int amount,int x,int citizen) {
        ActorRef actor=new ActorRef(new UUID(0,citizen),new UUID(1,citizen),DIM);
        Cuboid area=new Cuboid(DIM,x,64,0,x+1,64,1);
        return new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(actor),
                "amount",new IntValue(amount),"source",new AreaValue(area),
                "destination",new ContainerValue(new ContainerRef(DIM,x+3,64,0)))),owner,
                new ObservationRef(new UUID(0,8),1,DIM));
    }
    private static ObservationSnapshot observed(ValidatedRequest request) {
        Cuboid area=((AreaValue)request.request().arguments().get("source")).value();
        String target="source:"+UUID.nameUUIDFromBytes(area.toString().getBytes(StandardCharsets.UTF_8));
        return new ObservationSnapshot(request.observation(),ObservationStatus.PRESENT,target,100,4,
                Map.of("mature_wheat",4L,"unknown_cells",0L));
    }
    private static CapabilityResolver.Decision resolve(CapabilityRetrievalIndex index,ValidatedRequest request,
                                                      boolean fallback,boolean eligible) {
        var engine=new CapabilityResolver.Engine(CAPABILITIES,GatewayPrimitives.instance(),index.candidates(),
                fallback?index.authoritativeFallback():null,CapabilityResolver.cropDeliverySupport(),
                (actor,context)->context.equals(OWNER),(actor,ref,context)->eligible,
                (actor,effect,context)->true,CapabilityResolver.cropPrerequisites(),()->100,
                CapabilityResolver.Limits.defaults());
        return engine.resolve(request,observed(request));
    }

    @ParameterizedTest(name="stablePagesHaveNoDuplicatesOrOmissionsAtBoundary({0})") @ValueSource(ints={0,1,16,17})
    void stablePagesHaveNoDuplicatesOrOmissionsAtBoundary(int count) {
        Catalog source=new Catalog(); var rows=new ArrayList<Metadata>();
        for (int i=1;i<=count;i++) rows.add(row(i)); Collections.reverse(rows); source.replace(rows);
        var index=index(source,"pages"); ready(index);
        var refs=all(index,Query.capability(CropDelivery.ID),OWNER);
        assertEquals(count,refs.size()); assertEquals(count,refs.stream().distinct().count());
        for (int i=0;i<count;i++) assertEquals(ref(i+1),refs.get(i));
        assertEquals(0,source.descriptors); assertEquals(count,source.metadataReads);
    }
    @Test void differentBindingsReuseOneSharedImplementationWithoutPerRequestIndexing() {
        Catalog source=new Catalog(); source.replace(List.of(row(1))); var index=index(source,"bindings"); ready(index);
        var first=resolve(index,request(OWNER,1,0,10),true,true);
        var second=resolve(index,request(OWNER,4,10,11),true,true);
        assertEquals(ResolutionStatus.RESOLVED,first.routing().status());
        assertEquals(first.routing().artifact(),second.routing().artifact()); assertEquals(ref(1),second.routing().artifact());
        assertNotEquals(first.selectedBindings(),second.selectedBindings()); assertEquals(1,source.snapshots);
        assertEquals(1,source.metadataReads); assertFalse(first.fallbackUsed()); assertFalse(second.fallbackUsed());
    }
    @Test void reorderedCatalogsKeepIdenticalReferenceOrderAndOneGenerationHasStableCursors() {
        Catalog source=new Catalog(); var rows=new ArrayList<Metadata>(); for(int i=1;i<=17;i++)rows.add(row(i));
        source.replace(rows); var first=index(source,"ordered"); ready(first);
        Query query=Query.capability(CropDelivery.ID);
        assertEquals(first.query(query,OWNER).nextCursor(),first.query(query,OWNER).nextCursor());
        var expected=all(first,query,OWNER); Collections.reverse(rows); source.replace(rows);
        var reversed=index(source,"reversed"); ready(reversed); assertEquals(expected,all(reversed,query,OWNER));
    }
    @Test void aliasesAndTypedFiltersRemainLookupAidsAndPreserveRejectedAlternatives() {
        Catalog source=new Catalog(); source.replace(List.of(row(1,AdmissionStatus.QUARANTINED,CompatibilityStatus.COMPATIBLE),
                row(2,AdmissionStatus.ADMITTED,CompatibilityStatus.INCOMPATIBLE),row(3)));
        var index=index(source,"aliases"); ready(index);
        Query alias=new Query(null," DELIVER WHEAT ",Set.of("crop"),Filters.all(),16,64,"");
        assertEquals(List.of(ref(1),ref(2),ref(3)),all(index,alias,OWNER));
        var filtered=new Query(CropDelivery.ID,"",Set.of("crop"),new Filters(Set.of(AdmissionStatus.ADMITTED),
                Set.of(CompatibilityStatus.COMPATIBLE),Set.of(Integrity.VERIFIED)),16,64,"");
        assertEquals(List.of(ref(3)),all(index,filtered,OWNER));
        var result=resolve(index,request(OWNER,2,0,10),true,true);
        assertEquals(ref(3),result.routing().artifact()); assertEquals(3,result.candidates().size());
        assertEquals(State.UNKNOWN_LOOKUP,index.query(new Query(null,"unregistered",Set.of(),Filters.all(),16,64,""),OWNER).state());
        assertEquals(State.UNKNOWN_LOOKUP,index.query(new Query(CropDelivery.ID,"",Set.of("unknown"),Filters.all(),16,64,""),OWNER).state());
        assertEquals(3,source.rows.size());
        assertThrows(IllegalArgumentException.class,()->new LookupRegistry(Map.of("new",new CapabilityId("toy:unregistered",1)),Map.of(),CAPABILITIES));
    }
    @Test void compatibleAlternativeAfterIncompatibleAndQuarantinedPagesStillResolves() {
        Catalog source=new Catalog(); var rows=new ArrayList<Metadata>();
        for (int i=1;i<=16;i++) rows.add(row(i,i%2==0?AdmissionStatus.QUARANTINED:AdmissionStatus.ADMITTED,
                CompatibilityStatus.INCOMPATIBLE)); rows.add(row(17)); source.replace(rows);
        var index=index(source,"alternatives"); ready(index);
        var result=resolve(index,request(OWNER,2,0,10),true,true);
        assertEquals(ResolutionStatus.RESOLVED,result.routing().status()); assertEquals(ref(17),result.routing().artifact());
        assertEquals(17,result.candidates().size()); assertEquals(3,result.queriedPages()); assertEquals(1,source.descriptors);
    }
    @Test void cursorsRejectTamperingDifferentQueryPrincipalScopeAndGeneration() {
        Catalog source=new Catalog(); source.replace(List.of(row(1),row(2))); var index=index(source,"cursors"); ready(index);
        Query first=new Query(CropDelivery.ID,"",Set.of(),Filters.all(),1,64,"");
        String token=index.query(first,OWNER).nextCursor(); assertFalse(token.isEmpty()); assertTrue(token.length()<=512);
        assertEquals(State.INVALID_CURSOR,index.query(first.continueWith(token.substring(0,token.length()-2)+"!!"),OWNER).state());
        assertEquals(State.INVALID_CURSOR,index.query(first.continueWith(token),FOREIGN).state());
        var samePrincipal=new TrustedContext(OWNER.principal(),FOREIGN.scope());
        assertEquals(State.INVALID_CURSOR,index.query(first.continueWith(token),samePrincipal).state());
        assertEquals(State.INVALID_CURSOR,index.query(new Query(CropDelivery.ID,"",Set.of("crop"),Filters.all(),1,64,token),OWNER).state());
        ready(index); assertEquals(State.INVALID_CURSOR,index.query(first.continueWith(token),OWNER).state());
    }
    @Test void visibilityAndWorldDenialRevealNoCatalogMetadata() {
        Catalog source=new Catalog(); source.replace(List.of(row(1),row(2)));
        var index=index(source,"private",Limits.defaults(),IDENTITY,OWNER::equals,FaultInjector.none(),new AtomicLong()); ready(index);
        Result denied=index.query(Query.capability(CropDelivery.ID),FOREIGN);
        assertEquals(State.DENIED,denied.state()); assertEquals(-1,denied.catalogRevision()); assertTrue(denied.entries().isEmpty());
        var foreignWorld=new TrustedContext(OWNER.principal(),new ScopeRef(new UUID(0,99),OWNER.scope().domainId()));
        assertEquals(State.DENIED,index.query(Query.capability(CropDelivery.ID),foreignWorld).state());
        assertEquals(ResolutionStatus.UNAUTHORIZED,resolve(index,request(FOREIGN,2,0,10),true,true).routing().status());
        assertEquals(0,index.counters().fallbackPages());
    }
    @Test void runtimeCatalogAndVisibilityRevisionChangesCannotReuseCursorsOrProveGaps() {
        Catalog source=new Catalog(); source.replace(List.of(row(1),row(2))); var index=index(source,"epochs"); ready(index);
        Query query=new Query(CropDelivery.ID,"",Set.of(),Filters.all(),1,64,"");
        String token=index.query(query,OWNER).nextCursor(); source.runtime++;
        assertEquals(State.STALE,index.query(query.continueWith(token),OWNER).state());
        var result=resolve(index,request(OWNER,2,0,10),true,true);
        assertEquals(ResolutionStatus.RESOLVED,result.routing().status()); assertTrue(result.fallbackUsed());
        ready(index); source.visibility++;
        assertEquals(State.STALE,index.query(Query.capability(CropDelivery.ID),OWNER).state());
        ready(index); source.revision++;
        assertEquals(State.STALE,index.query(Query.capability(CropDelivery.ID),OWNER).state());
        assertEquals(ResolutionStatus.RESOLVED,resolve(index,request(OWNER,2,0,10),true,true).routing().status());
    }
    @Test void deliberatelyTruncatedIndexFallsBackToExactCatalogAndNeverInvitesResearch() {
        Catalog source=new Catalog(); source.replace(List.of(row(1,AdmissionStatus.ADMITTED,CompatibilityStatus.INCOMPATIBLE),row(2)));
        source.truncatedSnapshot=true; var index=index(source,"truncated");
        assertEquals(State.UNAVAILABLE,finish(index.beginRebuild()).state());
        var result=resolve(index,request(OWNER,2,0,10),true,true);
        assertEquals(ResolutionStatus.RESOLVED,result.routing().status()); assertEquals(ref(2),result.routing().artifact());
        assertTrue(result.fallbackUsed()); assertTrue(result.complete()); assertTrue(index.counters().fallbackPages()>0);
    }
    @Test void unavailableFallbackAndExhaustedResolverAllowanceAreHonestBlockers() {
        Catalog source=new Catalog(); var index=index(source,"unavailable"); source.unavailable=true;
        var result=resolve(index,request(OWNER,2,0,10),true,true);
        assertEquals(ResolutionStatus.BLOCKED,result.routing().status()); assertEquals(Reason.STORAGE_UNAVAILABLE,result.routing().reason());
        source.unavailable=false; var rows=new ArrayList<Metadata>();
        for (int i=1;i<=33;i++) rows.add(row(i)); source.replace(rows);
        result=resolve(index,request(OWNER,2,0,10),true,true);
        assertEquals(ResolutionStatus.BLOCKED,result.routing().status()); assertEquals(Reason.BUDGET_EXHAUSTED,result.routing().reason());
        assertFalse(result.complete()); assertEquals(32,result.candidateWork());
    }
    @Test void indexLimitsRemainTypedWhenNoExactFallbackIsAvailable() {
        Catalog source=new Catalog(); source.replace(List.of(row(1),row(2))); Limits d=Limits.defaults();
        var index=index(source,"limited",new Limits(1,d.pageSize(),d.querySlice(),d.rebuildSlice(),d.responseBytes(),
                d.entryBytes(),d.cacheBytes(),d.deadlineMillis()),IDENTITY,context->true,FaultInjector.none(),new AtomicLong());
        assertEquals(State.LIMIT,finish(index.beginRebuild()).state());
        var result=resolve(index,request(OWNER,2,0,10),false,true);
        assertEquals(ResolutionStatus.BLOCKED,result.routing().status()); assertEquals(Reason.BUDGET_EXHAUSTED,result.routing().reason());
        assertEquals(ResolutionStatus.RESOLVED,resolve(index,request(OWNER,2,0,10),true,true).routing().status());
    }
    @Test void workAndResponseLimitsContinueBeforeTheFirstUnreturnedAlternative() throws Exception {
        Catalog source=new Catalog(); source.replace(List.of(row(1,AdmissionStatus.ADMITTED,CompatibilityStatus.INCOMPATIBLE),row(2)));
        var normal=index(source,"work"); ready(normal);
        Query filtered=new Query(CropDelivery.ID,"",Set.of(),new Filters(Set.of(),Set.of(CompatibilityStatus.COMPATIBLE),Set.of()),16,1,"");
        Result first=normal.query(filtered,OWNER); assertEquals(State.LIMIT,first.state()); assertFalse(first.complete());
        assertTrue(first.entries().isEmpty()); assertEquals(1,first.examined());
        Result second=normal.query(filtered.continueWith(first.nextCursor()),OWNER);
        assertEquals(ref(2),second.entries().getFirst().candidate().ref()); assertTrue(second.complete());
        Limits d=Limits.defaults(); int cap=1024+RetrievalIndexCache.encode(row(1,AdmissionStatus.ADMITTED,CompatibilityStatus.INCOMPATIBLE)).length+4;
        var limited=index(source,"bytes",new Limits(d.entries(),d.pageSize(),d.querySlice(),d.rebuildSlice(),cap,
                d.entryBytes(),d.cacheBytes(),d.deadlineMillis()),IDENTITY,context->true,FaultInjector.none(),new AtomicLong()); ready(limited);
        first=limited.query(Query.capability(CropDelivery.ID),OWNER);
        assertEquals(State.LIMIT,first.state()); assertEquals(ref(1),first.entries().getFirst().candidate().ref());
        assertTrue(first.responseBytes()<=cap);
        second=limited.query(Query.capability(CropDelivery.ID).continueWith(first.nextCursor()),OWNER);
        assertEquals(List.of(ref(2)),second.entries().stream().map(r->r.candidate().ref()).toList()); assertTrue(second.complete());
    }
    @Test void deletionDoesNotChangeKnowledgeEligibilityOrAuthoritativeRows() throws Exception {
        Catalog source=new Catalog(); source.replace(List.of(row(1))); var index=index(source,"delete"); ready(index);
        List<Metadata> before=source.rows;
        assertEquals(Reason.KNOWLEDGE_REQUIRED,resolve(index,request(OWNER,2,0,10),true,false).routing().reason());
        index.deleteCache(); assertFalse(Files.exists(temp.resolve("delete/metadata.bin"))); assertEquals(before,source.rows);
        assertEquals(ref(1),resolve(index,request(OWNER,2,0,10),true,true).routing().artifact());
        assertEquals(Reason.KNOWLEDGE_REQUIRED,resolve(index,request(OWNER,2,0,10),true,false).routing().reason());
        ready(index); assertEquals(before,source.rows);
    }
    @Test void validCacheRestoresWithoutMetadataReadsButCorruptionRebuildsWithinOriginalOperation() throws Exception {
        Catalog source=new Catalog(); source.replace(List.of(row(1),row(2))); var first=index(source,"restore"); ready(first);
        int read=source.metadataReads; var restored=index(source,"restore");
        assertEquals(State.READY,finish(restored.beginRecovery()).state()); assertEquals(read,source.metadataReads);
        Path cache=temp.resolve("restore/metadata.bin"); byte[] bytes=Files.readAllBytes(cache);
        bytes[bytes.length-1]^=1; Files.write(cache,bytes);
        var repaired=index(source,"restore"); assertEquals(State.READY,finish(repaired.beginRecovery()).state());
        assertEquals(read+2,source.metadataReads); assertEquals(List.of(ref(1),ref(2)),all(repaired,Query.capability(CropDelivery.ID),OWNER));
    }
    @Test void futureCacheSchemaIsPreservedInactiveUntilExplicitCacheDeletion() throws Exception {
        Catalog source=new Catalog(); source.replace(List.of(row(1))); var first=index(source,"future"); ready(first);
        Path cache=temp.resolve("future/metadata.bin"); byte[] bytes=Files.readAllBytes(cache);
        ByteBuffer.wrap(bytes).putInt(4,99); Files.write(cache,bytes);
        var future=index(source,"future"); assertEquals(State.UNAVAILABLE,finish(future.beginRecovery()).state());
        assertArrayEquals(bytes,Files.readAllBytes(cache)); assertEquals(ref(1),resolve(future,request(OWNER,2,0,10),true,true).routing().artifact());
        future.deleteCache(); ready(future); assertEquals(1,source.rows.size());
    }
    @Test void futureStagingFormatIsPreservedInactiveInsteadOfDiscarded() throws Exception {
        Catalog source=new Catalog(); source.replace(List.of(row(1))); var index=index(source,"future-stage");
        Files.createDirectories(temp.resolve("future-stage"));
        byte[] future=ByteBuffer.allocate(40).putInt(RetrievalIndexCache.MAGIC).putInt(99).array();
        Path pending=temp.resolve("future-stage/metadata.pending.bin"); Files.write(pending,future);
        assertEquals(State.UNAVAILABLE,finish(index.beginRecovery()).state()); assertArrayEquals(future,Files.readAllBytes(pending));
        assertEquals(ref(1),resolve(index,request(OWNER,2,0,10),true,true).routing().artifact());
        index.deleteCache(); ready(index);
    }
    @Test void corruptedStagedBytesNeverRestoreAsACompleteGeneration() throws Exception {
        Catalog source=new Catalog(); source.replace(List.of(row(1),row(2)));
        var index=index(source,"corrupt-stage",Limits.defaults(),IDENTITY,context->true,point->{
            if(point==FaultPoint.BEFORE_REPLACE) Files.write(temp.resolve("corrupt-stage/metadata.pending.bin"),new byte[]{1,2,3});
        },new AtomicLong());
        ready(index); assertEquals(List.of(ref(1),ref(2)),all(index,Query.capability(CropDelivery.ID),OWNER));
        source.metadataReads=0; var recovered=index(source,"corrupt-stage");
        assertEquals(State.READY,finish(recovered.beginRecovery()).state()); assertEquals(2,source.metadataReads);
        assertEquals(List.of(ref(1),ref(2)),all(recovered,Query.capability(CropDelivery.ID),OWNER));
    }
    @Test void interruptedReplaceRetainsOnlyAValidatedPreviousGeneration() throws Exception {
        Catalog source=new Catalog(); source.replace(List.of(row(1))); var first=index(source,"interrupted"); ready(first);
        source.replace(List.of(row(1),row(2))); source.revision++;
        var broken=index(source,"interrupted",Limits.defaults(),IDENTITY,context->true,
                point->{if(point==FaultPoint.BEFORE_REPLACE)throw new IOException("injected publication failure");},new AtomicLong());
        assertEquals(State.UNAVAILABLE,finish(broken.beginRebuild()).state());
        assertEquals(State.STALE,first.query(Query.capability(CropDelivery.ID),OWNER).state());
        assertEquals(ref(1),resolve(broken,request(OWNER,2,0,10),true,true).routing().artifact());
        var reopened=index(source,"interrupted"); assertEquals(State.READY,finish(reopened.beginRecovery()).state());
        assertEquals(2,all(reopened,Query.capability(CropDelivery.ID),OWNER).size());
    }
    @Test void lostAcknowledgementAfterAtomicReplaceCanRecoverOnlyTheVerifiedCompleteCache() {
        Catalog source=new Catalog(); source.replace(List.of(row(1),row(2)));
        var broken=index(source,"lost-ack",Limits.defaults(),IDENTITY,context->true,
                point->{if(point==FaultPoint.AFTER_REPLACE)throw new IOException("lost acknowledgement");},new AtomicLong());
        assertEquals(State.UNAVAILABLE,finish(broken.beginRebuild()).state());
        var reopened=index(source,"lost-ack"); assertEquals(State.READY,finish(reopened.beginRecovery()).state());
        assertEquals(List.of(ref(1),ref(2)),all(reopened,Query.capability(CropDelivery.ID),OWNER));
    }
    @Test void cancellationFencesLateBatchesAndKeepsPreviousGenerationOnlyIfCurrent() throws Exception {
        Catalog source=new Catalog(); source.replace(List.of(row(1),row(2))); var index=index(source,"cancel"); ready(index);
        Query query=new Query(CropDelivery.ID,"",Set.of(),Filters.all(),1,64,""); String cursor=index.query(query,OWNER).nextCursor();
        var cancelled=index.beginRebuild(); cancelled.advance(); cancelled.advance(); cancelled.cancel(); cancelled.cancel();
        assertEquals(State.CANCELLED,cancelled.advance().state()); assertEquals(State.CANCELLED,cancelled.advance().state());
        assertEquals(ref(2),index.query(query.continueWith(cursor),OWNER).entries().getFirst().candidate().ref());
        assertFalse(Files.exists(temp.resolve("cancel/metadata.pending.bin")));
        var stale=index.beginRebuild(); stale.advance(); source.revision++; stale.cancel(); finish(stale);
        assertEquals(State.STALE,index.query(Query.capability(CropDelivery.ID),OWNER).state());
    }
    @Test void catalogRaceDuringRebuildAndAtDescriptorConsumptionCannotPublishStaleClaims() {
        Catalog source=new Catalog(); source.replace(List.of(row(1))); var index=index(source,"race");
        var work=index.beginRebuild(); work.advance(); source.revision++;
        assertEquals(State.STALE,work.advance().state()); assertEquals(0,index.status().entries());
        ready(index); source.descriptorHook=()->source.runtime++;
        var result=resolve(index,request(OWNER,2,0,10),true,true);
        assertEquals(ResolutionStatus.BLOCKED,result.routing().status()); assertEquals(Reason.STALE_OBSERVATION,result.routing().reason());
    }
    @Test void copiedCacheAndForeignWorldDoNotReuseOldMetadataOrCursors() throws Exception {
        Catalog original=new Catalog(); original.replace(List.of(row(1),row(2))); var first=index(original,"original"); ready(first);
        Query query=new Query(CropDelivery.ID,"",Set.of(),Filters.all(),1,64,""); String cursor=first.query(query,OWNER).nextCursor();
        Files.createDirectories(temp.resolve("copy")); Files.copy(temp.resolve("original/metadata.bin"),temp.resolve("copy/metadata.bin"));
        Catalog copied=new Catalog(); copied.replace(List.of(row(3))); var copy=index(copied,"copy",Limits.defaults(),
                new Identity(WORLD,new UUID(0,99)),context->true,FaultInjector.none(),new AtomicLong());
        assertEquals(State.READY,finish(copy.beginRecovery()).state()); assertEquals(1,copied.metadataReads);
        assertEquals(List.of(ref(3)),all(copy,Query.capability(CropDelivery.ID),OWNER));
        assertEquals(State.INVALID_CURSOR,copy.query(query.continueWith(cursor),OWNER).state());
    }
    @Test void cacheAndEntryByteLimitsDoNotDestroyVerifiedKnowledge() {
        Catalog source=new Catalog(); source.replace(List.of(row(1),row(2),row(3))); Limits d=Limits.defaults();
        var bytes=index(source,"cache-limit",new Limits(d.entries(),d.pageSize(),d.querySlice(),d.rebuildSlice(),
                d.responseBytes(),d.entryBytes(),512,d.deadlineMillis()),IDENTITY,context->true,FaultInjector.none(),new AtomicLong());
        assertEquals(State.LIMIT,finish(bytes.beginRebuild()).state());
        assertEquals(ref(1),resolve(bytes,request(OWNER,2,0,10),true,true).routing().artifact()); assertEquals(3,source.rows.size());
        var entry=index(source,"entry-limit",new Limits(d.entries(),d.pageSize(),d.querySlice(),d.rebuildSlice(),
                d.responseBytes(),128,d.cacheBytes(),d.deadlineMillis()),IDENTITY,context->true,FaultInjector.none(),new AtomicLong());
        assertEquals(State.LIMIT,finish(entry.beginRebuild()).state());
    }
    @Test void deadlineAndQueuedWorkerCloseFencePublicationWithoutTickIo() {
        Catalog source=new Catalog(); source.replace(List.of(row(1))); AtomicLong clock=new AtomicLong();
        var index=index(source,"deadline",Limits.defaults(),IDENTITY,context->true,FaultInjector.none(),clock);
        var work=index.beginRebuild(); work.advance(); clock.set(12_001);
        assertEquals(State.LIMIT,work.advance().state()); assertEquals(0,index.status().entries());
        var queued=new ArrayList<Runnable>(); var pending=index(source,"queued"); pending.maintain(queued::add);
        pending.maintain(queued::add); assertEquals(1,queued.size()); assertEquals(0,source.metadataReads);
        pending.close(); queued.getFirst().run(); assertEquals(State.CLOSED,pending.status().state());
        assertEquals(0,source.metadataReads);
    }
    @Test void malformedMetadataBatchesCannotProduceACompleteGeneration() {
        Catalog source=new Catalog(); var rows=new ArrayList<Metadata>(); for(int i=1;i<=129;i++)rows.add(row(i));
        source.replace(rows); source.oversizedBatch=true; var oversized=index(source,"oversized");
        assertEquals(State.UNAVAILABLE,finish(oversized.beginRebuild()).state()); assertEquals(0,oversized.status().entries());
        source.oversizedBatch=false; source.replace(List.of(row(1),row(1))); var duplicate=index(source,"duplicate");
        assertEquals(State.UNAVAILABLE,finish(duplicate.beginRebuild()).state()); assertEquals(0,duplicate.status().entries());
    }
    @ParameterizedTest(name="invalidAndOverflowedEntryLimitsRejectBeforeAnySourceRead({0})") @ValueSource(ints={-1,0,4097,Integer.MAX_VALUE})
    void invalidAndOverflowedEntryLimitsRejectBeforeAnySourceRead(int entries) {
        Limits d=Limits.defaults(); assertThrows(IllegalArgumentException.class,()->new Limits(entries,d.pageSize(),
                d.querySlice(),d.rebuildSlice(),d.responseBytes(),d.entryBytes(),d.cacheBytes(),d.deadlineMillis()));
    }
    @Test void otherInvalidLimitsAndOversizedLabelsRejectBeforeAllocation() {
        assertThrows(IllegalArgumentException.class,()->new Limits(4096,16,0,128,65536,4096,4_194_304,12000));
        assertThrows(IllegalArgumentException.class,()->new Limits(4096,16,64,0,65536,4096,4_194_304,12000));
        assertThrows(IllegalArgumentException.class,()->new Limits(4096,16,64,128,1023,4096,4_194_304,12000));
        assertThrows(IllegalArgumentException.class,()->new Limits(4096,16,64,128,65536,127,4_194_304,12000));
        assertThrows(IllegalArgumentException.class,()->new Limits(4096,16,64,128,65536,4096,511,12000));
        assertThrows(IllegalArgumentException.class,()->new Limits(4096,16,64,128,65536,4096,4_194_304,0));
        assertThrows(IllegalArgumentException.class,()->new Limits(4096,0,64,128,65536,4096,4_194_304,12000));
        assertThrows(IllegalArgumentException.class,()->new Limits(4096,17,64,128,65536,4096,4_194_304,12000));
        assertThrows(IllegalArgumentException.class,()->new Limits(4096,16,65,128,65536,4096,4_194_304,12000));
        assertThrows(IllegalArgumentException.class,()->new Limits(4096,16,64,129,65536,4096,4_194_304,12000));
        assertThrows(IllegalArgumentException.class,()->new Limits(4096,16,64,128,65537,4096,4_194_304,12000));
        assertThrows(IllegalArgumentException.class,()->new Limits(4096,16,64,128,65536,4097,4_194_304,12000));
        assertThrows(IllegalArgumentException.class,()->new Limits(4096,16,64,128,65536,4096,Long.MAX_VALUE,12000));
        assertThrows(IllegalArgumentException.class,()->new Limits(4096,16,64,128,65536,4096,4_194_304,Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->new Query(null,"a".repeat(129),Set.of(),Filters.all(),16,64,""));
        assertThrows(IllegalArgumentException.class,()->new Query(CropDelivery.ID,"",Set.of(),Filters.all(),16,64,"x".repeat(513)));
        assertThrows(IllegalArgumentException.class,()->new Metadata(row(1).candidate(),Set.of("x".repeat(65))));
    }
    @Test void sourceGrowthBeyondTotalLimitRefusesBeforeReadingRows() {
        Catalog source=new Catalog(); var rows=new ArrayList<Metadata>(); for(int i=1;i<=4097;i++)rows.add(row(i));
        source.replace(rows); var index=index(source,"growth-limit");
        assertEquals(State.LIMIT,finish(index.beginRebuild()).state()); assertEquals(0,source.metadataReads);
        assertEquals(ResolutionStatus.BLOCKED,resolve(index,request(OWNER,2,0,10),true,true).routing().status());
        assertTrue(source.exactPages<=4);
    }
    @Test void runtimeRaceDuringExactFallbackCannotClaimCurrentPolicy() {
        Catalog source=new Catalog(); source.replace(List.of(row(1))); var index=index(source,"fallback-race");
        source.descriptorHook=()->source.runtime++;
        var result=resolve(index,request(OWNER,2,0,10),true,true);
        assertEquals(ResolutionStatus.BLOCKED,result.routing().status()); assertEquals(Reason.STALE_OBSERVATION,result.routing().reason());
        assertEquals(1,index.counters().descriptors());
    }
    @Test void fourThousandNinetySixRowsStayBoundedAndLookupLoadsNoBodies() throws Exception {
        Catalog source=new Catalog(); var rows=new ArrayList<Metadata>(); for(int i=1;i<=4096;i++)rows.add(row(i));
        Collections.reverse(rows); source.replace(rows); var index=index(source,"scale");
        Progress result=finish(index.beginRebuild()); assertEquals(State.READY,result.state());
        assertEquals(4096,index.status().entries()); assertEquals(4096,source.metadataReads);
        assertTrue(source.maximumBatch<=128); assertEquals(8192,index.counters().rebuildExamined());
        assertTrue(index.counters().rebuildSlices()<=70); assertTrue(Files.size(temp.resolve("scale/metadata.bin"))<=4_194_304);
        var refs=all(index,Query.capability(CropDelivery.ID),OWNER);
        assertEquals(4096,refs.size()); assertEquals(ref(1),refs.getFirst()); assertEquals(ref(4096),refs.getLast());
        assertEquals(4096,refs.stream().distinct().count()); assertEquals(0,source.descriptors); assertEquals(0,index.counters().bodyLoads());
        Path evidence=Path.of("build/retrieval-evidence"); Files.createDirectories(evidence);
        String metrics="{\"entries\":4096,\"metadataReads\":"+source.metadataReads+",\"maximumRebuildBatch\":"+source.maximumBatch+
                ",\"rebuildWork\":"+index.counters().rebuildExamined()+",\"rebuildSlices\":"+index.counters().rebuildSlices()+
                ",\"lookupPages\":"+index.counters().pages()+",\"lookupExamined\":"+index.counters().examined()+
                ",\"cacheBytes\":"+Files.size(temp.resolve("scale/metadata.bin"))+",\"bodyLoads\":0}";
        Files.writeString(evidence.resolve("scale.json"),metrics); System.out.println("IMP-010 scale "+metrics);
    }
    @Test void authoritativeRepositoryMetadataHasNoPrivateOriginsAndRuntimeEpochIsIndependent() throws Exception {
        AtomicLong signatureReads=new AtomicLong();
        CapabilityCatalog inspected=id->{ signatureReads.incrementAndGet(); return CAPABILITIES.find(id); };
        var runtime=new VersionedSkillRepository.RuntimeSnapshot("minecraft-26.3",inspected,GatewayPrimitives.instance());
        try(var repository=VersionedSkillRepository.open(temp.resolve("world"),runtime,VersionedSkillRepository.Limits.defaults(),
                decision->true,TrustedContext::equals,VersionedSkillRepository.FaultInjector.none())) {
            String program=StrictJson.canonical(Map.of("schema",1L,"capability",CropDelivery.ID.name(),"capabilityVersion",1L,
                    "dependencies",List.of(),"body",List.of(Map.of("op","result","value",Map.of("param","amount")))));
            var compiled=new SkillCompiler(CAPABILITIES,GatewayPrimitives.instance(),repository).compile(program);
            assertInstanceOf(SkillCompiler.Success.class,compiled); var artifact=((SkillCompiler.Success)compiled).skill().artifact();
            var evidence=new EvidenceRef("synthetic-index-test","validator-1","test-only");
            var bundle=new VersionedSkillRepository.EvidenceBundle(evidence,evidence,evidence,"c".repeat(64));
            var decision=new VersionedSkillRepository.AdmissionDecision(new UUID(0,90),OWNER,bundle);
            var provenance=new Provenance(1,"private-model-data","compiler-1","minecraft-26.3",WORLD,List.of(),List.of(evidence));
            assertEquals(VersionedSkillRepository.PublishStatus.ADMITTED,repository.publish(artifact,decision,provenance).status());
            var captured=repository.metadataSnapshot(); long revision=repository.status().revision();
            byte[] manifest=Files.readAllBytes(temp.resolve("world").resolve(VersionedSkillRepository.WORLD_RELATIVE_PATH).resolve("manifest.json"));
            var registry=registry(); var index=new CapabilityRetrievalIndex(temp.resolve("real-cache"),IDENTITY,
                    CapabilityRetrievalIndex.repositorySource(repository,registry,()->0),registry,CAPABILITIES,Limits.defaults(),()->0);
            signatureReads.set(0); ready(index);
            assertEquals(1,signatureReads.get(),"one descriptor contract check; no body compiler traversal during rebuild");
            assertEquals(artifact.descriptor().ref(),resolve(index,request(OWNER,2,0,10),true,true).routing().artifact());
            assertTrue(signatureReads.get()>1,"authoritative descriptor consumption still validates the executable body");
            assertFalse(new String(Files.readAllBytes(temp.resolve("real-cache/metadata.bin")),StandardCharsets.UTF_8).contains("private-model-data"));
            repository.updateRuntime(runtime); assertEquals(revision,repository.status().revision());
            assertNotEquals(captured.revision(),repository.catalogRevision()); assertEquals(1,captured.next(128).size());
            assertEquals(State.STALE,index.query(Query.capability(CropDelivery.ID),OWNER).state());
            index.deleteCache(); ready(index);
            assertArrayEquals(manifest,Files.readAllBytes(temp.resolve("world").resolve(VersionedSkillRepository.WORLD_RELATIVE_PATH).resolve("manifest.json")));
            assertTrue(repository.resolve(artifact.descriptor().ref()).usable()); assertTrue(repository.privateOrigins(artifact.descriptor().ref(),FOREIGN).isEmpty());
        }
    }
}
