package dev.aivillages.core.kernel.interaction.contract;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

final class InteractionCodecTest {
    private final InteractionCodec codec = new InteractionCodec();
    private static Path contract() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("e2e/parallel_c/contracts"))) path = path.getParent();
        if (path == null) throw new AssertionError("Missing frozen C contract");
        return path.resolve("e2e/parallel_c/contracts");
    }
    @SuppressWarnings("unchecked")
    @TestFactory Stream<DynamicTest> canonicalFixtures() throws Exception {
        List<Path> files;
        try (var paths = Files.list(contract().resolve("fixtures"))) { files = paths.sorted().toList(); }
        assertEquals(57, files.size());
        return files.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
            var fixture = (Map<String, Object>) InteractionCodec.parseJson(Files.readString(path));
            String wire = InteractionCodec.canonical(fixture.get("message"));
            assertEquals(fixture.get("expected"), codec.inspect(wire, InteractionCodec.canonical(fixture.get("context"))).name());
            if (List.of("VALID", "REFUSED_EXPECTED").contains(fixture.get("expected"))) {
                var message = codec.decode(wire);
                assertEquals(wire, codec.encode(message));
                assertThrows(UnsupportedOperationException.class, () -> message.body().put("authority", true));
            }
        }));
    }
    @Test void rejectsDuplicateKeys() { assertThrows(InteractionWire.DecodeException.class, () -> InteractionCodec.parseJson("{\"a\":null,\"a\":1}")); }
    @Test void rejectsNonIntegerTokens() {
        for (String value : List.of("1.0", "1e1", "01", "NaN", "+1", "9007199254740992"))
            assertThrows(InteractionWire.DecodeException.class, () -> InteractionCodec.parseJson(value));
    }
    @Test void rejectsUnknownVersionAndFields() throws Exception {
        String valid = message("positive-StorageSelector.json");
        assertThrows(InteractionWire.DecodeException.class, () -> codec.decode(valid.replace("cc.cd/1.0.0", "cc.cd/2.0.0")));
        assertThrows(InteractionWire.DecodeException.class, () -> codec.decode(valid.replace("\"body\":{", "\"body\":{\"grant\":true,")));
    }
    @Test void refusesProjectionsAtLanguageIngress() throws Exception {
        assertThrows(InteractionWire.DecodeException.class, () -> codec.decodeCandidate(message("positive-CustodyCheckpoint.json")));
        assertTrue(codec.decodeCandidate(message("positive-DialogueIntent.json")).isCandidateIntent());
    }
    @Test void normalizesNeitherLabelsNorMalformedUnicode() {
        for (String bad : List.of("\"e\\u0301\"", "\"\\ud800\"", "\"\\udc00\"", "\"\u0001\""))
            assertThrows(InteractionWire.DecodeException.class, () -> InteractionCodec.parseJson(bad));
        assertEquals("é", InteractionCodec.parseJson("\"é\""));
    }
    @Test void refusesOversizeAndDeepInput() {
        assertThrows(InteractionWire.DecodeException.class, () -> InteractionCodec.parseJson(" ".repeat(InteractionCodec.MAX_INPUT_BYTES + 1)));
        assertThrows(InteractionWire.DecodeException.class, () -> InteractionCodec.parseJson("[".repeat(66)+"0"+"]".repeat(66)));
    }
    @Test void noPublicTrustedConstructorOrFactoryExists() {
        assertEquals(0, InteractionWire.TrustedProjection.class.getConstructors().length);
        assertTrue(Stream.of(InteractionCodec.class.getMethods()).noneMatch(m -> m.getReturnType() == InteractionWire.TrustedProjection.class));
    }
    @SuppressWarnings("unchecked")
    private static String message(String fixture) throws Exception {
        return InteractionCodec.canonical(((Map<String, Object>) InteractionCodec.parseJson(Files.readString(contract().resolve("fixtures/"+fixture)))).get("message"));
    }
}
