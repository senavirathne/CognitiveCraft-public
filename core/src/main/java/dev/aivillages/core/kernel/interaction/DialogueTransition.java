package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionWire;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Proposals only. No effect, settlement, owner, inventory or budget writer exists at this boundary. */
public record DialogueTransition(DialogueSnapshot snapshot, List<Proposal> proposals, String reason) {
    public DialogueTransition { proposals = List.copyOf(proposals); }
    public sealed interface Proposal permits PreparePrompt, DeliverPrompt, Reobserve, OwnerDecision { }
    public record PreparePrompt(UUID message, UUID question, long dialogueRevision, long responseGeneration, boolean correctionConfirmation) implements Proposal { }
    /** Delivery still requires C03's validated phrasing and J's authenticated native transport. */
    public record DeliverPrompt(UUID message, UUID question, long responseGeneration) implements Proposal { }
    public record Reobserve(UUID request, InteractionWire.UntrustedMessage signal) implements Proposal { }
    public record OwnerDecision(UUID request, DialogueSnapshot.OwnerAction action, BlockerProjection.ActivityRef activity,
                                UUID question, long dialogueRevision, Map<String,Object> untrustedCandidate) implements Proposal {
        public OwnerDecision { untrustedCandidate = Map.copyOf(untrustedCandidate); }
    }
}
