package dev.aivillages.fabric;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.aivillages.core.kernel.BootstrapController;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Registered structured operator interface; no generated game commands are executed. */
final class KernelCommands {
    private final Supplier<KernelSession> sessions;
    private final java.util.function.Consumer<dev.aivillages.core.kernel.LanguageRequests.View> interpretations;
    private KernelCommands(Supplier<KernelSession> sessions,
                           java.util.function.Consumer<dev.aivillages.core.kernel.LanguageRequests.View> interpretations) {
        this.sessions = sessions;
        this.interpretations = interpretations;
    }
    @FunctionalInterface private interface Action {
        int run(CommandContext<CommandSourceStack> context, KernelSession kernel)
                throws CommandSyntaxException;
    }
    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        attach(root, AiVillages::kernel);
    }
    /** The real command tree can target an isolated fixture's production composition root. */
    static void attach(LiteralArgumentBuilder<CommandSourceStack> root, Supplier<KernelSession> sessions) {
        attach(root, sessions, view -> { });
    }
    /** Observes the ticket returned by the real command, without a second dispatch. */
    static void attach(LiteralArgumentBuilder<CommandSourceStack> root, Supplier<KernelSession> sessions,
                       java.util.function.Consumer<dev.aivillages.core.kernel.LanguageRequests.View> interpretations) {
        new KernelCommands(sessions, interpretations).attachCommands(root);
    }
    private void attachCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        var kernel = literal("kernel");
        kernel.then(literal("activities").executes(c -> safe(c, (ctx, session) -> {
            tell(ctx, "activities=" + session.activities(ctx.getSource().getPlayerOrException(), 0, 4)); return 1;
        })).then(argument("offset", IntegerArgumentType.integer(0,64)).executes(c -> safe(c, (ctx, session) -> {
            tell(ctx, "activities=" + session.activities(ctx.getSource().getPlayerOrException(),
                    IntegerArgumentType.getInteger(ctx,"offset"), 4)); return 1;
        }))));
        kernel.then(literal("activity").then(argument("root-job-id",StringArgumentType.word())
                .executes(c -> safe(c, (ctx, session) -> {
                    var v = session.activity(ctx.getSource().getPlayerOrException(), uuid(ctx,"root-job-id"));
                    tell(ctx, "activity="+v.root()+" goal="+v.goal()+" principal="+v.authorizingPrincipal()
                            +" villager="+v.responsible()+" generation="+v.executionGeneration()+" state="+v.state()
                            +" owner="+v.route()+" recoveryOwner="+v.recoveryOwner()+" process="+v.process()
                            +" blocker="+v.blocker()+" reason="+v.jobReason()+" cancellation="+v.cancellationIntent()
                            +" verifiedLegacyQuantity="+v.verifiedLegacyQuantity()+" progress="+v.progressEvidence()
                            +" physical="+v.physicalEvidence()); return 1;
                }))));
        kernel.then(literal("enroll")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(context -> safe(context, (c, session) -> {
                    var player = c.getSource().getPlayerOrException();
                    var selected = player.level().getEntitiesOfClass(Villager.class,
                            player.getBoundingBox().inflate(6),
                            villager -> villager.isAlive() && !villager.isBaby()
                                    && !AiVillages.controls(villager)
                                    && !session.enrolled(villager)
                                    && (AiVillages.session() == null || AiVillages.session()
                                            .agents().stream().noneMatch(agent -> agent.entityId
                                                    .equals(villager.getUUID().toString()))))
                            .stream().min(Comparator.comparingDouble(player::distanceToSqr))
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Stand within 6 blocks of an unclaimed adult villager"));
                    var actor = session.enroll(player, selected);
                    tell(c, "Kernel actor=" + actor.citizenId() + " entity=" + actor.entityId()
                            + " scope=" + session.scope(player.getUUID())
                            + " (persisting enrollment)");
                    return 1;
                })));
        String[] names = {"from-x", "from-y", "from-z", "to-x", "to-y", "to-z",
                "container-x", "container-y", "container-z"};
        ArgumentBuilder<CommandSourceStack, ?> coords = argument(names[8],
                IntegerArgumentType.integer()).executes(c -> safe(c, (ctx, session) -> {
            int[] at = new int[9];
            for (int index = 0; index < at.length; index++)
                at[index] = IntegerArgumentType.getInteger(ctx, names[index]);
            var result = session.harvest(ctx.getSource().getPlayerOrException(),
                    uuid(ctx, "actor-id"), IntegerArgumentType.getInteger(ctx, "amount"),
                    at[0], at[1], at[2], at[3], at[4], at[5], at[6], at[7], at[8]);
            tell(ctx, "run=" + result.id() + " accepted=" + result.accepted()
                    + (result.reason() == null ? "" : " reason=" + result.reason()));
            return result.accepted() ? 1 : 0;
        }));
        for (int index = names.length - 2; index >= 0; index--)
            coords = argument(names[index], IntegerArgumentType.integer()).then(coords);
        kernel.then(literal("harvest")
                .then(argument("actor-id", StringArgumentType.word())
                        .suggests(this::suggestCitizenIds)
                        .then(argument("amount", IntegerArgumentType.integer()).then(coords))));
        for(boolean birch:new boolean[]{false,true}) {
        ArgumentBuilder<CommandSourceStack, ?> oakCoords=argument(names[8],IntegerArgumentType.integer())
                .executes(c -> safe(c,(ctx,session) -> {
                    int[] at=new int[9]; for(int i=0;i<at.length;i++) at[i]=IntegerArgumentType.getInteger(ctx,names[i]);
                    var result=birch ? session.gatherBirch(ctx.getSource().getPlayerOrException(),uuid(ctx,"actor-id"),
                            IntegerArgumentType.getInteger(ctx,"amount"),at[0],at[1],at[2],at[3],at[4],at[5],at[6],at[7],at[8]) : session.gatherOak(ctx.getSource().getPlayerOrException(),uuid(ctx,"actor-id"),
                            IntegerArgumentType.getInteger(ctx,"amount"),at[0],at[1],at[2],at[3],at[4],at[5],at[6],at[7],at[8]);
                    tell(ctx,"run="+result.id()+" accepted="+result.accepted()+(result.reason()==null ? "" : " reason="+result.reason()));
                    return result.accepted() ? 1 : 0;
                }));
        for(int i=names.length-2;i>=0;i--) oakCoords=argument(names[i],IntegerArgumentType.integer()).then(oakCoords);
        kernel.then(literal(birch ? "gather-birch" : "gather-oak").then(argument("actor-id",StringArgumentType.word()).suggests(this::suggestCitizenIds)
                .then(argument("amount",IntegerArgumentType.integer(1,dev.aivillages.core.kernel.OakLogDelivery.MAX_AMOUNT)).then(oakCoords))));
        }
        for(String recipe:new String[]{"planks","birch-planks","sticks","tables"}) {
        String[] plankNames={"source-x","source-y","source-z","chest-x","chest-y","chest-z"};
        ArgumentBuilder<CommandSourceStack, ?> plankCoords=argument(plankNames[5],IntegerArgumentType.integer())
                .executes(c->safe(c,(ctx,session)->{
                    int[] at=new int[6];for(int i=0;i<at.length;i++)at[i]=IntegerArgumentType.getInteger(ctx,plankNames[i]);
                    var result=recipe.equals("birch-planks") ? session.makeBirchPlanks(ctx.getSource().getPlayerOrException(),uuid(ctx,"actor-id"),
                            IntegerArgumentType.getInteger(ctx,"amount"),at[0],at[1],at[2],at[3],at[4],at[5]) : recipe.equals("tables") ? session.makeTables(ctx.getSource().getPlayerOrException(),uuid(ctx,"actor-id"),
                            IntegerArgumentType.getInteger(ctx,"amount"),at[0],at[1],at[2],at[3],at[4],at[5])
                            : recipe.equals("sticks") ? session.makeSticks(ctx.getSource().getPlayerOrException(),uuid(ctx,"actor-id"),
                            IntegerArgumentType.getInteger(ctx,"amount"),at[0],at[1],at[2],at[3],at[4],at[5])
                            : session.makePlanks(ctx.getSource().getPlayerOrException(),uuid(ctx,"actor-id"),
                            IntegerArgumentType.getInteger(ctx,"amount"),at[0],at[1],at[2],at[3],at[4],at[5]);
                    tell(ctx,"run="+result.id()+" accepted="+result.accepted()+(result.reason()==null ? "" : " reason="+result.reason()));
                    return result.accepted() ? 1 : 0;
                }));
        for(int i=4;i>=0;i--)plankCoords=argument(plankNames[i],IntegerArgumentType.integer()).then(plankCoords);
        kernel.then(literal("make-"+recipe).then(argument("actor-id",StringArgumentType.word()).suggests(this::suggestCitizenIds)
                .then(argument("amount",IntegerArgumentType.integer(1,recipe.equals("tables") ? 4 : 64)).then(plankCoords))));
        }
        String[] birchNames={"source-x","source-y","source-z","dest-x","dest-y","dest-z","log-min-x","log-min-y","log-min-z","log-max-x","log-max-y","log-max-z"};
        ArgumentBuilder<CommandSourceStack,?> birchCoords=argument(birchNames[11],IntegerArgumentType.integer()).executes(c->safe(c,(ctx,session)->{
            int[] at=new int[12];for(int i=0;i<12;i++)at[i]=IntegerArgumentType.getInteger(ctx,birchNames[i]);
            var player=ctx.getSource().getPlayerOrException();String dimension=player.level().dimension().identifier().toString();
            var result=session.composeBirchPlanks(player,uuid(ctx,"actor-id"),IntegerArgumentType.getInteger(ctx,"amount"),
                    new dev.aivillages.core.kernel.Contracts.ContainerRef(dimension,at[0],at[1],at[2]),new dev.aivillages.core.kernel.Contracts.ContainerRef(dimension,at[3],at[4],at[5]),
                    new dev.aivillages.core.kernel.Contracts.Cuboid(dimension,at[6],at[7],at[8],at[9],at[10],at[11]));
            tell(ctx,"job="+result.id()+" accepted="+result.accepted()+(result.reason()==null ? "" : " reason="+result.reason()));return result.accepted()?1:0;
        }));
        for(int i=10;i>=0;i--)birchCoords=argument(birchNames[i],IntegerArgumentType.integer()).then(birchCoords);
        kernel.then(literal("compose-birch-planks").then(argument("actor-id",StringArgumentType.word()).suggests(this::suggestCitizenIds).then(argument("amount",IntegerArgumentType.integer(1,64)).then(birchCoords))));
        String[] axeNames={"source-x","source-y","source-z","dest-x","dest-y","dest-z","table-x","table-y","table-z"};
        ArgumentBuilder<CommandSourceStack,?> axeCoords=argument(axeNames[8],IntegerArgumentType.integer()).executes(c->safe(c,(ctx,session)->{
            int[] at=new int[9];for(int i=0;i<9;i++)at[i]=IntegerArgumentType.getInteger(ctx,axeNames[i]);
            var result=session.makeAxes(ctx.getSource().getPlayerOrException(),uuid(ctx,"actor-id"),IntegerArgumentType.getInteger(ctx,"amount"),at[0],at[1],at[2],at[3],at[4],at[5],at[6],at[7],at[8]);
            tell(ctx,"run="+result.id()+" accepted="+result.accepted()+(result.reason()==null ? "" : " reason="+result.reason()));return result.accepted() ? 1 : 0;
        }));
        for(int i=7;i>=0;i--)axeCoords=argument(axeNames[i],IntegerArgumentType.integer()).then(axeCoords);
        kernel.then(literal("make-axes").then(argument("actor-id",StringArgumentType.word()).suggests(this::suggestCitizenIds).then(argument("amount",IntegerArgumentType.integer(1,4)).then(axeCoords))));
        String[] composeNames={"source-x","source-y","source-z","dest-x","dest-y","dest-z","table-x","table-y","table-z","oak-min-x","oak-min-y","oak-min-z","oak-max-x","oak-max-y","oak-max-z"};
        ArgumentBuilder<CommandSourceStack,?> composeCoords=argument(composeNames[14],IntegerArgumentType.integer()).executes(c->safe(c,(ctx,session)->{
            int[] at=new int[15];for(int i=0;i<15;i++)at[i]=IntegerArgumentType.getInteger(ctx,composeNames[i]);var player=ctx.getSource().getPlayerOrException();String dimension=player.level().dimension().identifier().toString();
            var result=session.composeAxes(player,uuid(ctx,"actor-id"),IntegerArgumentType.getInteger(ctx,"amount"),
                    new dev.aivillages.core.kernel.Contracts.ContainerRef(dimension,at[0],at[1],at[2]),new dev.aivillages.core.kernel.Contracts.ContainerRef(dimension,at[3],at[4],at[5]),
                    new dev.aivillages.core.kernel.Contracts.FacilityRef(dimension,at[6],at[7],at[8]),new dev.aivillages.core.kernel.Contracts.Cuboid(dimension,at[9],at[10],at[11],at[12],at[13],at[14]));
            tell(ctx,"job="+result.id()+" accepted="+result.accepted()+(result.reason()==null?"":" reason="+result.reason()));return result.accepted()?1:0;
        }));
        for(int i=13;i>=0;i--)composeCoords=argument(composeNames[i],IntegerArgumentType.integer()).then(composeCoords);
        kernel.then(literal("compose-axes").then(argument("actor-id",StringArgumentType.word()).suggests(this::suggestCitizenIds).then(argument("amount",IntegerArgumentType.integer(1,4)).then(composeCoords))));
        String[] tableNames={"source-x","source-y","source-z","dest-x","dest-y","dest-z","oak-min-x","oak-min-y","oak-min-z","oak-max-x","oak-max-y","oak-max-z"};
        ArgumentBuilder<CommandSourceStack,?> tableCoords=argument(tableNames[11],IntegerArgumentType.integer()).executes(c->safe(c,(ctx,session)->{
            int[] at=new int[12];for(int i=0;i<12;i++)at[i]=IntegerArgumentType.getInteger(ctx,tableNames[i]);
            var player=ctx.getSource().getPlayerOrException();String dim=player.level().dimension().identifier().toString();
            var result=session.composeTables(player,uuid(ctx,"actor-id"),IntegerArgumentType.getInteger(ctx,"amount"),
                    new dev.aivillages.core.kernel.Contracts.ContainerRef(dim,at[0],at[1],at[2]),new dev.aivillages.core.kernel.Contracts.ContainerRef(dim,at[3],at[4],at[5]),
                    new dev.aivillages.core.kernel.Contracts.Cuboid(dim,at[6],at[7],at[8],at[9],at[10],at[11]));
            tell(ctx,"job="+result.id()+" accepted="+result.accepted()+(result.reason()==null?"":" reason="+result.reason()));return result.accepted()?1:0;
        }));
        for(int i=10;i>=0;i--)tableCoords=argument(tableNames[i],IntegerArgumentType.integer()).then(tableCoords);
        kernel.then(literal("compose-tables").then(argument("actor-id",StringArgumentType.word()).suggests(this::suggestCitizenIds).then(argument("amount",IntegerArgumentType.integer(1,4)).then(tableCoords))));
        String[] placeNames={"source-x","source-y","source-z","target-x","target-y","target-z"};
        ArgumentBuilder<CommandSourceStack,?> placeCoords=argument(placeNames[5],IntegerArgumentType.integer()).executes(c->safe(c,(ctx,session)->{
            int[] at=new int[6];for(int i=0;i<6;i++)at[i]=IntegerArgumentType.getInteger(ctx,placeNames[i]);
            var player=ctx.getSource().getPlayerOrException();String dim=player.level().dimension().identifier().toString();
            var result=session.placeTable(player,uuid(ctx,"actor-id"),new dev.aivillages.core.kernel.Contracts.ContainerRef(dim,at[0],at[1],at[2]),new dev.aivillages.core.kernel.Contracts.FacilityRef(dim,at[3],at[4],at[5]));
            tell(ctx,"run="+result.id()+" accepted="+result.accepted()+(result.reason()==null?"":" reason="+result.reason()));return result.accepted()?1:0;
        }));
        for(int i=4;i>=0;i--)placeCoords=argument(placeNames[i],IntegerArgumentType.integer()).then(placeCoords);
        kernel.then(literal("place-table").then(argument("actor-id",StringArgumentType.word()).suggests(this::suggestCitizenIds).then(placeCoords)));
        ArgumentBuilder<CommandSourceStack,?> provisionCoords=argument(composeNames[14],IntegerArgumentType.integer()).executes(c->safe(c,(ctx,session)->{
            int[] at=new int[15];for(int i=0;i<15;i++)at[i]=IntegerArgumentType.getInteger(ctx,composeNames[i]);
            var player=ctx.getSource().getPlayerOrException();String dim=player.level().dimension().identifier().toString();
            var result=session.provisionAxe(player,uuid(ctx,"actor-id"),new dev.aivillages.core.kernel.Contracts.ContainerRef(dim,at[0],at[1],at[2]),new dev.aivillages.core.kernel.Contracts.ContainerRef(dim,at[3],at[4],at[5]),
                    new dev.aivillages.core.kernel.Contracts.FacilityRef(dim,at[6],at[7],at[8]),new dev.aivillages.core.kernel.Contracts.Cuboid(dim,at[9],at[10],at[11],at[12],at[13],at[14]));
            tell(ctx,"job="+result.id()+" accepted="+result.accepted()+(result.reason()==null?"":" reason="+result.reason()));return result.accepted()?1:0;
        }));
        for(int i=13;i>=0;i--)provisionCoords=argument(composeNames[i],IntegerArgumentType.integer()).then(provisionCoords);
        kernel.then(literal("provision-axe").then(argument("actor-id",StringArgumentType.word()).suggests(this::suggestCitizenIds).then(provisionCoords)));
        ArgumentBuilder<CommandSourceStack, ?> queueCoords=argument(names[8],IntegerArgumentType.integer())
                .executes(c->safe(c,(ctx,session)->{
                    int[] at=new int[9];for(int i=0;i<at.length;i++)at[i]=IntegerArgumentType.getInteger(ctx,names[i]);
                    var player=ctx.getSource().getPlayerOrException();var dimension=player.level().dimension().identifier().toString();
                    var queued=session.queueHarvest(player,uuid(ctx,"actor-id"),IntegerArgumentType.getInteger(ctx,"amount"),
                            new dev.aivillages.core.kernel.Contracts.Cuboid(dimension,at[0],at[1],at[2],at[3],at[4],at[5]),
                            new dev.aivillages.core.kernel.Contracts.ContainerRef(dimension,at[6],at[7],at[8]));
                    tell(ctx,"job="+queued.id()+" accepted="+queued.accepted()+(queued.reason()==null?"":" reason="+queued.reason()));
                    return queued.accepted()?1:0;
                }));
        for(int i=names.length-2;i>=0;i--)queueCoords=argument(names[i],IntegerArgumentType.integer()).then(queueCoords);
        kernel.then(literal("queue").then(argument("actor-id",StringArgumentType.word()).suggests(this::suggestCitizenIds)
                .then(argument("amount",IntegerArgumentType.integer()).then(queueCoords))));
        kernel.then(literal("job-status").then(argument("job-id",StringArgumentType.word())
                .suggests((c,b)->suggestJobIds(c,b,false)).executes(c->safe(c,(ctx,session)->{
                    var job=session.queuedJob(ctx.getSource().getPlayerOrException(),uuid(ctx,"job-id"));
                    tell(ctx,"job="+job.id()+" state="+job.state()+" delivered="+job.fulfilled()+" reason="+job.reason());return 1;
                }))));
        kernel.then(literal("job-cancel").then(argument("job-id",StringArgumentType.word())
                .suggests((c,b)->suggestJobIds(c,b,true)).executes(c->safe(c,(ctx,session)->{
                    var change=session.cancelQueuedJob(ctx.getSource().getPlayerOrException(),uuid(ctx,"job-id"));
                    tell(ctx,"job="+uuid(ctx,"job-id")+" cancellation="+change.accepted()+" reason="+change.reason());return change.accepted()?1:0;
                }))));
        kernel.then(literal("status").then(argument("run-id", StringArgumentType.word())
                .suggests((context, builder) -> suggestRunOrTicketIds(context, builder, false))
                .executes(c -> safe(c, (ctx, session) -> {
                    var player = ctx.getSource().getPlayerOrException();
                    var id = uuid(ctx, "run-id");
                    var interpretation = session.interpretationStatus(player, id);
                    tell(ctx, interpretation == null ? describe(session.status(player, id)) : interpretation.describe());
                    return 1;
                }))));
        kernel.then(literal("cancel").then(argument("run-id", StringArgumentType.word())
                .suggests((context, builder) -> suggestRunOrTicketIds(context, builder, true))
                .executes(c -> safe(c, (ctx, session) -> {
                    var player = ctx.getSource().getPlayerOrException();
                    var id = uuid(ctx, "run-id");
                    var interpretation = session.cancelInterpretation(player, id);
                    tell(ctx, interpretation == null ? describe(session.cancel(player, id)) : interpretation.describe());
                    return 1;
                }))));
        kernel.then(literal("inference")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(argument("enabled", BoolArgumentType.bool())
                        .executes(c -> safe(c, (ctx, session) -> {
                            boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
                            session.inference(enabled);
                            tell(ctx, "Kernel inference admission=" + enabled);
                            return 1;
                        }))));
        kernel.then(literal("catalog").executes(c -> safe(c, (ctx, session) -> {
            tell(ctx, session.catalog(ctx.getSource().getPlayerOrException()));
            return 1;
        })));
        kernel.then(literal("name").then(argument("actor-id", StringArgumentType.word())
                .suggests(this::suggestCitizenIds)
                .then(argument("name", StringArgumentType.greedyString()).executes(c -> safe(c, (ctx, session) -> {
                    var changed = session.name(ctx.getSource().getPlayerOrException(), uuid(ctx, "actor-id"),
                            StringArgumentType.getString(ctx, "name"));
                    if (!changed.accepted()) {
                        ctx.getSource().sendFailure(Component.literal("Name: " + changed.reason()));
                        return 0;
                    }
                    tell(ctx, "citizen=" + changed.citizen().actor().citizenId() + " name="
                            + changed.citizen().displayName() + (changed.pending() ? " (saving name)" : ""));
                    return 1;
                })))));
        kernel.then(literal("citizens").executes(c -> safe(c, (ctx, session) -> {
            var page = session.citizens(ctx.getSource().getPlayerOrException());
            tell(ctx, "citizens=" + page.citizens().stream().map(address ->
                    (address.displayName() == null ? "unnamed" : address.displayName())
                            + " id=" + address.actor().citizenId() + " availability=" + address.availability())
                    .toList() + " more=" + page.more());
            return 1;
        })));
        kernel.then(literal("ask").then(argument("message", StringArgumentType.greedyString())
                .executes(c -> safe(c, (ctx, session) -> {
                    var view = session.ask(ctx.getSource().getPlayerOrException(),
                            StringArgumentType.getString(ctx, "message"));
                    interpretations.accept(view);
                    tell(ctx, view.describe());
                    return view.phase() == dev.aivillages.core.kernel.LanguageRequests.Phase.INTERPRETING ? 1 : 0;
                }))));
        kernel.then(literal("blueprint-store").then(argument("json",StringArgumentType.greedyString())
                .executes(c->safe(c,(ctx,session)->{
                    String json=StringArgumentType.getString(ctx,"json");
                    session.storeBlueprint(ctx.getSource().getPlayerOrException(),json,"explicit-command")
                            .whenComplete((v,f)->tell(ctx,f==null?"blueprint="+(v.ref()==null?"none":v.ref().sha256())+" status="+v.status():"Blueprint unavailable"));
                    return 1;
                }))));
        String[] materialCoords={"origin-x","origin-y","origin-z","site-min-x","site-min-y","site-min-z",
                "site-max-x","site-max-y","site-max-z","input-x","input-y","input-z","staging-x","staging-y","staging-z",
                "oak-min-x","oak-min-y","oak-min-z","oak-max-x","oak-max-y","oak-max-z"};
        for(boolean stage:new boolean[]{false,true}) {
            ArgumentBuilder<CommandSourceStack,?> end=argument(materialCoords[20],IntegerArgumentType.integer()).executes(c->safe(c,(ctx,session)->{
                var player=ctx.getSource().getPlayerOrException();var citizen=session.citizen(player,uuid(ctx,"actor-id"));
                String dimension=citizen.actor().dimension();int[] at=new int[21];for(int i=0;i<21;i++)at[i]=IntegerArgumentType.getInteger(ctx,materialCoords[i]);
                var ref=new dev.aivillages.core.kernel.Blueprint.Ref(1,StringArgumentType.getString(ctx,"blueprint-sha"));
                session.blueprintMaterials(player,citizen.actor().citizenId(),ref,IntegerArgumentType.getInteger(ctx,"rotation"),
                        new dev.aivillages.core.kernel.Contracts.FacilityRef(dimension,at[0],at[1],at[2]),
                        new dev.aivillages.core.kernel.Contracts.Cuboid(dimension,at[3],at[4],at[5],at[6],at[7],at[8]),
                        new dev.aivillages.core.kernel.Contracts.ContainerRef(dimension,at[9],at[10],at[11]),
                        new dev.aivillages.core.kernel.Contracts.ContainerRef(dimension,at[12],at[13],at[14]),
                        new dev.aivillages.core.kernel.Contracts.Cuboid(dimension,at[15],at[16],at[17],at[18],at[19],at[20]),stage)
                        .whenComplete((v,f)->tell(ctx,f==null?"mode="+(stage?"STAGE_MATERIALS":"PREFLIGHT")+" job="+v.job()+" accepted="+v.accepted()+" reason="+v.reason()+" report="+v.report():"Denied or unavailable blueprint request"));
                return 1;
            }));
            for(int i=19;i>=0;i--)end=argument(materialCoords[i],IntegerArgumentType.integer()).then(end);
            kernel.then(literal("blueprint-materials").then(literal(stage?"stage":"preflight").then(argument("actor-id",StringArgumentType.word())
                    .suggests(this::suggestCitizenIds).then(argument("blueprint-sha",StringArgumentType.word()).then(argument("rotation",IntegerArgumentType.integer(0,3)).then(end))))));
        }
        kernel.then(literal("blueprint-status").then(argument("job-id",StringArgumentType.word()).suggests((c,b)->suggestJobIds(c,b,false))
                .executes(c->safe(c,(ctx,session)->{
                    session.blueprintMaterialStatus(ctx.getSource().getPlayerOrException(),uuid(ctx,"job-id"))
                            .whenComplete((v,f)->tell(ctx,f==null?"blueprint-job="+v.job().id()+" state="+v.job().state()+" allocated="+v.allocated()+" currentlyAllocated="+v.currentlyAllocated()+" report="+v.report():"Denied or unavailable blueprint status"));return 1;
                }))));
        root.then(kernel);
    }
    private CompletableFuture<Suggestions> suggestJobIds(CommandContext<CommandSourceStack> context,SuggestionsBuilder builder,boolean cancellable){
        var session=sessions.get();if(session==null)return builder.buildFuture();
        try{return UuidSuggestions.suggest(builder,session.queuedJobIds(context.getSource().getPlayerOrException(),cancellable));}
        catch(CommandSyntaxException|IllegalStateException|SecurityException unavailable){return builder.buildFuture();}
    }
    private CompletableFuture<Suggestions> suggestCitizenIds(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        var session = sessions.get();
        if (session == null) return builder.buildFuture();
        try {
            return UuidSuggestions.suggest(builder,
                    session.citizenIds(context.getSource().getPlayerOrException()));
        } catch (CommandSyntaxException | IllegalStateException | SecurityException unavailable) {
            return builder.buildFuture();
        }
    }

    private CompletableFuture<Suggestions> suggestRunOrTicketIds(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder,
            boolean cancellableOnly) {
        var session = sessions.get();
        if (session == null) return builder.buildFuture();
        try {
            return UuidSuggestions.suggest(builder,
                    session.runOrTicketIds(context.getSource().getPlayerOrException(), cancellableOnly));
        } catch (CommandSyntaxException | IllegalStateException | SecurityException unavailable) {
            return builder.buildFuture();
        }
    }

    private static String describe(BootstrapController.View view) {
        return KernelRunStatus.describe(view);
    }
    private static UUID uuid(CommandContext<CommandSourceStack> context, String name) {
        try { return UUID.fromString(StringArgumentType.getString(context, name)); }
        catch (IllegalArgumentException bad) { throw new IllegalArgumentException("Invalid " + name); }
    }
    private int safe(CommandContext<CommandSourceStack> context, Action action)
            throws CommandSyntaxException {
        var session = sessions.get();
        if (session == null) {
            context.getSource().sendFailure(Component.literal("Kernel unavailable"));
            return 0;
        }
        try { return action.run(context, session); }
        catch (IllegalArgumentException | IllegalStateException | SecurityException denied) {
            context.getSource().sendFailure(Component.literal(denied.getMessage()));
            return 0;
        }
    }
    private static void tell(CommandContext<CommandSourceStack> context, String text) {
        context.getSource().sendSuccess(() -> Component.literal(text), false);
    }
}
