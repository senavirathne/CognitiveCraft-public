package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;

/** One independently reported JUnit case per repository acceptance group. */
final class RepositoryJUnitTest {
    @Test void saveReload() throws Exception { RepositoryChecks.saveReload(); }
    @Test void dedupAndPrivacy() throws Exception { RepositoryChecks.dedupAndPrivacy(); }
    @Test void admissionAuthority() throws Exception { RepositoryChecks.admissionAuthority(); }
    @Test void publicationFaults() throws Exception { RepositoryChecks.publicationFaults(); }
    @Test void corruptionAndUnknownSchema() throws Exception {
        RepositoryChecks.corruptionAndUnknownSchema();
    }
    @Test void compatibility() throws Exception { RepositoryChecks.compatibility(); }
    @Test void quotas() throws Exception { RepositoryChecks.quotas(); }
    @Test void concurrentPublication() throws Exception { RepositoryChecks.concurrentPublication(); }
    @Test void rootsAndCacheOutage() throws Exception { RepositoryChecks.rootsAndCacheOutage(); }
}
