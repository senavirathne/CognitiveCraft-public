package dev.aivillages.providers;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.aivillages.core.kernel.LanguageReferenceClassifier;
import dev.aivillages.core.kernel.LanguageRequests;
import dev.aivillages.core.kernel.StrictJson;

import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import static dev.aivillages.core.kernel.WorldReferenceResolver.ReferenceState;

/** Bounded strict transport decoding; model output never contains execution authority or world facts. */
public final class NeedleWire {
    private NeedleWire() { }

    public record Prepared(String text, String tools, String from, String through, String destination,
                           String explicitCitizenId,
                           LanguageReferenceClassifier.Classification classification) { }

    private static final String COORD = "-?[0-9]+\\s*,\\s*-?[0-9]+\\s*,\\s*-?[0-9]+";

    /**
     * Concrete coordinate spans become disposable local tokens. Omission/unsupported provenance
     * is retained independently and never reconstructed from nullable model fields.
     */
    public static Prepared prepare(LanguageRequests.Input input) {
        LanguageReferenceClassifier.Classification classification =
                LanguageReferenceClassifier.classify(input.text(), input.references());
        String prompt = input.text(), from = null, through = null, destination = null;

        if (classification.source().state() == ReferenceState.EXPLICIT_CONCRETE) {
            from = classification.source().first();
            through = classification.source().second();
            var source = Pattern.compile("\\bfrom\\s+(" + COORD + ")\\s+through\\s+(" + COORD + ")",
                    Pattern.CASE_INSENSITIVE).matcher(prompt);
            if (source.find()) prompt = prompt.substring(0, source.start()) + "from farm" + prompt.substring(source.end());
        }
        if (classification.destination().state() == ReferenceState.EXPLICIT_CONCRETE) {
            destination = classification.destination().first();
            var target = Pattern.compile("\\b(deliver|deposit|put)\\b.{0,200}?\\b(?:at|to|into)\\s+"
                    + "(?:the\\s+)?(?:(?:chest|container|barrel)\\s+(?:at\\s+)?)?(" + COORD + ")",
                    Pattern.CASE_INSENSITIVE).matcher(prompt);
            if (target.find()) {
                String verb = target.group(1);
                prompt = prompt.substring(0, target.start()) + verb + " to chest" + prompt.substring(target.end());
            }
        }

        if (classification.source().state() == ReferenceState.EXPLICIT_NEAREST)
            prompt = prompt.replaceFirst("(?i)\\bfrom\\s+(?:the\\s+)?nearest\\s+(?:field|farm)\\b", "from farm");
        if (classification.destination().state() == ReferenceState.EXPLICIT_NEAREST)
            prompt = prompt.replaceFirst("(?i)\\b(?:to|into|in)\\s+(?:the\\s+)?nearest\\s+(?:chest|container|barrel)\\b", "to chest");

        String explicitId = null;
        String explicitCitizen = classification.actor().state() == ReferenceState.EXPLICIT_CONCRETE
                ? classification.actor().first() : null;
        if (explicitCitizen != null && explicitCitizen.matches("[0-9a-fA-F-]{36}")) {
            explicitId = explicitCitizen;
            prompt = Pattern.compile(Pattern.quote(explicitCitizen), Pattern.CASE_INSENSITIVE)
                    .matcher(prompt).replaceAll("selected");
            explicitCitizen = "selected";
        }

        if (classification.actor().state() == ReferenceState.EXPLICIT_NEAREST) {
            explicitCitizen = "selected";
            prompt = prompt.replaceFirst("(?i)^\\s*(?:the\\s+)?nearest\\s+(?:citizen|villager)\\b", "selected");
        }

        List<Map<String, Object>> tools = new ArrayList<>();
        if (classification.intent() != LanguageReferenceClassifier.Intent.NAME_CITIZEN) {
            Map<String, Object> harvestProperties = new LinkedHashMap<>();
            if (explicitCitizen != null)
                harvestProperties.put("citizen", ordered("type", "string", "enum", citizenLiterals(input, explicitCitizen),
                        "description", "Citizen who will harvest"));
            harvestProperties.put("amount", ordered("type", "integer",
                    "description", "How many wheat to harvest and deliver"));
            if (statedReference(classification.source().state()))
                harvestProperties.put("source", ordered("type", "string", "enum", List.of("farm"),
                        "description", "Farm to harvest wheat from"));
            if (statedReference(classification.destination().state()))
                harvestProperties.put("destination", ordered("type", "string", "enum", List.of("chest"),
                        "description", "Container to deliver wheat into"));
            tools.add(ordered("name", "harvest_wheat",
                    "description", "Harvest wheat from a source area and deliver it to a container. Copy only stated arguments.",
                    "parameters", ordered("type", "object", "properties", harvestProperties,
                            "required", List.copyOf(harvestProperties.keySet()))));
        }

        if (classification.intent() != LanguageReferenceClassifier.Intent.HARVEST_WHEAT) {
            Map<String, Object> namingProperties = new LinkedHashMap<>();
            if (explicitCitizen != null)
                namingProperties.put("citizen", ordered("type", "string", "enum", List.of(explicitCitizen),
                        "description", "Explicit citizen to rename"));
            namingProperties.put("name", ordered("type", "string",
                    "description", "New name to give the villager"));
            tools.add(ordered("name", "name_citizen",
                    "description", "Give a villager a name. Copy the name stated by the player.",
                    "parameters", ordered("type", "object", "properties", namingProperties,
                            "required", List.copyOf(namingProperties.keySet()))));
        }

        String toolJson = new Gson().toJson(tools);
        if (toolJson.getBytes(StandardCharsets.UTF_8).length > 8192)
            throw new IllegalArgumentException("Tool context limit");
        prompt = prompt.strip();
        if (!prompt.endsWith(".") && !prompt.endsWith("?") && !prompt.endsWith("!")) prompt += ".";
        return new Prepared(prompt, toolJson, from, through, destination, explicitId, classification);
    }

    private static boolean statedReference(ReferenceState state) {
        return state == ReferenceState.EXPLICIT_CONCRETE || state == ReferenceState.EXPLICIT_NEAREST;
    }

    private static List<String> citizenLiterals(LanguageRequests.Input input, String explicit) {
        if (explicit.equals("selected")) return List.of(explicit);
        var values = new LinkedHashSet<String>();
        for (String ref : input.references()) if (!ref.matches("[0-9a-fA-F-]{36}") && values.size() < 32) values.add(ref);
        if (!values.contains(explicit)) {
            if (values.size() == 32) values.remove(values.getLast());
            values.add(explicit);
        }
        return List.copyOf(values);
    }

    private static Map<String, Object> ordered(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) result.put((String)fields[i], fields[i + 1]);
        return result;
    }

    public static String tools(LanguageRequests.Input input) { return prepare(input).tools(); }

    public static LanguageRequests.Result decode(byte[] bytes, Prepared prepared) {
        if (bytes.length > LanguageRequests.MAX_OUTPUT_BYTES) return LanguageRequests.Result.invalid();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            JsonReader reader = new JsonReader(new StringReader(text)); reader.setLenient(false);
            Object value = read(reader, 0, new int[1]);
            if (reader.peek() != JsonToken.END_DOCUMENT || !(value instanceof Map<?, ?> root))
                return LanguageRequests.Result.invalid();
            if (!"call".equals(root.get("type")) && !"respond".equals(root.get("type")))
                return LanguageRequests.Result.invalid();
            if (!Boolean.TRUE.equals(root.get("success"))) return LanguageRequests.Result.unavailable();
            if (!(root.get("function_calls") instanceof List<?> calls)) return LanguageRequests.Result.invalid();
            if (calls.size() != 1) return LanguageRequests.Result.clarification();
            if (!(calls.getFirst() instanceof Map<?, ?> call)
                    || !(call.get("name") instanceof String tool)
                    || !(call.get("arguments") instanceof Map<?, ?> arguments))
                return LanguageRequests.Result.invalid();
            if (!(root.get("confidence") instanceof BigDecimal confidence))
                return LanguageRequests.Result.clarification();
            if (root.get("validation") instanceof Map<?, ?> validation
                    && (Boolean.TRUE.equals(validation.get("negation"))
                        || validation.get("ungrounded") instanceof List<?> ungrounded && !ungrounded.isEmpty()))
                return LanguageRequests.Result.clarification();

            if (prepared.classification().actor().state() == ReferenceState.EXPLICIT_UNSUPPORTED
                    || prepared.classification().source().state() == ReferenceState.EXPLICIT_UNSUPPORTED
                    || prepared.classification().destination().state() == ReferenceState.EXPLICIT_UNSUPPORTED)
                return LanguageRequests.Result.clarification();
            return switch (tool) {
                case "harvest_wheat" -> decodeHarvest(arguments, confidence, prepared);
                case "name_citizen" -> decodeNaming(arguments, confidence, prepared);
                default -> LanguageRequests.Result.invalid();
            };
        } catch (Exception malformed) { return LanguageRequests.Result.invalid(); }
    }

    private static LanguageRequests.Result decodeHarvest(Map<?, ?> arguments, BigDecimal confidence,
                                                          Prepared prepared) {
        Set<String> required = new LinkedHashSet<>();
        if (statedReference(prepared.classification().actor().state()))
            required.add("citizen");
        required.add("amount");
        if (statedReference(prepared.classification().source().state()))
            required.add("source");
        if (statedReference(prepared.classification().destination().state()))
            required.add("destination");
        if (!required.equals(arguments.keySet())) return LanguageRequests.Result.invalid();
        if (!(arguments.get("amount") instanceof BigDecimal number))
            return LanguageRequests.Result.invalid();
        Long amount = number.longValueExact();
        String citizen = null;
        if (required.contains("citizen")) {
            citizen = string(arguments, "citizen");
            if ("selected".equals(citizen) && prepared.explicitCitizenId() != null)
                citizen = prepared.explicitCitizenId();
        }
        if (required.contains("source") && !"farm".equals(string(arguments, "source")))
            return LanguageRequests.Result.invalid();
        if (required.contains("destination") && !"chest".equals(string(arguments, "destination")))
            return LanguageRequests.Result.invalid();
        if (prepared.classification().actor().state() == ReferenceState.EXPLICIT_NEAREST) {
            if (!"selected".equals(citizen)) return LanguageRequests.Result.invalid();
            citizen = null;
        }
        var extracted = new LanguageRequests.Extracted(LanguageRequests.Action.HARVEST_WHEAT,
                citizen, amount, prepared.from(), prepared.through(), prepared.destination(), null);
        return new LanguageRequests.Result(LanguageRequests.Kind.EXTRACTED, extracted,
                confidence.doubleValue());
    }

    private static LanguageRequests.Result decodeNaming(Map<?, ?> arguments, BigDecimal confidence,
                                                         Prepared prepared) {
        if (prepared.classification().intent() != LanguageReferenceClassifier.Intent.NAME_CITIZEN)
            return LanguageRequests.Result.clarification();
        Set<String> required = prepared.classification().actor().state() == ReferenceState.EXPLICIT_CONCRETE
                ? Set.of("citizen", "name") : Set.of("name");
        if (!required.equals(arguments.keySet())) return LanguageRequests.Result.invalid();
        String citizen = required.contains("citizen") ? string(arguments, "citizen") : null;
        if ("selected".equals(citizen) && prepared.explicitCitizenId() != null)
            citizen = prepared.explicitCitizenId();
        String name = string(arguments, "name");
        return new LanguageRequests.Result(LanguageRequests.Kind.EXTRACTED,
                new LanguageRequests.Extracted(LanguageRequests.Action.NAME_CITIZEN,
                        citizen, null, null, null, null, name), confidence.doubleValue());
    }

    private static String string(Map<?, ?> args, String key) {
        if (!args.containsKey(key)) return null;
        if (!(args.get(key) instanceof String value) || value.isBlank() || value.length() > 128)
            throw new IllegalArgumentException("Argument string");
        return value;
    }

    private static Object read(JsonReader r, int depth, int[] tokens) throws Exception {
        if (depth > 16 || ++tokens[0] > 512) throw new IllegalArgumentException("JSON work limit");
        return switch (r.peek()) {
            case BEGIN_OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>(); r.beginObject();
                while (r.hasNext()) {
                    String key = r.nextName();
                    if (key.length() > 128 || map.containsKey(key))
                        throw new IllegalArgumentException("Duplicate/long key");
                    map.put(key, read(r, depth + 1, tokens));
                }
                r.endObject(); yield map;
            }
            case BEGIN_ARRAY -> {
                List<Object> list = new ArrayList<>(); r.beginArray();
                while (r.hasNext()) list.add(read(r, depth + 1, tokens));
                r.endArray(); yield list;
            }
            case STRING -> {
                String s = r.nextString();
                if (s.length() > 4096) throw new IllegalArgumentException("String limit");
                yield s;
            }
            case NUMBER -> new BigDecimal(r.nextString());
            case BOOLEAN -> r.nextBoolean();
            case NULL -> { r.nextNull(); yield null; }
            default -> throw new IllegalArgumentException("JSON token");
        };
    }
}
