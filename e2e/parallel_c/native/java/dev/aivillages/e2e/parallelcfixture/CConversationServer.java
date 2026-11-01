package dev.aivillages.e2e.parallelcfixture;

import dev.aivillages.core.kernel.interaction.*;
import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.providers.interaction.ConversationProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerPlayer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Explicitly authorized isolated transport. Activity/terminal facts are SYNTHETIC_CONTRACT only. */
public final class CConversationServer implements ModInitializer {
    private static final UUID WORLD=UUID.randomUUID(),DOMAIN=id("synthetic-domain");
    private static final long DEADLINE=clock()+600_000;
    private static final InteractionPresenter PRESENTER=new InteractionPresenter();
    private static final Map<UUID,UUID> SESSIONS=new HashMap<>();
    private static final Map<UUID,Entry> ENTRIES=new HashMap<>();
    private static final Map<String,Map<String,Object>> FIXTURES=new HashMap<>();
    private static final java.util.concurrent.ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"c06-offline-provider-timer");t.setDaemon(true);return t;});
    private static final ConversationProvider PHRASING=new ConversationProvider(null,TIMER);
    private record Entry(InteractionPresenter.State state,String fixture,UUID message) { }
    public static UUID id(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));}
    private static long clock(){return System.nanoTime()/1_000_000;}
    @Override @SuppressWarnings("unchecked") public void onInitialize() {
        if(!"chat-only".equals(System.getenv("COGNITIVECRAFT_C_FIXTURE_ALLOWED")))throw new IllegalStateException("isolated C fixture opt-in required");
        try {
            var campaign=(Map<String,Object>)InteractionCodec.parseJson(Files.readString(Path.of(System.getenv("COGNITIVECRAFT_C_CAMPAIGN"))));
            for(Object c:(List<?>)campaign.get("cases"))for(Object step:(List<?>)((Map<?,?>)c).get("steps")){var row=(Map<String,Object>)step;FIXTURES.put((String)row.get("id"),row);}
        }catch(Exception failure){throw new IllegalStateException("frozen C06 campaign",failure);}
        PayloadTypeRegistry.serverboundPlay().register(ConversationPayloads.Request.TYPE,ConversationPayloads.Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ConversationPayloads.Response.TYPE,ConversationPayloads.Response.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ConversationPayloads.Request.TYPE,(payload,context)->receive(context.player(),payload.json()));
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{SESSIONS.put(handler.player.getUUID(),UUID.randomUUID());event("native-join",Map.of("player",handler.player.getUUID().toString(),"session",SESSIONS.get(handler.player.getUUID()).toString()));});
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{event("native-disconnect",Map.of("player",handler.player.getUUID().toString()));SESSIONS.remove(handler.player.getUUID());});
        ServerLifecycleEvents.SERVER_STARTED.register(server->event("server-start",Map.of("pid",ProcessHandle.current().pid(),"java",System.getProperty("java.version"),"guards",ConversationGuards.snapshot(),"facts_tier","SYNTHETIC_CONTRACT")));
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{event("server-stop",ConversationGuards.snapshot());TIMER.shutdownNow();});
    }
    @SuppressWarnings("unchecked") private static void receive(ServerPlayer player,String json) {
        try {
            var input=(Map<String,Object>)InteractionCodec.parseJson(json);
            if(!"cc.c.client-input/1".equals(input.get("schema")))throw new IllegalArgumentException("input version");
            String operation=(String)input.get("operation"),caseId=(String)input.get("case_id");var fixture=FIXTURES.get(caseId);
            if(fixture==null || !player.getUUID().equals(id("OfflinePlayer:"+fixture.get("player"))) || !SESSIONS.containsKey(player.getUUID()))throw new IllegalArgumentException("authenticated fixture principal");
            event("request",Map.of("native_player",player.getUUID().toString(),"input",input,"guards",ConversationGuards.snapshot()));
            if(operation.equals("MESSAGE")) {
                if(!input.keySet().equals(Set.of("schema","operation","id","case_id","text"))||!input.get("text").equals(fixture.get("text")))throw new IllegalArgumentException("message fields");
                UUID message=UUID.fromString((String)input.get("id"));var r=request(player,message,(String)fixture.get("fixture"),1);
                var projection=StatusProjection.project(r,authority(r,(String)fixture.get("fixture"),player));String phrasing=null,reason="TEMPLATE";
                if(r.requested()==StatusProjection.State.CHAT) {
                    var ticket=new ConversationProvider.Ticket(r.key().world(),r.key().principal(),r.key().citizen(),r.audience().session(),1,Math.max(1,clock()),message);
                    var result=PHRASING.respond(ticket,ResponseFacts.smallTalk("en"),ignored->true).join();phrasing=result.text();reason=result.reason().name();
                }
                var view=PRESENTER.present(projection,phrasing);ENTRIES.put(player.getUUID(),new Entry(InteractionPresenter.State.initial(view),(String)fixture.get("fixture"),message));sendView(player,caseId,view,reason);
            } else if(operation.equals("RESUME")) {
                if(!input.keySet().equals(Set.of("schema","operation","id","case_id")))throw new IllegalArgumentException("resume fields");
                Entry previous=Objects.requireNonNull(ENTRIES.get(player.getUUID()));var old=previous.state().view();var r=request(player,previous.message(),previous.fixture(),old.generation()+1);
                var view=PRESENTER.present(StatusProjection.project(r,authority(r,previous.fixture(),player)),null);var state=PRESENTER.rejoin(previous.state(),view);ENTRIES.put(player.getUUID(),new Entry(state,previous.fixture(),previous.message()));sendView(player,caseId,view,"REJOIN_TEMPLATE");
            } else if(operation.equals("CALLBACK")) {
                if(!input.keySet().equals(Set.of("schema","operation","id","case_id","event_id","message_id","session","generation","action")))throw new IllegalArgumentException("callback fields");
                var entry=Objects.requireNonNull(ENTRIES.get(player.getUUID()));var nativeAudience=new DialogueSnapshot.Audience(player.getUUID(),SESSIONS.get(player.getUUID()));
                var callback=new InteractionPresenter.Callback(UUID.fromString((String)input.get("event_id")),UUID.fromString((String)input.get("message_id")),new DialogueSnapshot.Audience(player.getUUID(),UUID.fromString((String)input.get("session"))),(Long)input.get("generation"),InteractionPresenter.Action.valueOf((String)input.get("action")));
                var transition=PRESENTER.callback(entry.state(),callback,nativeAudience,clock(),r->r.key().principal().equals(player.getUUID())&&r.audience().equals(nativeAudience));
                ENTRIES.put(player.getUUID(),new Entry(transition.next(),entry.fixture(),entry.message()));
                Map<String,Object> ack=new LinkedHashMap<>();ack.put("schema","cc.c.client-output/1");ack.put("kind","CALLBACK_ACK");ack.put("case_id",caseId);ack.put("request_id",input.get("id"));ack.put("disposition",transition.disposition().name());ack.put("proposal",transition.proposal()==null?null:Map.of("action",transition.proposal().action().name(),"message_id",transition.proposal().message().toString(),"confirmation_required",transition.proposal().confirmationRequired()));ack.put("facts_tier","SYNTHETIC_CONTRACT");send(player,ack);
            } else throw new IllegalArgumentException("unsupported UI operation");
        }catch(Exception failure){event("rejected-input",Map.of("native_player",player.getUUID().toString(),"failure",failure.getClass().getSimpleName()));}
    }
    private static StatusProjection.Request request(ServerPlayer player,UUID message,String fixture,long generation) {
        boolean a=player.getUUID().equals(id("OfflinePlayer:CPlayerA"));UUID citizen=id("synthetic-citizen:"+(a?"Ada":"Bob"));
        var key=new ConversationContext.SessionKey(WORLD,player.getUUID(),citizen);var audience=new DialogueSnapshot.Audience(player.getUUID(),SESSIONS.get(player.getUUID()));
        var activity=new BlockerProjection.ActivityRef(WORLD,DOMAIN,player.getUUID(),id("synthetic-root:"+player.getUUID()),id("synthetic-owner:"+player.getUUID()),id("synthetic-attempt:"+player.getUUID()),citizen,1,1,"a".repeat(64),DEADLINE);
        var state=switch(fixture){case "CHAT"->StatusProjection.State.CHAT;case "WAITING"->StatusProjection.State.WAITING;case "DENIED"->StatusProjection.State.DENIED;case "CLARIFICATION"->StatusProjection.State.CLARIFICATION;case "VERIFIED_COMPLETION","UNVERIFIED_COMPLETION"->StatusProjection.State.COMPLETED;default->StatusProjection.State.CHECKING;};
        return new StatusProjection.Request(key,audience,message,state==StatusProjection.State.CHAT?null:id("synthetic-question:"+player.getUUID()),state==StatusProjection.State.CHAT?null:activity,state,1,generation,clock(),Math.min(DEADLINE,clock()+120000),false);
    }
    private static StatusProjection.Authority authority(StatusProjection.Request r,String fixture,ServerPlayer player) {
        boolean a=player.getUUID().equals(id("OfflinePlayer:CPlayerA"));
        return new StatusProjection.Authority(){
            public StatusProjection.Evidence read(StatusProjection.Request ignored){return new StatusProjection.Evidence(r,!fixture.equals("DENIED"),r.nowMillis(),List.of(new StatusProjection.Material(a?"oak planks":"wheat",a?2:3)),fixture.equals("DENIED")?"Private secret chest":a?"Ada's chest":"Bob's barrel",r.activity()==null?null:new StatusProjection.TerminalReference(r.activity(),id("synthetic-terminal:"+player.getUUID())));}
            public boolean terminalAuthenticated(StatusProjection.Request wanted,StatusProjection.TerminalReference terminal){return fixture.equals("VERIFIED_COMPLETION")&&wanted.equals(r)&&wanted.activity().equals(terminal.activity());}
        };
    }
    private static void sendView(ServerPlayer player,String caseId,InteractionPresenter.View view,String reason){send(player,Map.of("schema","cc.c.client-output/1","kind","VIEW","case_id",caseId,"view",view.value(),"phrasing_reason",reason,"facts_tier","SYNTHETIC_CONTRACT"));}
    private static void send(ServerPlayer player,Map<String,Object> body){String json=InteractionCodec.canonical(body);ServerPlayNetworking.send(player,new ConversationPayloads.Response(json));event("sent",Map.of("native_player",player.getUUID().toString(),"payload",body,"guards",ConversationGuards.snapshot()));}
    public static synchronized void event(String kind,Object data) {
        try {Path dir=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR")).resolve("server/c-conversation");Files.createDirectories(dir);Files.writeString(dir.resolve("events.jsonl"),new com.google.gson.GsonBuilder().serializeNulls().create().toJson(Map.of("kind",kind,"time",System.currentTimeMillis(),"pid",ProcessHandle.current().pid(),"data",data))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(Exception failure){throw new IllegalStateException("native C server evidence",failure);}
    }
}
