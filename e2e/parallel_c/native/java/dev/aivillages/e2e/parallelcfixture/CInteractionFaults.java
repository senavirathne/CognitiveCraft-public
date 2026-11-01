package dev.aivillages.e2e.parallelcfixture;

import dev.aivillages.core.kernel.interaction.*;
import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.providers.LlmProvider;
import dev.aivillages.providers.interaction.ConversationProvider;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic fault outputs, explicitly distinct from genuine generations. Tokenization is actual pinned HTTP. */
public final class CInteractionFaults implements LlmProvider {
    private final String fault;
    private final URI base=URI.create(System.getenv("COGNITIVECRAFT_C_MODEL_URL"));
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String model=System.getenv("COGNITIVECRAFT_C_MODEL_SHA256");
    private final String tokenizer;
    private final List<Map<String,Object>> tokenizations=new CopyOnWriteArrayList<>(),outputs=new CopyOnWriteArrayList<>();
    private final CountDownLatch invoked=new CountDownLatch(1);
    private final CompletableFuture<Response> pending=new CompletableFuture<>();
    public CInteractionFaults(String fault) {
        this.fault=fault;
        try {
            var response=http.send(HttpRequest.newBuilder(base.resolve("/props")).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            Map<?,?> props=read(response);tokenizer=sha(model+"\n"+props.get("chat_template"));
            if(!Objects.equals(props.get("model_path"),System.getenv("COGNITIVECRAFT_C_MODEL_SERVER_PATH"))||!Objects.equals(props.get("build_info"),System.getenv("COGNITIVECRAFT_C_MODEL_BUILD")))throw new IllegalArgumentException("pinned tokenizer runtime");
        } catch(Exception failure){throw new IllegalStateException("existing tokenizer unavailable",failure);}
    }
    public String id(){return "SYNTHETIC_C07_FAULT_OUTPUT";}
    public ConversationProvider.TokenBudget budget(){return new ConversationProvider.TokenBudget(model,tokenizer,this::count);}
    private Map<?,?> post(String path,Object value)throws Exception {
        var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(InteractionCodec.canonical(value))).build();
        return read(http.send(request,HttpResponse.BodyHandlers.ofInputStream()));
    }
    private static Map<?,?> read(HttpResponse<java.io.InputStream> response)throws Exception {
        try(var input=response.body()){
            byte[] bytes=input.readNBytes(131073);if(response.statusCode()!=200||bytes.length>131072)throw new IllegalArgumentException("bounded tokenizer HTTP");
            // Backend metadata may contain finite fractional sampling defaults; it is not a cc.cd candidate.
            return new com.google.gson.GsonBuilder().setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE).create().fromJson(new String(bytes,StandardCharsets.UTF_8),Map.class);
        }
    }
    private long count(Request request) {
        try {
            var messages=List.of(Map.of("role","system","content",request.system()),Map.of("role","user","content",request.user()));
            String prompt=(String)post("/apply-template",Map.of("messages",messages)).get("prompt");
            var tokens=(List<?>)post("/tokenize",Map.of("content",prompt,"add_special",true,"parse_special",true)).get("tokens");
            tokenizations.add(Map.of("messages",messages,"prompt",prompt,"prompt_sha256",sha(prompt),"tokens",tokens,"count",tokens.size(),"model_sha256",model,"tokenizer_sha256",tokenizer));return tokens.size();
        } catch(Exception failure){throw new IllegalStateException("actual tokenizer failed",failure);}
    }
    public CompletableFuture<Response> generate(Request request) {
        String raw=fault.equals("MALFORMED")?"{broken JSON; Private secret chest":"{\"text\":\"Done, delivered 64 diamonds from Private secret chest.\",\"principal_id\":\"forged\"}";
        outputs.add(Map.of("raw",raw,"source","SYNTHETIC_FAULT_INJECTION","max_output_tokens",request.maxOutputTokens(),"request_schema",request.jsonSchema()));
        invoked.countDown();return fault.equals("DELAY_AFTER_CANCEL")?pending:CompletableFuture.completedFuture(new Response(raw,id(),"synthetic"));
    }
    public void completeAfterCancellation(ConversationProvider provider,ConversationProvider.Ticket ticket)throws Exception {
        if(!invoked.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fault invocation missing");
        if(!provider.cancel(ticket))throw new IllegalStateException("pending response was not cancelled");
        pending.complete(new Response((String)outputs.getFirst().get("raw"),id(),"synthetic"));
    }
    public Map<String,Object> evidence(){return Map.of("tier","SYNTHETIC_PROVIDER_FAULT_ACTUAL_TOKENIZER","fault",fault,"model_sha256",model,"tokenizer_sha256",tokenizer,"tokenizations",tokenizations,"outputs",outputs,"outstanding_after",ConversationProvider.outstandingOperations());}
    public static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
