package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionWire;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static dev.aivillages.core.kernel.interaction.AssistanceFixtures.id;
import static dev.aivillages.core.kernel.interaction.DialogueSnapshot.*;

/** Pure synthetic native-port doubles plus the actual frozen B planner. Never native/gameplay proof. */
final class DialogueFixtures {
    final AssistanceFixtures planning = new AssistanceFixtures();
    final BlockerProjection.ActivityRef root = planning.reference();
    final Audience audience = new Audience(id(101),id(102));
    final AssistanceDialogue.Actor actor = new AssistanceDialogue.Actor(root.world(),root.domain(),root.principal(),root.citizen(),audience.player(),audience.session());
    boolean permitted=true; long now=1000, ordinal=1000;
    final AssistanceDialogue dialogue = new AssistanceDialogue((origin,a,q,r,p,who)->permitted);
    DialogueSnapshot state=DialogueSnapshot.initial(planning.assess().request(),audience);
    UUID lastReply; long lastReplyGeneration; InteractionWire.UntrustedMessage lastIntent;
    UUID nextId() { return AssistanceFixtures.id(++ordinal); }
    DialogueTransition set(DialogueTransition transition) { state=transition.snapshot(); return transition; }
    DialogueTransition begin() { return set(dialogue.begin(state,actor,now)); }
    DialogueTransition model() { return set(dialogue.modelReady(state,actor,state.pendingPrompt(),state.responseGeneration(),now)); }
    DialogueTransition delivered() { return set(dialogue.delivered(state,actor,state.pendingPrompt(),state.responseGeneration(),now)); }
    DialogueSnapshot ready() { begin(); model(); delivered(); return state; }
    InteractionWire.UntrustedMessage intent(DialogueSnapshot snapshot,String reply) { return intent(snapshot,reply,null); }
    InteractionWire.UntrustedMessage intent(DialogueSnapshot snapshot,String reply,Object selection) {
        Map<String,Object> b=new LinkedHashMap<>(); b.put("question_id",snapshot.questionId().toString()); b.put("dialogue_revision",snapshot.dialogueRevision());
        b.put("reply",reply); b.put("text",reply.equals("CHECK_AGAIN")?"done":"explicit candidate goal or help"); b.put("selection",selection);
        return AssistanceDialogue.wire("PlayerReplyIntent",b);
    }
    DialogueTransition send(String reply) { return send(reply,null); }
    DialogueTransition send(String reply,Object selection) {
        UUID message=nextId(); long generation=state.responseGeneration(); var candidate=intent(state,reply,selection);
        var t=set(dialogue.reply(state,actor,message,root,generation,candidate,now));
        if(state.consumedReplyIds().contains(message)) { lastReply=message; lastReplyGeneration=generation; lastIntent=candidate; }
        return t;
    }
    DialogueTransition observe(long planks,long worldRevision) {
        planning.now=now; planning.planks=planks; planning.worldRevision=worldRevision;
        var e = new BlockerProjection.Evidence(root,nextId(),100,worldRevision,true,true,true,List.of());
        var result=new AssistanceFeasibility().assess(root,ref->Optional.of(e),planning.view(),planning.target(),
                new AssistanceFeasibility.Question(state.questionId(),state.dialogueRevision()+1,now,100,120000));
        return set(dialogue.observation(state,actor,state.pendingObservation(),root,state.questionId(),state.dialogueRevision(),e.evidenceId(),worldRevision,result,now));
    }
    long missing() {
        var rows=(List<?>)DialogueSnapshot.object(state.question().body().get("blocker")).get("requirements");
        return rows.isEmpty()?0:(Long)DialogueSnapshot.object(rows.getFirst()).get("missing");
    }
}
