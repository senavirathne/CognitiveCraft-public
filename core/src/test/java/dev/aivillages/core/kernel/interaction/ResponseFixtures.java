package dev.aivillages.core.kernel.interaction;

final class ResponseFixtures {
    static ResponseFacts shortage() { return ResponseFacts.forBlocker(new AssistanceFixtures().assess().blocker(), "en"); }
    static ResponseFacts blocker(BlockerProjection.Code code) {
        if (code == BlockerProjection.Code.INSUFFICIENT_MATERIAL) return shortage();
        return ResponseFacts.forBlocker(BlockerProjection.failure(new AssistanceFixtures().evidence(), code, "A_RUNTIME"), "en");
    }
}
