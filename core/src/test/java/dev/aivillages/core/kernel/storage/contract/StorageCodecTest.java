package dev.aivillages.core.kernel.storage.contract;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.storage.contract.StorageWire.*;

final class StorageCodecTest {
    private static Path contracts() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isDirectory(p.resolve("e2e/parallel_d/contracts"))) return p.resolve("e2e/parallel_d/contracts");
        throw new AssertionError("frozen fixtures missing");
    }
    @TestFactory Stream<DynamicTest> frozenFixtures() throws Exception {
        var paths = Files.list(contracts().resolve("fixtures"));
        return paths.sorted().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
            var fixture = object(StorageJson.parse(Files.readString(path)));
            var wire = StorageJson.canonical(fixture.get("message"));
            var context = object(fixture.get("context"));
            String actual;
            try {
                var decoded = StorageCodec.decode(wire);
                assertEquals(wire,StorageCodec.encode(decoded));
                actual = StorageCodec.evaluate(decoded, new Context((Long)context.get("trusted_generation"),
                        (Boolean)context.get("reobserved_requirements_satisfied"),(String)context.get("authenticated_principal"),
                        (String)context.get("question_principal"),(String)context.get("current_incarnation"),
                        (Long)context.get("current_acl_revision"),(Boolean)context.get("a_proof_authenticated"),
                        (Long)context.get("eligible_registered_count"))).name();
            } catch (StorageCodec.Invalid e) { actual = e.disposition().name(); }
            // Raw observations, not a self-certified pass flag. The Python verifier compares frozen expectations.
            System.out.println("CC_D00_FIXTURE " + StorageJson.canonical(Map.of("id",fixture.get("id"),"actual",actual)).strip());
            assertEquals(fixture.get("expected"),actual,(String)fixture.get("id"));
        })).onClose(paths::close);
    }
    @Test void malformedAndUnknownInputFailsClosed() throws Exception {
        String good = StorageJson.canonical(object(StorageJson.parse(Files.readString(contracts().resolve("fixtures/positive-StorageSelector.json")))).get("message"));
        for (String bad : List.of(good.replace("cc.cd/1.0.0","cc.cd/2.0.0"),good.replace("\"body\":","\"extra\":null,\"body\":"),
                good.replace("\"schema\":","\"schema\":\"cc.cd/1.0.0\",\"schema\":"),good + "{}", "\ufeff" + good,
                good.strip(),good.replace("\"label\":\"", "\"label\":\"e\\u0301"),"{\"x\":1.0}\n","{\"x\":9007199254740992}\n")) {
            assertThrows(StorageCodec.Invalid.class,() -> StorageCodec.decode(bad));
        }
        assertThrows(StorageCodec.Invalid.class,() -> StorageCodec.decode(new byte[]{(byte)0xc3,0x28}));
    }
    @Test void decodedProjectionIsDeeplyImmutableAndGrantsNothing() throws Exception {
        var message = object(StorageJson.parse(Files.readString(contracts().resolve("fixtures/positive-DialogueIntent.json")))).get("message");
        var decoded = StorageCodec.decode(StorageJson.canonical(message));
        assertThrows(UnsupportedOperationException.class,() -> decoded.body().put("action","DELIVER"));
        assertThrows(UnsupportedOperationException.class,() -> object(decoded.body().get("actor")).put("kind","EXPLICIT_ID"));
    }
}
