package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import java.util.Map;
import java.util.UUID;

import static dev.aivillages.core.kernel.interaction.CandidateValidator.*;

/** Synthetic subprocess seam for the Python negative oracle; excluded from the release JAR. */
public final class IntentParserProbe {
    private IntentParserProbe() { }
    public static void main(String[] args) {
        var key = new ConversationContext.SessionKey(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3));
        var transport = new Transport(key, new UUID(0, 4), 1, args[1], new Selection(new UUID(0, 5), "CHEST"), new Question(new UUID(0, 6), 7));
        Result result;
        if (args[0].equals("parse")) result = new IntentParser().parse(transport, new ConversationContext(), 0);
        else if (args[0].equals("validate")) {
            var extracted = IntentParser.extract(transport);
            if (extracted.problem() != null) throw new IllegalArgumentException("supported source text required");
            result = new CandidateValidator().validate(new Draft(key, transport.messageId(), args[2], extracted.draft().spans()), transport);
        } else throw new IllegalArgumentException("mode");
        if (result instanceof Candidate candidate)
            System.out.print(InteractionCodec.canonical(Map.of("result", "CANDIDATE", "kind", candidate.intent().kind().name(),
                    "body", candidate.intent().body(), "routing", candidate.routing().name(), "tier", "SYNTHETIC_INTENT")));
        else System.out.print(InteractionCodec.canonical(Map.of("result", "CLARIFY", "code", ((Clarify) result).code().name(), "tier", "SYNTHETIC_INTENT")));
    }
}
