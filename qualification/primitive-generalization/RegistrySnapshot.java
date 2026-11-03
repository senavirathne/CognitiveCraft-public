import dev.aivillages.core.kernel.Contracts.PrimitiveRequirement;
import dev.aivillages.core.kernel.GatewayPrimitives;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Read the production JAR's live catalog and compare it to the frozen v1 interface. */
final class RegistrySnapshot {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected golden TSV and observed output paths");
        var catalog = GatewayPrimitives.instance();
        var observed = new ArrayList<String>();
        observed.add("id\tversion\teffect\tresult\tparameters\tfingerprint");
        for (var signature : catalog.all()) {
            var requirement = new PrimitiveRequirement(signature.id(), signature.version(), signature.fingerprint());
            if (catalog.find(signature.id(), signature.version()).orElseThrow() != signature
                    || !catalog.operation(requirement).orElseThrow().signature().equals(signature)) {
                throw new AssertionError("Cannot resolve exact requirement " + signature.id());
            }
            String badHash = (signature.fingerprint().startsWith("0") ? "1" : "0") + signature.fingerprint().substring(1);
            if (catalog.operation(new PrimitiveRequirement(signature.id(), signature.version(), badHash)).isPresent()
                    || catalog.find(signature.id(), signature.version() + 1).isPresent()) {
                throw new AssertionError("Accepted an incompatible requirement for " + signature.id());
            }
            String parameters = signature.parameters().stream()
                    .map(p -> p.name() + ":" + p.type() + ":" + p.minimum() + ":" + p.maximum())
                    .collect(Collectors.joining(","));
            String effects = signature.effects().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
            observed.add(String.join("\t", signature.id(), Integer.toString(signature.version()), effects,
                    signature.resultType().name(), parameters, signature.fingerprint()));
        }
        Files.write(Path.of(args[1]), observed);
        List<String> expected = Files.readAllLines(Path.of(args[0]));
        if (expected.size() != 52 || !observed.equals(expected)) {
            throw new AssertionError("Legacy contract mismatch. Compare the preserved observed TSV to the golden TSV.");
        }
        System.out.println("PG00 v1: 51 exact signatures and 102 incompatible fingerprint/version controls verified");
    }
}
