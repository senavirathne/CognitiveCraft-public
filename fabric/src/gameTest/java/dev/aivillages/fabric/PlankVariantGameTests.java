package dev.aivillages.fabric;

import dev.aivillages.core.kernel.RecipeProduction;
import dev.aivillages.core.kernel.RecipeTransaction;
import dev.aivillages.core.kernel.production.RecipeChoice;
import dev.aivillages.core.kernel.production.planning.PlanningModel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import static dev.aivillages.core.kernel.Contracts.*;

/** Checks installed native recipes with test authority. No connected client or Job claim. */
public final class PlankVariantGameTests {
    private static final Set<String> FAMILIES = Set.of("acacia", "bamboo", "cherry", "crimson",
            "dark_oak", "jungle", "mangrove", "pale_oak", "poplar", "spruce", "warped");

    @GameTest(maxTicks = 1200, padding = 16)
    public void installedVariantProfilesAndAllIngredientForms(GameTestHelper test) {
        for (int x = 0; x < 7; x++)
            for (int z = 0; z < 7; z++) test.setBlock(x, 0, z, Blocks.STONE);
        test.setBlock(2, 1, 2, Blocks.CHEST);
        test.setBlock(2, 1, 4, Blocks.CHEST);
        var villager = test.spawnWithNoFreeWill(EntityTypes.VILLAGER, 2, 1, 3);
        villager.setNoAi(false);
        var level = test.getLevel();
        BootstrapGameTests.forceFixtureChunks(test, test.absolutePos(BlockPos.ZERO));
        String dimension = level.dimension().identifier().toString();
        var sourcePos = test.absolutePos(new BlockPos(2, 1, 2));
        var destinationPos = test.absolutePos(new BlockPos(2, 1, 4));
        var source = new ContainerRef(dimension, sourcePos.getX(), sourcePos.getY(), sourcePos.getZ());
        var destination = new ContainerRef(dimension, destinationPos.getX(), destinationPos.getY(), destinationPos.getZ());
        var actor = new ActorRef(UUID.randomUUID(), villager.getUUID(), dimension);
        UUID principal = UUID.randomUUID(), run = UUID.randomUUID();
        var owner = new TrustedContext(new PrincipalRef(principal), new ScopeRef(UUID.randomUUID(), principal));
        var world = new FabricGatewayWorld(level);
        var transactions = new FabricRecipeTransactions(level, world);
        RecipeTransaction.ChoiceAuthority authority = (caller, worker, target, phase) -> owner.equals(caller)
                && actor.equals(worker) && (target.equals(source) || target.equals(destination));
        test.succeedWhen(() -> {
            test.assertTrue(villager.onGround() && world.loadedForDiscovery(sourcePos), "Native fixture not ready");
            test.assertTrue(world.acquire(actor, run), "Native villager control unavailable");
            try {
                var from = (Container)level.getBlockEntity(sourcePos);
                var to = (Container)level.getBlockEntity(destinationPos);
                int executed = 0;
                for (String family : new TreeSet<>(FAMILIES)) {
                    String outputId = "minecraft:" + family + "_planks";
                    Item output = item(outputId);
                    int yield = family.equals("bamboo") ? 2 : 4;
                    int demand = yield + 1;
                    var versionTwo = new CapabilityId("cognitivecraft:produce_" + family + "_planks", 2);
                    var versionOne = new CapabilityId(versionTwo.name(), 1);
                    var profile = transactions.describeChoice(versionTwo, new PlanningModel.Cost(2, 3, 10));
                    var alternatives = switch (family) {
                        case "bamboo" -> forms(family, "block");
                        case "crimson", "warped" -> forms(family, "stem", "hyphae");
                        default -> forms(family, "log", "wood");
                    };
                    test.assertTrue(profile.yield() == yield && profile.result().id().equals(outputId)
                            && profile.cells().size() == 1
                            && new TreeSet<>(profile.cells().getFirst().stream().map(RecipeChoice.ItemKey::id).toList()).equals(alternatives),
                            "Installed native ingredients differ " + family);
                    var args = Map.<String, Value>of("actor", new ActorValue(actor), "amount", new IntValue(demand),
                            "source", new ContainerValue(source), "destination", new ContainerValue(destination));
                    var observation = new ObservationRef(UUID.randomUUID(), level.getGameTime(), dimension);
                    var request = new ValidatedRequest(new CapabilityRequest(versionTwo, args), owner, observation);
                    var admission = new ValidatedRequest(new CapabilityRequest(versionOne, args), owner, observation);
                    from.clearContent();
                    to.clearContent();
                    villager.getInventory().clearContent();
                    from.setItem(26, new ItemStack(item(RecipeProduction.of(versionOne).input().id()), 2));
                    var captured = transactions.preflight(admission);
                    test.assertTrue(captured.reason() == null && captured.recipe().outputs() == yield
                            && captured.availableInputs() == 2, "Canonical admission preflight differs " + family);
                    for (String inputId : alternatives) {
                        from.clearContent();
                        to.clearContent();
                        villager.getInventory().clearContent();
                        var ingredient = item(inputId);
                        from.setItem(26, new ItemStack(ingredient, 2));
                        from.setItem(0, new ItemStack(Items.COBBLESTONE, 7));
                        to.setItem(5, new ItemStack(output, 2));
                        var choice = transactions.selectChoice(request, run, authority);
                        test.assertTrue(choice.resultCount() == yield && choice.exactInputs().size() == 1
                                && choice.exactInputs().keySet().iterator().next().id().equals(inputId)
                                && choice.batches().size() == 2, "Exact choice changed the native ingredient " + inputId);
                        for (int batch = 0; batch < choice.batches().size(); batch++) {
                            var context = transactions.prepareChoice(request, choice, batch, run, authority);
                            for (var phase : List.of(RecipeTransaction.ChoicePhase.WITHDRAW,
                                    RecipeTransaction.ChoicePhase.CRAFT, RecipeTransaction.ChoicePhase.DEPOSIT)) {
                                var result = transactions.executeChoice(context, phase);
                                var actual = result.observation();
                                test.assertTrue(actual.kind() == RecipeTransaction.ChoiceObservationKind.APPLIED
                                        && actual.reason() == null && !actual.saveConfirmed()
                                        && actual.choiceDigest().equals(choice.digest()), "Native host phase failed " + inputId + " " + phase);
                                context = result.continuation();
                            }
                        }
                        int delivered = to.countItem(output) - 2;
                        int surplus = villager.getInventory().countItem(output);
                        test.assertTrue(from.countItem(ingredient) == 0 && from.countItem(Items.COBBLESTONE) == 7
                                && delivered == demand && surplus == yield - 1, "Native stock conservation differs " + inputId);
                        executed++;
                        System.out.printf("I00_VARIANT_NATIVE_HOST material=%s output=%s consumed=2 produced=%d delivered=%d surplus=%d%n",
                                inputId, outputId, 2 * yield, delivered, surplus);
                    }
                }
                test.assertTrue(executed == 42, "Expected all 42 additional native ingredient forms");
            } finally {
                world.release(actor, run);
            }
        });
    }

    private static Set<String> forms(String family, String... suffixes) {
        var result = new TreeSet<String>();
        for (String suffix : suffixes) {
            result.add("minecraft:" + family + "_" + suffix);
            result.add("minecraft:stripped_" + family + "_" + suffix);
        }
        return result;
    }

    private static Item item(String id) {
        var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        if (item == Items.AIR) throw new IllegalArgumentException("Missing installed native item " + id);
        return item;
    }
}
