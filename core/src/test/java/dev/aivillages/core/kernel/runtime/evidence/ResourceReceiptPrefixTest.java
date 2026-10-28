package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.Budgets;
import dev.aivillages.core.kernel.ResourceDelivery;
import dev.aivillages.core.kernel.StrictJson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic schema/receipt fixtures except the explicitly frozen read-only A05 native record. */
final class ResourceReceiptPrefixTest {
    static ResourceDelivery.Receipt receipt(ResourceDelivery.Receipt r,ResourceDelivery.Stage stage,long quantity,UUID batch,List<UUID> drops) {
        return new ResourceDelivery.Receipt(UUID.randomUUID(),r.origin(),r.runId(),UUID.randomUUID(),batch,r.actor(),r.source(),r.destination(),r.item(),stage,quantity,r.sourceBlock(),drops);
    }
    static List<ResourceDelivery.Receipt> prefix(Intent intent) {
        var r=NativeDepositTest.completed(intent).result().receipt();
        return List.of(receipt(r,ResourceDelivery.Stage.HARVEST,1,r.batchId(),r.dropIds()),receipt(r,ResourceDelivery.Stage.PICKUP,1,r.batchId(),r.dropIds()));
    }
    static Intent with(Intent i,ResourceReceiptPrefix prefix) {
        return new Intent(i.key(),i.origin(),i.run(),i.operation(),i.actor(),i.chest(),i.item(),i.actorSlot(),i.chestSlot(),i.capacity(),i.conditions(),prefix);
    }
    @Test void qualifiedNativeV1RecordRetainsItsExactBytesAndOriginalReceipt() throws Exception {
        byte[] raw;
        try(var input=getClass().getResourceAsStream("a05-native-deposit-v1.json")){assertNotNull(input);raw=input.readAllBytes();}
        assertEquals("89ccbf970dff091b56a59872715a7ac37238fc606813d7725a9f88f9063ebf98",sha(raw));Entry old=decode(raw);
        assertNull(old.intent().receiptPrefix());assertEquals(Live.COMPLETE,old.result().live());
        assertNotNull(old.result().receipt());assertArrayEquals(raw,encode(old));
    }
    @Test void schemaTwoRetainsOriginalPrefixAndBindsTheActualCurrentReceipt() throws Exception {
        Intent old=NativeDepositTest.intent();var prefix=new ResourceReceiptPrefix(prefix(old));
        Entry entry=NativeDepositTest.completed(with(old,prefix));byte[] raw=encode(entry);
        assertEquals(2L,StrictJson.object(new String(raw,StandardCharsets.UTF_8)).get("schema"));assertEquals(entry,decode(raw));
        assertEquals(prefix.receipts(),decode(raw).intent().receiptPrefix().receipts());
        assertNotEquals(old.digest(),entry.intent().digest());assertEquals(old.key(),entry.intent().key());
        var marker=NativeDepositTest.marker(entry,MarkerPhase.RESULT);
        assertEquals(Reconciliation.UNPROVEN,classify(entry,new Witness(entry.result().actor(),marker,false),new Witness(entry.result().chest(),marker,false)));
    }
    @Test void missingChangedOrFuturePrefixSchemasCannotDowngradeToLegacyCertainty() throws Exception {
        Entry entry=NativeDepositTest.completed(with(NativeDepositTest.intent(),null));
        var original=StrictJson.object(new String(encode(entry),StandardCharsets.UTF_8));
        original.put("schema",2L);assertThrows(IllegalArgumentException.class,()->decode(StrictJson.canonical(original).getBytes(StandardCharsets.UTF_8)));
        Intent i=entry.intent();var value=StrictJson.object(new String(encode(NativeDepositTest.completed(with(i,new ResourceReceiptPrefix(prefix(i))))),StandardCharsets.UTF_8));
        value.put("schema",1L);assertThrows(IllegalArgumentException.class,()->decode(StrictJson.canonical(value).getBytes(StandardCharsets.UTF_8)));
        value.put("schema",3L);assertThrows(IllegalArgumentException.class,()->decode(StrictJson.canonical(value).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void conflictingRepeatedOrUnattributableDropChainsCannotBeRetained() {
        Intent i=NativeDepositTest.intent();var p=prefix(i);var h=p.getFirst();var pickup=p.getLast();
        assertThrows(IllegalArgumentException.class,()->new ResourceReceiptPrefix(List.of()));
        assertThrows(IllegalArgumentException.class,()->new ResourceReceiptPrefix(List.of(pickup)));
        assertThrows(IllegalArgumentException.class,()->new ResourceReceiptPrefix(List.of(h,h)));
        assertThrows(IllegalArgumentException.class,()->new ResourceReceiptPrefix(List.of(h,receipt(pickup,ResourceDelivery.Stage.PICKUP,2,h.batchId(),h.dropIds()))));
        assertThrows(IllegalArgumentException.class,()->new ResourceReceiptPrefix(List.of(h,receipt(pickup,ResourceDelivery.Stage.PICKUP,1,h.batchId(),List.of(UUID.randomUUID())))));
        assertThrows(IllegalArgumentException.class,()->new ResourceReceiptPrefix(List.of(h,receipt(h,ResourceDelivery.Stage.HARVEST,1,UUID.randomUUID(),h.dropIds()))));
        var foreign=new ResourceReceiptPrefix(prefix(NativeDepositTest.intent()));
        assertThrows(IllegalArgumentException.class,()->new Entry(with(i,foreign),null));
    }
    @Test void currentResultCannotAttachToAnUnrelatedRetainedBatch() {
        Intent i=NativeDepositTest.intent();Entry good=NativeDepositTest.completed(with(i,new ResourceReceiptPrefix(prefix(i))));
        var result=good.result();var r=result.receipt();
        var wrong=receipt(r,ResourceDelivery.Stage.DEPOSIT,1,UUID.randomUUID(),r.dropIds());
        // Preserve the intent's actual operation binding while changing its original batch.
        wrong=new ResourceDelivery.Receipt(wrong.receiptId(),wrong.origin(),wrong.runId(),i.operation(),wrong.batchId(),wrong.actor(),wrong.source(),wrong.destination(),wrong.item(),wrong.stage(),wrong.quantity(),wrong.sourceBlock(),wrong.dropIds());
        var changed=new Result(result.intent(),result.live(),result.actor(),result.chest(),result.failure(),wrong,result.usage());
        assertThrows(IllegalArgumentException.class,()->new Entry(good.intent(),changed));
    }
    @Test void prefixReservationRetainsTheExistingRecordAndResultQuotas() {
        Intent i=NativeDepositTest.intent();var sample=NativeDepositTest.completed(i).result().receipt();var many=new ArrayList<ResourceDelivery.Receipt>();
        for(int n=0;n<64;n++)many.add(receipt(sample,ResourceDelivery.Stage.HARVEST,1,UUID.randomUUID(),List.of(UUID.randomUUID())));
        var conditions=i.conditions();var usage=Map.of(Budgets.Kind.COMMITTED_EFFECTS,64L);
        Intent large=new Intent(i.key(),i.origin(),i.run(),i.operation(),i.actor(),i.chest(),i.item(),i.actorSlot(),i.chestSlot(),i.capacity(),
                new Conditions(conditions.citizen(),conditions.jobRevision(),conditions.deadlineEpochMillis(),conditions.actionDeadlineEpochMillis(),conditions.effectsRemaining(),conditions.observedTick(),conditions.maximumReachMilli(),conditions.observedReachMilli(),conditions.claims(),usage),new ResourceReceiptPrefix(many.subList(0,63)));
        assertThrows(IllegalArgumentException.class,()->encode(new Entry(large,null)));
        assertThrows(IllegalArgumentException.class,()->new Entry(with(large,new ResourceReceiptPrefix(many)),null));
        many.add(receipt(sample,ResourceDelivery.Stage.HARVEST,1,UUID.randomUUID(),List.of(UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class,()->new ResourceReceiptPrefix(many));
    }
}
