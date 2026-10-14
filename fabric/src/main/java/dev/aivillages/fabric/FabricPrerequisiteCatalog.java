package dev.aivillages.fabric;

import dev.aivillages.core.kernel.Prerequisites;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import java.util.*;

/** Exact installed recipe keys only, at most eight. Never searches all recipes or assembles an item. */
public final class FabricPrerequisiteCatalog {
    private FabricPrerequisiteCatalog() { }
    public static Prerequisites.Catalog capture(MinecraftServer server,List<String> selectedKeys) {
        if(!server.isSameThread())throw new IllegalStateException("Recipe capture game thread");
        if(selectedKeys.size()>8 || new HashSet<>(selectedKeys).size()!=selectedKeys.size())
            throw new IllegalArgumentException("At most eight exact recipe keys");
        var manager=server.getRecipeManager();var recipes=new ArrayList<Prerequisites.Recipe>();var unsupported=new HashSet<String>();
        boolean complete=true;
        for(String key:selectedKeys.stream().sorted().toList()) {
            if(key.length()>128)throw new IllegalArgumentException("Bounded recipe key");
            var installed=manager.byKey(ResourceKey.create(Registries.RECIPE,Identifier.parse(key)));
            if(installed.isEmpty()){complete=false;continue;}
            var recipe=installed.get().value();
            // Dynamic recipes and ingredient-dependent/custom remainders require an existing physical mapping.
            if(!(recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)){complete=false;continue;}
            try {
                var displays=recipe.display();
                if(displays.size()!=1)throw new IllegalArgumentException("Static recipe display");
                ItemStackTemplate result=switch(displays.getFirst().result()) {
                    case SlotDisplay.ItemStackSlotDisplay stack -> stack.stack();
                    case SlotDisplay.ItemSlotDisplay item -> new ItemStackTemplate(item.item(),1,net.minecraft.core.component.DataComponentPatch.EMPTY);
                    default -> throw new IllegalArgumentException("Dynamic recipe output");
                };
                if(result.count()<1 || !result.components().isEmpty())throw new IllegalArgumentException("Plain static output");
                var placement=recipe.placementInfo();var ingredients=placement.ingredients();var slots=placement.slotsToIngredientIndex();
                if(placement.isImpossibleToPlace() || ingredients.size()>8 || slots.size()>9)
                    throw new IllegalArgumentException("Bounded ordinary crafting layout");
                var occurrences=new TreeMap<Integer,Long>();
                for(int index:slots)if(index!=PlacementInfo.EMPTY_SLOT) {
                    if(index<0 || index>=ingredients.size())throw new IllegalArgumentException("Ingredient index");
                    occurrences.merge(index,1L,Math::addExact);
                }
                var inputs=new ArrayList<Prerequisites.Ingredient>();var remainders=new TreeMap<String,Long>();
                for(var occurrence:occurrences.entrySet()) {
                    var options=ingredients.get(occurrence.getKey()).items().limit(17).toList();
                    if(options.isEmpty() || options.size()>16)throw new IllegalArgumentException("Bounded installed alternatives");
                    var names=options.stream().map(item->item.unwrapKey().orElseThrow().identifier().toString()).toList();
                    inputs.add(new Prerequisites.Ingredient(names,occurrence.getValue()));
                    ItemStackTemplate remainder=options.getFirst().value().getCraftingRemainder();
                    for(var option:options)if(!Objects.equals(remainder,option.value().getCraftingRemainder()))
                        throw new IllegalArgumentException("Alternative-dependent remainder");
                    if(remainder!=null) {
                        if(!remainder.components().isEmpty())throw new IllegalArgumentException("Component remainder");
                        String name=remainder.item().unwrapKey().orElseThrow().identifier().toString();
                        remainders.merge(name,Math.multiplyExact(occurrence.getValue(),remainder.count()),Math::addExact);
                    }
                }
                String output=result.item().unwrapKey().orElseThrow().identifier().toString();
                recipes.add(new Prerequisites.Recipe(key,output,result.count(),inputs,remainders));unsupported.add(output);
            } catch(IllegalArgumentException | NoSuchElementException invalid) {complete=false;}
        }
        var productions=new ArrayList<Prerequisites.Production>();productions.add(Prerequisites.Production.cropDelivery());
        for(var recipe:recipes)if(recipe.id().equals(dev.aivillages.core.kernel.StickProduction.RECIPE)
                && recipe.output().equals("minecraft:stick") && recipe.outputPerBatch()==4 && recipe.remainders().isEmpty()
                && recipe.inputs().stream().allMatch(i -> i.alternatives().contains("minecraft:oak_planks"))
                && recipe.inputs().stream().mapToLong(Prerequisites.Ingredient::quantity).sum()==2) {
            productions.add(dev.aivillages.core.kernel.RecipeProduction.of(dev.aivillages.core.kernel.StickProduction.ID).relationship());
            unsupported.remove("minecraft:stick");
        }
        for(var recipe:recipes)if(recipe.id().equals(dev.aivillages.core.kernel.WoodenAxeProduction.RECIPE) && recipe.output().equals("minecraft:wooden_axe")
                && recipe.outputPerBatch()==1 && recipe.remainders().isEmpty()
                && recipe.inputs().stream().filter(i->i.alternatives().contains("minecraft:oak_planks")).mapToLong(Prerequisites.Ingredient::quantity).sum()==3
                && recipe.inputs().stream().filter(i->i.alternatives().equals(List.of("minecraft:stick"))).mapToLong(Prerequisites.Ingredient::quantity).sum()==2) {
            productions.add(dev.aivillages.core.kernel.RecipeProduction.of(dev.aivillages.core.kernel.WoodenAxeProduction.ID).relationship());unsupported.remove("minecraft:wooden_axe");
        }
        // Mapping is declarative; admission and current runtime owners still gate any execution.
        return new Prerequisites.Catalog(Integer.toUnsignedLong(System.identityHashCode(manager)),recipes,
                productions,unsupported,complete);
    }
}
