package dev.aivillages.fabric;

import dev.aivillages.core.kernel.StrictJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded test diagnostics, always read/written off the game thread; no lifecycle ownership. */
final class HardKillEvidence {
    private static final int MAX_FILES = 16, MAX_BYTES = 131_072, MAX_PROOF_BYTES = 16_384;

    static Path configuredPath() {
        String configured = System.getProperty("cognitivecraft.gametest.hardKillReady");
        if (configured == null) throw new IllegalStateException("Missing CI hard-kill evidence path");
        return Path.of(configured).toAbsolutePath().normalize();
    }

    static void publish(Path world, Path path, Map<String, Object> context) {
        try {
            Map<String, Object> proof = new LinkedHashMap<>(context);
            proof.put("world", world.toAbsolutePath().normalize().toString());
            proof.put("files", snapshot(world));
            byte[] bytes = StrictJson.canonical(proof).getBytes(StandardCharsets.UTF_8);
            require(bytes.length <= MAX_PROOF_BYTES, "Oversized hard-kill proof");
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) { throw new IllegalStateException("Hard-kill evidence write failed", failure); }
    }

    static Map<String, Object> verify(Path world, Path path) {
        try {
            Map<String, Object> proof = read(path);
            Map<String, Object> result = read(path.resolveSibling("hardkill-verification.json"));
            require(Long.valueOf(1).equals(proof.get("schema"))
                            && "before-interrupted-journal-replace".equals(proof.get("point"))
                            && "SIGKILL".equals(result.get("signal"))
                            && Boolean.TRUE.equals(result.get("observedTermination"))
                            && Boolean.TRUE.equals(result.get("sourceFilesUnchanged"))
                            && proof.get("process").equals(result.get("process"))
                            && ((Number)result.get("launcherExit")).longValue() != 0,
                    "Missing verified external SIGKILL");
            require(world.toAbsolutePath().normalize().toString().equals(proof.get("world"))
                            && snapshot(world).equals(proof.get("files")),
                    "SIGKILL changed protected authoritative records before recovery");
            return Map.copyOf(proof);
        } catch (Exception failure) { throw new IllegalStateException("Hard-kill evidence read failed", failure); }
    }

    private static Map<String, Object> read(Path path) throws Exception {
        require(!Files.isSymbolicLink(path) && Files.size(path) <= MAX_PROOF_BYTES, "Invalid test evidence file");
        return StrictJson.object(Files.readString(path));
    }

    private static Map<String, String> snapshot(Path world) throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        int bytes = 0;
        try (var stream = Files.walk(world.resolve("data"))) {
            var paths = stream.limit(65).toList();
            require(paths.size() <= 64, "Hard-kill traversal exceeded its bound");
            for (Path file : paths) {
                require(!Files.isSymbolicLink(file), "Symbolic test store path");
                if (!Files.isRegularFile(file) || file.getFileName().toString().endsWith(".lock")
                        || file.toString().contains("/staging/")) continue;
                require(Files.size(file) <= 65_536, "Hard-kill file exceeds its read bound");
                byte[] content = Files.readAllBytes(file);
                bytes = Math.addExact(bytes, content.length);
                require(content.length <= 65_536 && bytes <= MAX_BYTES && hashes.size() < MAX_FILES,
                        "Hard-kill snapshot exceeded its finite envelope");
                hashes.put(world.relativize(file).toString(), java.util.HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
            }
        }
        require(!hashes.isEmpty(), "Missing authoritative records");
        return Map.copyOf(hashes);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
