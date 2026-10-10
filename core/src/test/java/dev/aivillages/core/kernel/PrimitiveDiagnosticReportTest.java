package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.PrimitiveDiagnostics.*;
import static dev.aivillages.core.kernel.PrimitiveDiagnosticCodec.*;
import static dev.aivillages.core.kernel.PrimitiveDiagnosticServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PrimitiveDiagnosticReportTest {
    static Snapshot row(long n,CapabilityId capability,String catalog,int primitiveVersion,long count) {
        var first=initial(observation(n,OWNER,"$.body[0].id","secret-descriptor",capability,catalog,primitiveVersion),
                "00000000-0000-0000-0000-000000000001:1",new Time().wall,new byte[32],Policy.metadata(),Map.of());
        return new Snapshot(first.aggregateId(),count,count,first.firstSeen(),first.lastSeen(),first.bucketStart(),
                first.worldRef(),first.scopeRef(),first.principalRef(),first.capability(),first.catalog(),first.primitive(),
                first.fingerprint(),first.latestEventId(),first.representative(),false,true,Map.of());
    }
    @Test void groupingSortingAndPrivateFieldOmissionAreDeterministic() {
        var another=new CapabilityId("test:registered",1);
        var rows=List.of(row(1,CropDelivery.ID,"b".repeat(64),1,5),row(2,another,"b".repeat(64),1,7),
                row(3,CropDelivery.ID,"c".repeat(64),2,12));
        var families=Map.of(CropDelivery.ID,"Farming",another,"Other");
        var report=PrimitiveDiagnosticReport.render(rows,Policy.metadata(),families,new Time().wall);
        var reversed=new ArrayList<>(rows);Collections.reverse(reversed);
        assertArrayEquals(report.json(),PrimitiveDiagnosticReport.render(reversed,Policy.metadata(),families,new Time().wall).json());
        var m=parse(report.json(),65536);var grouped=(List<?>)m.get("rows");assertEquals(2,grouped.size());
        var first=cast(grouped.getFirst());assertEquals(12,num(first,"observedRejectedAttempts"));
        assertEquals(2,num(first,"distinctRegisteredCapabilities"));assertEquals(1,num(obj(first,"primitive"),"version"));
        String text=new String(report.json(),StandardCharsets.UTF_8);
        for(String forbidden:List.of("secret-descriptor",rows.getFirst().worldRef(),rows.getFirst().principalRef(),
                rows.getFirst().representative().attemptRef(),rows.getFirst().representative().candidateSha256(),
                "$.body","requestProjection","candidateExcerpt"))assertFalse(text.contains(forbidden),forbidden);
        assertTrue(bool(m,"potentiallyIncomplete"));assertFalse(bool(m,"lossCountsKnown"));
        assertEquals(bucket(new Time().wall)+7*DAY,report.expiry());
    }
    @Test void topNByteCapsUnmappedLabelsAndExpiredRowsAreExplicit() {
        var rows=new ArrayList<Snapshot>();
        for(int i=1;i<=101;i++)rows.add(row(i,CropDelivery.ID,"b".repeat(64),i,1));
        var result=PrimitiveDiagnosticReport.render(rows,Policy.metadata(),Map.of(),new Time().wall);
        assertTrue(result.rows()<=100);assertEquals(101-result.rows(),result.omitted());
        assertTrue(result.json().length<=65536);assertTrue(new String(result.json(),StandardCharsets.UTF_8).contains("Unmapped"));
        var tiny=PrimitiveDiagnosticReport.render(rows,policy(Map.of(Limit.REPORT_BYTES,1024L,Limit.REPORT_ROWS,2L)),Map.of(),new Time().wall);
        assertTrue(tiny.json().length<=1024);assertTrue(tiny.rows()<=2);assertEquals(101-tiny.rows(),tiny.omitted());
        var expired=PrimitiveDiagnosticReport.render(rows,Policy.metadata(),Map.of(),bucket(new Time().wall)+7*DAY);
        assertEquals(0,expired.rows());assertTrue(((List<?>)parse(expired.json(),65536).get("rows")).isEmpty());
        assertEquals(0,PrimitiveDiagnosticReport.render(rows,policy(Map.of(Limit.REPORT_BYTES,0L)),Map.of(),new Time().wall).json().length);
    }
    @Test void saturatedDemandCountsAndTrustedFamilyMapStayBounded() {
        var rows=List.of(row(1,CropDelivery.ID,"b".repeat(64),1,Long.MAX_VALUE-1),
                row(2,new CapabilityId("test:other",1),"b".repeat(64),1,2));
        var result=PrimitiveDiagnosticReport.render(rows,Policy.metadata(),Map.of(),new Time().wall);
        var first=cast(((List<?>)parse(result.json(),65536).get("rows")).getFirst());
        assertEquals(Long.MAX_VALUE,num(first,"observedRejectedAttempts"));assertTrue(bool(first,"saturated"));
        assertThrows(IllegalArgumentException.class,()->PrimitiveDiagnosticReport.render(rows,Policy.metadata(),
                Map.of(CropDelivery.ID,"malicious\nlabel"),new Time().wall));
    }
    @Test void schemaFieldValidationRejectsHostileAndNonIntegerPayloads() {
        var row=row(1,CropDelivery.ID,"b".repeat(64),1,1);
        byte[] valid=encode(row,8192);assertEquals(row,decode(valid));
        String base=new String(valid,StandardCharsets.UTF_8);
        for(String invalid:List.of(base.replace("\"schema\":1","\"schema\":2"),
                base.replace("\"revision\":1","\"revision\":1.5"),base.replace("\"schema\":1","\"schema\":1,\"schema\":1"),
                base.replace("\"model\":\"secret-descriptor\"","\"model\":\"evil\\u202e\""),
                base.replace("\"occurrences\":1","\"occurrences\":0"),
                base.replace("\"context\":\"compiler:1\"","\"context\":\"arbitrary\""))) {
            assertThrows(IllegalArgumentException.class,()->decode(invalid.getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(IllegalArgumentException.class,()->decode(new byte[]{(byte)0xc0,(byte)0xaf}));
    }
}
