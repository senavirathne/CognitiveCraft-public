package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import static dev.aivillages.core.kernel.Mandates.*;
import static dev.aivillages.core.kernel.MandateRegistryTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MandateJournalTest {
    @TempDir Path world;
    static Snapshot rootState() { var s=new Scene();s.root();return s.registry.snapshot(); }
    void seed(Snapshot state) throws Exception { try(var journal=MandateJournal.open(world,WORLD)){journal.replace(journal.snapshot(),state);} }
    Path file(String name) { return world.resolve(MandateJournal.WORLD_RELATIVE_PATH).resolve(name); }
    static Stream<Arguments> interruptions() { return Arrays.stream(MandateJournal.Point.values()).flatMap(p->Stream.of(Arguments.of(p,false),Arguments.of(p,true))); }
    @ParameterizedTest @MethodSource("interruptions")
    void interruptedGrantOrRevokeNeverReactivatesCachedAuthority(MandateJournal.Point point,boolean revoke) throws Exception {
        var s=new Scene();Grant root=s.root();Snapshot original=s.registry.snapshot();seed(original);
        if(revoke){s.registry.revoke(root.id(),root.revision(),OWNER);s.registry.tick();}
        else{s.grant(root.id(),COORDINATOR,CHILD,rights(),budget(100,10000),10000);}
        Snapshot proposed=s.registry.snapshot();
        try(var writer=MandateJournal.open(world,WORLD,p->{if(p==point)throw new IOException("power-loss");})) {
            assertThrows(IOException.class,()->writer.replace(original,proposed));assertTrue(writer.readOnly());
        }
        try(var reopened=MandateJournal.open(world,WORLD)) {
            var registry=new MandateRegistry(reopened.snapshot(),(a,b)->{throw new AssertionError("No automatic recovery write");},s,s,s.clock,Limits.defaults(),reopened.readOnly());
            assertFalse(registry.controls(ACTOR,Contracts.Effect.HARVEST,COORDINATOR));
            assertFalse(registry.controls(ACTOR,Contracts.Effect.HARVEST,CHILD));
            if(revoke&&point==MandateJournal.Point.AFTER_REPLACE)
                assertTrue(reopened.snapshot().grants().getFirst().revoked());
            assertTrue(Files.exists(file("state.jsonl")));assertTrue(Files.size(file("state.jsonl"))>0);
        }
    }
    static Stream<Arguments> drifts() { return Stream.of(MandateJournal.Point.BEFORE_STAGE,MandateJournal.Point.AFTER_STAGE,
            MandateJournal.Point.BEFORE_REPLACE,MandateJournal.Point.AFTER_REPLACE).flatMap(p->Stream.of("state.jsonl","state.prev.jsonl","state.next.jsonl").map(f->Arguments.of(p,f))); }
    @ParameterizedTest @MethodSource("drifts")
    void everyPublicationWindowPreservesForeignCurrentBackupAndStage(MandateJournal.Point point,String name) throws Exception {
        var s=new Scene();var root=s.root();Snapshot original=s.registry.snapshot();seed(original);
        s.registry.revoke(root.id(),root.revision(),OWNER);s.registry.tick();Snapshot next=s.registry.snapshot();byte[] foreign="future-authority-record".getBytes(StandardCharsets.UTF_8);
        try(var writer=MandateJournal.open(world,WORLD,p->{if(p==point)Files.write(file(name),foreign);})) {
            assertThrows(IOException.class,()->writer.replace(original,next));assertTrue(writer.readOnly());assertArrayEquals(foreign,Files.readAllBytes(file(name)));
        }
    }
    @Test void exactReferencesRevocationsAndCumulativeReservationsRoundTrip() throws Exception {
        var s=new Scene();var root=s.root();Snapshot first=s.registry.snapshot();
        var run=s.run(COORDINATOR,100);s.reserve(COORDINATOR,run);Snapshot second=s.registry.snapshot();
        s.registry.revoke(root.id(),s.find(root.id()).revision(),OWNER);s.registry.tick();Snapshot third=s.registry.snapshot();
        try(var writer=MandateJournal.open(world,WORLD)){writer.replace(writer.snapshot(),first);writer.replace(first,second);writer.replace(second,third);}
        try(var reader=MandateJournal.open(world,WORLD)){assertEquals(third,reader.snapshot());assertFalse(reader.readOnly());
            assertEquals(100,reader.snapshot().grants().getFirst().reserved().get(Budgets.Kind.CALLS));assertEquals(run.runId(),reader.snapshot().bindings().getFirst().run());}
        assertEquals(third,MandateJournal.decode(MandateJournal.encode(third),WORLD));
    }
    @Test void missingCurrentCannotRestoreOldBackupAuthority() throws Exception {
        var s=new Scene();var root=s.root();Snapshot first=s.registry.snapshot();seed(first);
        s.registry.revoke(root.id(),root.revision(),OWNER);s.registry.tick();
        try(var writer=MandateJournal.open(world,WORLD)){writer.replace(first,s.registry.snapshot());}
        Files.delete(file("state.jsonl"));byte[] original=Files.readAllBytes(file("state.prev.jsonl"));
        try(var reader=MandateJournal.open(world,WORLD)){assertTrue(reader.readOnly());assertTrue(reader.snapshot().grants().isEmpty());}
        assertArrayEquals(original,Files.readAllBytes(file("state.prev.jsonl")));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"current","previous","stage"})
    void unknownFutureAndCorruptEnvelopesStayPreservedAndInactive(String envelope) throws Exception {
        seed(rootState());String name=switch(envelope){case "current"->"state.jsonl";case "previous"->"state.prev.jsonl";default->"state.next.jsonl";};
        byte[] unknown="{\"schema\":999,\"rights\":\"unknown\"}\n".getBytes(StandardCharsets.UTF_8);Files.write(file(name),unknown);
        try(var reader=MandateJournal.open(world,WORLD)){assertTrue(reader.readOnly());assertThrows(IOException.class,()->reader.replace(reader.snapshot(),rootState()));}
        assertArrayEquals(unknown,Files.readAllBytes(file(name)));
    }
    @Test void checksumUtf8WrongWorldUnknownFieldsAndOversizedInputAreRejected() throws Exception {
        byte[] valid=MandateJournal.encode(rootState());byte[] tampered=valid.clone();tampered[tampered.length-3]^=1;
        assertThrows(IOException.class,()->MandateJournal.decode(tampered,WORLD));
        assertThrows(IOException.class,()->MandateJournal.decode(new byte[]{(byte)0xc3,0x28},WORLD));
        assertThrows(IOException.class,()->MandateJournal.decode(valid,UUID.randomUUID()));
        assertThrows(IOException.class,()->MandateJournal.decode(new byte[MandateJournal.MAX_BYTES+1],WORLD));
        var header=StrictJson.object(new String(valid,StandardCharsets.UTF_8).split("\n")[0]);header.put("title","king");
        String payload=new String(valid,StandardCharsets.UTF_8).substring(new String(valid,StandardCharsets.UTF_8).indexOf('\n')+1);
        assertThrows(IOException.class,()->MandateJournal.decode((StrictJson.canonical(header)+"\n"+payload).getBytes(StandardCharsets.UTF_8),WORLD));
    }
    @Test void closedWriterCannotPublishAfterSuccessorAcquiresLock() throws Exception {
        Snapshot first=rootState();var old=MandateJournal.open(world,WORLD);old.replace(old.snapshot(),first);old.close();old.close();
        byte[] original=Files.readAllBytes(file("state.jsonl"));try(var successor=MandateJournal.open(world,WORLD)){
            assertThrows(IOException.class,()->old.replace(first,new Snapshot(WORLD,2,first.grants(),List.of())));assertEquals(first,successor.snapshot());}
        assertArrayEquals(original,Files.readAllBytes(file("state.jsonl")));
    }
    @Test void writerLockAndStaleExpectedRevisionCannotOverwriteAuthority() throws Exception {
        try(var owner=MandateJournal.open(world,WORLD)){
            assertThrows(IOException.class,()->MandateJournal.open(world,WORLD));Snapshot first=rootState();owner.replace(owner.snapshot(),first);
            assertThrows(IOException.class,()->owner.replace(Snapshot.empty(WORLD),first));assertEquals(first,owner.snapshot());}
    }
    @Test void grantDefinitionAndDurableRevocationCannotBeWidenedOrRemoved() throws Exception {
        var s=new Scene();var root=s.root();Snapshot first=s.registry.snapshot();seed(first);
        s.registry.revoke(root.id(),root.revision(),OWNER);s.registry.tick();Snapshot revoked=s.registry.snapshot();
        try(var owner=MandateJournal.open(world,WORLD)){
            owner.replace(first,revoked);Grant old=revoked.grants().getFirst();
            var resurrected=new Grant(old.id(),old.revision()+1,old.issuer(),old.grantee(),old.parent(),old.organization(),old.rights(),old.issuedAt(),old.expiresAt(),old.budget(),old.reserved(),false,old.events());
            assertThrows(IOException.class,()->owner.replace(revoked,new Snapshot(WORLD,revoked.revision()+1,List.of(resurrected),List.of())));
            assertThrows(IOException.class,()->owner.replace(revoked,new Snapshot(WORLD,revoked.revision()+1,List.of(),List.of())));
        }
    }
    @Test void cumulativeBudgetAndExecutionReservationNeverDisappear() throws Exception {
        var s=new Scene();var root=s.root();Snapshot first=s.registry.snapshot();seed(first);s.reserve(COORDINATOR,s.run(COORDINATOR,100));Snapshot used=s.registry.snapshot();
        try(var owner=MandateJournal.open(world,WORLD)){
            owner.replace(first,used);Grant old=used.grants().getFirst();
            var refunded=new Grant(old.id(),old.revision()+1,old.issuer(),old.grantee(),old.parent(),old.organization(),old.rights(),old.issuedAt(),old.expiresAt(),old.budget(),Map.of(),false,old.events());
            assertThrows(IOException.class,()->owner.replace(used,new Snapshot(WORLD,used.revision()+1,List.of(refunded),used.bindings())));
            assertThrows(IOException.class,()->owner.replace(used,new Snapshot(WORLD,used.revision()+1,used.grants(),List.of())));
        }
    }
    @Test void quotaFailurePreservesOriginalAndRetentionProtectsAuthorityReferences() throws Exception {
        Snapshot first=rootState();try(var owner=MandateJournal.open(world,WORLD)){
            owner.capacity(bytes->{assertEquals(3L*MandateJournal.MAX_BYTES,bytes);return false;});
            assertThrows(IOException.class,()->owner.replace(owner.snapshot(),first));assertFalse(Files.exists(file("state.jsonl")));
            owner.capacity(bytes->true);owner.replace(owner.snapshot(),first);
            var retention=owner.retentionOwner();assertEquals(2048,retention.admissionCeiling().orElseThrow().records());
            try(var cursor=retention.open()){var page=cursor.next(1,1024);assertEquals(1,page.inspected());assertTrue(page.entries().getFirst().protectedRecord());}
        }
    }
    @Test void exactByteCeilingAllowsValidBoundedRowsAndRejectsOneExtraByte() throws Exception {
        var s=new Scene();for(int n=0;n<9;n++)s.grant(null,OWNER,COORDINATOR,rights(),budget(100,11000),11000);
        String encoded=new String(MandateJournal.encode(s.registry.snapshot()),StandardCharsets.UTF_8);String[] lines=encoded.split("\n");
        var header=StrictJson.object(lines[0]);int padding=MandateJournal.MAX_BYTES-encoded.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder payload=new StringBuilder();for(int n=1;n<lines.length;n++){
            int amount=padding/(lines.length-n);padding-=amount;payload.append(" ".repeat(amount)).append(lines[n]).append('\n');
        }
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.toString().getBytes(StandardCharsets.UTF_8)));header.put("sha256",digest);
        byte[] atLimit=(StrictJson.canonical(header)+"\n"+payload).getBytes(StandardCharsets.UTF_8);assertEquals(MandateJournal.MAX_BYTES,atLimit.length);
        assertEquals(s.registry.snapshot(),MandateJournal.decode(atLimit,WORLD));assertThrows(IOException.class,()->MandateJournal.decode(Arrays.copyOf(atLimit,atLimit.length+1),WORLD));
    }
}
