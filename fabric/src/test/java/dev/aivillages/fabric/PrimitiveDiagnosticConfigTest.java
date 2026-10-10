package dev.aivillages.fabric;

import org.junit.jupiter.api.Test;
import java.util.*;
import dev.aivillages.core.kernel.PrimitiveDiagnostics.*;
import static org.junit.jupiter.api.Assertions.*;

class PrimitiveDiagnosticConfigTest {
    @Test void defaultOffAndTrustedMetadataEnablementHaveNoPlayerInput() {
        assertEquals(Mode.OFF,PrimitiveDiagnosticConfig.read(Map.of()).policy().mode());
        var c=PrimitiveDiagnosticConfig.read(Map.of("COGNITIVECRAFT_PRIMITIVE_DIAGNOSTICS","metadata"));
        assertTrue(c.valid());assertTrue(c.policy().enabled());assertEquals(Mode.METADATA,c.policy().mode());
    }
    @Test void excerptEnablementRequiresExplicitFiniteScopeAllowlist() {
        assertFalse(PrimitiveDiagnosticConfig.read(Map.of("COGNITIVECRAFT_PRIMITIVE_DIAGNOSTICS","excerpts")).valid());
        assertFalse(PrimitiveDiagnosticConfig.read(Map.of("COGNITIVECRAFT_PRIMITIVE_DIAGNOSTICS","excerpts",
                "COGNITIVECRAFT_PRIMITIVE_DIAGNOSTIC_SCOPES","*")).valid());
        String scope=UUID.randomUUID()+"/"+UUID.randomUUID();
        var c=PrimitiveDiagnosticConfig.read(Map.of("COGNITIVECRAFT_PRIMITIVE_DIAGNOSTICS","excerpts",
                "COGNITIVECRAFT_PRIMITIVE_DIAGNOSTIC_SCOPES",scope));
        assertTrue(c.valid());assertEquals(1,c.policy().excerptScopes().size());
        assertFalse(PrimitiveDiagnosticConfig.read(Map.of("COGNITIVECRAFT_PRIMITIVE_DIAGNOSTICS","excerpts",
                "COGNITIVECRAFT_PRIMITIVE_DIAGNOSTIC_SCOPES",scope+","+scope)).valid());
    }
    @Test void invalidOverflowNegativeAndInconsistentValuesDisableDiagnosticsOnly() {
        for(String value:List.of("-1","9223372036854775807","99999999999999999999","129","not-an-integer")) {
            var c=PrimitiveDiagnosticConfig.read(Map.of("COGNITIVECRAFT_PRIMITIVE_DIAGNOSTICS","metadata",
                    "COGNITIVECRAFT_PRIMITIVE_DIAGNOSTIC_QUEUE",value));
            assertFalse(c.valid());assertEquals(Mode.OFF,c.policy().mode());
        }
        assertFalse(PrimitiveDiagnosticConfig.read(Map.of("COGNITIVECRAFT_PRIMITIVE_DIAGNOSTICS","metadata",
                "COGNITIVECRAFT_PRIMITIVE_DIAGNOSTIC_QUEUE_BYTES","1")).valid());
        assertTrue(PrimitiveDiagnosticConfig.read(Map.of("COGNITIVECRAFT_PRIMITIVE_DIAGNOSTICS","metadata",
                "COGNITIVECRAFT_PRIMITIVE_DIAGNOSTIC_QUEUE","0")).valid());
    }
}
