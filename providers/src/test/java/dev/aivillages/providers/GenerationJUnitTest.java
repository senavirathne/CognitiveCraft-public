package dev.aivillages.providers;

import org.junit.jupiter.api.Test;

class GenerationJUnitTest {
    @Test void structuredRequest() throws Exception { GenerationChecks.structuredRequest(); }
    @Test void generationRepair() { GenerationChecks.generationRepair(); }
    @Test void transportBounds() { GenerationChecks.transportBounds(); }
    @Test void deadline() { GenerationChecks.deadline(); }
    @Test void cancellation() { GenerationChecks.cancellation(); }
    @Test void availabilityAndLifecycle() { GenerationChecks.availabilityAndLifecycle(); }
    @Test void duplicateCallbacksAndShutdown() { GenerationChecks.duplicateCallbacksAndShutdown(); }
    @Test void isolationAndUsage() { GenerationChecks.isolationAndUsage(); }
    @Test void threadingAndOverload() { GenerationChecks.threadingAndOverload(); }
    @Test void invalidAndZeroLimits() { GenerationChecks.invalidAndZeroLimits(); }
    @Test void localHttpTransport() throws Exception { GenerationChecks.localHttpTransport(); }
    @Test void missingInstalledTag() throws Exception { GenerationChecks.missingInstalledTag(); }
}
