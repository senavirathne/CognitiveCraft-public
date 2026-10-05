package dev.aivillages.core;
import com.google.gson.*;
import java.util.ArrayList;

/** Local structural, semantic and resource validation, independent of provider JSON mode. */
public final class PlanCodec {
    private PlanCodec() { }
    public static Plan decode(String text) {
        JsonObject o = Json.object(text, 24000);
        Json.keys(o, "id", "version", "goal", "maxTicks", "steps");
        String id = Json.string(o, "id", 64);
        if (!id.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid skill id");
        String goal = Json.string(o, "goal", 32);
        if (!goal.equals("FOOD_SUPPLY")) throw new IllegalArgumentException("Unsupported goal");
        int version = Json.integer(o, "version", 1, 1000), maxTicks = Json.integer(o, "maxTicks", 20, 12000);
        if (!o.get("steps").isJsonArray()) throw new IllegalArgumentException("steps must be array");
        JsonArray array = o.getAsJsonArray("steps");
        if (array.isEmpty() || array.size() > 24) throw new IllegalArgumentException("Expected 1–24 steps");
        var steps = new ArrayList<Plan.Step>(); int operations = 0; boolean crafts = false;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Invalid step");
            var s = element.getAsJsonObject(); Json.keys(s, "action", "count", "timeoutTicks", "when", "text");
            var action = Plan.Action.valueOf(Json.string(s, "action", 32));
            var when = Plan.Condition.valueOf(Json.string(s, "when", 32));
            int count = Json.integer(s, "count", 1, 64), timeout = Json.integer(s, "timeoutTicks", 1, 6000);
            String speech = Json.string(s, "text", 240);
            if (action != Plan.Action.SPEAK && !speech.isEmpty()) throw new IllegalArgumentException("Text only for speech");
            if ((action == Plan.Action.SPEAK || action == Plan.Action.WAIT || action == Plan.Action.GO_HOME) && count != 1)
                throw new IllegalArgumentException("Control actions require count=1");
            if (action == Plan.Action.SPEAK && (speech.isBlank() || speech.startsWith("/"))) throw new IllegalArgumentException("Invalid speech");
            crafts |= action == Plan.Action.CRAFT_BREAD; operations += count;
            steps.add(new Plan.Step(action, count, timeout, when, speech));
        }
        if (!crafts || operations > 256) throw new IllegalArgumentException("Must craft food within 256 operations");
        return new Plan(id, version, goal, maxTicks, steps);
    }
    public static Plan validate(Plan plan) { return decode(Json.GSON.toJson(plan)); }
    public static String schema() {
        return """
            {"type":"object","additionalProperties":false,"required":["id","version","goal","maxTicks","steps"],
            "properties":{"id":{"type":"string","pattern":"^[a-z][a-z0-9_]{0,63}$"},
            "version":{"type":"integer","minimum":1,"maximum":1000},"goal":{"type":"string","enum":["FOOD_SUPPLY"]},
            "maxTicks":{"type":"integer","minimum":20,"maximum":12000},"steps":{"type":"array","minItems":1,"maxItems":24,
            "items":{"type":"object","additionalProperties":false,"required":["action","count","timeoutTicks","when","text"],
            "properties":{"action":{"type":"string","enum":["HARVEST_WHEAT","PLANT_WHEAT","COLLECT_WHEAT","CRAFT_BREAD","DELIVER_BREAD","GO_HOME","SPEAK","WAIT"]},
            "count":{"type":"integer","minimum":1,"maximum":64},"timeoutTicks":{"type":"integer","minimum":1,"maximum":6000},
            "when":{"type":"string","enum":["ALWAYS","HAS_WHEAT","HAS_SEEDS","HAS_BREAD"]},"text":{"type":"string","maxLength":240}}}}}}
            """;
    }
}
