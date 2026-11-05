package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic crop lineage contracts. Native wheat qualification requires the real crop callback. */
final class NativeCropHarvestTest {
    static NativeHarvest.Entry entry() {
        var log=NativePickupTest.harvest(1);var i=log.intent();var s=i.source();
        var before=new NativeHarvest.Source(s.dimension(),s.x(),s.y(),s.z(),s.incarnation(),s.revision(),"minecraft:wheat[age=7]");
        var crop=new NativeHarvest.Intent(i.key(),i.origin(),i.run(),i.operation(),i.actor(),i.area(),i.destination(),"minecraft:wheat",before,i.conditions(),i.actorBefore());
        var outputs=List.of(new NativeHarvest.Drop(s.dimension(),UUID.randomUUID(),UUID.randomUUID(),0,"minecraft:wheat",1),
                new NativeHarvest.Drop(s.dimension(),UUID.randomUUID(),UUID.randomUUID(),0,"minecraft:wheat_seeds",3));
        var receipt=new CropDelivery.CropReceipt(UUID.randomUUID(),crop.run(),UUID.randomUUID(),crop.actor(),crop.area(),crop.destination(),CropDelivery.Stage.HARVEST,1);
        return new NativeHarvest.Entry(crop,NativeHarvest.observedCrop(crop,log.result().source(),outputs,receipt,log.result().usage(),"",crop.actorBefore()));
    }
    static Marker marker(NativeHarvest.Entry e){return new Marker(e.token(),e.intent().digest(),MarkerPhase.RESULT,e.result().digest());}
    static Witness actor(NativeHarvest.Entry e){return new Witness(e.result().actor(),marker(e),true);}
    static NativeHarvest.SourceWitness source(NativeHarvest.Entry e){return new NativeHarvest.SourceWitness(e.result().source(),marker(e),true);}
    static List<NativeHarvest.DropWitness> drops(NativeHarvest.Entry e){return e.result().drops().stream().map(d->new NativeHarvest.DropWitness(d,marker(e),true)).toList();}
    @Test void actualLegacyCropReceiptAndEverySpawnedSeedOutputRoundTripWithoutAResourceReceipt() {
        var e=entry();assertNull(e.result().receipt());assertNotNull(e.result().cropReceipt());assertEquals(2,e.result().drops().size());
        assertEquals(NativeHarvest.Reconciliation.SAVED_COMPLETE,NativeHarvest.classify(e,actor(e),source(e),drops(e)));
        var verified=new NativeHarvest.Entry(e.intent(),e.result(),new NativeHarvest.Verification(NativeHarvest.Reconciliation.SAVED_COMPLETE,actor(e),source(e),drops(e),UUID.randomUUID(),100));
        assertEquals(verified,NativeHarvest.decode(NativeHarvest.encode(verified)));
        assertTrue(new String(NativeHarvest.encode(e),StandardCharsets.UTF_8).contains("\"schema\":2"));
    }
    @Test void missingSeedParticipantDoesNotProveTheOriginalHarvest() {
        var e=entry();assertEquals(NativeHarvest.Reconciliation.WAIT_WORLD,NativeHarvest.classify(e,actor(e),source(e),drops(e).subList(0,1)));
    }
    @Test void exactRetainedCropOutputsAreProtectedAcrossVerification() {
        var e=entry();
        var verified=new NativeHarvest.Entry(e.intent(),e.result(),new NativeHarvest.Verification(
                NativeHarvest.Reconciliation.SAVED_COMPLETE,actor(e),source(e),drops(e),UUID.randomUUID(),100));
        for(var drop:e.result().drops()) {
            assertTrue(NativeHarvest.retainsCropOutput(e,drop,marker(e)));
            assertTrue(NativeHarvest.retainsCropOutput(verified,drop,marker(e)));
            assertFalse(NativeHarvest.retainsCropOutput(e,new NativeHarvest.Drop(drop.dimension(),UUID.randomUUID(),
                    drop.incarnation(),drop.revision(),drop.item(),drop.quantity()),marker(e)));
            assertFalse(NativeHarvest.retainsCropOutput(e,drop,new Marker(e.token(),e.intent().digest(),MarkerPhase.PREPARED,"")));
        }
    }
    @Test void mergedOrChangedSeedCountCannotBeReconstructedFromTheWheatCount() {
        var e=entry();var witnesses=new ArrayList<>(drops(e));var d=e.result().drops().getLast();
        witnesses.set(1,new NativeHarvest.DropWitness(new NativeHarvest.Drop(d.dimension(),d.entity(),d.incarnation(),d.revision()+1,d.item(),4),marker(e),true));
        assertEquals(NativeHarvest.Reconciliation.UNPROVEN,NativeHarvest.classify(e,actor(e),source(e),witnesses));
    }
    @Test void equalAirSourceReplacementAndLiveOnlyParticipantsRemainUnproven() {
        var e=entry();var s=e.result().source();var replacement=new NativeHarvest.Source(s.dimension(),s.x(),s.y(),s.z(),UUID.randomUUID(),s.revision(),s.state());
        assertEquals(NativeHarvest.Reconciliation.UNPROVEN,NativeHarvest.classify(e,actor(e),new NativeHarvest.SourceWitness(replacement,marker(e),true),drops(e)));
        assertEquals(NativeHarvest.Reconciliation.UNPROVEN,NativeHarvest.classify(e,new Witness(e.result().actor(),marker(e),false),source(e),drops(e)));
    }
    @Test void changedLegacyReceiptRunOrWheatQuantityCannotBorrowTheNativeOutputs() {
        var e=entry();var r=e.result().cropReceipt();
        for(var changed:List.of(new CropDelivery.CropReceipt(r.receiptId(),UUID.randomUUID(),r.batchId(),r.actor(),r.source(),r.destination(),r.stage(),r.wheat()),
                new CropDelivery.CropReceipt(r.receiptId(),r.runId(),r.batchId(),r.actor(),r.source(),r.destination(),r.stage(),2)))
            assertThrows(IllegalArgumentException.class,()->new NativeHarvest.Entry(e.intent(),NativeHarvest.observedCrop(e.intent(),e.result().source(),e.result().drops(),changed,e.result().usage(),"",e.result().actor())));
    }
    @Test void cropSchemaCannotMasqueradeAsTheOriginalLogSchema() {
        var e=entry();String encoded=new String(NativeHarvest.encode(e),StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,()->NativeHarvest.decode(encoded.replace("\"schema\":2","\"schema\":1").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->NativeHarvest.observedCrop(NativePickupTest.harvest(1).intent(),e.result().source(),e.result().drops(),e.result().cropReceipt(),e.result().usage(),"",e.result().actor()));
    }
}
