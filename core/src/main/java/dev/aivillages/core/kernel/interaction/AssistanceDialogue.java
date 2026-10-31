package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.core.kernel.interaction.contract.InteractionWire;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import static dev.aivillages.core.kernel.interaction.BlockerProjection.*;
import static dev.aivillages.core.kernel.interaction.DialogueSnapshot.*;
import static dev.aivillages.core.kernel.interaction.DialogueTransition.*;

/** Pure bounded conversation reducer. A authenticates/reobserves/adjudicates; J alone supplies native adapters. */
public final class AssistanceDialogue {
    public enum Origin { PLAYER, PRESENTER, OWNER }
    public record Actor(UUID world, UUID domain, UUID principal, UUID citizen, UUID player, UUID session) {
        public Actor { Objects.requireNonNull(world); Objects.requireNonNull(domain); Objects.requireNonNull(principal);
            Objects.requireNonNull(citizen); Objects.requireNonNull(player); Objects.requireNonNull(session); }
    }
    /** Read-only native port. DTO fields and a public test lambda cannot confer production authority. */
    @FunctionalInterface public interface NativeGate {
        boolean allow(Origin origin, ActivityRef original, UUID question, long revision, Audience audience, Actor actor);
    }
    private final NativeGate gate;
    public AssistanceDialogue(NativeGate gate) { this.gate = Objects.requireNonNull(gate); }

    public DialogueTransition begin(DialogueSnapshot s, Actor actor, long now) {
        DialogueTransition stopped = guard(s, now); if (stopped != null) return stopped;
        if (s.phase() != Phase.IDLE || !authorized(s, Origin.PRESENTER, actor)) return unchanged(s,"BEGIN_REFUSED");
        Builder b = advance(s, now); b.phase = Phase.ASKED;
        return prompt(b, false, "ASKED");
    }
    public DialogueTransition modelReady(DialogueSnapshot s, Actor actor, UUID message, long generation, long now) {
        DialogueTransition stopped = guard(s, now); if (stopped != null) return stopped;
        if (!authorized(s,Origin.PRESENTER,actor) || message == null || !Objects.equals(message,s.pendingPrompt()) || generation != s.responseGeneration()
                || s.deliveryProposed() || s.deliveredMessageIds().contains(message)) return unchanged(s,"STALE_OR_DUPLICATE_MODEL");
        Builder b = advance(s,now); b.deliveryProposed=true;
        return changed(b,List.of(new DeliverPrompt(message,s.questionId(),generation)),"DELIVERY_PROPOSED");
    }
    public DialogueTransition delivered(DialogueSnapshot s, Actor actor, UUID message, long generation, long now) {
        DialogueTransition stopped = guard(s,now); if (stopped != null) return stopped;
        if (!authorized(s,Origin.PRESENTER,actor) || !s.deliveryProposed() || !Objects.equals(message,s.pendingPrompt())
                || generation != s.responseGeneration() || s.deliveredMessageIds().contains(message)) return unchanged(s,"DELIVERY_REFUSED");
        if (s.deliveredMessageIds().size() == MAX_DELIVERED) return close(s,now,Phase.CLOSED,"DELIVERY_LIMIT");
        Builder b = advance(s,now); List<UUID> ids = new ArrayList<>(b.delivered); ids.add(message); b.delivered=ids; b.clearPrompt();
        if (b.phase == Phase.ASKED) b.phase=Phase.AWAITING_REPLY;
        return changed(b,List.of(),"DELIVERED_ONCE");
    }
    public DialogueTransition reply(DialogueSnapshot s, Actor actor, UUID message, ActivityRef activity, long generation,
                                    InteractionWire.UntrustedMessage intent, long now) {
        DialogueTransition stopped = guard(s,now); if (stopped != null) return stopped;
        if (!authorized(s,Origin.PLAYER,actor) || !s.activity().equals(activity) || generation != s.responseGeneration()
                || message == null || s.consumedReplyIds().contains(message) || intent == null || intent.kind() != InteractionWire.Kind.PlayerReplyIntent
                || !s.questionId().toString().equals(intent.body().get("question_id")) || !Objects.equals(s.dialogueRevision(),intent.body().get("dialogue_revision"))
                || !((List<?>)s.question().body().get("allowed_replies")).contains(intent.body().get("reply"))
                || s.phase() == Phase.IDLE || s.phase() == Phase.ASKED) return unchanged(s,"REPLY_REFUSED");
        String reply = (String)intent.body().get("reply");
        if (reply.equals("CHECK_AGAIN") && (s.phase() != Phase.AWAITING_REPLY || s.waitingRelevantChange() || now < s.nextCheckMillis())
                || !List.of("CANCEL","HELP").contains(reply) && s.phase() != Phase.AWAITING_REPLY && s.phase() != Phase.CLARIFICATION
                || s.phase() == Phase.WAITING_OWNER_DECISION) return unchanged(s,"WAIT_FOR_NATIVE_CHANGE_OR_DECISION");
        if (reply.equals("CHOOSE_TARGET") && intent.body().get("selection") == null) return unchanged(s,"SELECTION_REQUIRED");
        if (s.replyRounds() == MAX_ROUNDS) return close(s,now,Phase.CLOSED,"REPLY_ROUND_LIMIT");
        Builder b = advance(s,now); b.consume(message);
        return switch (reply) {
            case "CHECK_AGAIN" -> {
                b.clearPending(); b.generation++; b.phase=Phase.REOBSERVE_REQUESTED; b.observation=correlation(s,"observe",message);
                Map<String,Object> blocker = object(s.question().body().get("blocker"));
                InteractionWire.UntrustedMessage signal = wire("ActivityResumeSignal",Map.of("activity",s.activity().value(),"question_id",s.questionId().toString(),
                        "dialogue_revision",s.dialogueRevision(),"observations",List.of(blocker.get("evidence_id")),
                        "observed_world_revision",s.observedWorldRevision(),"request_kind","REVALIDATE_ONLY"));
                yield changed(b,List.of(new Reobserve(b.observation,signal)),"REOBSERVE_ONLY_NO_CREDIT");
            }
            case "CANCEL" -> ownerProposal(b,OwnerAction.CANCEL,message,Map.of(),"CANCELLATION_AWAITING_A");
            case "CHOOSE_TARGET" -> ownerProposal(b,OwnerAction.CHOOSE_TARGET,message,Map.of("selection",intent.body().get("selection")),"UNTRUSTED_SELECTION_AWAITING_A");
            case "CORRECT_GOAL" -> {
                b.clearPending(); b.phase=Phase.CLARIFICATION; b.correction=(String)intent.body().get("text"); b.confirmation=correlation(s,"confirm",message);
                yield prompt(b,true,"EXPLICIT_CONFIRMATION_REQUIRED");
            }
            case "HELP" -> prompt(b,b.phase == Phase.CLARIFICATION,"HELP_ONLY");
            default -> throw new IllegalArgumentException("unsupported reply");
        };
    }
    /** Separate explicit player confirmation, bound to the exact pending candidate and current response generation. */
    public DialogueTransition confirmCorrection(DialogueSnapshot s, Actor actor, UUID message, UUID confirmation, long generation, boolean confirmed, long now) {
        DialogueTransition stopped=guard(s,now); if(stopped!=null) return stopped;
        if (!authorized(s,Origin.PLAYER,actor) || s.phase()!=Phase.CLARIFICATION || !Objects.equals(confirmation,s.confirmationId())
                || generation!=s.responseGeneration() || message==null || s.consumedReplyIds().contains(message)) return unchanged(s,"CONFIRMATION_REFUSED");
        if (s.replyRounds()==MAX_ROUNDS) return close(s,now,Phase.CLOSED,"REPLY_ROUND_LIMIT");
        Builder b=advance(s,now); b.consume(message); String text=b.correction;
        if (confirmed) return ownerProposal(b,OwnerAction.AMEND,message,Map.of("text",text,"confirmation_id",confirmation.toString()),"CONFIRMED_AMENDMENT_AWAITING_A");
        b.clearPending(); b.phase=Phase.AWAITING_REPLY; return prompt(b,false,"CORRECTION_DECLINED");
    }
    public DialogueTransition observation(DialogueSnapshot s, Actor owner, UUID request, ActivityRef activity, UUID question, long revision,
                                          UUID evidence, long worldRevision, AssistanceFeasibility.Result result, long now) {
        DialogueTransition stopped=guard(s,now); if(stopped!=null) return stopped;
        bounded(worldRevision,0);
        if (!authorized(s,Origin.OWNER,owner) || s.phase()!=Phase.REOBSERVE_REQUESTED || !Objects.equals(request,s.pendingObservation())
                || !s.activity().equals(activity) || !s.questionId().equals(question) || revision!=s.dialogueRevision()
                || evidence==null || worldRevision<s.observedWorldRevision() || result==null) return unchanged(s,"OBSERVATION_REFUSED");
        Builder b=advance(s,now); b.clearPending(); b.generation++; b.worldRevision=worldRevision;
        if (result.plannerFeasibleNow()) {
            b.phase=Phase.WAITING_OWNER_DECISION; b.ownerAction=OwnerAction.REVALIDATE; b.ownerRequest=request;
            return changed(b,List.of(),"FEASIBILITY_OBSERVED_A_STILL_DECIDES");
        }
        var blocker=result.blocker();
        if (blocker==null || blocker.code()==Code.UNAUTHORIZED || blocker.code()==Code.STALE_REFERENCE
                || !Objects.equals(blocker.wire().body().get("activity"),s.activity().value())) return close(s,now,Phase.CLOSED,"OLD_QUESTION_INVALIDATED");
        // The callback must cite the actual native observation, not a player assertion or a newly invented root.
        if (!evidence.toString().equals(blocker.wire().body().get("evidence_id")) || !Objects.equals(worldRevision,blocker.wire().body().get("world_revision")))
            return unchanged(s,"OBSERVATION_EVIDENCE_MISMATCH");
        boolean identical = fingerprint(object(s.question().body().get("blocker"))).equals(fingerprint(blocker.wire().body()));
        b.identical=identical?Math.min(3,b.identical+1):0; b.waitingChange=b.identical==3;
        b.nextCheck=Math.min(s.expiresMillis(),now+(b.identical<=1?2000:b.identical==2?4000:8000));
        Map<String,Object> body=new LinkedHashMap<>(s.question().body()); body.put("dialogue_revision",s.dialogueRevision()+1); body.put("blocker",blocker.wire().body());
        if (blocker.code()!=Code.INSUFFICIENT_MATERIAL) body.put("allowed_replies",List.of("CANCEL","CHECK_AGAIN","HELP"));
        b.question=wire("AssistanceRequest",body); b.phase=Phase.AWAITING_REPLY;
        return prompt(b,false,identical?"UNCHANGED_REMAINDER_BACKOFF":"REOBSERVED_REMAINDER");
    }
    public DialogueTransition relevantChange(DialogueSnapshot s, Actor owner, ActivityRef activity, long worldRevision, long now) {
        DialogueTransition stopped=guard(s,now); if(stopped!=null) return stopped;
        bounded(worldRevision,0);
        if (!authorized(s,Origin.OWNER,owner) || !s.activity().equals(activity) || worldRevision<=s.observedWorldRevision()
                || !s.waitingRelevantChange()) return unchanged(s,"RELEVANT_CHANGE_REFUSED");
        Builder b=advance(s,now); b.identical=0; b.waitingChange=false; b.nextCheck=now;
        return changed(b,List.of(),"NATIVE_RELEVANT_CHANGE_NO_AUTO_RESUME");
    }
    public DialogueTransition ownerDecision(DialogueSnapshot s, Actor owner, UUID request, ActivityRef activity, boolean accepted, long now) {
        DialogueTransition stopped=guard(s,now); if(stopped!=null) return stopped;
        if (!authorized(s,Origin.OWNER,owner) || s.phase()!=Phase.WAITING_OWNER_DECISION || !Objects.equals(request,s.pendingOwnerRequest())
                || !s.activity().equals(activity)) return unchanged(s,"OWNER_DECISION_REFUSED");
        if (accepted) return close(s,now,s.pendingOwnerAction()==OwnerAction.CANCEL?Phase.CANCELLED:Phase.CLOSED,"A_DECISION_RECORDED_NO_C_CREDIT");
        Builder b=advance(s,now); b.clearPending(); b.phase=Phase.AWAITING_REPLY;
        Map<String,Object> body=new LinkedHashMap<>(s.question().body()); body.put("dialogue_revision",s.dialogueRevision()+1); b.question=wire("AssistanceRequest",body);
        return prompt(b,false,"A_REFUSED_PROPOSAL");
    }
    /** Native owner/root/generation/revision change invalidates the old question; its original identity is never rebased. */
    public DialogueTransition ownerChanged(DialogueSnapshot s, Actor owner, ActivityRef current, long now) {
        DialogueTransition stopped=guard(s,now); if(stopped!=null) return stopped;
        if (!authorized(s,Origin.OWNER,owner) || current==null || s.activity().equals(current)) return unchanged(s,"OWNER_CHANGE_REFUSED");
        return close(s,now,Phase.CLOSED,"OWNER_CHANGED_OLD_QUESTION_CLOSED");
    }
    /** A must validate this digest against its durable question, replay journal, original owner/budget and current root. */
    public DialogueTransition revalidateRestored(DialogueSnapshot s, Actor owner, ActivityRef current, UUID question, long revision, String snapshotDigest, boolean stillWaiting, long now) {
        bounded(now,0);
        if (s.terminal()) return unchanged(s,"TERMINAL_RESTORE");
        if (!s.needsOwnerRevalidation() || now<s.clockMillis() || !authorized(s,Origin.OWNER,owner) || !s.questionId().equals(question)
                || revision!=s.dialogueRevision() || !s.digest().equals(snapshotDigest)) return unchanged(s,"RESTORE_RECEIPT_REFUSED");
        if (now>=s.expiresMillis()) return close(s,now,Phase.EXPIRED,"EXPIRED");
        if (!s.activity().equals(current) || !stillWaiting) return close(s,now,Phase.CLOSED,"RESTORED_QUESTION_NO_LONGER_CURRENT");
        if (s.replyRounds()==MAX_ROUNDS || s.responseGeneration()>=MAX_INTEGER-2 || s.dialogueRevision()>=MAX_INTEGER-1)
            return close(s,now,Phase.CLOSED,"RESTORE_LIMIT");
        Builder b=advance(s,now); b.revalidate=false; b.clearPending(); b.generation++; b.phase=Phase.AWAITING_REPLY;
        Map<String,Object> body=new LinkedHashMap<>(s.question().body()); body.put("dialogue_revision",s.dialogueRevision()+1); b.question=wire("AssistanceRequest",body);
        // No prompt or proposal is replayed after restore. A fresh explicit reply can request help.
        return changed(b,List.of(),"RESTORED_REVALIDATED_NO_REPLAY");
    }
    public DialogueTransition tick(DialogueSnapshot s, long now) {
        DialogueTransition stopped=guard(s,now); return stopped!=null?stopped:changed(advance(s,now),List.of(),"WAITING");
    }
    private boolean authorized(DialogueSnapshot s, Origin origin, Actor actor) {
        ActivityRef a=s.activity();
        if (origin!=Origin.OWNER && (actor==null || !a.world().equals(actor.world()) || !a.domain().equals(actor.domain())
                || !a.principal().equals(actor.principal()) || !a.citizen().equals(actor.citizen()) || !s.audience().player().equals(actor.player())
                || !s.audience().session().equals(actor.session()))) return false;
        try { return gate.allow(origin,a,s.questionId(),s.dialogueRevision(),s.audience(),actor); }
        catch (RuntimeException unavailable) { return false; }
    }
    private static DialogueTransition guard(DialogueSnapshot s,long now) {
        Objects.requireNonNull(s); bounded(now,0);
        if (s.terminal()) return unchanged(s,"TERMINAL");
        if (now<s.clockMillis()) return unchanged(s,"CLOCK_ROLLBACK");
        if (now>=s.expiresMillis()) return close(s,now,Phase.EXPIRED,"EXPIRED");
        if (s.needsOwnerRevalidation()) return unchanged(s,"RESTORE_REQUIRES_A");
        if (s.responseGeneration()>=MAX_INTEGER-2 || s.dialogueRevision()>=MAX_INTEGER-1) return close(s,now,Phase.CLOSED,"CORRELATION_LIMIT");
        return null;
    }
    private static Builder advance(DialogueSnapshot s,long now) { Builder b=new Builder(s); b.clock=now; return b; }
    private static DialogueTransition prompt(Builder b,boolean confirmation,String reason) {
        b.generation++; b.clearPrompt(); b.prompt=UUID.nameUUIDFromBytes((b.question.body().get("question_id")+":prompt:"+b.generation).getBytes(StandardCharsets.UTF_8));
        DialogueSnapshot s=b.build();
        return changed(b,List.of(new PreparePrompt(b.prompt,s.questionId(),s.dialogueRevision(),s.responseGeneration(),confirmation)),reason);
    }
    private static DialogueTransition ownerProposal(Builder b,OwnerAction action,UUID message,Map<String,Object> candidate,String reason) {
        DialogueSnapshot previous=b.build(); b.clearPending(); b.generation++; b.phase=Phase.WAITING_OWNER_DECISION; b.ownerAction=action;
        b.ownerRequest=correlation(previous,action.name(),message); DialogueSnapshot s=b.build();
        return changed(b,List.of(new OwnerDecision(b.ownerRequest,action,s.activity(),s.questionId(),s.dialogueRevision(),candidate)),reason);
    }
    private static DialogueTransition close(DialogueSnapshot s,long now,Phase phase,String reason) {
        Builder b=advance(s,now); b.clearPending(); b.phase=phase; b.generation=Math.max(1,b.generation); return changed(b,List.of(),reason);
    }
    private static DialogueTransition changed(Builder b,List<Proposal> proposals,String reason) { return new DialogueTransition(b.build(),proposals,reason); }
    private static DialogueTransition unchanged(DialogueSnapshot s,String reason) { return new DialogueTransition(s,List.of(),reason); }
    private static UUID correlation(DialogueSnapshot s,String action,UUID message) {
        return UUID.nameUUIDFromBytes((s.questionId()+":"+s.dialogueRevision()+":"+action+":"+message).getBytes(StandardCharsets.UTF_8));
    }
    private static String fingerprint(Map<String,Object> blocker) {
        Map<String,Object> selected=new LinkedHashMap<>();
        for (String key:List.of("code","requirements","storage_id","visibility","knowledge","continuation")) selected.put(key,blocker.get(key));
        return InteractionCodec.canonical(selected);
    }
    static InteractionWire.UntrustedMessage wire(String kind,Map<String,Object> body) {
        return new InteractionCodec().decode(InteractionCodec.canonical(Map.of("schema",InteractionWire.VERSION,"kind",kind,"body",body)));
    }
}
