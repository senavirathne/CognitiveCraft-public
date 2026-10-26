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
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Isolated observer JAR only. Reads the real returned guard; never changes it or loads chunks. */
@Mixin(value=FabricRecipeTransactions.class,remap=false)
public abstract class BRecipeDiagnosticsMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private FabricGatewayWorld world;
    @Unique private static int parallelB$rows;
    @Unique private static final Gson parallelB$json=new Gson();
    @Unique private Map<String,Object> parallelB$target(ContainerRef ref){var pos=new BlockPos(ref.x(),ref.y(),ref.z());var out=new LinkedHashMap<String,Object>();boolean loaded=level.hasChunkAt(pos);out.put("reference",ref);out.put("loaded",loaded);
        if(loaded){out.put("block",level.getBlockState(pos).toString());var entity=level.getBlockEntity(pos);out.put("blockEntityIdentity",entity==null?"absent":entity.getClass().getName()+":"+System.identityHashCode(entity));if(entity instanceof Container c){out.put("fingerprint",FabricRecipeTransactions.fingerprint(c));out.put("slots",c.getContainerSize());}}return out;}
    @Unique private void parallelB$emit(String phase,Object result,Object details){String directory=System.getenv("COGNITIVECRAFT_E2E_DIR");if(directory==null)return;var root=Path.of(directory);if(!Files.exists(root.resolve("b-diagnostics-enabled")))return;if(++parallelB$rows>2048)throw new IllegalStateException("B diagnostic row bound exceeded");
        try{Files.writeString(root.resolve("server/b-guards.jsonl"),parallelB$json.toJson(Map.of("phase",phase,"gameTick",level.getGameTime(),"actualReturned",result==null?"ALLOW":result,"details",details,"readOnly",true,"saveConfirmed",false))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(java.io.IOException e){throw new IllegalStateException("Required B diagnostic evidence unavailable",e);}}
    @Inject(method="preflight(Ldev/aivillages/core/kernel/Contracts$ValidatedRequest;Z)Ldev/aivillages/core/kernel/RecipeTransaction$Preflight;",at=@At("RETURN"),remap=false)
    private void parallelB$preflight(ValidatedRequest request,boolean future,CallbackInfoReturnable<RecipeTransaction.Preflight> returned){var from=((ContainerValue)request.request().arguments().get("source")).value();var to=((ContainerValue)request.request().arguments().get("destination")).value();parallelB$emit("preflight",returned.getReturnValue(),Map.of("request",request,"futureFacility",future,"source",parallelB$target(from),"destination",parallelB$target(to)));}
    @Inject(method="guard",at=@At("RETURN"),remap=false)
    private void parallelB$guard(Villager actor,Container box,ContainerRef ref,String identity,String custody,CallbackInfoReturnable<Reason> returned){var out=new LinkedHashMap<String,Object>();out.put("target",parallelB$target(ref));out.put("expectedIdentity",identity);out.put("expectedCustody",custody);out.put("containerPresent",box!=null);if(box!=null)out.put("actualIdentity",FabricRecipeTransactions.identity(ref,box));if(actor!=null){out.put("actor",actor.getUUID().toString());out.put("pos",List.of(actor.getX(),actor.getY(),actor.getZ()));out.put("onGround",actor.onGround());out.put("controlled",GatewayControl.controls(actor));out.put("actualCustody",FabricRecipeTransactions.fingerprint(actor.getInventory()));out.put("actualRecipeReach",world.recipeReach(actor,new BlockPos(ref.x(),ref.y(),ref.z())));}parallelB$emit("final-guard",returned.getReturnValue(),out);}
}
