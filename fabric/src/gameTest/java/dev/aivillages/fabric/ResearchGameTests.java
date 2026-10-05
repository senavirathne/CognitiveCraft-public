package dev.aivillages.fabric;

import dev.aivillages.core.kernel.BoundedSkillExecutor;
import dev.aivillages.core.kernel.Budgets;
import dev.aivillages.core.kernel.CapabilityResolver;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.CropDelivery;
import dev.aivillages.core.kernel.CropFixtureRunner;
import dev.aivillages.core.kernel.GatewayPrimitives;
import dev.aivillages.core.kernel.Generation;
import dev.aivillages.core.kernel.Outcomes.ResearchStatus;
import dev.aivillages.core.kernel.ResearchAdmissionController;
import dev.aivillages.core.kernel.StrictJson;
import dev.aivillages.core.kernel.SurvivalGateway;
import dev.aivillages.core.kernel.VersionedSkillRepository;
import dev.aivillages.providers.LocalGenerationAdapter;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static dev.aivillages.core.kernel.Budgets.Kind;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;

/** A real gateway trial and durable publication; an explicit model tag also runs synthesis. */
public final class ResearchGameTests {
    private static final BlockPos CROP = new BlockPos(2, 1, 2);
    private static final BlockPos CHEST = new BlockPos(2, 1, 0);

    @GameTest(maxTicks = 3_000_000, padding = 16)
    public void researchAdmitsOnlyAfterPhysicalDelivery(GameTestHelper h) throws Exception {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++)
            h.setBlock(x, 0, z, Blocks.STONE);
        h.setBlock(CROP.below(), Blocks.FARMLAND.defaultBlockState()
                .setValue(FarmlandBlock.MOISTURE, 7));
        h.setBlock(CROP, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        h.setBlock(CHEST, Blocks.CHEST);
        Villager villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER, 3, 1, 2);
        villager.setNoAi(false);
        String dimension = h.getLevel().dimension().identifier().toString();
        BlockPos crop = h.absolutePos(CROP), chest = h.absolutePos(CHEST);
        ActorRef actor = new ActorRef(UUID.randomUUID(), villager.getUUID(), dimension);
        Cuboid source = new Cuboid(dimension, crop.getX(), crop.getY(), crop.getZ(),
                crop.getX(), crop.getY(), crop.getZ());
        TrustedContext owner = new TrustedContext(new PrincipalRef(UUID.randomUUID()),
                new ScopeRef(UUID.randomUUID(), UUID.randomUUID()));
        ObservationRef observation = new ObservationRef(UUID.randomUUID(), 1, dimension);
        ValidatedRequest bound = new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,
                Map.of("actor", new ActorValue(actor), "amount", new IntValue(1),
                        "source", new AreaValue(source), "destination", new ContainerValue(
                                new ContainerRef(dimension, chest.getX(), chest.getY(), chest.getZ())))),
                owner, observation);
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "research-test-storage");
            t.setDaemon(true);
            return t;
        });
        Path world = Path.of(System.getProperty("java.io.tmpdir"),
                "cc-research-gametest-" + UUID.randomUUID());
        Clock clock = Clock.systemUTC();
        CapabilityCatalog specs = id -> id.equals(CropDelivery.ID)
                ? Optional.of(CropDelivery.SPEC) : Optional.empty();
        ResearchAdmissionController.DecisionGuard guard =
                new ResearchAdmissionController.DecisionGuard();
        CompletableFuture<VersionedSkillRepository> opened = CompletableFuture.supplyAsync(() -> {
            try {
                return VersionedSkillRepository.open(world,
                        new VersionedSkillRepository.RuntimeSnapshot("minecraft-26.3", specs,
                                GatewayPrimitives.instance()),
                        VersionedSkillRepository.Limits.defaults(), guard,
                        TrustedContext::equals, VersionedSkillRepository.FaultInjector.none());
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }, worker);
        String modelTag = System.getenv("COGNITIVECRAFT_OLLAMA_MODEL");
        LocalGenerationAdapter model = modelTag == null || modelTag.isBlank() ? null
                : new LocalGenerationAdapter(new LocalGenerationAdapter.Config(
                        URI.create("http://127.0.0.1:11434/api/chat"), modelTag, true, 3_072));
        var holder = new Object() {
            ResearchAdmissionController.Attempt attempt;
            VersionedSkillRepository repository;
            CompletableFuture<Void> closing;
        };
        h.failIfEver(() -> {
            if (holder.attempt == null) return;
            var status = holder.attempt.status(owner);
            if (status.phase() == ResearchAdmissionController.Phase.TERMINAL
                    && status.outcome().status() != ResearchStatus.ADMITTED)
                h.fail("Research outcome: " + status.outcome() + " usage=" + status.usage()
                        + " effects=" + status.committedEffects()
                        + " receipts=" + status.receipts().size()
                        + " diagnostics=" + status.diagnostics());
        });
        h.succeedWhen(() -> {
            h.assertTrue(opened.isDone(), "Waiting for off-tick repository open");
            if (holder.attempt == null) {
                holder.repository = opened.join();
                var staging = new ResearchAdmissionController.TrialArtifacts(holder.repository);
                var grants = new ResearchAdmissionController.TrialGrants(clock);
                SurvivalGateway gateway = new SurvivalGateway(new FabricGatewayWorld(h.getLevel()),
                        (candidate, effect, context) -> candidate.equals(actor)
                                && context.equals(owner),
                        new SurvivalGateway.Limits(1, 64, 64, 16, 16, 1, 200, 32), clock);
                BoundedSkillExecutor executor = new BoundedSkillExecutor(staging, specs,
                        GatewayPrimitives.instance(), (candidate, ref, context) ->
                                candidate.equals(actor) && context.equals(owner),
                        grants, new BoundedSkillExecutor.ControlPolicy() {
                            @Override public boolean mayInspect(TrustedContext caller,
                                    TrustedContext selected, UUID run) { return caller.equals(owner); }
                            @Override public boolean mayCancel(TrustedContext caller,
                                    TrustedContext selected, UUID run) { return caller.equals(owner); }
                        }, gateway, gateway::releaseRun, clock,
                        () -> h.getLevel().getGameTime(),
                        new BoundedSkillExecutor.Settings(8, 16, 32, 64));
                var resolver = new CapabilityResolver.Engine(specs, GatewayPrimitives.instance(),
                        CapabilityResolver.exactCatalog(holder.repository), null,
                        CapabilityResolver.cropDeliverySupport(),
                        (candidate, context) -> candidate.equals(actor) && context.equals(owner),
                        (candidate, ref, context) -> context.equals(owner),
                        (candidate, effect, context) -> context.equals(owner),
                        (request, method, snapshot) -> null, () -> h.getLevel().getGameTime(),
                        CapabilityResolver.Limits.defaults());
                ResearchAdmissionController controller = new ResearchAdmissionController(specs,
                        GatewayPrimitives.instance(), holder.repository,
                        model == null ? (request, limits, usage) -> {
                            throw new AssertionError("Supplied candidate invoked model");
                        } : (request, limits, usage) -> {
                            Generation.Handle handle = model.generate(request, limits, usage);
                            handle.result().thenAccept(result -> System.out.println(
                                    "IMP-006 local model response: role=" + result.role()
                                            + " outcome=" + result.outcome()
                                            + " descriptor=" + result.descriptor()
                                            + " usage=" + result.usage()
                                            + " candidate=" + result.candidateIr()));
                            return handle;
                        }, new CropFixtureRunner(staging, specs, GatewayPrimitives.instance(),
                        (candidate, ref, context) -> candidate.equals(actor)
                                && context.equals(owner), worker, clock),
                        new ResearchAdmissionController.ExecutorTrialPort(executor),
                        staging, grants, ResearchAdmissionController.repositoryPublication(
                                holder.repository, worker), guard, resolver,
                        (candidate, context) -> candidate.equals(actor) && context.equals(owner),
                        (candidate, effect, context) -> context.equals(owner),
                        clock, worker, new ResearchAdmissionController.Settings(2, 8, 2_048));
                if (model == null) holder.attempt = controller.evaluateCandidate(bound,
                        fixtureIr(), limits(clock));
                else {
                    long tick = h.getLevel().getGameTime();
                    var snapshot = new ObservationSnapshot(observation, ObservationStatus.PRESENT,
                            "source:" + UUID.nameUUIDFromBytes(
                                    source.toString().getBytes(StandardCharsets.UTF_8)),
                            tick, 1, Map.of("mature_wheat", 1L, "unknown_cells", 0L));
                    holder.attempt = controller.startMissing(resolver.resolve(bound, snapshot),
                            bound, limits(clock));
                }
            }
            var status = holder.attempt.tick(owner);
            h.assertTrue(status.phase() == ResearchAdmissionController.Phase.TERMINAL,
                    "Research active: " + status.phase());
            h.assertTrue(status.outcome().status() == ResearchStatus.ADMITTED,
                    "Research outcome: " + status.outcome() + " usage=" + status.usage()
                            + " diagnostics=" + status.diagnostics());
            h.assertTrue(status.outcome().published()
                    && holder.repository.resolve(status.outcome().artifact()).usable(),
                    "Durable admitted artifact missing");
            h.assertTrue(status.committedEffects() >= 3 && status.receipts().size() >= 3,
                    "Trial did not produce custody receipts");
            Container container = (Container)h.getLevel().getBlockEntity(chest);
            h.assertTrue(container.countItem(Items.WHEAT) >= 1 && h.getBlockState(CROP).isAir(),
                    "Wheat did not physically move from crop through villager to chest");
            h.assertTrue(!AiVillages.gatewayControls(villager), "Gateway still owns villager");
            if (model != null) h.assertTrue(status.outcome().modelCalls() > 0,
                    "Configured local model was not used");
            if (holder.closing == null) {
                System.out.println("IMP-006 physical trial: model=" + (modelTag == null ? "disabled" : modelTag)
                        + " ref=" + status.outcome().artifact().sha256()
                        + " calls=" + status.outcome().modelCalls()
                        + " effects=" + status.committedEffects()
                        + " receipts=" + status.receipts().size());
                holder.closing = CompletableFuture.runAsync(() -> {
                    try { holder.repository.close(); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                    if (model != null) model.close();
                    worker.shutdown();
                }, worker);
            }
            h.assertTrue(holder.closing.isDone(), "Waiting for off-tick repository close");
            holder.closing.join();
        });
    }

    static Budgets.ResearchLimits limits(Clock clock) {
        long deadline = clock.millis() + 600_000;
        EnumMap<Kind, Long> total = new EnumMap<>(Kind.class);
        total.put(Kind.CALLS, 512L); total.put(Kind.REPAIRS, 1L);
        total.put(Kind.INPUT_BYTES, 40_000L); total.put(Kind.OUTPUT_BYTES, 20_000L);
        total.put(Kind.CANDIDATES, 2L); total.put(Kind.TRIALS, 1L);
        total.put(Kind.INSTRUCTIONS, 3_000L); total.put(Kind.OBSERVATIONS, 700L);
        total.put(Kind.TRAVEL_BLOCKS, 100L); total.put(Kind.ATTEMPTED_EFFECTS, 300L);
        total.put(Kind.COMMITTED_EFFECTS, 300L); total.put(Kind.ELAPSED_TICKS, 1_000L);
        var inference = new Budgets.InferenceLimits(new Budgets.Limits(Map.of(
                Kind.CALLS, 2L, Kind.REPAIRS, 1L, Kind.INPUT_BYTES, 40_000L,
                Kind.OUTPUT_BYTES, 20_000L), deadline), 16_384, 10_000);
        var trial = new Budgets.ExecutionLimits(new Budgets.Limits(Map.of(
                Kind.CALLS, 256L, Kind.INSTRUCTIONS, 768L, Kind.OBSERVATIONS, 256L,
                Kind.TRAVEL_BLOCKS, 32L, Kind.ATTEMPTED_EFFECTS, 64L,
                Kind.COMMITTED_EFFECTS, 64L, Kind.ELAPSED_TICKS, 600L), deadline), 15_000);
        return new Budgets.ResearchLimits(inference, trial, new Budgets.Limits(total, deadline));
    }
    private static Map<String, Object> call(Operation op, String name, Map<String, Object> args) {
        return Map.of("op", "call", "kind", "primitive", "id", op.signature().id(),
                "version", 1L, "fingerprint", op.signature().fingerprint(),
                "args", args, "into", name);
    }
    static String fixtureIr() {
        var actor = Map.<String,Object>of("actor", Map.of("param", "actor"));
        var source = Map.<String,Object>of("actor", Map.of("param", "actor"),
                "source", Map.of("param", "source"));
        var deposit = Map.<String,Object>of("actor", Map.of("param", "actor"),
                "destination", Map.of("param", "destination"), "amount", Map.of("int", 1L));
        var body = List.of(Map.of("op", "repeat", "count", Map.of("param", "amount"),
                "body", List.of(call(Operation.HARVEST_NEXT_WHEAT, "h", source),
                        Map.of("op", "repeat", "count", Map.of("int", 30L),
                                "body", List.of(call(Operation.OBSERVE_INVENTORY, "stock", actor))),
                        call(Operation.PICKUP_TRACKED_WHEAT, "p", actor),
                        call(Operation.TRANSFER_WHEAT, "d", deposit))),
                Map.of("op", "result", "value", Map.of("param", "amount")));
        return StrictJson.canonical(Map.of("schema", 1L, "capability", CropDelivery.ID.name(),
                "capabilityVersion", 1L, "dependencies", List.of(), "body", body));
    }

    /** Test-supplied admitted method for distant-target fixtures; never production seed knowledge. */
    static String movementFixtureIr() {
        var actor = Map.<String,Object>of("actor", Map.of("param", "actor"));
        var source = Map.<String,Object>of("actor", Map.of("param", "actor"),
                "source", Map.of("param", "source"));
        var destination = Map.<String,Object>of("actor", Map.of("param", "actor"),
                "destination", Map.of("param", "destination"));
        var deposit = Map.<String,Object>of("actor", Map.of("param", "actor"),
                "destination", Map.of("param", "destination"), "amount", Map.of("int", 1L));
        var body = List.of(Map.of("op", "repeat", "count", Map.of("param", "amount"),
                "body", List.of(call(Operation.MOVE_TO_SOURCE, "ms", source),
                        call(Operation.HARVEST_NEXT_WHEAT, "h", source),
                        Map.of("op", "repeat", "count", Map.of("int", 30L),
                                "body", List.of(call(Operation.OBSERVE_INVENTORY, "stock", actor))),
                        call(Operation.PICKUP_TRACKED_WHEAT, "p", actor),
                        call(Operation.MOVE_TO_DESTINATION, "md", destination),
                        call(Operation.TRANSFER_WHEAT, "d", deposit))),
                Map.of("op", "result", "value", Map.of("param", "amount")));
        return StrictJson.canonical(Map.of("schema", 1L, "capability", CropDelivery.ID.name(),
                "capabilityVersion", 1L, "dependencies", List.of(), "body", body));
    }
}
