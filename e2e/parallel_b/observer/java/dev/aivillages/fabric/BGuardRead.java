package dev.aivillages.fabric;

import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;
import dev.aivillages.core.kernel.RecipeTransaction;
import dev.aivillages.core.kernel.Outcomes.Reason;
import static dev.aivillages.core.kernel.Contracts.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;

/** Test-only package access and read of B's existing private world reference. No writes/authority. */
public final class BGuardRead {
    private static int rows;
    private static final Gson JSON=new Gson();
    private static Map<String,Object> target(ServerLevel level,ContainerRef ref){var pos=new BlockPos(ref.x(),ref.y(),ref.z());var out=new LinkedHashMap<String,Object>();boolean loaded=level.hasChunkAt(pos);out.put("reference",ref);out.put("loaded",loaded);
        if(loaded){out.put("block",level.getBlockState(pos).toString());var entity=level.getBlockEntity(pos);out.put("blockEntityIdentity",entity==null?"absent":entity.getClass().getName()+":"+System.identityHashCode(entity));if(entity instanceof Container c){out.put("fingerprint",FabricRecipeTransactions.fingerprint(c));out.put("slots",c.getContainerSize());}}return out;}
    private static void emit(ServerLevel level,String phase,Object result,Object details){String directory=System.getenv("COGNITIVECRAFT_E2E_DIR");if(directory==null)return;var root=Path.of(directory);if(!Files.exists(root.resolve("b-diagnostics-enabled")))return;if(++rows>2048)throw new IllegalStateException("B diagnostic row bound exceeded");
        try{Files.writeString(root.resolve("server/b-guards.jsonl"),JSON.toJson(Map.of("phase",phase,"gameTick",level.getGameTime(),"actualReturned",result==null?"ALLOW":result,"details",details,"readOnly",true,"saveConfirmed",false))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(java.io.IOException e){throw new IllegalStateException("Required B diagnostic evidence unavailable",e);}}
    public static void preflight(ServerLevel level,ValidatedRequest request,boolean future,RecipeTransaction.Preflight result){var from=((ContainerValue)request.request().arguments().get("source")).value();var to=((ContainerValue)request.request().arguments().get("destination")).value();emit(level,"preflight",result,Map.of("request",request,"futureFacility",future,"source",target(level,from),"destination",target(level,to)));}
    public static void withdraw(ServerLevel level,ActorRef actor,ContainerRef source,RecipeTransaction.Preflight captured,RecipeTransaction.Mutation result){emit(level,"withdraw-return",result,Map.of("actor",actor,"source",target(level,source),"captured",captured));}
    public static void guard(ServerLevel level,Object transaction,Villager actor,Container box,ContainerRef ref,String identity,String custody,Reason result){var out=new LinkedHashMap<String,Object>();out.put("target",target(level,ref));out.put("expectedIdentity",identity);out.put("expectedCustody",custody);out.put("containerPresent",box!=null);if(box!=null)out.put("actualIdentity",FabricRecipeTransactions.identity(ref,box));if(actor!=null){out.put("actor",actor.getUUID().toString());out.put("pos",List.of(actor.getX(),actor.getY(),actor.getZ()));out.put("onGround",actor.onGround());out.put("controlled",GatewayControl.controls(actor));out.put("actualCustody",FabricRecipeTransactions.fingerprint(actor.getInventory()));
            try{var field=FabricRecipeTransactions.class.getDeclaredField("world");field.setAccessible(true);var world=(FabricGatewayWorld)field.get(transaction);out.put("actualRecipeReach",world.recipeReach(actor,new BlockPos(ref.x(),ref.y(),ref.z())));}catch(ReflectiveOperationException e){throw new IllegalStateException("B read-only guard probe incompatible",e);}}emit(level,"final-guard",result,out);}
}
