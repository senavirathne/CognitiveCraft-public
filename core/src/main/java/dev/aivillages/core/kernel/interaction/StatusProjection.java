package dev.aivillages.core.kernel.interaction;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static dev.aivillages.core.kernel.interaction.BlockerProjection.ActivityRef;

/** Read-only, disclosure-checked status. J03 supplies actual A evidence; JSON cannot supply this port. */
public final class StatusProjection {
    public enum State { CHAT, CLARIFICATION, WAITING, CHECKING, DENIED, COMPLETED, CANCEL_PENDING, UNKNOWN, EXPIRED }
    public record Request(ConversationContext.SessionKey key, DialogueSnapshot.Audience audience, UUID message,
                          UUID question, ActivityRef activity, State requested, long revision, long generation,
                          long nowMillis, long expiresMillis, boolean detailsRequested) {
        public Request {
            Objects.requireNonNull(key); Objects.requireNonNull(audience); Objects.requireNonNull(message); Objects.requireNonNull(requested);
            if (revision < 1 || revision > BlockerProjection.MAX_INTEGER || generation < 1 || generation > BlockerProjection.MAX_INTEGER || nowMillis < 0 || expiresMillis <= nowMillis
                    || expiresMillis - nowMillis > 600_000 || expiresMillis > BlockerProjection.MAX_INTEGER)
                throw new IllegalArgumentException("finite presentation correlation/clock");
        }
    }
    public record Material(String displayName, long missing) {
        public Material { if (missing < 1 || missing > 64 || !safe(displayName,64)) throw new IllegalArgumentException("bounded authorized material"); }
    }
    /** Opaque A receipt reference, not a decoded assertion that completion occurred. */
    public record TerminalReference(ActivityRef activity, UUID receipt) {
        public TerminalReference { Objects.requireNonNull(activity); Objects.requireNonNull(receipt); }
    }
    public record Evidence(Request request, boolean disclosureAllowed, long observedMillis,
                           List<Material> materials, String storageLabel, TerminalReference terminal) {
        public Evidence {
            Objects.requireNonNull(request); materials=List.copyOf(materials);
            if (observedMillis<0 || observedMillis>BlockerProjection.MAX_INTEGER || materials.size()>16 || materials.stream().mapToLong(Material::missing).sum()>64) throw new IllegalArgumentException("material bound");
        }
    }
    public interface Authority {
        Evidence read(Request request);
        /** Default refusal. Only the qualified J03 adapter can authenticate actual A terminal evidence. */
        default boolean terminalAuthenticated(Request request, TerminalReference reference) { return false; }
    }
    private final Request request;
    private final State state;
    private final List<Material> materials;
    private final String storage;
    private final boolean disclosed;
    private StatusProjection(Request r,State s,List<Material> m,String label,boolean disclosed) {
        request=r;state=s;materials=List.copyOf(m);storage=label;this.disclosed=disclosed;
    }
    public static StatusProjection project(Request r,Authority authority) {
        Objects.requireNonNull(r);
        if (!r.key().principal().equals(r.audience().player()) || r.activity()!=null &&
                (!r.activity().principal().equals(r.key().principal()) || !r.activity().world().equals(r.key().world()) || !r.activity().citizen().equals(r.key().citizen()))) return denied(r);
        Evidence e;
        try { e=authority==null?null:authority.read(r); } catch(RuntimeException unavailable) { return denied(r); }
        if (e==null || !r.equals(e.request()) || !e.disclosureAllowed()) return denied(r);
        if (e.observedMillis()>r.nowMillis() || r.nowMillis()-e.observedMillis()>2000) return new StatusProjection(r,State.UNKNOWN,List.of(),null,false);
        if (r.requested()==State.DENIED) return denied(r);
        if (r.requested()==State.COMPLETED) {
            boolean terminal=false;
            try { terminal=r.activity()!=null && e.terminal()!=null && r.activity().equals(e.terminal().activity()) && authority.terminalAuthenticated(r,e.terminal()); }
            catch(RuntimeException unavailable) { /* A missing or failed verifier never confirms completion. */ }
            return new StatusProjection(r,terminal?State.COMPLETED:State.CHECKING,List.of(),null,true);
        }
        if (r.activity()!=null && r.nowMillis()>=r.activity().absoluteDeadlineMillis()) return new StatusProjection(r,State.EXPIRED,List.of(),null,true);
        if (r.requested()==State.WAITING && (r.activity()==null || r.question()==null || e.materials().isEmpty())) return new StatusProjection(r,State.UNKNOWN,List.of(),null,false);
        return new StatusProjection(r,r.requested(),r.requested()==State.WAITING?e.materials():List.of(),
                r.requested()==State.WAITING && safe(e.storageLabel(),64)?e.storageLabel():null,true);
    }
    private static StatusProjection denied(Request r) { return new StatusProjection(r,State.DENIED,List.of(),null,false); }
    static boolean safe(String text,int maximum) {
        return text!=null && ConversationContext.validUtterance(text) && text.codePointCount(0,text.length())<=maximum
                && !java.util.regex.Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(text).find();
    }
    public Request request() { return request; }
    public State state() { return state; }
    public List<Material> materials() { return materials; }
    public String storageLabel() { return storage; }
    public boolean disclosed() { return disclosed; }
}
