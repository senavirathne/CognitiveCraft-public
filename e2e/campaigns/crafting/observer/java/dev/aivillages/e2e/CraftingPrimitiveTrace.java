package dev.aivillages.e2e;

import com.google.gson.Gson;
import java.util.Map;

/** Evidence only. A failed evidence serializer never changes a gameplay return value. */
public final class CraftingPrimitiveTrace {
    private static final Gson JSON = new Gson();
    private CraftingPrimitiveTrace() { }
    public static void emit(String kind, Map<String, ?> value) {
        try {
            System.out.println("CRAFTING_PRIMITIVE_TRACE " + JSON.toJson(Map.of(
                    "time", System.currentTimeMillis(), "kind", kind, "value", value)));
        } catch (RuntimeException failure) {
            System.out.println("CRAFTING_PRIMITIVE_TRACE_INCOMPLETE " + failure.getClass().getName());
        }
    }
}
