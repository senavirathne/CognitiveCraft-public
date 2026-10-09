package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;

/** Actual research/compiler/fixture/executor/publication owners; controlled generation transport. */
class BrokerResearchIntegrationTest {
    @Test void coalescingPreservesIndependentTrialsAndAdmission() throws Exception {
        ResearchChecks.brokerSharesGenerationButKeepsAdmissionAndTrialsSeparate();
    }
    @Test void cancellationCannotPromoteLateCandidate() throws Exception {
        ResearchChecks.brokerCancellationCannotPromoteLateCandidate();
    }
    @Test void repairKeepsOriginalCumulativeResearchAllowance() throws Exception {
        ResearchChecks.brokerRepairConsumesOriginalResearchParent();
    }
}
