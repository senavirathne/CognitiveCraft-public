package dev.aivillages.e2e.parallelcfixture;

import dev.aivillages.core.kernel.interaction.*;
import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.providers.interaction.ConversationProvider;
import net.minecraft.server.level.ServerPlayer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.aivillages.e2e.parallelcfixture.CConversationServer.id;

/** Test-only composition: actual native identity/transport, declared synthetic facts, zero admission/effect ports. */
public final class CInteractionSafetyServer {
    private static final Map<String,Map<String,Object>> CASES=new HashMap<>();
    private static final ConversationContext MEMORY=new ConversationContext();
    private static final InteractionSafety SAFETY=new InteractionSafety();
    private static final InteractionPresenter PRESENTER=new InteractionPresenter();
    private static final UUID WORLD=UUID.randomUUID(),DOMAIN=id("C07-synthetic-domain");
    private static final long DEADLINE=clock()+600000;
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"c07-provider-timer");t.setDaemon(true);return t;});
    private static final ConversationProvider OFFLINE=new ConversationProvider(null,TIMER);
    private static long sequence;
    private static long clock(){return System.nanoTime()/1000000;}
    @SuppressWarnings("unchecked") public static void initialize(){
        String path=System.getenv("COGNITIVECRAFT_C_SAFETY_SCENARIOS");if(path==null)return;
        try {var campaign=(Map<?,?>)InteractionCodec.parseJson(Files.readString(Path.of(path)));for(Object value:(List<?>)campaign.get("cases")){var row=(Map<String,Object>)value;CASES.put((String)row.get("id"),row);}}
        catch(Exception failure){throw new IllegalStateException("frozen safety fixtures",failure);}
    }
    @SuppressWarnings("unchecked") public static void receive(ServerPlayer player,Map<String,Object> input,UUID session)throws Exception {
        if(!input.keySet().equals(Set.of("schema","operation","id","case_id","text","reply_question_id","reply_generation")))throw new IllegalArgumentException("safety input fields");
        var row=CASES.get(input.get("case_id"));
        if(row==null||session==null||!player.getUUID().equals(id("OfflinePlayer:"+row.get("player")))||!Objects.equals(input.get("text"),row.get("text")))throw new IllegalArgumentException("safety native fixture correlation");
        CConversationServer.event("request",Map.of("native_player",player.getUUID().toString(),"input",input,"guards",ConversationGuards.snapshot()));
        String citizen=(String)row.get("citizen"),fault=(String)row.get("provider_fault");var facts=(Map<String,Object>)row.get("facts");
        UUID message=UUID.fromString((String)input.get("id")),question=id("C07-question:"+player.getUUID()+":"+citizen);
        var key=new ConversationContext.SessionKey(WORLD,player.getUUID(),id("C07-citizen:"+citizen));
        boolean current=Boolean.TRUE.equals(facts.get("reply_current"))&&question.toString().equals(input.get("reply_question_id"))&&Long.valueOf(1).equals(input.get("reply_generation"));
        var selection=facts.get("selection")==null?null:new CandidateValidator.Selection(id("C07-selection:"+player.getUUID()+":"+facts.get("selection")),(String)facts.get("selection"));
        var transport=new CandidateValidator.Transport(key,message,++sequence,(String)input.get("text"),selection,new CandidateValidator.Question(question,1));
        var before=MEMORY.snapshot(key,clock());MEMORY.associate(key,id("C07-association:"+player.getUUID()+":"+citizen),clock());
        ReferenceClarification.Resolver refs=(query,candidate)->{
            var s=(Map<?,?>)candidate.get("selector");String label=s.containsKey("label")?(String)s.get("label"):"Visible storage";
            String type=(String)s.get("container_type");if(type==null||type.equals("ANY_MVP"))type="CHEST";
            var visible=new ArrayList<ReferenceClarification.VisibleCandidate>();for(int i=0;i<((Long)facts.get("visible_count")).intValue();i++)visible.add(new ReferenceClarification.VisibleCandidate(id("C07-storage:"+player.getUUID()+":"+i),1,label,type));
            return new ReferenceClarification.Observation(query,ReferenceClarification.Status.MATCHES,visible,false,Boolean.TRUE.equals(facts.get("configured_default")),query.tick()+1);
        };
        var decision=SAFETY.interpret(transport,MEMORY,clock(),t->new InteractionSafety.Review(t,citizen,Boolean.TRUE.equals(facts.get("disclosed")),current,Boolean.TRUE.equals(facts.get("cancelled")),Boolean.TRUE.equals(facts.get("unsupported")),1,refs));
        var ticket=new ConversationProvider.Ticket(WORLD,player.getUUID(),key.citizen(),session,1,sequence,message);var responseFacts=ResponseFacts.smallTalk("en");
        CInteractionFaults injected=null;ConversationProvider.Result phrasing;long providerStart=System.nanoTime();
        if(fault.equals("MALFORMED")||fault.equals("DELAY_AFTER_CANCEL")) {
            injected=new CInteractionFaults(fault);var provider=new ConversationProvider(injected,injected.budget(),TIMER);var future=provider.respond(ticket,responseFacts,t->true);
            if(fault.equals("DELAY_AFTER_CANCEL"))injected.completeAfterCancellation(provider,ticket);
            phrasing=future.get(12,TimeUnit.SECONDS);
        } else phrasing=OFFLINE.respond(ticket,responseFacts,t->true).join();
        long providerElapsed=System.nanoTime()-providerStart;
        String classification=decision.kind().name(),reason=decision.reason();
        if(phrasing.reason()==ConversationProvider.Reason.INVALID_RESPONSE){classification="REJECT";reason="INVALID_RESPONSE";}
        Map<String,Object> rejected=null;
        if(fault.equals("FORGED_AUTHORITY")) {
            var draft=IntentDrafts.forgedAuthority(transport);var result=new CandidateValidator().validate(draft,transport);
            rejected=Map.of("raw",draft.json(),"result",result instanceof CandidateValidator.Clarify c?c.code().name():"UNEXPECTED_CANDIDATE","tier","SYNTHETIC_EXTRACTOR_FAULT");
        }
        Map<String,Object> privateDefault=null;
        if(Boolean.TRUE.equals(facts.get("private_default"))) {
            var query=new ReferenceClarification.Query(key,message,ReferenceClarification.Operation.DEPOSIT,1);
            var result=new ReferenceClarification().omittedSource(query,"CHEST",(q,s)->new ReferenceClarification.Observation(q,ReferenceClarification.Status.DENIED,List.of(),false,false,2));
            privateDefault=Map.of("result",result instanceof ReferenceClarification.Clarify c?c.reason().name():"UNEXPECTED_REFERENCE","visible_count",0,"permission","DEPOSIT","authority_changed",false);
        }
        var activity=new BlockerProjection.ActivityRef(WORLD,DOMAIN,player.getUUID(),id("C07-root:"+player.getUUID()+":"+citizen),id("C07-owner:"+player.getUUID()),id("C07-attempt:"+player.getUUID()),key.citizen(),1,1,"b".repeat(64),DEADLINE);
        boolean waiting=Set.of("ASK","CHECK_AGAIN","HELP").contains(classification);
        var status=waiting?StatusProjection.State.WAITING:classification.equals("CANCEL")?StatusProjection.State.CANCEL_PENDING:StatusProjection.State.CHAT;
        var request=new StatusProjection.Request(key,new DialogueSnapshot.Audience(player.getUUID(),session),message,question,activity,status,1,1,clock(),Math.min(DEADLINE,clock()+120000),false);
        var projected=StatusProjection.project(request,r->new StatusProjection.Evidence(r,true,r.nowMillis(),List.of(new StatusProjection.Material("oak planks",((Long)facts.get("missing")).intValue())),(String)facts.get("storage_label"),null));
        var presentation=PRESENTER.present(projected,phrasing.deliverable()?phrasing.text():null);
        String text=switch(classification) {
            case "ASK","CHECK_AGAIN","HELP","CANCEL"->presentation.text();
            case "CHAT"->phrasing.text();
            case "FORGET"->"Cleared this conversation's text.";
            case "UNSUPPORTED_EFFECT"->"This task needs an unsupported action. Choose a supported action.";
            case "COMMAND"->"Proposed "+decision.candidate().intent().body().get("action")+". "+(decision.confirmationRequired()?"Confirmation required.":"Authoritative admission required.");
            case "CORRECT_GOAL"->"A goal correction requires confirmation and review by the original owner.";
            case "LABEL_DATA"->"A storage label is data and grants no permission.";
            case "SELECTOR"->"Storage reference proposed; authorization and identity must be checked at admission.";
            case "REDACTED"->"I cannot share that conversation.";
            case "REFUSED_EXPECTED","REVALIDATE_ONLY"->"That reply is unavailable. Use the current authorized question.";
            case "REJECT"->"That request or response was rejected. No action was performed.";
            default->"Please clarify the intended authorized target.";
        };
        Map<String,Object> body=new LinkedHashMap<>();body.put("schema","cc.c.safety-output/1");body.put("kind","SAFETY");body.put("case_id",input.get("case_id"));body.put("message_id",message.toString());body.put("native_player",player.getUUID().toString());body.put("session",session.toString());body.put("citizen",citizen);body.put("classification",classification);body.put("reason",reason);body.put("candidate",decision.candidate()==null?null:Map.of("schema","cc.cd/1.0.0","kind",decision.candidate().intent().kind().name(),"body",decision.candidate().intent().body()));body.put("selector",decision.selector());body.put("confirmation_required",decision.confirmationRequired());
        body.put("text",text);body.put("accessible_text",text);body.put("question_id",classification.equals("REDACTED")?null:question.toString());body.put("root_id",classification.equals("REDACTED")?null:activity.root().toString());body.put("generation",1L);body.put("facts_tier","SYNTHETIC_CONTRACT");body.put("production_enabled",false);body.put("a_settlement",null);
        Map<String,Object> provider=new LinkedHashMap<>();provider.put("reason",phrasing.reason().name());provider.put("deliverable",phrasing.deliverable());provider.put("text",phrasing.text());provider.put("calls",phrasing.providerCalls());provider.put("reserved_input_tokens",phrasing.reservedInputTokens());provider.put("reserved_output_tokens",phrasing.reservedOutputTokens());provider.put("actual_input_tokens",phrasing.actualInputTokens());provider.put("actual_output_tokens",phrasing.actualOutputTokens());provider.put("compute_termination_confirmed",phrasing.computeTerminationConfirmed());provider.put("elapsed_ns",providerElapsed);provider.put("fault",injected==null?null:injected.evidence());provider.put("facts",responseFacts.packet());provider.put("facts_sha256",responseFacts.digest());
        var after=MEMORY.snapshot(key,clock());
        Map<String,Object> observation=new LinkedHashMap<>();observation.put("case_id",input.get("case_id"));observation.put("native_player",player.getUUID().toString());observation.put("citizen",citizen);observation.put("question_matches_current",current);observation.put("declared_facts",facts);observation.put("provider",provider);observation.put("rejected_candidate",rejected);observation.put("private_default",privateDefault);observation.put("context_before",before.turns().stream().map(t->t.messageId().toString()).toList());observation.put("context_after",after.turns().stream().map(t->t.messageId().toString()).toList());observation.put("association_retained",id("C07-association:"+player.getUUID()+":"+citizen).equals(after.assistanceAssociation()));observation.put("output",body);observation.put("guards",ConversationGuards.snapshot());
        CConversationServer.event("safety-observation",observation);CConversationServer.send(player,body);
    }
    /** Construct only invalid untrusted JSON. This test-only helper cannot access codec trusted constructors. */
    private static final class IntentDrafts {
        static CandidateValidator.Draft forgedAuthority(CandidateValidator.Transport t) {
            var body=new LinkedHashMap<String,Object>();body.put("action","CHAT");body.put("actor",Map.of("kind","ADDRESSED"));body.put("utterance",t.text());body.put("source",null);body.put("destination",null);body.put("items",List.of());body.put("label",null);body.put("role",null);body.put("requested_access",null);body.put("principal_id",id("other-player").toString());
            return new CandidateValidator.Draft(t.key(),t.messageId(),InteractionCodec.canonical(Map.of("schema","cc.cd/1.0.0","kind","DialogueIntent","body",body)),Map.of("/utterance",new CandidateValidator.Span(0,t.text().length())));
        }
    }
}
