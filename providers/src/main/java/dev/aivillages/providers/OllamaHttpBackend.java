package dev.aivillages.providers;

import dev.aivillages.core.kernel.StrictJson;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** HTTP implementation of the local backend seam; no model download, fallback or world I/O. */
public final class OllamaHttpBackend implements LocalGenerationAdapter.Backend {
    private static final int MAX_TAGS_BYTES = 32_768;
    private final URI endpoint;
    private final String model;
    private final HttpClient client;

    public OllamaHttpBackend(URI endpoint, String model) {
        this(endpoint, model, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address,
                            java.io.IOException error) { }
                })
                .connectTimeout(Duration.ofSeconds(2)).build());
    }
    public OllamaHttpBackend(URI endpoint, String model, HttpClient client) {
        this.endpoint = endpoint;
        this.model = model;
        this.client = client;
    }

    @Override public LocalGenerationAdapter.Transfer start(byte[] request, long remainingMillis,
            int maxWireBytes, LocalGenerationAdapter.Sink sink) {
        URI tags = endpoint.resolve("/api/tags");
        HttpRequest listing = HttpRequest.newBuilder(tags).timeout(Duration.ofMillis(remainingMillis))
                .header("Accept-Encoding", "identity").GET().build();
        HttpRequest http = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(remainingMillis))
                .header("Content-Type", "application/json")
                .header("Accept-Encoding", "identity")
                .POST(HttpRequest.BodyPublishers.ofByteArray(request)).build();
        Object lock = new Object();
        @SuppressWarnings("unchecked") CompletableFuture<HttpResponse<Void>>[] chat = new CompletableFuture[1];
        boolean[] cancelled = { false };
        CompletableFuture<HttpResponse<byte[]>> preflight = client.sendAsync(listing,
                info -> new TagsBody(MAX_TAGS_BYTES));
        preflight.whenComplete((response, error) -> {
            synchronized (lock) {
                if (cancelled[0]) return;
                if (error != null) { sink.complete(503); return; }
                if (response.statusCode() != 200) { sink.complete(response.statusCode()); return; }
                try {
                    String json = StandardCharsets.UTF_8.newDecoder().decode(
                            ByteBuffer.wrap(response.body())).toString();
                    Map<String, Object> parsed = StrictJson.object(json);
                    if (!(parsed.get("models") instanceof List<?> models)) throw new IllegalArgumentException();
                    String digest = null;
                    boolean found = false;
                    for (Object entry : models) {
                        if (!(entry instanceof Map<?, ?> value)) throw new IllegalArgumentException();
                        if (model.equals(value.get("name"))) {
                            found = true;
                            if (value.get("digest") instanceof String sha) digest = sha;
                            break;
                        }
                    }
                    if (!found) { sink.complete(404); return; }
                    sink.modelDigest(digest);
                    // This is the only point where genuine inference demand can load the model.
                    chat[0] = client.sendAsync(http,
                            info -> new LimitedBody(maxWireBytes, info.statusCode(), sink));
                    chat[0].whenComplete((reply, failure) -> { if (failure != null) sink.error(); });
                } catch (Exception malformed) { sink.complete(503); }
            }
        });
        return () -> {
            synchronized (lock) {
                cancelled[0] = true;
                if (chat[0] != null) {
                    // Transport interruption does not establish backend compute cessation.
                    chat[0].cancel(true);
                    return false;
                }
                preflight.cancel(true);
                sink.stopped();
                return true;
            }
        };
    }

    private static final class TagsBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int max;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean terminal;
        TagsBody(int max) { this.max = max; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription; subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if (terminal) return;
            for (ByteBuffer chunk : chunks) {
                int size = chunk.remaining();
                if (size > max - bytes.size()) {
                    terminal = true; subscription.cancel();
                    body.completeExceptionally(new IllegalStateException("Model list too large")); return;
                }
                byte[] part = new byte[size]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) {
            if (terminal) return;
            terminal = true; body.completeExceptionally(error);
        }
        @Override public void onComplete() {
            if (terminal) return;
            terminal = true; body.complete(bytes.toByteArray());
        }
    }

    /** Streams at most max bytes; checks each chunk before allocating/copying it. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<Void> {
        private final int max;
        private final int status;
        private final LocalGenerationAdapter.Sink sink;
        private final CompletableFuture<Void> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int count;
        private boolean terminal;
        LimitedBody(int max, int status, LocalGenerationAdapter.Sink sink) {
            this.max = max; this.status = status; this.sink = sink;
        }
        @Override public CompletionStage<Void> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if (terminal) return;
            for (ByteBuffer buffer : chunks) {
                int size = buffer.remaining();
                if (size > max - count) {
                    terminal = true;
                    sink.chunk(new byte[max - count + 1]);
                    subscription.cancel();
                    body.completeExceptionally(new IllegalStateException("Response limit"));
                    return;
                }
                byte[] bytes = new byte[size]; buffer.get(bytes);
                count += size;
                sink.chunk(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) {
            if (terminal) return;
            terminal = true; sink.error(); body.completeExceptionally(error);
        }
        @Override public void onComplete() {
            if (terminal) return;
            terminal = true; sink.complete(status); body.complete(null);
        }
    }
}
