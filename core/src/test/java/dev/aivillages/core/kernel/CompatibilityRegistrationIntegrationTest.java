package dev.aivillages.core.kernel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompatibilityRegistrationIntegrationTest {
    @Test void combinedOwnersRegisterWithoutAdmittingUnknownVersions() {
        var registry = SaveCompatibility.registered();
        assertEquals(17, registry.readers().size());
        for (var reader : registry.readers()) {
            assertTrue(registry.readable(new SaveCompatibility.Format(reader.domain(), reader.current())));
            assertFalse(registry.readable(new SaveCompatibility.Format(reader.domain(), reader.current() + 1L)));
        }
        assertFalse(registry.readable(new SaveCompatibility.Format("unregistered-owner", 1)));
    }

    @Test void trustedRegistrationCapacityRemainsFiniteAndRejectsDuplicateDomains() {
        var readers = new ArrayList<SaveCompatibility.Reader>();
        for (int i = 0; i < SaveCompatibility.MAX_READER_REGISTRATIONS; i++)
            readers.add(new SaveCompatibility.Reader("bounded-owner-" + i, 1, Set.of(1)));
        assertDoesNotThrow(() -> new SaveCompatibility.Registry(readers, List.of()));
        readers.add(new SaveCompatibility.Reader("overflow-owner", 1, Set.of(1)));
        assertThrows(IllegalArgumentException.class, () -> new SaveCompatibility.Registry(readers, List.of()));
        var first = readers.getFirst();
        assertThrows(IllegalArgumentException.class, () -> new SaveCompatibility.Registry(List.of(first, first), List.of()));
    }
}
