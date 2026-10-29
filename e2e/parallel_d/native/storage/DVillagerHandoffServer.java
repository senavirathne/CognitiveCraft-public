package dev.aivillages.e2e.paralleld;

import java.nio.file.*;
import java.util.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.network.chat.Component;
import dev.aivillages.core.kernel.logistics.*;
import dev.aivillages.core.kernel.logistics.RecipientDeliveryPolicy.Source;
import static dev.aivillages.core.kernel.logistics.TransferObservation.*;

/** Dedicated fixture initializer only: actual native menus over enrolled villagers' eight native slots. */
public final class DVillagerHandoffServer implements ModInitializer {
    static final List<String> MODES=List.of("EXISTING","STORAGE","DEATH","UNLOAD","RIVAL","MOVED","FULL");
    private DVillagerHandoffScene scene;private String mode,state="IDLE";private Path root;private long deadline;
    private final Map<String,DVillagerHandoffScene> scenes=new LinkedHashMap<>();
    public void onInitialize(){
        DTestAuthority.acquireTaggedWorld();
        CommandRegistrationCallback.EVENT.register((dispatcher,context,environment)->dispatcher.register(Commands.literal("d-handoff")
            .then(Commands.literal("prepare").then(Commands.argument("mode",StringArgumentType.word()).executes(this::prepare)))
            .then(Commands.literal("inspect").then(Commands.argument("role",StringArgumentType.word()).executes(this::inspect)))
            .then(Commands.literal("start").executes(this::start))
            .then(Commands.literal("execute").executes(this::execute))
            .then(Commands.literal("audit").executes(this::audit))));
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(!List.of("MOVING","MOVING_RECIPIENT","WAITING_RECIPIENT").contains(state))return;
            try{
                if(server.overworld().getGameTime()>deadline)throw new IllegalStateException("finite native handoff fixture deadline");
                if(state.equals("WAITING_RECIPIENT")){if(scene.receiver.onGround() && scene.receiver.tickCount>=2){scene.moveRecipient();if(!scene.recipientMoveAccepted)throw new IllegalStateException("native recipient walk did not admit");state="MOVING_RECIPIENT";}return;}
                if(state.equals("MOVING_RECIPIENT")){scene.frame("RECIPIENT_MOTION",null);if(scene.base.arena.actor.distanceTo(scene.receiver)>2.5){scene.receiver.getNavigation().stop();finish();}return;}
                var observed=scene.tick();if(observed.terminal()){if(observed.status()!=MobilityObservation.Status.ARRIVED)throw new IllegalStateException("native recipient route "+observed);finish();}
            }catch(RuntimeException error){fail(error);}
        });
    }
    private int prepare(CommandContext<CommandSourceStack> c){
        if(c.getSource().getEntity()!=null || !state.equals("IDLE"))throw new SecurityException("one fixture console preparation");
        mode=StringArgumentType.getString(c,"mode");int index=MODES.indexOf(mode);if(index<0 || scenes.containsKey(mode))throw new IllegalArgumentException("one registered independent scene");
        var server=c.getSource().getServer();var principal=server.getPlayerList().getPlayerByName("PlayerD_A");if(principal==null)throw new IllegalStateException("actual connected principal required");
        root=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR")).resolve("server/d09");state="PREPARING";BlockPos origin=new BlockPos((index%3)*24,201,(index/3)*28);
        DVillagerHandoffScene.open(server.overworld(),origin,principal,root.resolve(mode),"D09-CL01-"+mode,scene,s->{
            scene=s;scenes.put(mode,s);s.reset(mode,mode.equals("STORAGE")?Source.STORAGE:Source.EXISTING_CARRIER,mode.equals("STORAGE")?3:9,mode.equals("STORAGE"));state="READY";
            DComeScene.write(root.resolve("ready-"+mode+".json"),Map.of("sender",s.base.arena.actor.getUUID(),"recipient",s.receiver.getUUID(),"recipient_citizen",s.citizen,"principal",principal.getUUID(),"source_pos",List.of(s.base.sourcePos.getX(),s.base.sourcePos.getY(),s.base.sourcePos.getZ()),"sender_pos",List.of(s.base.arena.actor.getX(),s.base.arena.actor.getY(),s.base.arena.actor.getZ()),"recipient_pos",List.of(s.receiver.getX(),s.receiver.getY(),s.receiver.getZ()),"seed",s.level.getSeed()));
        });return 1;
    }
    private ServerPlayer owner(CommandContext<CommandSourceStack> c){if(!(c.getSource().getEntity() instanceof ServerPlayer player))throw new SecurityException("authenticated native player required");if(scene==null || player!=scene.base.arena.principal)throw new SecurityException("current authenticated carrier principal required");return player;}
    private int inspect(CommandContext<CommandSourceStack> c){
        if(scene==null)throw new IllegalStateException("native scene unavailable");if(!(c.getSource().getEntity() instanceof ServerPlayer player))throw new SecurityException("authenticated native observer required");String role=StringArgumentType.getString(c,"role");
        String expected=role.equals("sender")?"PlayerD_A":role.equals("recipient")?"PlayerD_B":"";if(expected.isEmpty() || player!=c.getSource().getServer().getPlayerList().getPlayerByName(expected))throw new SecurityException("explicit fixture inspection grant");
        var nativeInventory=role.equals("sender")?scene.base.arena.actor.getInventory():scene.receiver.getInventory();
        var view=new CompoundContainer(nativeInventory,new SimpleContainer(1){@Override public boolean canPlaceItem(int slot,ItemStack stack){return false;}});
        player.openMenu(new SimpleMenuProvider((id,inventory,observer)->new ChestMenu(MenuType.GENERIC_9x1,id,inventory,view,1),Component.literal("D09 "+role)));return 1;
    }
    private int start(CommandContext<CommandSourceStack> c){owner(c);if(!state.equals("READY"))throw new IllegalStateException("one fresh handoff admission");var result=scene.prepare();if(result.cargo()==null)throw new IllegalStateException("native cargo admission "+result.observation());state="HOLDING";DComeScene.write(root.resolve("accepted-"+mode+".json"),Map.of("request",scene.session.goal().request(),"in_flight",scene.session.inFlight(),"delivered",scene.session.delivered()));return 1;}
    private int execute(CommandContext<CommandSourceStack> c){
        owner(c);if(!state.equals("HOLDING"))throw new IllegalStateException("one explicit admitted execution");deadline=scene.level.getGameTime()+600;
        try{
            switch(mode){
                case "EXISTING"->{scene.renameAndNamesake();finish();}
                case "STORAGE"->{scene.travel();state="MOVING";}
                case "DEATH"->{scene.receiver.kill(scene.level);scene.frame("EXTERNAL_RECIPIENT_DEAD",null);finish();}
                case "UNLOAD"->{scene.receiver.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);scene.frame("EXTERNAL_RECIPIENT_UNLOADED",null);finish();}
                case "RIVAL"->{scene.exclusiveRecipient=false;scene.frame("EXTERNAL_RIVAL_CUSTODY",null);finish();}
                case "MOVED"->{state="WAITING_RECIPIENT";}
                case "FULL"->{DVillagerHandoffScene.fill(scene.receiver.getInventory(),Items.STONE,512);scene.frame("EXTERNAL_NATIVE_FULL",null);finish();}
                default->throw new IllegalStateException("unregistered handoff mode");
            }
        }catch(RuntimeException error){fail(error);}return 1;
    }
    private void finish(){
        var before=DVillagerHandoffGameTests.images(scene);var result=scene.handoff();boolean success=mode.equals("EXISTING") || mode.equals("STORAGE");Reason expected=switch(mode){case "EXISTING","STORAGE"->Reason.NONE;case "DEATH","UNLOAD"->Reason.STALE_ENDPOINT;case "RIVAL"->Reason.UNAUTHORIZED;case "MOVED"->Reason.OUT_OF_REACH;case "FULL"->Reason.CAPACITY;default->throw new IllegalStateException();};
        if(result.reason()!=expected || success && (result.status()!=Status.LIVE_DEPOSITED || scene.session.delivered()!=4) || !success && (result.status()!=Status.REFUSED || scene.session.delivered()!=0 || !before.equals(DVillagerHandoffGameTests.images(scene))))throw new IllegalStateException("native handoff terminal "+result);
        var replay=scene.adapter.handoff(scene.cargo);scene.frame("REPLAY_REFUSED",replay);if(replay.reason()!=Reason.UNKNOWN_CUSTODY)throw new IllegalStateException("one-use handoff cargo replay");
        DComeScene.write(root.resolve("terminal-"+mode+".json"),Map.of("observation",result,"request",scene.session.goal().request(),"delivered",scene.session.delivered(),"in_flight",scene.session.inFlight(),"phase",scene.session.phase(),"sender",scene.base.arena.actor.getUUID(),"recipient",scene.receiver.getUUID()));state="IDLE";
    }
    private int audit(CommandContext<CommandSourceStack> c){
        if(c.getSource().getEntity()!=null || !state.equals("IDLE") || !scenes.keySet().equals(new LinkedHashSet<>(MODES)))throw new SecurityException("complete fixture console custody audit");
        Map<String,Object> result=new TreeMap<>();for(var entry:scenes.entrySet()){var s=entry.getValue();s.frame("FINAL_CUSTODY",null);if(!s.loaded(s.base.arena.actor))throw new IllegalStateException("actual carrier unexpectedly unloaded before final audit");result.put(entry.getKey(),Map.of("sender",s.base.arena.actor.getUUID(),"sender_loaded",true,"sender_slots",s.base.stacks(s.base.arena.actor.getInventory()),"delivered",s.session.delivered(),"in_flight",s.session.inFlight(),"recipient_loaded",s.loaded(s.receiver)));}DComeScene.write(root.resolve("final-custody.json"),result);return 1;
    }
    private void fail(RuntimeException error){DComeScene.write(root.resolve("error.json"),Map.of("mode",mode,"state",state,"error",error.toString()));state="ERROR";}
}
