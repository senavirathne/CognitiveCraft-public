package dev.aivillages.providers;

import com.sun.net.httpserver.HttpServer;
import dev.aivillages.core.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderTest {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"),ZoneOffset.UTC);
    private static final LlmProvider.Request REQUEST=new LlmProvider.Request("Return JSON", "food",PlanCodec.schema(),2048);
    static ProviderConfig config(String id) {var c=new ProviderConfig();c.id=id;c.model="test-model";c.enabled=true;c.minimumIntervalSeconds=0;return c;}
    static final class Mock implements LlmProvider {
        final String id;final AtomicInteger calls=new AtomicInteger();final CompletableFuture<Response> answer;
        Mock(String id,CompletableFuture<Response> answer){this.id=id;this.answer=answer;}
        public String id(){return id;}public CompletableFuture<Response> generate(Request r){calls.incrementAndGet();return answer;}
    }
    static Mock mock(String id,String text){return new Mock(id,CompletableFuture.completedFuture(new LlmProvider.Response(text,id,"test-model")));}
    static ProviderRouter.Entry entry(Mock m){return new ProviderRouter.Entry(config(m.id),m);}
    @Test void malformedPlanFallsThroughToNextProvider() throws Exception {
        var first=mock("first","{\"command\":\"op player\"}");var second=mock("second",Json.GSON.toJson(Plan.food()));
        try(var router=new ProviderRouter(List.of(entry(first),entry(second)),2,Map.of(),CLOCK)) {
            var answer=router.submit(REQUEST,PlanCodec::decode).get(5,TimeUnit.SECONDS);
            assertEquals("second",answer.provider());assertEquals(Plan.food(),answer.value());assertEquals(1,first.calls.get());assertEquals(1,second.calls.get());
        }
    }
    @Test void exhaustedTokenBudgetSendsNoRequest() {
        var m=mock("limited","ok");var c=config(m.id);c.dailyTokenBudget=1;
        try(var router=new ProviderRouter(List.of(new ProviderRouter.Entry(c,m)),1,Map.of(),CLOCK)) {
            assertThrows(ExecutionException.class,()->router.submit(REQUEST,s->s).get(5,TimeUnit.SECONDS));assertEquals(0,m.calls.get());
        }
    }
    @Test void dailyRequestCapSurvivesRouterRestart() throws Exception {
        var m=mock("limited","ok");var c=config(m.id);c.dailyRequests=1;Map<String,WorldData.Usage> saved;
        try(var router=new ProviderRouter(List.of(new ProviderRouter.Entry(c,m)),1,Map.of(),CLOCK)) {
            router.submit(REQUEST,s->s).get(5,TimeUnit.SECONDS);saved=router.usageSnapshot();
        }
        try(var router=new ProviderRouter(List.of(new ProviderRouter.Entry(c,m)),1,saved,CLOCK)) {
            assertThrows(ExecutionException.class,()->router.submit(REQUEST,s->s).get(5,TimeUnit.SECONDS));assertEquals(1,m.calls.get());
        }
        try(var router=new ProviderRouter(List.of(new ProviderRouter.Entry(c,m)),1,saved,Clock.offset(CLOCK,Duration.ofDays(1)))) {
            router.submit(REQUEST,s->s).get(5,TimeUnit.SECONDS);assertEquals(2,m.calls.get());
        }
    }
    @Test void rateLimitCircuitPreventsRetryStorm() {
        var m=new Mock("rate_limited",CompletableFuture.failedFuture(new ProviderFailure("HTTP 429",60)));
        try(var router=new ProviderRouter(List.of(entry(m)),1,Map.of(),CLOCK)) {
            for(int i=0;i<5;i++)assertThrows(ExecutionException.class,()->router.submit(REQUEST,s->s).get(5,TimeUnit.SECONDS));
            assertEquals(1,m.calls.get());assertTrue(router.status().getFirst().contains("HTTP 429"));
        }
    }
    @Test void cancellationReachesTransport() throws Exception {
        var m=new Mock("pending",new CompletableFuture<>());
        try(var router=new ProviderRouter(List.of(entry(m)),1,Map.of(),CLOCK)) {
            var future=router.submit(REQUEST,s->s);awaitCalls(m,1);future.cancel(true);
            assertThrows(CancellationException.class,()->m.answer.get(5,TimeUnit.SECONDS));
        }
    }
    @Test void atMostOneConcurrentCallPerProvider() throws Exception {
        var m=new Mock("pending",new CompletableFuture<>());
        try(var router=new ProviderRouter(List.of(entry(m)),2,Map.of(),CLOCK)) {
            var first=router.submit(REQUEST,s->s);awaitCalls(m,1);
            assertThrows(ExecutionException.class,()->router.submit(REQUEST,s->s).get(5,TimeUnit.SECONDS));assertEquals(1,m.calls.get());
            first.cancel(true);
        }
    }
    @Test void shutdownCancelsOutstandingRequests() throws Exception {
        var m=new Mock("pending",new CompletableFuture<>());var router=new ProviderRouter(List.of(entry(m)),1,Map.of(),CLOCK);
        var future=router.submit(REQUEST,s->s);awaitCalls(m,1);router.close();assertTrue(future.isCancelled());
        assertThrows(ExecutionException.class,()->router.submit(REQUEST,s->s).get(5,TimeUnit.SECONDS));
    }
    @Test void allHttpDialectsSendCorrectAuthAndParseResponse() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var observed=new AtomicReference<com.google.gson.JsonObject>();var auth=new AtomicReference<String>();var path=new AtomicReference<String>();var dialect=new AtomicReference<String>();
        server.createContext("/",exchange->{
            observed.set(Json.object(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),30000));
            auth.set(exchange.getRequestHeaders().getFirst(dialect.get().equals("gemini")?"x-goog-api-key":"Authorization"));path.set(exchange.getRequestURI().getPath());
            String response=switch(dialect.get()) {
                case "gemini"->"{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hidden\"},{\"text\":\"ok\"}]}}]}";
                case "ollama"->"{\"done\":true,\"done_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}";
                default->"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}]}";
            };byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try(var client=HttpClient.newBuilder().proxy(new NoProxy()).build()) {
            for(String type:List.of("gemini","groq","cloudflare","ollama","openai-compatible")) {
                dialect.set(type);var c=config(type);c.type=type;c.endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/api";
                var response=new HttpLlmProvider(c,client,"test-secret").generate(REQUEST).get(5,TimeUnit.SECONDS);
                assertEquals("ok",response.text());assertEquals(type,response.provider());
                assertEquals(type.equals("gemini")?"test-secret":"Bearer test-secret",auth.get());
                if(type.equals("gemini")){assertEquals("/api/models/test-model:generateContent",path.get());assertTrue(observed.get().getAsJsonObject("generationConfig").has("responseJsonSchema"));}
                else if(type.equals("ollama")){assertFalse(observed.get().get("stream").getAsBoolean());assertTrue(observed.get().has("format"));}
                else assertEquals("json_object",observed.get().getAsJsonObject("response_format").get("type").getAsString());
            }
        } finally {server.stop(0);}
    }
    @Test void httpErrorsDoNotExposeResponseSecretsAndHonorCooldown() throws Exception {
        var failure=httpFailure(401,"private-secret",null);assertEquals("HTTP 401",failure.getMessage());assertEquals(3600,((ProviderFailure)failure).cooldownSeconds());
        var rate=httpFailure(429,"private-secret","999999");assertEquals("HTTP 429",rate.getMessage());assertEquals(86400,((ProviderFailure)rate).cooldownSeconds());
    }
    @Test void oversizedAndTruncatedResponsesAreRejected() throws Exception {
        assertNotNull(httpFailure(200,"x".repeat(131073),null));
        assertEquals("Invalid provider response",httpFailure(200,"{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}",null).getMessage());
    }
    @Test void rejectsRemotePlaintextAndCredentialsInUrls() {
        for(String endpoint:List.of("http://example.com/api","https://user:pass@example.com/api","https://example.com/api?key=secret","file:///secret")) {
            var c=config("invalid");c.endpoint=endpoint;assertThrows(IllegalArgumentException.class,c::validate);
        }
        var c=config("local");c.validate();
    }
    @Test void retryAfterIsBounded() {
        assertEquals(1,HttpLlmProvider.retryAfter("-5",30));assertEquals(86400,HttpLlmProvider.retryAfter("999999",30));assertEquals(30,HttpLlmProvider.retryAfter("bad",30));
        String date=ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(120).format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
        long delay=HttpLlmProvider.retryAfter(date,30);assertTrue(delay>=118 && delay<=120);
    }
    private static Throwable httpFailure(int status,String body,String retry) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{
            if(retry!=null)e.getResponseHeaders().add("Retry-After",retry);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();
        try(var client=HttpClient.newBuilder().proxy(new NoProxy()).build()) {
            var c=config("local");c.type="groq";c.endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/";
            return assertThrows(ExecutionException.class,()->new HttpLlmProvider(c,client,"test-secret").generate(REQUEST).get(5,TimeUnit.SECONDS)).getCause();
        } finally {server.stop(0);}
    }
    private static void awaitCalls(Mock m,int n) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(m.calls.get()<n && System.nanoTime()<end)Thread.sleep(5);assertEquals(n,m.calls.get());
    }
    static class NoProxy extends ProxySelector {
        public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI uri,java.net.SocketAddress a,java.io.IOException e){}
    }
}
