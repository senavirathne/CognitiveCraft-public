package dev.aivillages.e2e.parallelc;

import java.nio.file.Path;
import org.gradle.api.internal.tasks.testing.results.serializable.SerializableTestResultStore;

/** Read-only export of the pinned Gradle result store; never registers or executes tests. */
public final class GradleResults {
    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }

    public static void main(String[] args) throws Exception {
        var store = new SerializableTestResultStore(Path.of(args[0]));
        if (!store.hasResults()) throw new IllegalStateException("Missing Gradle binary results");
        store.forEachResult((id, parent, result, output) -> System.out.println(
                "{\"id\":" + id + ",\"parent\":" + parent + ",\"name\":" + quote(result.getName())
                + ",\"display\":" + quote(result.getDisplayName()) + ",\"class\":" + quote(result.getClassName())
                + ",\"outcome\":" + quote(result.getResultType().name()) + ",\"failures\":" + result.getFailures().size()
                + ",\"start\":" + result.getStartTime() + ",\"end\":" + result.getEndTime() + "}"));
    }
}
