package dev.aivillages.e2e.paralleld;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.aivillages.core.kernel.logistics.*;
import static dev.aivillages.core.kernel.logistics.TransferObservation.*;
public final class DChestCourierServer implements ModInitializer {
 private DChestCourierScene scene;private String mode,state="IDLE";private final Set<String> prepared=new HashSet<>();private final Map<String,DChestCourierScene> scenes=new LinkedHashMap<>();private Path root;
 public void onInitialize(){DTestAuthority.acquireTaggedWorld();CommandRegistrationCallback.EVENT.register((dispatcher,context,env)->dispatcher.register(Commands.literal("d-courier")
  .then(Commands.literal("prepare").then(Commands.argument("mode",StringArgumentType.word()).executes(c->{if(c.getSource().getEntity()!=null || !state.equals("IDLE"))throw new SecurityException("one test console preparation");String name=StringArgumentType.getString(c,"mode");int index=List.of("SUCCESS","REVOKED","REPLACED").indexOf(name);if(index<0 || !prepared.add(name))throw new IllegalStateException("one explicit independent scene");mode=name;state="PREPARING";var server=c.getSource().getServer();var p=server.getPlayerList().getPlayerByName("PlayerD_A");if(p==null)throw new IllegalStateException("real connected principal");root=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR")).resolve("server/d08");DChestCourierScene.open(server.overworld(),new BlockPos(index*32,201,0),p,root.resolve(name),"D08-CL01-"+name,scene,s->{scene=s;scenes.put(name,s);scene.reset(name,Items.OAK_PLANKS,4,10,3,2);state="READY";DComeScene.write(root.resolve("ready-"+name+".json"),Map.of("actor",s.arena.actor.getUUID(),"principal",p.getUUID(),"source_pos",List.of(s.sourcePos.getX(),s.sourcePos.getY(),s.sourcePos.getZ()),"destination_pos",List.of(s.destPos.getX(),s.destPos.getY(),s.destPos.getZ()),"seed",s.level.getSeed()));});return 1;})))
  .then(Commands.literal("audit").executes(c->{if(c.getSource().getEntity()!=null || !state.equals("IDLE"))throw new SecurityException("test console audit");var out=new TreeMap<String,Object>();for(var e:scenes.entrySet()){e.getValue().frame("FINAL_CUSTODY",null);out.put(e.getKey(),Map.of("actor",e.getValue().arena.actor.getUUID(),"carrier",e.getValue().stacks(e.getValue().arena.actor.getInventory()),"delivered",e.getValue().session.delivered(),"in_flight",e.getValue().session.inFlight()));}DComeScene.write(root.resolve("final-custody.json"),out);return 1;}))
  .then(Commands.literal("run").executes(c->{if(!state.equals("READY") || c.getSource().getPlayerOrException()!=scene.arena.principal)throw new SecurityException("authenticated fresh request");var w=scene.withdraw();if(w.status()!=Status.LIVE_WITHDRAWN)throw new IllegalStateException("fixture withdrawal "+w);DComeScene.write(root.resolve("withdrawn-"+mode+".json"),w);if(mode.equals("SUCCESS")){state="RENAMING";scene.rename(()->{scene.travel();state="MOVING";});}else{scene.travel();state="MOVING";}return 1;}))));
  ServerTickEvents.END_SERVER_TICK.register(server->{if(!state.equals("MOVING"))return;try{var m=scene.tick();if(!m.terminal())return;if(m.status()!=MobilityObservation.Status.ARRIVED)throw new IllegalStateException("courier physical route "+m);state="AT_DESTINATION";
   if(mode.equals("REVOKED")){scene.revoke(()->finish());return;}
   if(mode.equals("REPLACED")){scene.frame("BEFORE_REPLACEMENT",null);scene.level.setBlockAndUpdate(scene.destPos,Blocks.AIR.defaultBlockState());scene.level.setBlockAndUpdate(scene.destPos,Blocks.CHEST.defaultBlockState());scene.frame("DESTINATION_REPLACED",null);}
   finish();
  }catch(RuntimeException error){DComeScene.write(root.resolve("error.json"),Map.of("error",error.toString(),"mode",mode));state="ERROR";}});
 }
 private void finish(){var result=scene.deposit();if(mode.equals("SUCCESS") && result.status()!=Status.LIVE_DEPOSITED || !mode.equals("SUCCESS") && (result.status()!=Status.REFUSED || scene.session.delivered()!=0 || scene.arena.actor.getInventory().getItem(scene.batch.slot()).getCount()!=4))throw new IllegalStateException("courier exact terminal "+result);DComeScene.write(root.resolve("terminal-"+mode+".json"),Map.of("observation",result,"delivered",scene.session.delivered(),"in_flight",scene.session.inFlight(),"phase",scene.session.phase(),"actor",scene.arena.actor.getUUID()));state="IDLE";}
}
