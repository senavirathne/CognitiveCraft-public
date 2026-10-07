package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import static org.junit.jupiter.api.Assertions.*;

class ResourceLeaseBoundsTest {
    @TempDir Path world;
    @Test void sixtyFourActiveAndOneHundredTwentyEightRetainedNeverEvictProtectedClaims() throws Exception {
        var f=new ResourceLeaseServiceTest.Fixture();var owners=new ArrayList<Owner>();
        for(int n=0;n<9;n++){
            Owner owner=new Owner(UUID.randomUUID(),0);owners.add(owner);
            f.owners.put(owner.job(),new OwnerFacts(f.a,f.actorA,UUID.randomUUID(),true,null));
        }
        int serial=0;
        for(int cycle=0;cycle<2;cycle++) {
            var grants=new ArrayList<Result>();
            for(int n=0;n<8;n++)for(int g=0;g<2;g++) {
                var demands=new ArrayList<Demand>();for(int k=0;k<4;k++)demands.add(new Demand(f.facility(++serial),1));
                var granted=f.acquire(owners.get(n),f.a,demands,100);assertTrue(granted.usable(),granted.toString());grants.add(granted);
            }
            assertEquals(64,f.service.snapshot().leases().stream().filter(l->l.state()==State.ACTIVE).count());
            assertEquals(Reason.STORAGE_LIMIT_REACHED,f.service.acquire(owners.get(8),List.of(new Demand(f.facility(++serial),1)),100,f.a).reason());
            for(var grant:grants){assertEquals(Code.PENDING,f.service.release(grant.leases(),f.a).code());f.ack();}
        }
        assertEquals(128,f.service.snapshot().leases().size());Snapshot before=f.service.snapshot();
        assertEquals(Reason.STORAGE_LIMIT_REACHED,f.service.acquire(owners.get(8),List.of(new Demand(f.facility(++serial),1)),100,f.a).reason());
        assertEquals(before,f.service.snapshot());assertEquals(8,f.service.protectedRoots().jobs().size());
        long[] samples=new long[1000];
        for(int n=0;n<samples.length;n++){
            long started=System.nanoTime();var inspected=f.service.inspect(owners.get(8),f.stock,f.a);
            samples[n]=System.nanoTime()-started;assertEquals(5,inspected.available());assertTrue(inspected.inspected()<=256);
        }
        Arrays.sort(samples);long start=System.nanoTime();
        Snapshot snapshot=new Snapshot(f.world,f.disk.epoch(),1,f.disk.generation(),f.disk.leases());
        try(var journal=ResourceLeaseJournal.open(world,f.world)){journal.replace(journal.snapshot(),snapshot);}
        long bytes=Files.size(world.resolve(ResourceLeaseJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl"));
        assertTrue(bytes<=ResourceLeaseJournal.MAX_BYTES);
        var evidence=new LinkedHashMap<String,Object>();
        evidence.put("schema",1);evidence.put("activeLimit",64);evidence.put("retainedLimit",128);evidence.put("resourcesPerGroup",4);
        evidence.put("leasesPerJob",8);evidence.put("reconciliationSlice",8);evidence.put("querySamples",1000);
        evidence.put("queryP95Nanos",samples[949]);evidence.put("queryMaxNanos",samples[999]);
        evidence.put("publicationNanos",System.nanoTime()-start);evidence.put("snapshotBytes",bytes);
        evidence.put("maximumSnapshotBytes",ResourceLeaseJournal.MAX_BYTES);evidence.put("modelCalls",0);
        Path output=Path.of("build/lease-evidence/scale.json");Files.createDirectories(output.getParent());Files.writeString(output,StrictJson.canonical(evidence)+"\n");
    }
    @Test void maintenanceReconcilesAtMostEightRecordsPerSlice() {
        var f=new ResourceLeaseServiceTest.Fixture();
        for(int n=0;n<3;n++){
            Owner owner=new Owner(UUID.randomUUID(),0);f.owners.put(owner.job(),new OwnerFacts(f.a,f.actorA,null,true,null));
            for(int g=0;g<2;g++){
                List<Demand> group=new ArrayList<>();for(int k=0;k<4;k++)group.add(new Demand(f.facility(100*n+10*g+k),1));
                assertTrue(f.acquire(owner,f.a,group,100).usable());
            }
            f.control(owner,false,Reason.CANCELLED);
        }
        // One owner's four-record group may retire together; the selected records remain bounded.
        for(int n=0;n<20&&f.service.snapshot().leases().stream().anyMatch(l->l.state()==State.ACTIVE);n++){
            f.service.tick();assertTrue(f.service.lastReconciled()<=8);if(!f.service.ready())f.ack();
        }
        assertTrue(f.service.snapshot().leases().stream().noneMatch(l->l.state()==State.ACTIVE));
    }
}
