package dev.aivillages.core.kernel;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import static dev.aivillages.core.kernel.LanguageReferenceClassifier.Slot;
import static dev.aivillages.core.kernel.WorldReferenceResolver.ObjectType;

/** Shared nominal-reference policy. Role owners supply the supported types; no world facts here. */
public final class WorldReferenceLanguage {
    private WorldReferenceLanguage() { }
    private static final String COORD = "(-?[0-9]+\\s*,\\s*-?[0-9]+\\s*,\\s*-?[0-9]+)";
    private static final String ARTICLE = "(?:(?:the|a|an|this|that)\\s+)?";
    private static final String NOUN = "(chest|container|barrel|(?:crafting\\s+)?table|field|farm|(?:oak|birch)\\s+logs?|log\\s+area)";
    private static final Pattern AUTOMATIC = Pattern.compile("^" + ARTICLE
            + "(?:nearest\\s+)?(?:(?:available|suitable)\\s+)?" + NOUN + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONCRETE = Pattern.compile("^" + ARTICLE + "(?:" + NOUN
            + "\\s+(?:at\\s+)?)?" + COORD + "$", Pattern.CASE_INSENSITIVE);

    public static Slot parse(String phrase, ObjectType... supported) {
        String evidence = phrase.strip();
        var concrete = CONCRETE.matcher(evidence);
        if (concrete.matches()) {
            ObjectType type = concrete.group(1) == null ? ObjectType.UNSPECIFIED : type(concrete.group(1));
            if (type == ObjectType.UNSPECIFIED || Arrays.asList(supported).contains(type))
                return Slot.concrete(concrete.group(2).replaceAll("\\s+", ""), null, evidence, type);
        }
        var automatic = AUTOMATIC.matcher(evidence);
        if (automatic.matches()) {
            ObjectType type = type(automatic.group(1));
            if (Arrays.asList(supported).contains(type)) return Slot.nearest(evidence, type);
        }
        return Slot.unsupported(evidence);
    }

    public static Slot container(String phrase) {
        return parse(phrase, ObjectType.CONTAINER, ObjectType.CHEST, ObjectType.BARREL);
    }

    private static ObjectType type(String noun) {
        return switch (noun.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")) {
            case "chest" -> ObjectType.CHEST;
            case "barrel" -> ObjectType.BARREL;
            case "container" -> ObjectType.CONTAINER;
            case "table", "crafting table" -> ObjectType.CRAFTING_TABLE;
            case "field", "farm" -> ObjectType.FIELD;
            case "oak log", "oak logs" -> ObjectType.OAK_LOG_AREA;
            case "birch log", "birch logs" -> ObjectType.BIRCH_LOG_AREA;
            case "log area" -> ObjectType.LOG_AREA;
            default -> throw new IllegalArgumentException("Unregistered reference noun");
        };
    }

    /** Replace only the independently classified span, preserving amount/action and provenance. */
    public static String alias(String text, Slot slot, String alias, String roleWords) {
        if (slot.evidence() == null) return text;
        return Pattern.compile("(\\b(?:"+roleWords+")\\s+)"+Pattern.quote(slot.evidence())+"(?=\\s|[.!?;]|$)",
                Pattern.CASE_INSENSITIVE).matcher(text)
                .replaceFirst(match -> java.util.regex.Matcher.quoteReplacement(match.group(1)+alias));
    }
}
