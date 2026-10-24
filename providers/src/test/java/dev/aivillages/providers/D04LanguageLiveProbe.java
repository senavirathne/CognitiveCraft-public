package dev.aivillages.providers;

import com.google.gson.*;
import dev.aivillages.core.kernel.LanguageReferenceClassifier;
import dev.aivillages.core.kernel.LanguageRequests;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Genuine interpreter measurement; the host, never the model, owns reference anchors. */
public final class D04LanguageLiveProbe {
    public static void main(String[] args) throws Exception {
        boolean baseline=args.length>0 && args[0].equals("baseline");
        Path corpus=Path.of("providers/src/test/resources/nlu/d04-requests.json");
        byte[] original=Files.readAllBytes(corpus);
        Path assets=Path.of(Objects.requireNonNull(System.getenv("COGNITIVECRAFT_NEEDLE_DIR")));
        Path evidence=Path.of("build/d04-evidence");Files.createDirectories(evidence);
        Gson gson=new GsonBuilder().setPrettyPrinting().create();
        List<Map<String,Object>> rows=new ArrayList<>();int correct=0;long calls;
        try(var model=new LocalNeedleAdapter(new LocalNeedleAdapter.Config(assets.resolve("needle"),assets.resolve("needle3.cact")))) {
            for(JsonElement element:JsonParser.parseString(new String(original,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray()) {
                var item=element.getAsJsonObject();String text=item.get("text").getAsString();
                var input=new LanguageRequests.Input(text,List.of("Ada","Mira"));
                var prepared=NeedleWire.prepare(input);long start=System.nanoTime();
                var result=model.interpret(input).result().toCompletableFuture().get(35,TimeUnit.SECONDS);
                long elapsed=(System.nanoTime()-start)/1_000_000;
                String expected=item.get("outcome").getAsString();
                boolean pass=result.kind()!=LanguageRequests.Kind.EXTRACTED;
                if(expected.equals("EXTRACTED")) {
                    var e=result.extracted();
                    String citizen=item.get("citizen").isJsonNull()?null:item.get("citizen").getAsString();
                    String from=item.has("from")?item.get("from").getAsString():null;
                    String destination=item.has("destination")?item.get("destination").getAsString():null;
                    var slot=gson.toJsonTree(prepared.classification().destination()).getAsJsonObject();
                    String anchor=slot.has("anchor")?slot.get("anchor").getAsString():"ROLE_DEFAULT";
                    pass=prepared.classification().intent()==LanguageReferenceClassifier.Intent.MAKE_BIRCH_PLANKS
                            && result.kind()==LanguageRequests.Kind.EXTRACTED && e!=null
                            && e.action()==LanguageRequests.Action.MAKE_BIRCH_PLANKS
                            && Objects.equals(e.amount(),item.get("amount").getAsLong())
                            && Objects.equals(e.citizen(),citizen) && Objects.equals(e.from(),from)
                            && Objects.equals(e.through(),from) && Objects.equals(e.destination(),destination)
                            && result.confidence()>=LanguageRequests.MIN_CONFIDENCE
                            && anchor.equals(item.get("anchor").getAsString())
                            && prepared.classification().destination().objectType().name().equals(item.get("type").getAsString());
                }
                Map<String,Object> row=new LinkedHashMap<>();row.put("id",item.get("id").getAsString());
                row.put("text",text);row.put("classification",prepared.classification());row.put("preparedPrompt",prepared.text());
                row.put("tools",JsonParser.parseString(prepared.tools()));row.put("result",result);
                row.put("elapsedMillis",elapsed);row.put("expected",expected);row.put("correct",pass);
                rows.add(row);if(pass)correct++;
                System.out.println(row.get("id")+" result="+result.kind()+" correct="+pass);
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(model.busy()&&System.nanoTime()<until)Thread.sleep(10);
            }
            calls=model.calls();
        }
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("source",System.getenv("GITHUB_SHA"));report.put("branch",System.getenv("GITHUB_REF_NAME"));
        report.put("runId",System.getenv("GITHUB_RUN_ID"));report.put("attempt",System.getenv("GITHUB_RUN_ATTEMPT"));
        report.put("corpusPath",corpus.toString());report.put("measurementStage",baseline?"BEFORE_SHARED_SPATIAL_CORRECTION":"AFTER_SHARED_SPATIAL_CORRECTION");
        report.put("corpusSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
        report.put("modelRevision",LocalNeedleAdapter.REVISION);report.put("modelSha256",LocalNeedleAdapter.MODEL_SHA256);
        report.put("runnerSha256",LocalNeedleAdapter.RUNNER_SHA256);report.put("actualCalls",calls);
        report.put("correct",correct);report.put("total",rows.size());report.put("cases",rows);
        report.put("originalPhysicalDelivery","MEASURED_SEPARATELY_BY_CONNECTED_CLIENT_PROFILE");report.put("fullTaskPass",false);
        Files.writeString(evidence.resolve("actual-needle.json"),gson.toJson(report)+"\n");
        if(calls!=rows.size()||!baseline&&correct!=rows.size())throw new IllegalStateException("D04 actual checks "+correct+"/"+rows.size()+"; calls="+calls);
    }
}
