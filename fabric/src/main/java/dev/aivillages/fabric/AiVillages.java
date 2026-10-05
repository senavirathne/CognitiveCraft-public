package dev.aivillages.fabric;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.world.entity.npc.villager.Villager;
import org.slf4j.*;

public final class AiVillages implements ModInitializer {
    public static final Logger LOG=LoggerFactory.getLogger("ai-villages");
    private static volatile VillageSession session;
    private static volatile KernelSession kernel;
    public static VillageSession session(){return session;}
    public static KernelSession kernel(){return kernel;}
    @Override public void onInitialize() {
        VillageCommands.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            GatewayControl.clearForServerLifecycle();
            try{session=new VillageSession(server,ModConfig.load(FabricLoader.getInstance().getConfigDir().resolve("ai-villages.json")));LOG.info("AI Villages started: {} persistent villagers",session.agents().size());}
            catch(Exception ex){LOG.error("AI Villages disabled: could not load config or world data",ex);}
            try{kernel=new KernelSession(server);}catch(Exception ex){LOG.error("CognitiveCraft kernel disabled",ex);}
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{var current=session;if(current!=null)current.tick();var active=kernel;if(active!=null)active.tick();});
        ServerLivingEntityEvents.AFTER_DEATH.register((entity,source)->{var current=session;if(current!=null)current.died(entity);});
        ServerLivingEntityEvents.AFTER_DEATH.register((entity,source)->{var active=kernel;if(active!=null)active.entityUnavailable(entity);});
        ServerLivingEntityEvents.MOB_CONVERSION.register((previous,replacement,params)->{var current=session;if(current!=null)current.converted(previous,replacement);});
        ServerLivingEntityEvents.MOB_CONVERSION.register((previous,replacement,params)->{var active=kernel;if(active!=null)active.entityReplacementUnresolved(previous);});
        ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{var active=kernel;if(active!=null)active.entityLoaded(entity);});
        ServerEntityEvents.ENTITY_UNLOAD.register((entity,world)->{var active=kernel;if(active!=null)active.entityUnloaded(entity);});
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{var active=kernel;kernel=null;if(active!=null)active.close();var current=session;session=null;if(current!=null)current.close();GatewayControl.clearForServerLifecycle();});
    }
    public static boolean controls(Villager v){var current=session;return GatewayControl.controls(v) || current!=null && current.controls(v);}
    public static boolean gatewayControls(Villager v){return GatewayControl.controls(v);}
}
