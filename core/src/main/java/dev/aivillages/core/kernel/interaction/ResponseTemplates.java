package dev.aivillages.core.kernel.interaction;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Finite English phrasings. Every factual noun/count/status is fixed by the redacted packet or static policy text. */
public final class ResponseTemplates {
    public static final int MAX_OUTPUT_BYTES = 256;
    private ResponseTemplates() { }
    public static List<String> choices(ResponseFacts facts) {
        Objects.requireNonNull(facts);
        if (facts.mode() == ResponseFacts.Mode.SMALL_TALK)
            return List.of("Hello.", "Hi there. How can I help?", "We can chat or discuss a supported task.");
        String text = switch (facts.blocker()) {
            case INSUFFICIENT_MATERIAL -> {
                String items = facts.materials().stream().map(m -> m.missing() + " " + m.registryId()).collect(Collectors.joining(", "));
                String exact = "This task needs " + items + ". Add these materials to authorized staging storage, then ask to check again.";
                yield exact.length() <= 230 ? exact : "This task is missing required materials. Review the authorized material list, add them to staging storage, then ask to check again.";
            }
            case NO_SUPPORTED_PRODUCTION_PATH -> "There is no supported production path for this task. Choose a supported task or review the available methods.";
            case UNSUPPORTED_EFFECT -> "This task needs an unsupported action. Choose a supported action.";
            case UNKNOWN_WORLD_STATE -> "There is not enough current information about this task. Ask for a fresh observation.";
            case UNAUTHORIZED -> "I cannot share details for this task. Ask its owner to review access.";
            case DESTINATION_FULL -> "The destination cannot accept this task's items. Free capacity in an authorized destination, then ask to check again.";
            case TARGET_UNAVAILABLE -> "The target is unavailable for this task. Select an available authorized target, then ask to check again.";
            case AMBIGUOUS_TARGET -> "The target is ambiguous. Select the intended authorized target.";
            case RESOURCE_RESERVED -> "The required resource is reserved. Wait for its reservation to end or select another authorized resource.";
            case ACTOR_UNAVAILABLE -> "The citizen is unavailable for this task. Select an available citizen, then ask to check again.";
            case BUDGET_OR_DEADLINE_EXCEEDED -> "This task has reached a budget or deadline limit. Review the task with its owner.";
            case STALE_REFERENCE -> "This task reference is out of date. Select the current target, then ask to check again.";
            case INCOMPATIBLE_SCHEMA -> "This request uses an incompatible format. Use the supported request format.";
            case SAME_ENDPOINT -> "The source and destination are the same. Select a different authorized destination.";
            case DEPENDENCY_NOT_QUALIFIED -> "This task depends on an unqualified component. Wait for component qualification, then ask to check again.";
        };
        return List.of(text, "Hello. " + text);
    }
    public static String fallback(ResponseFacts facts) { return choices(facts).getFirst(); }
}
