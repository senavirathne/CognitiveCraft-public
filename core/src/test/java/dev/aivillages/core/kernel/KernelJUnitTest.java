package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;

/** Gradle/JUnit entry points for the standalone deterministic matrix fixtures. */
class KernelJUnitTest {
    @Test void typedRequests() { KernelChecks.typedRequests(); }
    @Test void irTyping() { KernelChecks.irTyping(); }
    @Test void strictRepresentation() { KernelChecks.strictRepresentation(); }
    @Test void controlFlowBounds() { KernelChecks.controlFlowBounds(); }
    @Test void canonicalIdentity() { KernelChecks.canonicalIdentity(); }
    @Test void parameterReuse() { KernelChecks.parameterReuse(); }
    @Test void outcomeTaxonomy() throws Exception { KernelChecks.outcomeTaxonomy(); }
    @Test void budgetComposition() { KernelChecks.budgetComposition(); }
    @Test void dependencyIntegrity() { KernelChecks.dependencyIntegrity(); }
    @Test void trustedCompletion() { KernelChecks.trustedCompletion(); }
    @Test void noExternalDependencies() { KernelChecks.noExternalDependencies(); }
}
