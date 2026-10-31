package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static dev.aivillages.core.kernel.interaction.DialogueTransition.*;

/** Actual reducer + B planner bytecode, driven by the frozen synthetic 100 x 20-event campaign. */
public final class DialogueSequenceProbe {
    private static DialogueTransition event(DialogueFixtures f,String event,int step) {
        var s=f.state;
        return switch(event) {
            case "BEGIN" -> f.begin();
            case "MODEL_READY" -> f.model();
            case "DELIVERED" -> f.delivered();
            case "CHECK_AGAIN","HELP","CANCEL","CORRECT_GOAL" -> f.send(event);
            case "UNCHANGED","PARTIAL","SUFFICIENT" -> {
                if(s.pendingObservation()==null) yield f.dialogue.tick(s,f.now);
                yield f.observe(event.equals("UNCHANGED")?1:event.equals("PARTIAL")?2:3,s.observedWorldRevision()+1);
            }
            case "DUPLICATE_REPLY" -> f.lastReply==null?f.dialogue.tick(s,f.now):f.dialogue.reply(s,f.actor,f.lastReply,f.root,f.lastReplyGeneration,f.lastIntent,f.now);
            case "WRONG_PLAYER" -> {
                var a=f.actor; var other=new AssistanceDialogue.Actor(a.world(),a.domain(),a.principal(),a.citizen(),f.nextId(),a.session());
                yield f.dialogue.reply(s,other,f.nextId(),f.root,s.responseGeneration(),f.intent(s,"CHECK_AGAIN"),f.now);
            }
            case "WRONG_ROOT","OWNER_CHANGED" -> {
                var a=f.root; var other=new BlockerProjection.ActivityRef(a.world(),a.domain(),a.principal(),f.nextId(),a.originalOwner(),a.attempt(),a.citizen(),a.generation()+1,a.revision(),a.originalBudgetDigest(),a.absoluteDeadlineMillis());
                yield event.equals("OWNER_CHANGED")?f.dialogue.ownerChanged(s,f.actor,other,f.now)
                        :f.dialogue.reply(s,f.actor,f.nextId(),other,s.responseGeneration(),f.intent(s,"CHECK_AGAIN"),f.now);
            }
            case "LATE_MODEL" -> f.dialogue.modelReady(s,f.actor,f.nextId(),Math.max(0,s.responseGeneration()-1),f.now);
            case "RESTORE" -> new DialogueTransition(DialogueSnapshot.restore(s.encode()),List.of(),"RESTORED_UNTRUSTED");
            case "REVALIDATE" -> f.dialogue.revalidateRestored(s,f.actor,f.root,s.questionId(),s.dialogueRevision(),s.digest(),true,f.now);
            case "OWNER_ACK" -> f.dialogue.ownerDecision(s,f.actor,s.pendingOwnerRequest(),f.root,true,f.now);
            case "CONFIRM" -> f.dialogue.confirmCorrection(s,f.actor,f.nextId(),s.confirmationId(),s.responseGeneration(),true,f.now);
            case "NATIVE_CHANGE" -> f.dialogue.relevantChange(s,f.actor,f.root,s.observedWorldRevision()+1,f.now);
            case "TICK" -> { f.now+=2000; yield f.dialogue.tick(s,f.now); }
            case "EXPIRE" -> { f.now=s.expiresMillis(); yield f.dialogue.tick(s,f.now); }
            default -> throw new IllegalArgumentException("unregistered event: "+event);
        };
    }
    private static Map<String,Object> proposal(Proposal p) {
        if(p instanceof Reobserve r) return Map.of("kind","REOBSERVE","request",r.request().toString(),"signal",InteractionCodec.parseJson(new InteractionCodec().encode(r.signal())));
        if(p instanceof PreparePrompt r) return Map.of("kind","PREPARE_PROMPT","message",r.message().toString(),"question",r.question().toString(),
                "dialogue_revision",r.dialogueRevision(),"response_generation",r.responseGeneration(),"correction_confirmation",r.correctionConfirmation());
        if(p instanceof DeliverPrompt r) return Map.of("kind","DELIVER_PROMPT","message",r.message().toString(),"question",r.question().toString(),"response_generation",r.responseGeneration());
        var r=(OwnerDecision)p;
        return Map.of("kind","OWNER_DECISION","request",r.request().toString(),"action",r.action().name(),"activity",r.activity().value(),
                "question",r.question().toString(),"dialogue_revision",r.dialogueRevision(),"untrusted_candidate",r.untrustedCandidate());
    }
    public static void main(String[] args) throws Exception {
        var campaign=DialogueSnapshot.object(InteractionCodec.parseJson(Files.readString(Path.of(args[0]))));
        Path jar=Path.of(AssistanceDialogue.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if(!Files.isRegularFile(jar)) throw new IllegalStateException("measured production JAR required");
        String jarSha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        List<Object> sequences=new ArrayList<>();
        for(Object row:(List<?>)campaign.get("sequences")) {
            var specification=DialogueSnapshot.object(row); var f=new DialogueFixtures(); List<Object> frames=new ArrayList<>(); int step=0;
            for(Object name:(List<?>)specification.get("events")) {
                String event=(String)name; var before=f.state; var transition=event(f,event,step); f.set(transition);
                Map<String,Object> frame=new LinkedHashMap<>(); frame.put("step",(long)++step); frame.put("event",event); frame.put("clock_ms",f.now);
                frame.put("before",InteractionCodec.parseJson(before.encode())); frame.put("before_restore_locked",before.needsOwnerRevalidation());
                frame.put("after",InteractionCodec.parseJson(f.state.encode())); frame.put("after_restore_locked",f.state.needsOwnerRevalidation());
                frame.put("reason",transition.reason()); frame.put("proposals",transition.proposals().stream().map(DialogueSequenceProbe::proposal).toList()); frames.add(frame);
            }
            sequences.add(Map.of("id",specification.get("id"),"seed",specification.get("seed"),"frames",frames));
        }
        System.out.print(InteractionCodec.canonical(Map.of("tier","SYNTHETIC_DIALOGUE_SEQUENCES","production_jar_sha256",jarSha,"sequences",sequences)));
    }
}
