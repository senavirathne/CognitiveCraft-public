package dev.aivillages.providers;
/** Sanitized failure. Never exposes provider response bodies or credentials. */
public final class ProviderFailure extends RuntimeException {
    private final long cooldownSeconds;
    public ProviderFailure(String message, long cooldownSeconds) { super(message); this.cooldownSeconds = Math.clamp(cooldownSeconds, 1, 86400); }
    public long cooldownSeconds() { return cooldownSeconds; }
}
