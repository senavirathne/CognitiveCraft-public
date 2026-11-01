package dev.aivillages.core.kernel.interaction;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static dev.aivillages.core.kernel.interaction.CandidateValidator.*;

/** Inert interpretation policy. A review is a read-only ingress observation, never effect authority. */
public final class InteractionSafety {
    public enum Kind { CHAT, REDACTED, FORGET, CLARIFY, ASK, CHECK_AGAIN, CANCEL, HELP,
        CORRECT_GOAL, CHOOSE_TARGET, REVALIDATE_ONLY, UNSUPPORTED_EFFECT, SELECTOR, COMMAND, REJECT, LABEL_DATA, REFUSED_EXPECTED }
    public record Review(Transport transport, String addressedLabel, boolean disclosed,
                         boolean replyCurrent, boolean cancelled, boolean unsupported,
                         long tick, ReferenceClarification.Resolver references) {
        public Review { Objects.requireNonNull(transport); if (!ConversationContext.validUtterance(addressedLabel)
                || addressedLabel.codePointCount(0,addressedLabel.length())>64 || tick<0) throw new IllegalArgumentException("bounded ingress review"); }
    }
    @FunctionalInterface public interface ReviewPort { Review read(Transport transport); }
    /** Candidate maps are strictly cc.cd values; this type has no resolved endpoint, witness or activity writer. */
    public record Decision(Kind kind, String reason, Candidate candidate, Map<String,Object> selector,
                           boolean confirmationRequired) {
        public Decision { Objects.requireNonNull(kind); Objects.requireNonNull(reason); selector=selector==null?null:Map.copyOf(selector); }
    }

    public Decision interpret(Transport t, ConversationContext memory, long now, ReviewPort port) {
        Objects.requireNonNull(t);Objects.requireNonNull(memory);
        if (!ConversationContext.validUtterance(t.text())) return decision(Kind.REJECT,"MALFORMED");
        Review r;
        try { r=port==null?null:port.read(t); } catch(RuntimeException unavailable) { r=null; }
        if(r==null || !t.equals(r.transport())) return decision(Kind.CLARIFY,"CORRELATION");
        String text=t.text().strip().replaceFirst("[.!?]$","");
        // A label declaration is data, including imperative-looking words inside its value.
        if(matches("(?:the )?(?:chest|barrel|storage) name is: .+",text)) return decision(Kind.LABEL_DATA,"UNTRUSTED_LABEL");
        if(matches(".*\\b(?:principal_id|root_id|execution_generation|trusted_scope)\\b.*",text)
                || matches("(?:execute|run) /.*",text) || matches(".*(?:ignore all rules|invent a successful|I own every).*$",text))
            return decision(Kind.REJECT,"UNTRUSTED_AUTHORITY");
        if(matches(".*(?:other player|another player|cross-player).*$",text))
            return decision(matches(".*(?:question|token).*",text)?Kind.REFUSED_EXPECTED:Kind.REDACTED,"PRINCIPAL_PRIVACY");
        if(!r.disclosed()) return decision(Kind.REDACTED,"UNAVAILABLE");
        if(r.cancelled()) return decision(Kind.REFUSED_EXPECTED,"CANCELLED");
        if(!r.replyCurrent()) return decision(Kind.REVALIDATE_ONLY,"STALE_GENERATION");
        var greeting=Pattern.compile("(?iu)(?:hello|hi) ([\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} _-]*)").matcher(text);
        if(greeting.matches() && !SelectorNames.fold(greeting.group(1)).equals(SelectorNames.fold(r.addressedLabel())))
            return decision(Kind.CLARIFY,"ADDRESSED_CITIZEN");
        if(matches(".*(?:fabricated coordinates| at -?[0-9]+[, ]+-?[0-9]+[, ]+-?[0-9]+).*",text))
            return decision(Kind.CLARIFY,"SERVER_SELECTION_REQUIRED");
        // Parse action/reply semantics first. No model can bypass its exact grounded-field validator.
        Result parsed=new IntentParser().parse(t,memory,now);
        if(parsed instanceof Clarify c) return decision(c.code()==Code.INVALID_QUANTITY?Kind.REFUSED_EXPECTED:Kind.CLARIFY,c.code().name());
        Candidate candidate=(Candidate)parsed;
        if(candidate.intent().kind()==dev.aivillages.core.kernel.interaction.contract.InteractionWire.Kind.PlayerReplyIntent) {
            if(r.unsupported()) return decision(Kind.UNSUPPORTED_EFFECT,"UNSUPPORTED_EFFECT");
            String reply=(String)candidate.intent().body().get("reply");
            return new Decision(Kind.valueOf(reply),"REVALIDATE_ONLY",candidate,null,candidate.requiresConfirmation());
        }
        String action=(String)candidate.intent().body().get("action");
        if(action.equals("FORGET")) return new Decision(Kind.FORGET,"SESSION_ONLY",candidate,null,false);
        if(!action.equals("CHAT")) return new Decision(Kind.COMMAND,"CANDIDATE_ONLY",candidate,null,
                candidate.requiresConfirmation() || action.equals("ASSIGN_STORAGE"));
        if(r.unsupported()) return decision(Kind.UNSUPPORTED_EFFECT,"UNSUPPORTED_EFFECT");
        if(matches("what do you need for .+",text)) return decision(Kind.ASK,"AUTHORIZED_FACTS_ONLY");
        if(matches("why can.t you perform .+",text)) return decision(Kind.HELP,"REVALIDATE_ONLY");
        // Greetings remain chat. Bare proper labels are references, never implicit authorizations.
        if(!matches("(?:hello|hi)(?: .*)?",text)) {
            var selector=new SelectorParser().parse(text,t.selection(),r.addressedLabel());
            if(selector instanceof SelectorParser.Parsed p) {
                if(r.references()!=null) {
                    var q=new ReferenceClarification.Query(t.key(),t.messageId(),ReferenceClarification.Operation.INSPECT,r.tick());
                    var result=new ReferenceClarification().check(p,q,r.references());
                    if(result instanceof ReferenceClarification.Clarify c) return decision(Kind.CLARIFY,c.reason().name());
                }
                return new Decision(Kind.SELECTOR,"CANDIDATE_ONLY",null,p.endpoint(),false);
            }
            if(matches("(?:the |this |selected |nearest |.*[’']s ).*(?:chest|barrel|warehouse|storage).*",text))
                return decision(Kind.CLARIFY,"MISSING_TARGET");
        }
        return new Decision(Kind.CHAT,"BOUNDED_PHRASING",candidate,null,false);
    }
    private static Decision decision(Kind kind,String reason) { return new Decision(kind,reason,null,null,false); }
    private static boolean matches(String pattern,String text) { return Pattern.compile(pattern,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(text).matches(); }
}
