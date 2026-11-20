package dev.aivillages.fabric;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.aivillages.core.kernel.ResourceDelivery.Stage;
import dev.aivillages.core.kernel.runtime.evidence.NativeRecipeJournal;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Holds one owned journal worker at a real native interruption boundary. */
public final class I00ChoiceCutFixture implements ModInitializer {
    private static final Gson JSON = new Gson();
    private static final AtomicReference<Trial> ARMED = new AtomicReference<>();
    private static final Path REQUEST = Path.of("i00-choice-cut.json");
    private static final Set<String> CUTS = Set.of("before-native", "after-native-before-publication",
            "after-publication-before-participant-saves", "after-saves-before-acknowledgement");

    private record Trial(UUID id, Stage phase, String boundary, String method, Path path,
                         AtomicReference<NativeRecipeJournal.Intent> intent) { }

    private UUID armedId;

    @Override public void onInitialize() {
        String evidence = System.getenv("COGNITIVECRAFT_E2E_DIR");
        if (evidence == null) return;
        Path directory = Path.of(evidence).resolve("server");
        Path request = directory.resolve(REQUEST);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (armedId != null || !Files.isRegularFile(request)) return;
            try {
                if (Files.size(request) > 1024) throw new IOException("Choice cut request exceeds fixture envelope");
                var row = JsonParser.parseString(Files.readString(request)).getAsJsonObject();
                UUID id = UUID.fromString(row.get("id").getAsString());
                String boundary = row.get("boundary").getAsString();
                Stage stage = Stage.valueOf(row.get("phase").getAsString());
                String method = row.get("method").getAsString();
                if (!CUTS.contains(boundary) || !Set.of(Stage.WITHDRAW, Stage.CRAFT, Stage.DEPOSIT, Stage.PLACE).contains(stage)
                        || !method.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid native choice cut");
                var trial = new Trial(id, stage, boundary, method, directory, new AtomicReference<>());
                armedId = id;
                ARMED.set(trial);
                write(trial, "armed", Map.of("id", id, "boundary", boundary, "phase", stage, "pid", ProcessHandle.current().pid()));
            } catch (IOException failure) {
                throw new IllegalStateException("Could not arm isolated native choice cut", failure);
            }
        });
    }

    public static void prepared(NativeRecipeJournal.Entry entry) {
        Trial trial = ARMED.get();
        if (trial == null || entry.result() != null || entry.intent().phase() != trial.phase()
                || !entry.intent().method().equals(trial.method()) || !trial.intent().compareAndSet(null, entry.intent())) return;
        if (trial.boundary().equals("before-native")) hold(trial, entry.intent(), null, false);
    }

    public static void beforeResult(NativeRecipeJournal.Result result) {
        Trial trial = ARMED.get();
        if (trial == null || !trial.boundary().equals("after-native-before-publication")) return;
        var intent = trial.intent().get();
        if (intent != null && intent.token().equals(result.token())) hold(trial, intent, result, false);
    }

    public static void afterResult(NativeRecipeJournal.Entry entry) {
        Trial trial = ARMED.get();
        if (trial == null || entry.result() == null || !entry.intent().equals(trial.intent().get())
                || !(trial.boundary().equals("after-publication-before-participant-saves")
                        || trial.boundary().equals("after-saves-before-acknowledgement"))) return;
        hold(trial, entry.intent(), entry.result(), true);
    }

    private static void hold(Trial trial, NativeRecipeJournal.Intent intent, NativeRecipeJournal.Result result, boolean published) {
        if (result != null && result.status() != NativeRecipeJournal.Status.APPLIED) return;
        if (!ARMED.compareAndSet(trial, null)) return;
        try {
            write(trial, "reached", Map.of("id", trial.id(), "boundary", trial.boundary(), "phase", trial.phase(),
                    "pid", ProcessHandle.current().pid(), "time", System.currentTimeMillis(), "intent", journalRow("prepare", intent),
                    "observedResult", result == null ? Map.of() : journalRow("result", result), "resultPublished", published));
            // This worker has no authority to change the native result. The
            // supervisor ends the cold JVM or the ordinary result proceeds.
            new CountDownLatch(1).await(70, TimeUnit.SECONDS);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not retain the original native cut", failure);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private static Object journalRow(String kind, Record record) {
        var row = JSON.toJsonTree(record).getAsJsonObject();
        row.addProperty("kind", kind);
        return row;
    }

    private static void write(Trial trial, String stage, Map<String, ?> value) throws IOException {
        Path target = trial.path().resolve("i00-choice-cut-" + stage + ".json");
        Path next = target.resolveSibling(target.getFileName() + ".next");
        Files.writeString(next, JSON.toJson(value));
        Files.move(next, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
