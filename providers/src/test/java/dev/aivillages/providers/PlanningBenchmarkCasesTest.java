package dev.aivillages.providers;

import dev.aivillages.core.kernel.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class PlanningBenchmarkCasesTest {
    @Test void fortyUniqueRequestsIncludeExplicitGoldenFeasibilityAndRepresentativeCrafting() throws Exception {
        var cases=PlanningBenchmarkCases.load();assertEquals(40,cases.size());assertEquals(40,cases.stream().map(PlanningBenchmarkCases.Case::id).distinct().count());
        assertEquals(12,cases.stream().filter(c->c.material()==null).count());
        assertEquals(8,cases.stream().filter(c->c.expectedStatus().equals("insufficient_resources")).count());
        assertEquals(2,cases.stream().filter(c->c.expectedStatus().equals("unsupported")).count());
        assertEquals(9,cases.stream().filter(c->c.category().equals("single_step")).count());
        for(var c:cases)if(c.material()!=null)assertEquals(c.expectedStatus(),MaterialPlan.requirements(c.material()).status(),c.id());
    }
    @Test void fiftyDisjointFrozenCasesPreserveFeasibilityAndAllCraftingCategories() throws Exception {
        var cases=PlanningBenchmarkCases.load("acceptance-requests.json");assertEquals(50,cases.size());
        assertEquals(50,cases.stream().map(PlanningBenchmarkCases.Case::id).distinct().count());
        assertEquals(40,cases.stream().filter(c->c.expectedStatus().equals("plan")).count());
        assertEquals(28,cases.stream().filter(c->c.material()!=null&&c.expectedStatus().equals("plan")).count());
        for(var c:cases)if(c.material()!=null)assertEquals(c.expectedStatus(),MaterialPlan.requirements(c.material()).status(),c.id());
        try(var in=getClass().getResourceAsStream("/planning/acceptance-requests.json")) {
            assertEquals("629798799640026f8ae7f096027bece765d3dda74386fbbceead0237be76a9a9",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(in).readAllBytes())));
        }
    }
    @Test void recoveredHistoricalCandidatesMatchEveryOriginalDigest() throws Exception {
        try(var in=getClass().getResourceAsStream("/planning/legacy-evidence.json")) {
            var root=StrictJson.transportObject(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8));
            var rows=(List<?>)root.get("cases");assertEquals(3,rows.size());
            for(var value:rows) {
                var row=(Map<?,?>)value;byte[] bytes=((String)row.get("candidate")).getBytes(StandardCharsets.UTF_8);
                assertEquals(row.get("candidateSha256"),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                assertEquals(row.get("outputBytes"),Long.valueOf(bytes.length));
            }
        }
    }
}
