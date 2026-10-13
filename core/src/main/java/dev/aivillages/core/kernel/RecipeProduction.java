package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;

/** Registered immutable recipe data, shared by existing owners. Contains no program or inventory. */
public final class RecipeProduction {
    private RecipeProduction() { }
    public record Definition(CapabilitySpec spec,String recipe,ResourceDelivery.ItemIdentity input,
                             ResourceDelivery.ItemIdentity output,int inputPerBatch) {
        public int batches(long amount) {return OakPlankProduction.batches(amount);}
        public int inputs(long amount) {return Math.multiplyExact(batches(amount),inputPerBatch);}
        public String inputCount() {return inputPerBatch==1 ? "input_logs" : "input_planks";}
        public String missingCount() {return inputPerBatch==1 ? "missing_logs" : "missing_planks";}
        public ResourceLeases.Resource stock(UUID world,ContainerRef box) {
            return new ResourceLeases.Resource(world,ResourceLeases.Kind.STOCK,ResourceLeases.point(box),input.id(),-1);
        }
        public Prerequisites.Production relationship() {
            return new Prerequisites.Production(spec.id().name(),output.id(),spec.id(),recipe,4,
                    List.of(new Prerequisites.Ingredient(List.of(input.id()),inputPerBatch)));
        }
    }
    private static final Definition PLANKS=new Definition(OakPlankProduction.SPEC,OakPlankProduction.RECIPE,
            ResourceDelivery.OAK_LOG,ResourceDelivery.OAK_PLANKS,1);
    private static final Definition STICKS=new Definition(StickProduction.SPEC,StickProduction.RECIPE,
            ResourceDelivery.OAK_PLANKS,ResourceDelivery.STICK,2);
    public static boolean is(CapabilityId id) {return OakPlankProduction.ID.equals(id) || StickProduction.ID.equals(id);}
    public static Definition of(CapabilityId id) {
        if(OakPlankProduction.ID.equals(id))return PLANKS;
        if(StickProduction.ID.equals(id))return STICKS;
        throw new IllegalArgumentException("Unsupported production capability");
    }
    public static Definition recipe(String key) {
        if(OakPlankProduction.RECIPE.equals(key))return PLANKS;
        if(StickProduction.RECIPE.equals(key))return STICKS;
        throw new IllegalArgumentException("Unsupported static recipe");
    }
    public static boolean permits(CapabilityId id,Operation op) {
        if(!is(id))return false;
        return op==Operation.MOVE_TO_INPUT || op==Operation.MOVE_TO_DESTINATION
                || id.equals(StickProduction.ID) && op.stick()
                || id.equals(OakPlankProduction.ID) && op.plank();
    }
    public static List<Operation> operations(CapabilityId id) {
        return id.equals(StickProduction.ID) ? List.of(Operation.OBSERVE_STICK_INPUTS,Operation.MOVE_TO_INPUT,
                Operation.WITHDRAW_OAK_PLANKS,Operation.CRAFT_STICKS,Operation.MOVE_TO_DESTINATION,Operation.TRANSFER_STICKS)
                : List.of(Operation.OBSERVE_PLANK_INPUTS,Operation.MOVE_TO_INPUT,Operation.WITHDRAW_OAK_LOG,
                Operation.CRAFT_OAK_PLANKS,Operation.MOVE_TO_DESTINATION,Operation.TRANSFER_OAK_PLANKS);
    }
}
