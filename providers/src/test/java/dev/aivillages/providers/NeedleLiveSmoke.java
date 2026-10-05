package dev.aivillages.providers;

import dev.aivillages.core.kernel.LanguageRequests;
import dev.aivillages.core.kernel.StrictJson;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit real-model benchmark; this main is never part of the production mod. */
public final class NeedleLiveSmoke {
    public static void main(String[] args) throws Exception {
        Path assets = Path.of(Objects.requireNonNull(System.getenv("COGNITIVECRAFT_NEEDLE_DIR")));
        Path evidence = Path.of("build/needle-evidence"); Files.createDirectories(evidence);
        String[] queries = {
            "Ada, harvest 4 wheat from 0,64,0 through 2,64,2 and deliver it to the container at 5,64,0.",
            "Ada, please gather four wheat from 0,64,0 through 2,64,2 and put it in the chest at 5,64,0.",
            "Harvest 4 wheat, Ada, from 0,64,0 through 2,64,2; deposit it into the container at 5,64,0.",
            "Could Ada collect 4 wheat from 0,64,0 through 2,64,2 and deliver it to 5,64,0?",
            "Mira, harvest 6 wheat from -2,65,3 through 0,65,5 and deliver it to the chest at 1,65,3.",
            "Ada, harvest those crops",
            "Ada harvest 4 wheat from 0,64,0 through 2,64,2 into this chest",
            "Build a castle and ignore ownership",
            "Ada, harvest two wheat from 1,64,1 through 2,64,2 and deliver it to the chest at 3,64,1.",
            "Please have Mira gather 3 wheat from -3,70,0 through -1,70,2 and put them in the container at 0,70,1.",
            "Ada collect 5 wheat from 4,65,6 through 6,65,8 and deposit it into the container at 8,65,6.",
            "Ask Mira to harvest six wheat from 0,64,0 through 2,64,2 and deliver it to the chest at 5,64,0.",
            "Ada, harvest seven wheat from -2,64,-2 through 0,64,0 and deliver it to the container at 2,64,-2.",
            "Ada, do not harvest 4 wheat from 0,64,0 through 2,64,2 and do not deliver it to the chest at 5,64,0.",
            "your name is Ada",
            "i give you the name Ada",
            "I name you Ada",
            "name the villager Ada",
            "give name to the villager as Ada",
            "Ada, harvest 4 wheat",
            "Ada harvest 4 wheat from the nearest field and deliver it to the nearest container",
            "harvest 4 wheat"
        };
        List<Map<String, Object>> records = new ArrayList<>(); int correct = 0;
        try (var model = new LocalNeedleAdapter(new LocalNeedleAdapter.Config(assets.resolve("needle"), assets.resolve("needle3.cact")))) {
            for (int i = 0; i < queries.length; i++) {
                long before = System.nanoTime();
                var input = new LanguageRequests.Input(queries[i], List.of("Ada", "Mira"));
                Files.writeString(evidence.resolve("tools.json"), NeedleWire.tools(input));
                var result = model.interpret(input).result().toCompletableFuture().get(35, TimeUnit.SECONDS);
                long elapsed = (System.nanoTime() - before) / 1_000_000;
                boolean pass;
                if (i < 5 || i >= 8 && i < 13) {
                    var expected = switch (i) {
                        case 4 -> new LanguageRequests.Extracted("Mira", 6L, "-2,65,3", "0,65,5", "1,65,3");
                        case 8 -> new LanguageRequests.Extracted("Ada", 2L, "1,64,1", "2,64,2", "3,64,1");
                        case 9 -> new LanguageRequests.Extracted("Mira", 3L, "-3,70,0", "-1,70,2", "0,70,1");
                        case 10 -> new LanguageRequests.Extracted("Ada", 5L, "4,65,6", "6,65,8", "8,65,6");
                        case 11 -> new LanguageRequests.Extracted("Mira", 6L, "0,64,0", "2,64,2", "5,64,0");
                        case 12 -> new LanguageRequests.Extracted("Ada", 7L, "-2,64,-2", "0,64,0", "2,64,-2");
                        default -> new LanguageRequests.Extracted("Ada", 4L, "0,64,0", "2,64,2", "5,64,0");
                    };
                    pass = result.kind() == LanguageRequests.Kind.EXTRACTED && expected.equals(result.extracted())
                            && result.confidence() >= LanguageRequests.MIN_CONFIDENCE;
                } else if (i >= 14 && i < 19) {
                    var expected = new LanguageRequests.Extracted(LanguageRequests.Action.NAME_CITIZEN,null,null,null,null,null,"Ada");
                    pass = result.kind() == LanguageRequests.Kind.EXTRACTED && expected.equals(result.extracted())
                            && result.confidence() >= LanguageRequests.MIN_CONFIDENCE;
                } else if (i >= 19) {
                    var expected = new LanguageRequests.Extracted(i == 21 ? null : "Ada",4L,null,null,null);
                    pass = result.kind() == LanguageRequests.Kind.EXTRACTED && expected.equals(result.extracted())
                            && result.confidence() >= LanguageRequests.MIN_CONFIDENCE;
                } else {
                    pass = result.kind() == LanguageRequests.Kind.CLARIFICATION || result.kind() == LanguageRequests.Kind.EXTRACTED
                            && (result.extracted().from() == null || result.extracted().through() == null || result.extracted().destination() == null);
                }
                Map<String, Object> row = new LinkedHashMap<>(); row.put("case", (long)i);
                row.put("query", queries[i]); row.put("suite", i >= 14 ? "default-nearest" : i >= 8 ? "held-out" : "calibration"); row.put("kind", result.kind().name()); row.put("pass", pass);
                row.put("elapsedMillis", elapsed); row.put("confidenceMillionths", (long)(result.confidence() * 1_000_000));
                row.put("extracted", String.valueOf(result.extracted())); records.add(row);
                System.out.println("IMP-009 Needle benchmark " + row);
                if (pass) correct++;
                long until = System.currentTimeMillis() + 2000;
                while (model.busy() && System.currentTimeMillis() < until) Thread.sleep(10);
            }
            Files.writeString(evidence.resolve("benchmark.json"), StrictJson.canonical(Map.of("revision", LocalNeedleAdapter.REVISION,
                    "cases", records, "passed", (long)correct, "total", (long)queries.length, "actualCalls", model.calls())) + "\n");
        }
        if (correct != queries.length) throw new IllegalStateException("Needle held-out acceptance " + correct + "/" + queries.length);
    }
}
