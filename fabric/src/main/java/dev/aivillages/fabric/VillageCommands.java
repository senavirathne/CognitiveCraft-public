package dev.aivillages.fabric;

import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.aivillages.core.WorldData;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.npc.villager.Villager;
import java.util.*;
import static net.minecraft.commands.Commands.*;

public final class VillageCommands {
    private VillageCommands(){ }
    @FunctionalInterface private interface Action {int run(CommandContext<CommandSourceStack> context,VillageSession session) throws CommandSyntaxException;}
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
            var root=literal("aivillage").executes(c->safe(c,(ctx,s)->{tell(ctx,"AI Villages: enroll, list, status, home, food, teach, auto, say, village, stop, memory, release, providers. Use tab completion.");return 1;}));
            root.then(literal("enroll").requires(src->src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(argument("name",StringArgumentType.word()).executes(c->safe(c,(ctx,s)->{
                    var p=ctx.getSource().getPlayerOrException();var v=p.level().getEntitiesOfClass(Villager.class,p.getBoundingBox().inflate(6),e->e.isAlive() && !e.isBaby())
                        .stream().min(Comparator.comparingDouble(p::distanceToSqr)).orElseThrow(()->new IllegalArgumentException("Stand within 6 blocks of an adult villager."));
                    var a=s.enroll(p,v,StringArgumentType.getString(ctx,"name"));tell(ctx,"Enrolled "+a.name+". Set a work area with /aivillage home "+a.name+" <x y z>.");return 1;
                }))));
            root.then(literal("list").executes(c->safe(c,(ctx,s)->{var p=ctx.getSource().getPlayerOrException();for(var a:s.agents())if(s.canControl(p,a))tell(ctx,s.status(a));return 1;})));
            root.then(literal("providers").requires(src->src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(c->safe(c,(ctx,s)->{tell(ctx,"Reusable skills: "+s.skillCount());if(s.providerStatus().isEmpty())tell(ctx,"No LLM providers enabled. Deterministic farming is available.");else s.providerStatus().forEach(t->tell(ctx,t));return 1;})));
            root.then(literal("village").executes(c->safe(c,(ctx,s)->{int n=s.villageFood(ctx.getSource().getPlayerOrException());tell(ctx,"Shared food goal assigned to "+n+" villagers.");return n;})));
            for(String command:List.of("food","teach","stop","status","memory","release"))
                root.then(literal(command).then(named().executes(c->safe(c,(ctx,s)->{
                    var a=owned(ctx,s);
                    switch(command){case "food"->s.food(a,false);case "teach"->s.food(a,true);case "stop"->{s.autonomous(a,false);s.stop(a,"player_stop");}
                        case "status"->tell(ctx,s.status(a));case "memory"->a.memories.stream().skip(Math.max(0,a.memories.size()-10)).forEach(m->tell(ctx,m));case "release"->s.release(a);default->throw new IllegalArgumentException();}
                    if(!command.equals("status") && !command.equals("memory"))tell(ctx,command+": "+a.name);return 1;
                }))));
            root.then(literal("home").then(named().then(argument("position",BlockPosArgument.blockPos()).executes(c->safe(c,(ctx,s)->{
                var a=owned(ctx,s);s.home(a,ctx.getSource().getLevel(),BlockPosArgument.getLoadedBlockPos(ctx,"position"));tell(ctx,"Work area set. A barrel or chest at home stores bread.");return 1;
            })))));
            root.then(literal("auto").then(named().then(argument("enabled",BoolArgumentType.bool()).executes(c->safe(c,(ctx,s)->{
                var a=owned(ctx,s);s.autonomous(a,BoolArgumentType.getBool(ctx,"enabled"));tell(ctx,s.status(a));return 1;
            })))));
            root.then(literal("say").then(named().then(argument("message",StringArgumentType.greedyString()).executes(c->safe(c,(ctx,s)->{
                var a=owned(ctx,s);s.chat(ctx.getSource().getPlayerOrException(),a,StringArgumentType.getString(ctx,"message"));return 1;
            })))));
            KernelCommands.attach(root);
            dispatcher.register(root);
        });
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack,String> named() {
        return argument("name",StringArgumentType.word()).suggests((ctx,b)->{
            var s=AiVillages.session();var p=ctx.getSource().getPlayer();if(s!=null && p!=null)for(var a:s.agents())if(s.canControl(p,a) && a.name.toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase()))b.suggest(a.name);return b.buildFuture();
        });
    }
    private static WorldData.Agent owned(CommandContext<CommandSourceStack> c,VillageSession s) throws CommandSyntaxException {var a=s.named(StringArgumentType.getString(c,"name"));s.authorize(c.getSource().getPlayerOrException(),a);return a;}
    private static int safe(CommandContext<CommandSourceStack> c,Action action) throws CommandSyntaxException {
        var s=AiVillages.session();if(s==null){c.getSource().sendFailure(Component.literal("AI Villages unavailable. Check the server log."));return 0;}
        try{return action.run(c,s);}catch(IllegalArgumentException ex){c.getSource().sendFailure(Component.literal(ex.getMessage()));return 0;}
    }
    private static void tell(CommandContext<CommandSourceStack> c,String t){c.getSource().sendSuccess(()->Component.literal(t),false);}
}
