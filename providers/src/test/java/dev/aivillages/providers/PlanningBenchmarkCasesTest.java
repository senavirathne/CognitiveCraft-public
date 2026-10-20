package dev.aivillages.providers;

import dev.aivillages.core.kernel.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PlanningBenchmarkCasesTest {
    @Test void fortyUniqueRequestsIncludeExplicitGoldenFeasibilityAndRepresentativeCrafting() throws Exception {
        var cases=PlanningBenchmarkCases.load();assertEquals(40,cases.size());assertEquals(40,cases.stream().map(PlanningBenchmarkCases.Case::id).distinct().count());
        assertEquals(12,cases.stream().filter(c->c.material()==null).count());
        assertEquals(8,cases.stream().filter(c->c.expectedStatus().equals("insufficient_resources")).count());
        assertEquals(2,cases.stream().filter(c->c.expectedStatus().equals("unsupported")).count());
        assertEquals(9,cases.stream().filter(c->c.category().equals("single_step")).count());
        for(var c:cases)if(c.material()!=null)assertEquals(c.expectedStatus(),MaterialPlan.requirements(c.material()).status(),c.id());
    }
}
