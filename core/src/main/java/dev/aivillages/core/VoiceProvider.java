package dev.aivillages.core;
import java.util.concurrent.CompletableFuture;
/** Phase-two extension boundary. The MVP does not capture or generate audio. */
public final class VoiceProvider {
    private VoiceProvider() { }
    public record Audio(byte[] data, String mimeType) { }
    public interface SpeechToText { CompletableFuture<String> transcribe(Audio audio); }
    public interface TextToSpeech { CompletableFuture<Audio> synthesize(String text, String voiceId); }
}
