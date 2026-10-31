package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.core.kernel.interaction.contract.InteractionWire;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import static dev.aivillages.core.kernel.interaction.BlockerProjection.*;

/** Inert conversation DTO. Restore always requires A's native root/question/journal revalidation. */
public final class DialogueSnapshot {
    public static final String VERSION = "cc.c.dialogue.snapshot/1";
    public static final int MAX_BYTES = 65536, MAX_ROUNDS = 8, MAX_DELIVERED = 16;
    public enum Phase { IDLE, ASKED, AWAITING_REPLY, REOBSERVE_REQUESTED, WAITING_OWNER_DECISION, CLARIFICATION, EXPIRED, CANCELLED, CLOSED }
    public enum OwnerAction { NONE, REVALIDATE, CANCEL, CHOOSE_TARGET, AMEND }
    public record Audience(UUID player, UUID session) {
        public Audience { Objects.requireNonNull(player); Objects.requireNonNull(session); }
    }
    final Builder data;
    private DialogueSnapshot(Builder source) {
        data = new Builder(source);
        Objects.requireNonNull(data.audience); Objects.requireNonNull(data.phase); Objects.requireNonNull(data.ownerAction);
        if (data.question == null || data.question.kind() != InteractionWire.Kind.AssistanceRequest) throw new IllegalArgumentException("assistance question required");
        bounded(data.generation, 0); bounded(data.nextCheck, 0); bounded(data.clock, 0); bounded(data.worldRevision, 0);
        if (data.rounds < 0 || data.rounds > MAX_ROUNDS || data.identical < 0 || data.identical > 3
                || data.consumed.size() != data.rounds || data.delivered.size() > MAX_DELIVERED
                || Set.copyOf(data.consumed).size() != data.consumed.size() || Set.copyOf(data.delivered).size() != data.delivered.size()
                || data.phase == Phase.IDLE != (data.generation == 0) || data.nextCheck > expiresMillis()
                || data.deliveryProposed && data.prompt == null || data.prompt != null && data.delivered.contains(data.prompt)
                || (data.phase == Phase.REOBSERVE_REQUESTED) != (data.observation != null)
                || (data.phase == Phase.WAITING_OWNER_DECISION) != (data.ownerAction != OwnerAction.NONE && data.ownerRequest != null)
                || data.ownerAction == OwnerAction.NONE != (data.ownerRequest == null)
                || (data.correction == null) != (data.confirmation == null)
                || data.phase == Phase.CLARIFICATION != (data.correction != null)
                || data.correction != null && (data.correction.isBlank() || data.correction.codePointCount(0, data.correction.length()) > 1024)
                || data.waitingChange && data.identical != 3) throw new IllegalArgumentException("bounded consistent dialogue snapshot");
        data.consumed = List.copyOf(data.consumed); data.delivered = List.copyOf(data.delivered);
    }
    public static DialogueSnapshot initial(InteractionWire.UntrustedMessage question, Audience audience) {
        Builder b = new Builder(); b.question = Objects.requireNonNull(question); b.audience = audience;
        b.worldRevision = (Long) object(question.body().get("blocker")).get("world_revision");
        return new DialogueSnapshot(b);
    }
    public Phase phase() { return data.phase; }
    public Audience audience() { return data.audience; }
    public InteractionWire.UntrustedMessage question() { return data.question; }
    public UUID questionId() { return UUID.fromString((String) data.question.body().get("question_id")); }
    public long dialogueRevision() { return (Long) data.question.body().get("dialogue_revision"); }
    public long responseGeneration() { return data.generation; }
    public int replyRounds() { return data.rounds; }
    public int identicalChecks() { return data.identical; }
    public long nextCheckMillis() { return data.nextCheck; }
    public long clockMillis() { return data.clock; }
    public long observedWorldRevision() { return data.worldRevision; }
    public boolean waitingRelevantChange() { return data.waitingChange; }
    public boolean needsOwnerRevalidation() { return data.revalidate; }
    public List<UUID> deliveredMessageIds() { return data.delivered; }
    public List<UUID> consumedReplyIds() { return data.consumed; }
    public UUID pendingPrompt() { return data.prompt; }
    public boolean deliveryProposed() { return data.deliveryProposed; }
    public UUID pendingObservation() { return data.observation; }
    public UUID pendingOwnerRequest() { return data.ownerRequest; }
    public OwnerAction pendingOwnerAction() { return data.ownerAction; }
    public UUID confirmationId() { return data.confirmation; }
    public long expiresMillis() { return (Long) data.question.body().get("expires_ms"); }
    public ActivityRef activity() {
        Map<String,Object> a = object(data.question.body().get("activity")), s = object(a.get("scope"));
        return new ActivityRef(uuid(s,"world_id"), uuid(s,"domain_id"), uuid(s,"principal_id"), uuid(a,"root_id"), uuid(a,"original_owner_id"),
                uuid(a,"attempt_id"), uuid(a,"responsible_citizen_id"), (Long)a.get("execution_generation"), (Long)a.get("root_revision"),
                (String)a.get("original_budget_digest"), (Long)a.get("absolute_deadline_ms"));
    }
    public boolean terminal() { return Set.of(Phase.EXPIRED, Phase.CANCELLED, Phase.CLOSED).contains(phase()); }
    public String encode() {
        String json = InteractionCodec.canonical(value());
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("snapshot byte bound");
        return json;
    }
    /** Digest excludes the mandatory local restore lock; it binds all original replay/correlation state. */
    public String digest() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static DialogueSnapshot restore(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("snapshot byte bound");
        Map<String,Object> m = object(InteractionCodec.parseJson(json));
        Set<String> fields = Set.of("schema","question","audience","phase","response_generation","reply_rounds","identical_checks","next_check_ms","clock_ms",
                "world_revision","delivered","consumed","prompt","delivery_proposed","observation","owner_request","owner_action","correction","confirmation","waiting_change");
        if (!m.keySet().equals(fields) || !VERSION.equals(m.get("schema"))) throw new IllegalArgumentException("snapshot schema/fields");
        Builder b = new Builder(); b.question = new InteractionCodec().decode(InteractionCodec.canonical(m.get("question")));
        Map<String,Object> audience = object(m.get("audience"));
        if (!audience.keySet().equals(Set.of("player","session"))) throw new IllegalArgumentException("audience fields");
        b.audience = new Audience(uuid(audience,"player"), uuid(audience,"session")); b.phase = Phase.valueOf((String)m.get("phase"));
        b.generation = (Long)m.get("response_generation"); b.rounds = Math.toIntExact((Long)m.get("reply_rounds")); b.identical = Math.toIntExact((Long)m.get("identical_checks"));
        b.nextCheck = (Long)m.get("next_check_ms"); b.clock = (Long)m.get("clock_ms"); b.worldRevision = (Long)m.get("world_revision");
        b.delivered = uuids(m.get("delivered")); b.consumed = uuids(m.get("consumed")); b.prompt = uuid(m,"prompt"); b.deliveryProposed = (Boolean)m.get("delivery_proposed");
        b.observation = uuid(m,"observation"); b.ownerRequest = uuid(m,"owner_request"); b.ownerAction = OwnerAction.valueOf((String)m.get("owner_action"));
        b.correction = (String)m.get("correction"); b.confirmation = uuid(m,"confirmation"); b.waitingChange = (Boolean)m.get("waiting_change"); b.revalidate = true;
        return new DialogueSnapshot(b);
    }
    private Map<String,Object> value() {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("schema", VERSION); m.put("question", InteractionCodec.parseJson(new InteractionCodec().encode(data.question)));
        m.put("audience", Map.of("player",data.audience.player().toString(),"session",data.audience.session().toString())); m.put("phase",data.phase.name());
        m.put("response_generation",data.generation); m.put("reply_rounds",(long)data.rounds); m.put("identical_checks",(long)data.identical);
        m.put("next_check_ms",data.nextCheck); m.put("clock_ms",data.clock); m.put("world_revision",data.worldRevision);
        m.put("delivered",data.delivered.stream().map(UUID::toString).toList()); m.put("consumed",data.consumed.stream().map(UUID::toString).toList());
        m.put("prompt",string(data.prompt)); m.put("delivery_proposed",data.deliveryProposed); m.put("observation",string(data.observation));
        m.put("owner_request",string(data.ownerRequest)); m.put("owner_action",data.ownerAction.name()); m.put("correction",data.correction);
        m.put("confirmation",string(data.confirmation)); m.put("waiting_change",data.waitingChange); return m;
    }
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object x) { if (!(x instanceof Map<?,?>)) throw new IllegalArgumentException("object required"); return (Map<String,Object>)x; }
    static UUID uuid(Map<String,Object> m, String k) { return m.get(k) == null ? null : UUID.fromString((String)m.get(k)); }
    private static String string(UUID id) { return id == null ? null : id.toString(); }
    private static List<UUID> uuids(Object x) { if (!(x instanceof List<?> list)) throw new IllegalArgumentException("UUID list required"); return list.stream().map(v -> UUID.fromString((String)v)).toList(); }
    static final class Builder {
        InteractionWire.UntrustedMessage question; Audience audience; Phase phase = Phase.IDLE;
        long generation, nextCheck, clock, worldRevision; int rounds, identical;
        List<UUID> delivered = List.of(), consumed = List.of(); UUID prompt, observation, ownerRequest, confirmation;
        OwnerAction ownerAction = OwnerAction.NONE; String correction; boolean deliveryProposed, waitingChange, revalidate;
        Builder() { }
        Builder(Builder d) {
            question=d.question; audience=d.audience; phase=d.phase; generation=d.generation; nextCheck=d.nextCheck; clock=d.clock; worldRevision=d.worldRevision;
            rounds=d.rounds; identical=d.identical; delivered=d.delivered; consumed=d.consumed; prompt=d.prompt; observation=d.observation; ownerRequest=d.ownerRequest;
            confirmation=d.confirmation; ownerAction=d.ownerAction; correction=d.correction; deliveryProposed=d.deliveryProposed; waitingChange=d.waitingChange; revalidate=d.revalidate;
        }
        Builder(DialogueSnapshot s) { this(s.data); }
        DialogueSnapshot build() { return new DialogueSnapshot(this); }
        void consume(UUID id) { List<UUID> copy = new ArrayList<>(consumed); copy.add(id); consumed=copy; rounds++; }
        void clearPrompt() { prompt=null; deliveryProposed=false; }
        void clearPending() { clearPrompt(); observation=null; ownerRequest=null; ownerAction=OwnerAction.NONE; correction=null; confirmation=null; }
    }
}
