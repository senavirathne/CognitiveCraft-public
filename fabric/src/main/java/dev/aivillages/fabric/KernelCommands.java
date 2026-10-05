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
        root.then(kernel);
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
