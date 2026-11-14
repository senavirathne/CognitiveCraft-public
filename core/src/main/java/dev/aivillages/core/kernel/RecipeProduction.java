package dev.aivillages.core.kernel;

import java.util.*;
import dev.aivillages.core.kernel.production.BatchQuantities;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;

/** Registered immutable recipe data, shared by existing owners. Contains no program or inventory. */
public final class RecipeProduction {
    private RecipeProduction() { }
    public record Definition(CapabilitySpec spec,String recipe,ResourceDelivery.ItemIdentity input,
                             ResourceDelivery.ItemIdentity output,int inputPerBatch) {
        public boolean axe() {return spec.id().equals(WoodenAxeProduction.ID);}
        public boolean table() {return spec.id().equals(CraftingTableProduction.ID);}
        public int outputPerBatch() {return axe() || table() ? 1 : 4;}
        public int totalInputs() {return inputPerBatch+(axe() ? 2 : 0);}
        public int secondaryInputs(long amount) {return axe() ? WoodenAxeProduction.sticks(amount) : 0;}
        public int maximumAmount() {return axe() || table() ? 4 : 64;}
        public int maximumBatches() {return axe() || table() ? 4 : 16;}
        public BatchQuantities quantities(long demand,BatchQuantities.Role role) {
            return BatchQuantities.project(demand,outputPerBatch(),maximumAmount(),maximumBatches(),role);
        }
        public int batches(long amount) {return table() ? CraftingTableProduction.batches(amount) : axe() ? WoodenAxeProduction.batches(amount) : OakPlankProduction.batches(amount);}
        public int withdrawalUnits(ResourceDelivery.ItemIdentity item) {
            return input.equals(item) ? inputPerBatch : axe() && ResourceDelivery.STICK.equals(item) ? 2 : 0;
        }
        public ResourceLeases.Resource secondaryStock(UUID world,ContainerRef box) {
            if(!axe())throw new IllegalArgumentException("No second ingredient");
            return new ResourceLeases.Resource(world,ResourceLeases.Kind.STOCK,ResourceLeases.point(box),ResourceDelivery.STICK.id(),-1);
        }
        public int inputs(long amount) {return Math.multiplyExact(batches(amount),inputPerBatch);}
        public String inputCount() {return LogAcquisition.log(input) ? "input_logs" : "input_planks";}
        public String missingCount() {return LogAcquisition.log(input) ? "missing_logs" : "missing_planks";}
        public ResourceLeases.Resource stock(UUID world,ContainerRef box) {
            return new ResourceLeases.Resource(world,ResourceLeases.Kind.STOCK,ResourceLeases.point(box),input.id(),-1);
        }
        public Prerequisites.Production relationship() {
            return new Prerequisites.Production(spec.id().name(),output.id(),spec.id(),recipe,outputPerBatch(),
                    axe() ? List.of(new Prerequisites.Ingredient(List.of(input.id()),3),new Prerequisites.Ingredient(List.of(ResourceDelivery.STICK.id()),2)) : List.of(new Prerequisites.Ingredient(List.of(input.id()),inputPerBatch)));
        }
    }
    private static final Definition PLANKS=new Definition(OakPlankProduction.SPEC,OakPlankProduction.RECIPE,
            ResourceDelivery.OAK_LOG,ResourceDelivery.OAK_PLANKS,1);
    private static final Definition BIRCH_PLANKS=new Definition(BirchPlankProduction.SPEC,BirchPlankProduction.RECIPE,
            ResourceDelivery.BIRCH_LOG,ResourceDelivery.BIRCH_PLANKS,1);
    private static final Definition STICKS=new Definition(StickProduction.SPEC,StickProduction.RECIPE,
            ResourceDelivery.OAK_PLANKS,ResourceDelivery.STICK,2);
    private static final Definition AXES=new Definition(WoodenAxeProduction.SPEC,WoodenAxeProduction.RECIPE,
            ResourceDelivery.OAK_PLANKS,ResourceDelivery.WOODEN_AXE,3);
    private static final Definition TABLES=new Definition(CraftingTableProduction.SPEC,CraftingTableProduction.RECIPE,
            ResourceDelivery.OAK_PLANKS,ResourceDelivery.CRAFTING_TABLE,4);
    public static boolean is(CapabilityId id) {return BirchPlankProduction.ID.equals(id) || OakPlankProduction.ID.equals(id) || StickProduction.ID.equals(id) || WoodenAxeProduction.ID.equals(id) || CraftingTableProduction.ID.equals(id);}
    public static Definition of(CapabilityId id) {
        if(BirchPlankProduction.ID.equals(id))return BIRCH_PLANKS;
        if(OakPlankProduction.ID.equals(id))return PLANKS;
        if(StickProduction.ID.equals(id))return STICKS;
        if(WoodenAxeProduction.ID.equals(id))return AXES;
        if(CraftingTableProduction.ID.equals(id))return TABLES;
        throw new IllegalArgumentException("Unsupported production capability");
    }
    /** Version 2 is proposed only; this does not register a runtime capability or alter version 1. */
    public static Definition choiceDefinition(CapabilityId id) {
        if(id.version()!=2)throw new IllegalArgumentException("Concrete choice compatibility version 2 required");
        return of(new CapabilityId(id.name(),1));
    }
    public static Definition recipe(String key) {
        if(BirchPlankProduction.RECIPE.equals(key))return BIRCH_PLANKS;
        if(OakPlankProduction.RECIPE.equals(key))return PLANKS;
        if(StickProduction.RECIPE.equals(key))return STICKS;
        if(WoodenAxeProduction.RECIPE.equals(key))return AXES;
        if(CraftingTableProduction.RECIPE.equals(key))return TABLES;
        throw new IllegalArgumentException("Unsupported static recipe");
    }
    public static boolean permits(CapabilityId id,Operation op) {
        if(!is(id))return false;
        return op==Operation.MOVE_TO_INPUT || op==Operation.MOVE_TO_DESTINATION
                || id.equals(WoodenAxeProduction.ID) && op.axe()
                || id.equals(CraftingTableProduction.ID) && op.table()
                || id.equals(StickProduction.ID) && op.stick()
                || id.equals(OakPlankProduction.ID) && op.plank()
                || id.equals(BirchPlankProduction.ID) && op.birchPlank();
    }
    public static List<Operation> operations(CapabilityId id) {
        if(id.equals(BirchPlankProduction.ID))return List.of(Operation.OBSERVE_BIRCH_PLANK_INPUTS,Operation.MOVE_TO_INPUT,
                Operation.WITHDRAW_BIRCH_LOG,Operation.CRAFT_BIRCH_PLANKS,Operation.MOVE_TO_DESTINATION,Operation.TRANSFER_BIRCH_PLANKS);
        if(id.equals(CraftingTableProduction.ID))return List.of(Operation.OBSERVE_TABLE_INPUTS,Operation.MOVE_TO_INPUT,
                Operation.WITHDRAW_TABLE_PLANKS,Operation.CRAFT_CRAFTING_TABLE,Operation.MOVE_TO_DESTINATION,Operation.TRANSFER_CRAFTING_TABLES);
        if(id.equals(WoodenAxeProduction.ID))return List.of(Operation.OBSERVE_AXE_INPUTS,Operation.MOVE_TO_INPUT,
                Operation.WITHDRAW_AXE_INPUTS,Operation.MOVE_TO_TABLE,Operation.CRAFT_WOODEN_AXE,Operation.MOVE_TO_DESTINATION,Operation.TRANSFER_WOODEN_AXES);
        return id.equals(StickProduction.ID) ? List.of(Operation.OBSERVE_STICK_INPUTS,Operation.MOVE_TO_INPUT,
                Operation.WITHDRAW_OAK_PLANKS,Operation.CRAFT_STICKS,Operation.MOVE_TO_DESTINATION,Operation.TRANSFER_STICKS)
                : List.of(Operation.OBSERVE_PLANK_INPUTS,Operation.MOVE_TO_INPUT,Operation.WITHDRAW_OAK_LOG,
                Operation.CRAFT_OAK_PLANKS,Operation.MOVE_TO_DESTINATION,Operation.TRANSFER_OAK_PLANKS);
    }
}
