package dev.aivillages.providers;

import com.google.gson.*;
import dev.aivillages.core.kernel.LanguageReferenceClassifier;
import dev.aivillages.core.kernel.LanguageRequests;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit actual-model measurement. Unit-test extraction fixtures are never counted here. */
public final class D01LanguageLiveProbe {
    public static void main(String[] args) throws Exception {
        Path corpus = Path.of("providers/src/test/resources/nlu/d01-requests.json");
        byte[] original = Files.readAllBytes(corpus);
        Path assets = Path.of(Objects.requireNonNull(System.getenv("COGNITIVECRAFT_NEEDLE_DIR")));
        Path evidence = Path.of("build/d01-evidence");
        Files.createDirectories(evidence);
        List<Map<String,Object>> rows = new ArrayList<>();
        int correct = 0;
        long actualCalls;
        try (var model = new LocalNeedleAdapter(new LocalNeedleAdapter.Config(
                assets.resolve("needle"), assets.resolve("needle3.cact")))) {
            for (JsonElement element : JsonParser.parseString(new String(original, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray()) {
                JsonObject item = element.getAsJsonObject();
                String text = item.get("text").getAsString();
                var input = new LanguageRequests.Input(text, List.of("Ada", "Mira"));
                var prepared = NeedleWire.prepare(input);
                long before = System.nanoTime();
                var result = model.interpret(input).result().toCompletableFuture().get(35, TimeUnit.SECONDS);
                long elapsed = (System.nanoTime()-before)/1_000_000;
                String expected = item.get("outcome").getAsString();
                boolean recognized = prepared.classification().intent() == LanguageReferenceClassifier.Intent.GATHER_OAK_LOGS;
                boolean pass = expected.equals("UNSUPPORTED")
                        ? !recognized && result.kind() != LanguageRequests.Kind.EXTRACTED
                        : recognized && result.kind().name().equals(expected);
                if (expected.equals("EXTRACTED")) {
                    var extracted = result.extracted();
                    boolean implicit = item.get("id").getAsString().equals("D01-fetch-implicit-actor");
                    pass &= extracted != null && extracted.action() == LanguageRequests.Action.GATHER_OAK_LOGS
                            && Objects.equals(extracted.amount(), item.get("amount").getAsLong())
                            && Objects.equals(extracted.citizen(), implicit ? null : "Ada")
                            && result.confidence() >= LanguageRequests.MIN_CONFIDENCE;
                }
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("id",item.get("id").getAsString()); row.put("text",text);
                row.put("classification",prepared.classification()); row.put("preparedPrompt",prepared.text());
                row.put("tools",JsonParser.parseString(prepared.tools())); row.put("result",result);
                row.put("elapsedMillis",elapsed); row.put("expected",expected); row.put("correct",pass);
                rows.add(row); if (pass) correct++;
                System.out.println(row.get("id")+" intent="+prepared.classification().intent()+" result="+result.kind()+" correct="+pass);
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(model.busy() && System.nanoTime()<until) Thread.sleep(10);
            }
            actualCalls=model.calls();
        }
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("source",System.getenv("GITHUB_SHA")); report.put("branch",System.getenv("GITHUB_REF_NAME"));
        report.put("runId",System.getenv("GITHUB_RUN_ID")); report.put("attempt",System.getenv("GITHUB_RUN_ATTEMPT"));
        report.put("corpusSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
        report.put("modelRevision",LocalNeedleAdapter.REVISION); report.put("modelSha256",LocalNeedleAdapter.MODEL_SHA256);
        report.put("runnerSha256",LocalNeedleAdapter.RUNNER_SHA256); report.put("actualCalls",actualCalls);
        report.put("correct",correct); report.put("total",rows.size()); report.put("cases",rows);
        report.put("originalPhysicalDelivery","NOT_EXECUTED_UNBOUND_DEMONSTRATIVE");
        report.put("fullTaskPass",false);
        Files.writeString(evidence.resolve("actual-needle.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        if(correct!=rows.size() || actualCalls!=rows.size())
            throw new IllegalStateException("D01 actual language checks "+correct+"/"+rows.size()+"; calls="+actualCalls);
    }
}
