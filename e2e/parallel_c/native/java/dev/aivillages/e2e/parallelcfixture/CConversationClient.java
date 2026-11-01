package dev.aivillages.e2e.parallelcfixture;

import dev.aivillages.core.kernel.interaction.InteractionPresenter;
import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.util.*;

/** Actual Minecraft payload receiver, visible screen, native Button callbacks and raw observer. */
public final class CConversationClient implements ClientModInitializer {
    private static final Map<String,ConversationScreen> VIEWS=new LinkedHashMap<>();
    private static Path box;
    private static Map<String,Object> pressing;
    private String last="";
    private long tick;
    @Override public void onInitializeClient() {
        String player=System.getenv("COGNITIVECRAFT_E2E_PLAYER");
        if(!"chat-only".equals(System.getenv("COGNITIVECRAFT_C_FIXTURE_ALLOWED"))||player==null||!player.matches("CPlayer[AB]"))throw new IllegalStateException("isolated C client required");
        box=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR")).resolve("clients/"+player+"/c-conversation");
        event("client-start",Map.of("pid",ProcessHandle.current().pid(),"java",System.getProperty("java.version")));
        ClientPlayNetworking.registerGlobalReceiver(ConversationPayloads.Response.TYPE,(payload,context)->receive(context.client(),context.player().getUUID(),payload.json()));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }
    @SuppressWarnings("unchecked") private static void receive(Minecraft client,UUID nativePlayer,String json) {
        var body=(Map<String,Object>)InteractionCodec.parseJson(json);
        if(!"cc.c.client-output/1".equals(body.get("schema"))||!"SYNTHETIC_CONTRACT".equals(body.get("facts_tier")))throw new IllegalArgumentException("output envelope");
        event("payload-received",Map.of("native_player",nativePlayer.toString(),"payload",body));
        if("VIEW".equals(body.get("kind"))) {
            if(!body.keySet().equals(Set.of("schema","kind","case_id","view","phrasing_reason","facts_tier")))throw new IllegalArgumentException("output fields");
            var view=(Map<String,Object>)body.get("view");
            if(!view.keySet().equals(Set.of("schema","message_id","audience","scope","status","text","accessible_text","locale_key","language","question_id","activity","generation","revision","expires_ms","choices"))||!"cc.c.presentation/1".equals(view.get("schema"))||!nativePlayer.toString().equals(((Map<?,?>)view.get("audience")).get("player")))throw new IllegalArgumentException("native receiver correlation");
            var screen=new ConversationScreen(view,(String)body.get("case_id"));String key=view.get("message_id")+":"+view.get("generation");
            if(VIEWS.size()>=16&&!VIEWS.containsKey(key))VIEWS.remove(VIEWS.keySet().iterator().next());VIEWS.put(key,screen);
            client.player.sendSystemMessage(Component.literal((String)view.get("accessible_text")));
            client.gui.setScreen(screen);
        } else if(!"CALLBACK_ACK".equals(body.get("kind")))throw new IllegalArgumentException("output kind");
    }
    @SuppressWarnings("unchecked") private void tick(Minecraft client) {
        tick++;if(tick%2!=0||client.player==null||client.getConnection()==null||!client.getConnection().hasClientLoaded())return;
        try {
            Path request=box.resolve("request.json");if(!Files.isRegularFile(request)||Files.size(request)>8192)return;
            var input=(Map<String,Object>)InteractionCodec.parseJson(Files.readString(request));String id=(String)input.get("id");if(id.equals(last))return;last=id;
            String operation=(String)input.get("operation");
            if(operation.equals("CLICK")||operation.equals("RAW_CALLBACK")) {
                String key=input.get("message_id")+":"+input.get("generation");ConversationScreen screen=Objects.requireNonNull(VIEWS.get(key));
                if(operation.equals("CLICK")) {
                    InteractionPresenter.Action action=InteractionPresenter.Action.valueOf((String)input.get("action"));Button button=Objects.requireNonNull(screen.buttons.get(action));pressing=input;
                    try {button.onPress(new KeyEvent(257,257,0));}finally{pressing=null;}
                    event("native-button-pressed",Map.of("id",id,"case_id",input.get("case_id"),"action",action.name(),"message_id",input.get("message_id"),"generation",input.get("generation"),"native_player",client.player.getUUID().toString(),"native_input","KeyEvent:ENTER"));
                } else sendChoice(screen,InteractionPresenter.Action.valueOf((String)input.get("action")),input);
            } else {
                Map<String,Object> packet=new LinkedHashMap<>(input);packet.put("schema","cc.c.client-input/1");send(packet);
            }
        }catch(Exception failure){event("client-error",Map.of("class",failure.getClass().getSimpleName(),"tick",tick));}
    }
    private static void sendChoice(ConversationScreen screen,InteractionPresenter.Action action,Map<String,Object> control) {
        Map<String,Object> packet=new LinkedHashMap<>();packet.put("schema","cc.c.client-input/1");packet.put("operation","CALLBACK");packet.put("id",control==null?UUID.randomUUID().toString():control.get("id"));packet.put("case_id",control==null?screen.caseId:control.get("case_id"));packet.put("event_id",control==null?UUID.randomUUID().toString():control.get("event_id"));packet.put("message_id",screen.view.get("message_id"));packet.put("session",((Map<?,?>)screen.view.get("audience")).get("session"));packet.put("generation",screen.view.get("generation"));packet.put("action",action.name());send(packet);
    }
    private static void send(Map<String,Object> packet){ClientPlayNetworking.send(new ConversationPayloads.Request(InteractionCodec.canonical(packet)));event("payload-sent",Map.of("native_player",Minecraft.getInstance().player.getUUID().toString(),"payload",packet));}
    private static final class ConversationScreen extends Screen {
        final Map<String,Object> view;
        final String caseId;
        final Map<InteractionPresenter.Action,Button> buttons=new EnumMap<>(InteractionPresenter.Action.class);
        boolean rendered;
        ConversationScreen(Map<String,Object> view,String caseId){super(Component.literal((String)view.get("accessible_text")));this.view=view;this.caseId=caseId;}
        @Override protected void init(){
            buttons.clear();int y=110;
            for(Object value:(List<?>)view.get("choices")){
                Map<?,?> choice=(Map<?,?>)value;var action=InteractionPresenter.Action.valueOf((String)choice.get("action"));
                Button button=Button.builder(Component.literal((String)choice.get("label")),b->sendChoice(this,action,pressing)).bounds(20,y,180,20).build();
                buttons.put(action,button);addRenderableWidget(button);y+=24;
            }
        }
        @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta){
            super.extractRenderState(graphics,mouseX,mouseY,delta);
            graphics.textWithWordWrap(font,Component.literal((String)view.get("accessible_text")),20,20,Math.min(150,width-40),0xffffffff);
            if(!rendered){rendered=true;event("native-ui-rendered",Map.of("case_id",caseId,"message_id",view.get("message_id"),"generation",view.get("generation"),"text",view.get("text"),"accessible_text",view.get("accessible_text"),"native_player",Minecraft.getInstance().player.getUUID().toString(),"screen",getClass().getName()));}
        }
        @Override public boolean isPauseScreen(){return false;}
    }
    public static synchronized void event(String kind,Object data){
        try{Files.createDirectories(box);Files.writeString(box.resolve("events.jsonl"),new com.google.gson.GsonBuilder().serializeNulls().create().toJson(Map.of("kind",kind,"time",System.currentTimeMillis(),"pid",ProcessHandle.current().pid(),"data",data))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(Exception failure){throw new IllegalStateException("C client observer",failure);}
    }
}
