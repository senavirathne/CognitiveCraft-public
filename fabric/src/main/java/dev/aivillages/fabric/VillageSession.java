package dev.aivillages.fabric;

import dev.aivillages.core.*;
import dev.aivillages.providers.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Per-world authority. Delayed model results are fenced by session, identity and revision. */
public final class VillageSession implements AutoCloseable {
    private record Active(FarmPort port,PlanRunner runner,String provider,long startTick) { }
    private record Ticket(long revision,long expiresAt,CompletableFuture<?> future) { }
    private final MinecraftServer server;
    public final ModConfig config;
    private final WorldData data; private final WorldStore store;
    private final HttpClient http=HttpLlmProvider.newClient();
    private final ProviderRouter router; private final Planner planner;
    private final Reservations reservations=new Reservations();
    private final Map<String,Active> active=new HashMap<>();
    private final Map<String,Ticket> pending=new HashMap<>();
    private final Map<String,CompletableFuture<?>> dialogues=new HashMap<>();
    private final Map<String,Long> nextCheck=new HashMap<>(),nextChat=new HashMap<>(),nextCommand=new HashMap<>();
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"ai-villages-save");t.setDaemon(true);return t;});
    private CompletableFuture<Void> save=CompletableFuture.completedFuture(null);
    private volatile boolean closed; private long tick;

    public VillageSession(MinecraftServer server,ModConfig config) throws IOException {
        this.server=server;this.config=config;
        store=new WorldStore(server.getWorldPath(LevelResource.ROOT).resolve("data/ai-villages/state.json"));data=store.load();
        var entries=new ArrayList<ProviderRouter.Entry>();
        for(var p:config.providers) {
            if(!p.enabled)continue;
            String key=p.apiKeyEnv.isBlank()?null:System.getenv(p.apiKeyEnv);
            if(!p.apiKeyEnv.isBlank() && (key==null || key.isBlank())){AiVillages.LOG.warn("Provider {} disabled: {} is not set",p.id,p.apiKeyEnv);continue;}
            entries.add(new ProviderRouter.Entry(p,new HttpLlmProvider(p,http,key)));
        }
        router=new ProviderRouter(entries,config.concurrentLlmRequests,data.usage,Clock.systemUTC());planner=new Planner(router);
        for(var a:data.agents.values()){a.revision++;nextCheck.put(a.id,(long)Math.floorMod(a.id.hashCode(),config.autonomousCheckTicks));}
    }
    public Collection<WorldData.Agent> agents(){return List.copyOf(data.agents.values());}
    public List<String> providerStatus(){return router.status();}
    public int skillCount(){return data.skills.size();}
    public WorldData.Agent named(String name) {
        return data.agents.values().stream().filter(a->a.name.equalsIgnoreCase(name) || a.id.equals(name)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Unknown villager. Use /aivillage list."));
    }
    public boolean canControl(ServerPlayer p,WorldData.Agent a){return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || a.owner.equals(p.getUUID().toString());}
    public void authorize(ServerPlayer p,WorldData.Agent a) {
        if(!canControl(p,a))throw new IllegalArgumentException("Only the owner or an operator can control this villager.");
        var v=find(a);if(v!=null && (v.level()!=p.level() || v.distanceToSqr(p)>4096))throw new IllegalArgumentException("Get within 64 blocks of the villager.");
    }
    public WorldData.Agent enroll(ServerPlayer p,Villager v,String name) {
        if(AiVillages.kernel()!=null && AiVillages.kernel().enrolled(v))throw new IllegalArgumentException("Villager is enrolled in the kernel.");
        if(!name.matches("[A-Za-z][A-Za-z0-9_]{0,23}"))throw new IllegalArgumentException("Use a unique name with letters, digits or underscores (max 24).");
        if(data.agents.size()>=config.maxAgents)throw new IllegalArgumentException("Configured villager limit reached.");
        if(data.agents.values().stream().anyMatch(a->a.name.equalsIgnoreCase(name) || a.entityId.equals(v.getUUID().toString())))throw new IllegalArgumentException("Name or villager already enrolled.");
        if(v.isBaby() || !v.isAlive())throw new IllegalArgumentException("Choose a living adult villager.");
        var a=new WorldData.Agent();a.id=UUID.randomUUID().toString();a.entityId=v.getUUID().toString();a.owner=p.getUUID().toString();a.name=name;
        a.home=place((ServerLevel)v.level(),v.blockPosition());
        a.profession=v.getVillagerData().profession().unwrapKey().map(k->k.identifier().toString()).orElse("minecraft:none");
        a.courage=0.25+Math.random()*0.5;a.sociability=0.25+Math.random()*0.5;a.remember("Joined the village as "+name);
        data.agents.put(a.id,a);nextCheck.put(a.id,tick+200);v.setCustomName(Component.literal(name));v.setPersistenceRequired();scheduleSave();return a;
    }
    public void home(WorldData.Agent a,ServerLevel level,BlockPos pos) {
        var v=find(a);if(v==null || v.level()!=level || v.distanceToSqr(Vec3.atCenterOf(pos))>1024 || !level.hasChunkAt(pos))throw new IllegalArgumentException("Home must be loaded and within 32 blocks of the villager.");
        stop(a,"home_changed");a.home=place(level,pos);a.lastFoodEvidence="";a.remember("Work area moved.");scheduleSave();
    }
    public void autonomous(WorldData.Agent a,boolean enabled){a.autoFood=enabled;nextCheck.put(a.id,tick+20);scheduleSave();}
    public String status(WorldData.Agent a) {
        var v=find(a);var run=active.get(a.id);
        String state=!a.alive?"dead":!a.form.equals("villager")?a.form:v==null?"unloaded":run!=null?"working "+run.runner().cursor()+"/"+run.runner().plan().steps().size():pending.containsKey(a.id)?"planning":"idle";
        return a.name+" | "+state+" | health="+(v==null?"unknown":v.getHealth()+"/"+v.getMaxHealth())
            +" | wheat="+(v==null?"unknown":v.getInventory().countItem(Items.WHEAT))+" | bread="+(v==null?"unknown":v.getInventory().countItem(Items.BREAD))+" | auto="+a.autoFood;
    }
    public void food(WorldData.Agent a,boolean forceLearning) {
        if(tick<nextCommand.getOrDefault(a.id,0L))throw new IllegalArgumentException("Wait a few seconds before requesting another plan.");
        nextCommand.put(a.id,tick+100);requestFood(List.of(a),forceLearning);
    }
    /** One request for a nearby group; each farmer works inside its own enrolled area. */
    public int villageFood(ServerPlayer p) {
        var group=data.agents.values().stream().filter(a->a.alive && canControl(p,a))
            .filter(a->{var v=find(a);return v!=null && v.level()==p.level() && v.distanceToSqr(p)<=2304;})
            .filter(a->!active.containsKey(a.id) && !pending.containsKey(a.id))
            .limit(Math.max(0,config.maxActivePlans-active.size()-pending.size())).toList();
        if(group.isEmpty())throw new IllegalArgumentException("No available enrolled villagers nearby.");
        requestFood(group,false);return group.size();
    }
    private void requestFood(List<WorldData.Agent> group,boolean forceLearning) {
        if(closed)return;
        Set<String> replacing=new HashSet<>();group.forEach(a->replacing.add(a.id));
        long existing=active.keySet().stream().filter(id->!replacing.contains(id)).count()+pending.keySet().stream().filter(id->!replacing.contains(id)).count();
        if(existing+group.size()>config.maxActivePlans)throw new IllegalArgumentException("Active plan limit reached.");
        for(var a:group) {
            var v=find(a);if(!a.alive || v==null)throw new IllegalArgumentException(a.name+" is not loaded and alive.");
            if(!v.level().dimension().identifier().toString().equals(a.home.dimension()))throw new IllegalArgumentException("Villager left its enrolled dimension.");
            var port=port(a,v);if(port.unsafe())throw new IllegalArgumentException(a.name+" is unsafe, sleeping or trading.");
            a.lastFoodEvidence=port.evidence();
        }
        for(var a:group)stop(a,"new_goal");
        var cached=forceLearning?Optional.<WorldData.Skill>empty():data.reusableFoodSkill();
        if(cached.isPresent()){for(var a:group)start(a,cached.get().plan,cached.get().provider+" (cached)");return;}
        var context=new LinkedHashMap<String,Object>();context.put("goal","Produce bread using real wheat and preserve crops by replanting.");context.put("workers",group.stream().map(this::context).toList());
        var future=planner.food(context);var revisions=new HashMap<String,Long>();
        for(var a:group){revisions.put(a.id,a.revision);pending.put(a.id,new Ticket(a.revision,tick+2400,future));say(a,"I'll work out a food plan.");}
        future.whenComplete((answer,error)->{
            if(closed)return;
            server.execute(()->{
                if(closed)return;
                for(var a:group) {
                    var ticket=pending.get(a.id);
                    if(ticket==null || ticket.future()!=future || a.revision!=revisions.get(a.id))continue;
                    pending.remove(a.id);if(!a.alive || tick>ticket.expiresAt() || find(a)==null)continue;
                    start(a,answer==null?Plan.food():answer.value(),answer==null?"deterministic":answer.provider()+"/"+answer.model());
                }
            });
        });
    }
    private void start(WorldData.Agent a,Plan plan,String source) {
        var v=find(a);if(v==null || !a.alive || !v.level().dimension().identifier().toString().equals(a.home.dimension()))return;
        if(AiVillages.kernel()!=null && AiVillages.kernel().enrolled(v))return;
        if(GatewayControl.controls(v))return;
        var port=port(a,v);if(port.unsafe()){a.remember("Plan postponed for safety, rest or trade.");return;}
        v.getBrain().stopAll((ServerLevel)v.level(),v);v.getNavigation().stop();
        active.put(a.id,new Active(port,new PlanRunner(plan,port,tick),source,tick));a.remember("Started "+plan.id()+" via "+source);
        say(a,source.contains("cached")?"I remember how to make bread. I'll use that plan.":"I'll harvest wheat, replant it and make bread.");
    }
    public void stop(WorldData.Agent a,String reason) {
        a.revision++;var ticket=pending.remove(a.id);
        if(ticket!=null && pending.values().stream().noneMatch(t->t.future()==ticket.future()))ticket.future().cancel(true);
        var chat=dialogues.remove(a.id);if(chat!=null)chat.cancel(true);
        var run=active.remove(a.id);if(run!=null)run.runner().cancel(reason);nextCheck.put(a.id,tick+config.replanCooldownTicks);
    }
    public void release(WorldData.Agent a){stop(a,"released");data.agents.remove(a.id);nextCheck.remove(a.id);nextChat.remove(a.id);nextCommand.remove(a.id);scheduleSave();}
    public void chat(ServerPlayer p,WorldData.Agent a,String text) {
        if(!a.alive || find(a)==null)throw new IllegalArgumentException("The villager must be loaded and alive.");
        if(text.length()>400 || text.isBlank() || text.chars().anyMatch(c->c<32 || c==167))throw new IllegalArgumentException("Message must be 1–400 plain characters.");
        if(tick<nextChat.getOrDefault(a.id,0L) || dialogues.containsKey(a.id))throw new IllegalArgumentException("Wait for this villager's reply.");
        nextChat.put(a.id,tick+100);a.interaction(p.getUUID().toString());a.remember("Player said: "+text);
        if(!config.allowCloudDialogue){say(a,localReply(a));return;}
        var context=context(a);context.put("playerMessage",text);long revision=a.revision;
        var future=planner.chat(context);dialogues.put(a.id,future);
        future.whenComplete((answer,error)->{
            if(closed)return;
            server.execute(()->{
                if(closed || dialogues.get(a.id)!=future)return;dialogues.remove(a.id);
                if(a.revision!=revision || !a.alive || find(a)==null)return;
                String reply=answer==null?localReply(a):answer.value();say(a,reply);a.remember("I said: "+reply);
            });
        });
    }
    private String localReply(WorldData.Agent a) {
        var run=active.get(a.id);
        if(run!=null)return "I'm working on our food supply. Current step: "+run.runner().plan().steps().get(Math.min(run.runner().cursor(),run.runner().plan().steps().size()-1)).action().name().toLowerCase(Locale.ROOT)+".";
        return pending.containsKey(a.id)?"I'm still planning.":"I'm available. Use /aivillage food "+a.name+" to ask me to make bread.";
    }
    private Map<String,Object> context(WorldData.Agent a) {
        var result=new LinkedHashMap<String,Object>();var v=find(a);
        result.put("name",a.name);result.put("status",status(a));result.put("personality",Map.of("courage",a.courage,"sociability",a.sociability));
        result.put("memories",a.memories.stream().skip(Math.max(0,a.memories.size()-3)).map(m->m.substring(0,Math.min(160,m.length()))).toList());
        result.put("workRadius",config.workRadius);result.put("profession",a.profession);
        if(v!=null)result.put("seeds",v.getInventory().countItem(Items.WHEAT_SEEDS));return result;
    }
    public void tick() {
        if(closed)return;tick++;if(tick%5!=0)return;
        for(var entry:new ArrayList<>(active.entrySet())) {
            var a=data.agents.get(entry.getKey());var run=entry.getValue();
            if(a==null){run.runner().cancel("released");active.remove(entry.getKey());continue;}
            if(run.port().villager().isRemoved() || find(a)!=run.port().villager()){stop(a,"unloaded_or_transformed");continue;}
            PlanRunner.State state;
            try{state=run.runner().tick(tick);}catch(RuntimeException ex){AiVillages.LOG.error("Action failed for {}",a.name,ex);run.runner().cancel("action_error");state=run.runner().state();}
            if(state!=PlanRunner.State.RUNNING) {
                active.remove(a.id);a.revision++;
                if(state!=PlanRunner.State.CANCELLED)data.record(run.runner().plan(),run.provider(),state==PlanRunner.State.SUCCEEDED,run.runner().reason(),tick-run.startTick());
                a.remember("Food plan ended: "+run.runner().reason());nextCheck.put(a.id,tick+config.replanCooldownTicks);
                if(state==PlanRunner.State.SUCCEEDED)say(a,"I made "+run.port().breadCrafted()+" bread. I remember this plan.");
                else if(state==PlanRunner.State.FAILED)say(a,"I couldn't finish: "+run.runner().reason()+". Please check my work area.");
            }
        }
        for(var a:data.agents.values()) {
            var ticket=pending.get(a.id);if(ticket!=null && tick>ticket.expiresAt())stop(a,"planning_expired");
            if(!a.alive){if(tick%20==0)tryRespawn(a);continue;}
            if(!a.autoFood || !a.form.equals("villager") || active.containsKey(a.id) || pending.containsKey(a.id) || tick<nextCheck.getOrDefault(a.id,0L))continue;
            nextCheck.put(a.id,tick+config.autonomousCheckTicks);var v=find(a);
            if(v==null || active.size()+pending.size()>=config.maxActivePlans)continue;
            var port=port(a,v);if(port.unsafe() || port.foodReserve()>=config.autoFoodThreshold)continue;
            // Retry unchanged shortages only on the slow Minecraft-day heartbeat.
            // Proven local skills can continue executing without another LLM call.
            if(data.reusableFoodSkill().isEmpty() && port.evidence().equals(a.lastFoodEvidence))continue;
            try{requestFood(List.of(a),false);}catch(IllegalArgumentException ignored){nextCheck.put(a.id,tick+config.replanCooldownTicks);}
        }
        if(tick%200==0)scheduleSave();
    }
    public boolean controls(Villager v) {
        return !closed && v.level() instanceof ServerLevel && active.values().stream().anyMatch(run->run.port().villager()==v && !run.port().unsafe());
    }
    public void converted(Mob previous,Mob replacement) {
        var a=data.agents.values().stream().filter(agent->agent.entityId.equals(previous.getUUID().toString())).findFirst().orElse(null);if(a==null)return;
        stop(a,"transformed");a.entityId=replacement.getUUID().toString();a.alive=true;a.respawnAtEpochMillis=0;a.form=replacement instanceof Villager?"villager":"transformed";
        a.remember(replacement instanceof Villager?"Returned to villager form.":"Transformed; work is paused.");
        replacement.setCustomName(Component.literal(a.name));replacement.setPersistenceRequired();scheduleSave();
    }
    public void died(LivingEntity entity) {
        var a=data.agents.values().stream().filter(agent->agent.entityId.equals(entity.getUUID().toString())).findFirst().orElse(null);if(a==null || !a.alive)return;
        stop(a,"death");a.alive=false;a.remember("I died.");
        if(config.deathMode.equals("respawn"))a.respawnAtEpochMillis=System.currentTimeMillis()+config.respawnDelaySeconds*1000L;
        WorldData.boundedAdd(data.villageMemory,a.name+" died.",64);
        for(var other:data.agents.values())if(other!=a && other.alive && other.home.dimension().equals(a.home.dimension()))other.remember(a.name+" died; their tasks are unassigned.");
        scheduleSave();
    }
    private void tryRespawn(WorldData.Agent a) {
        if(!config.deathMode.equals("respawn") || a.respawnAtEpochMillis==0 || System.currentTimeMillis()<a.respawnAtEpochMillis)return;
        var existing=find(a);if(existing!=null && existing.isAlive()){a.alive=true;a.respawnAtEpochMillis=0;return;}
        var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,Identifier.parse(a.home.dimension())));if(level==null)return;
        var center=new BlockPos(a.home.x(),a.home.y(),a.home.z());if(!level.hasChunkAt(center))return;
        for(var pos:BlockPos.betweenClosed(center.offset(-3,-1,-3),center.offset(3,2,3))) {
            if(!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()
                || !level.getBlockState(pos.below()).isCollisionShapeFullBlock(level,pos.below()))continue;
            var v=EntityTypes.VILLAGER.create(level,EntitySpawnReason.MOB_SUMMONED);if(v==null)return;
            v.setPos(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5);
            if(!level.noCollision(v) || !level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,v.getBoundingBox().inflate(8),e->e.isAlive()).isEmpty())continue;
            try{v.setVillagerData(v.getVillagerData().withProfession(level.registryAccess(),ResourceKey.create(Registries.VILLAGER_PROFESSION,Identifier.parse(a.profession))));}catch(RuntimeException ignored){ }
            v.setCustomName(Component.literal(a.name));v.setPersistenceRequired();if(!level.addFreshEntity(v))return;
            a.entityId=v.getUUID().toString();a.alive=true;a.form="villager";a.respawnAtEpochMillis=0;a.revision++;
            if(!config.preserveMemory){a.memories.clear();a.trust.clear();}
            a.remember("Returned to the village.");nextCheck.put(a.id,tick+200);scheduleSave();return;
        }
    }
    private FarmPort port(WorldData.Agent a,Villager v){return new FarmPort(v,a.home,config.workRadius,reservations,text->say(a,text));}
    public Villager find(WorldData.Agent a) {
        UUID id=UUID.fromString(a.entityId);for(var level:server.getAllLevels()){var e=level.getEntity(id);if(e instanceof Villager v && !v.isRemoved())return v;}return null;
    }
    private static WorldData.Place place(ServerLevel level,BlockPos p){return new WorldData.Place(level.dimension().identifier().toString(),p.getX(),p.getY(),p.getZ());}
    public void say(WorldData.Agent a,String text) {
        var v=find(a);if(v==null)return;for(var p:((ServerLevel)v.level()).players())if(p.distanceToSqr(v)<=1024)p.sendSystemMessage(Component.literal("["+a.name+"] "+text));
    }
    private void scheduleSave() {
        if(!save.isDone())return;data.usage=router.usageSnapshot();String snapshot=Json.GSON.toJson(data);
        save=CompletableFuture.runAsync(()->{try{store.saveJson(snapshot);}catch(IOException ex){AiVillages.LOG.error("Could not save AI Villages state",ex);}},writer);
    }
    @Override public void close() {
        closed=true;for(var run:active.values())run.runner().cancel("server_stopping");active.clear();dialogues.values().forEach(f->f.cancel(true));dialogues.clear();router.close();
        data.usage=router.usageSnapshot();
        try{save.get(10,TimeUnit.SECONDS);store.save(data);}catch(Exception ex){AiVillages.LOG.error("Final AI Villages save failed",ex);}
        writer.shutdown();http.shutdownNow();
    }
}
