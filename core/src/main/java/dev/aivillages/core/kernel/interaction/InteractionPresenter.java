package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Platform-neutral, inert presentation and typed callback proposals. No dispatcher, IO, timer or effect port. */
public final class InteractionPresenter {
    public static final int MAX_TEXT_BYTES=1024, MAX_CALLBACKS=16;
    public enum Action { START, CHECK_AGAIN, CANCEL, CORRECT, FORGET }
    public record Choice(Action action,String label,boolean confirmationRequired) { }
    public static final class View {
        private final StatusProjection.Request request;
        private final StatusProjection.State status;
        private final String text,accessible;
        private final List<Choice> choices;
        private final boolean disclosed;
        private View(StatusProjection.Request r,StatusProjection.State s,String text,List<Choice> c,boolean disclosed) {
            request=r;status=s;this.text=text;choices=List.copyOf(c);this.disclosed=disclosed;
            accessible=text+(c.isEmpty()?"":" Choices: "+c.stream().map(Choice::label).collect(Collectors.joining(", "))+".");
            if (accessible.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_TEXT_BYTES) throw new IllegalArgumentException("presentation byte bound");
        }
        public UUID messageId() { return request.message(); }
        public DialogueSnapshot.Audience audience() { return request.audience(); }
        public long generation() { return request.generation(); }
        public String text() { return text; }
        public String accessibleText() { return accessible; }
        public List<Choice> choices() { return choices; }
        public StatusProjection.State status() { return status; }
        public Map<String,Object> value() {
            Map<String,Object> m=new LinkedHashMap<>();m.put("schema","cc.c.presentation/1");m.put("message_id",messageId().toString());
            m.put("audience",Map.of("player",audience().player().toString(),"session",audience().session().toString()));
            m.put("scope",Map.of("world",request.key().world().toString(),"principal",request.key().principal().toString(),"citizen",request.key().citizen().toString()));
            m.put("status",status.name());m.put("text",text);m.put("accessible_text",accessible);m.put("locale_key","interaction."+status.name().toLowerCase(java.util.Locale.ROOT));m.put("language","en");
            m.put("question_id",disclosed&&request.question()!=null?request.question().toString():null);m.put("activity",disclosed&&request.activity()!=null?request.activity().value():null);
            m.put("generation",request.generation());m.put("revision",request.revision());m.put("expires_ms",request.expiresMillis());
            m.put("choices",choices.stream().map(c->Map.of("action",c.action().name(),"label",c.label(),"confirmation_required",c.confirmationRequired())).toList());
            return java.util.Collections.unmodifiableMap(m);
        }
        public String encode() { return InteractionCodec.canonical(value()); }
    }
    public View present(StatusProjection projection,String phrasing) {
        Objects.requireNonNull(projection);var r=projection.request();
        String text=switch(projection.state()) {
            case CHAT -> new ResponseValidator().acceptsText(ResponseFacts.smallTalk("en"),phrasing)?phrasing:ResponseTemplates.fallback(ResponseFacts.smallTalk("en"));
            case CLARIFICATION -> "Please clarify the intended target before continuing.";
            case WAITING -> "Waiting for "+projection.materials().stream().map(m->m.missing()+" "+m.displayName()).collect(Collectors.joining(", "))+
                    (projection.storageLabel()==null?" in authorized storage.":" in "+projection.storageLabel()+".");
            case CHECKING -> "I can check again. Your claim does not confirm completion.";
            case DENIED -> "I cannot share details for this activity.";
            case COMPLETED -> "Completed. The activity owner verified the result.";
            case CANCEL_PENDING -> "Cancellation requested. Waiting for the activity owner to confirm.";
            case UNKNOWN -> "Current activity status is unavailable. Request a fresh authorized observation.";
            case EXPIRED -> "This activity reference has expired. Ask its owner to review it.";
        };
        if (projection.disclosed() && r.detailsRequested() && r.activity()!=null) text+=" Root "+r.activity().root()+".";
        List<Choice> choices=switch(projection.state()) {
            case WAITING -> List.of(choice(Action.CHECK_AGAIN),choice(Action.CANCEL),choice(Action.CORRECT),choice(Action.FORGET));
            case CHAT,CLARIFICATION -> List.of(choice(Action.START),choice(Action.FORGET));
            case CHECKING,CANCEL_PENDING,UNKNOWN -> List.of(choice(Action.FORGET));
            default -> List.of();
        };
        return new View(r,projection.state(),text,choices,projection.disclosed());
    }
    private static Choice choice(Action action) { return new Choice(action,switch(action) {case START->"Start";case CHECK_AGAIN->"Check again";case CANCEL->"Cancel";case CORRECT->"Correct";case FORGET->"Forget conversation";},action==Action.START||action==Action.CORRECT); }
    /** Caller-owned ephemeral replay state. It is not an A journal or persistence format. */
    public record State(View view,List<UUID> events,List<String> consumedChoices) {
        public State { Objects.requireNonNull(view);events=List.copyOf(events);consumedChoices=List.copyOf(consumedChoices);if(events.size()>MAX_CALLBACKS||consumedChoices.size()>MAX_CALLBACKS||events.size()!=consumedChoices.size()||events.stream().distinct().count()!=events.size()||consumedChoices.stream().distinct().count()!=consumedChoices.size())throw new IllegalArgumentException("finite callback replay state"); }
        public static State initial(View view) { return new State(view,List.of(),List.of()); }
    }
    public record Callback(UUID event,UUID message,DialogueSnapshot.Audience audience,long generation,Action action) {
        public Callback {Objects.requireNonNull(event);Objects.requireNonNull(message);Objects.requireNonNull(audience);Objects.requireNonNull(action);}
    }
    public record Proposal(Action action,UUID message,UUID question,BlockerProjection.ActivityRef activity,boolean confirmationRequired) { }
    public enum Disposition { PROPOSED, DUPLICATE, STALE, DENIED, EXPIRED, CAPACITY }
    public record Transition(State next,Disposition disposition,Proposal proposal) { }
    @FunctionalInterface public interface CallbackAuthority { boolean current(StatusProjection.Request request); }
    public Transition callback(State state,Callback event,DialogueSnapshot.Audience nativeAudience,long now,CallbackAuthority authority) {
        Objects.requireNonNull(state);Objects.requireNonNull(event);View v=state.view();
        if (!v.audience().equals(nativeAudience) || !nativeAudience.equals(event.audience()) || !v.messageId().equals(event.message()) || v.generation()!=event.generation()) return refused(state,Disposition.STALE);
        boolean current=false;try {current=authority!=null&&authority.current(v.request);}catch(RuntimeException unavailable){ }
        if (!current) return refused(state,Disposition.DENIED);
        if (now<v.request.nowMillis()||now>=v.request.expiresMillis()) return refused(state,Disposition.EXPIRED);
        String key=v.messageId()+":"+v.generation()+":"+event.action();
        if (state.events().contains(event.event())||state.consumedChoices().contains(key)) return refused(state,Disposition.DUPLICATE);
        Choice chosen=v.choices().stream().filter(c->c.action()==event.action()).findFirst().orElse(null);
        if(chosen==null)return refused(state,Disposition.DENIED);
        if(state.events().size()==MAX_CALLBACKS)return refused(state,Disposition.CAPACITY);
        var events=new ArrayList<>(state.events());events.add(event.event());var consumed=new ArrayList<>(state.consumedChoices());consumed.add(key);
        return new Transition(new State(v,events,consumed),Disposition.PROPOSED,new Proposal(event.action(),v.messageId(),v.disclosed?v.request.question():null,v.disclosed?v.request.activity():null,chosen.confirmationRequired()));
    }
    private static Transition refused(State state,Disposition reason) {return new Transition(state,reason,null);}
    /** Rejoin requires a newly authenticated projection, preserves identity, and advances the callback generation. */
    public State rejoin(State previous,View current) {
        View old=previous.view();
        if(!old.request.key().equals(current.request.key())||!old.audience().player().equals(current.audience().player())||!old.messageId().equals(current.messageId())
                ||current.generation()<=old.generation()||!Objects.equals(old.request.activity(),current.request.activity())||!Objects.equals(old.request.question(),current.request.question()))throw new IllegalArgumentException("rejoin cannot change caller/root/question identity");
        return new State(current,previous.events(),previous.consumedChoices());
    }
}
