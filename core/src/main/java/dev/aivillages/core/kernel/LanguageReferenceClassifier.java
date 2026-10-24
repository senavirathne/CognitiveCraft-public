package dev.aivillages.core.kernel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.aivillages.core.kernel.WorldReferenceResolver.ReferenceState;
import static dev.aivillages.core.kernel.WorldReferenceResolver.ObjectType;
import static dev.aivillages.core.kernel.WorldReferenceResolver.ReferenceAnchor;

/** Deterministic raw-text provenance. It never supplies world facts or executable authority. */
public final class LanguageReferenceClassifier {
    private LanguageReferenceClassifier() { }

    public enum Intent { HARVEST_WHEAT, NAME_CITIZEN, UNKNOWN, GATHER_OAK_LOGS, MAKE_OAK_PLANKS, MAKE_STICKS, MAKE_WOODEN_AXES, MAKE_CRAFTING_TABLES, GATHER_BIRCH_LOGS, MAKE_BIRCH_PLANKS }

    public record Slot(ReferenceState state, String first, String second, String evidence, ObjectType objectType,
                       ReferenceAnchor anchor) {
        public Slot(ReferenceState state, String first, String second, String evidence, ObjectType objectType) {
            this(state, first, second, evidence, objectType, ReferenceAnchor.ROLE_DEFAULT);
        }
        public Slot(ReferenceState state, String first, String second, String evidence) {
            this(state, first, second, evidence, ObjectType.UNSPECIFIED);
        }
        public Slot {
            Objects.requireNonNull(state); Objects.requireNonNull(objectType); Objects.requireNonNull(anchor);
            if (anchor != ReferenceAnchor.ROLE_DEFAULT && state != ReferenceState.EXPLICIT_NEAREST)
                throw new IllegalArgumentException("Only explicit nearest references select an anchor");
            if (evidence != null && evidence.length() > 256)
                throw new IllegalArgumentException("Reference evidence limit");
            if (state == ReferenceState.EXPLICIT_CONCRETE && first == null)
                throw new IllegalArgumentException("Concrete reference missing");
            if (state != ReferenceState.EXPLICIT_CONCRETE && (first != null || second != null))
                throw new IllegalArgumentException("Only concrete references carry values");
        }
        public static Slot omitted() { return new Slot(ReferenceState.OMITTED, null, null, null); }
        public static Slot nearest(String evidence) {
            return new Slot(ReferenceState.EXPLICIT_NEAREST, null, null, evidence);
        }
        public static Slot nearest(String evidence, ObjectType objectType) {
            return new Slot(ReferenceState.EXPLICIT_NEAREST, null, null, evidence, objectType);
        }
        public static Slot nearest(String evidence, ObjectType objectType, ReferenceAnchor anchor) {
            return new Slot(ReferenceState.EXPLICIT_NEAREST, null, null, evidence, objectType, anchor);
        }
        public static Slot concrete(String first, String second, String evidence) {
            return new Slot(ReferenceState.EXPLICIT_CONCRETE, first, second, evidence);
        }
        public static Slot concrete(String first, String second, String evidence, ObjectType objectType) {
            return new Slot(ReferenceState.EXPLICIT_CONCRETE, first, second, evidence, objectType);
        }
        public static Slot unsupported(String evidence) {
            return new Slot(ReferenceState.EXPLICIT_UNSUPPORTED, null, null, evidence);
        }
    }

    public record Classification(Intent intent, Slot actor, Slot source, Slot destination,
                                 String proposedName,Slot table,ProductionLanguage.Description production) {
        public Classification(Intent intent,Slot actor,Slot source,Slot destination,String proposedName) {
            this(intent,actor,source,destination,proposedName,Slot.omitted());
        }
        public Classification(Intent intent,Slot actor,Slot source,Slot destination,String proposedName,Slot table) {
            this(intent,actor,source,destination,proposedName,table,null);
        }
        public Classification {
            Objects.requireNonNull(intent); Objects.requireNonNull(actor);
            Objects.requireNonNull(source); Objects.requireNonNull(destination);
            if (proposedName != null && proposedName.length() > CitizenRegistry.MAX_NAME_BYTES)
                throw new IllegalArgumentException("Name evidence limit");
        }
    }

    private static final String COORD = "(-?[0-9]+\\s*,\\s*-?[0-9]+\\s*,\\s*-?[0-9]+)";
    private static final String ACQUISITION_VERBS = "harvest|gather|collect";
    /** Delivery wording selects the existing log-acquisition capability, never an inventory transfer. */
    private static final String LOG_DELIVERY_VERBS = "bring|fetch";
    private static final String REQUEST_VERBS = ACQUISITION_VERBS + "|" + LOG_DELIVERY_VERBS + "|make|craft|produce|"+ProductionLanguage.TRANSFORMATION_VERBS+"|"+ProductionLanguage.GOAL_VERBS;
    private static final Pattern SOURCE_ANY = Pattern.compile(
            "\\bfrom\\s+(.{1,160}?)(?=\\s+(?:and\\s+)?(?:deliver|deposit|put|to(?!\\s+you\\b)|into|in)\\b|[.!?]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DEST_STANDALONE = Pattern.compile(
            "\\b(?:to|into|in)\\s+(.{1,160}?)(?=[.!?]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_NAMING = Pattern.compile(
            "^\\s*(?:rename|name)\\s+(.+?)\\s+(?:to|as)\\s+(.+?)\\s*[.!?]*\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final List<Pattern> NAMING = List.of(
            Pattern.compile("^\\s*your\\s+name\\s+is\\s+(.+?)\\s*[.!?]*\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*i\\s+give\\s+you\\s+the\\s+name\\s+(.+?)\\s*[.!?]*\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*i\\s+name\\s+you\\s+(.+?)\\s*[.!?]*\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*name\\s+the\\s+villager\\s+(.+?)\\s*[.!?]*\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*give\\s+name\\s+to\\s+the\\s+villager\\s+as\\s+(.+?)\\s*[.!?]*\\s*$", Pattern.CASE_INSENSITIVE));

    private record NamingMatch(Slot actor, String proposedName) { }

    public static Classification classify(String text, List<String> references) {
        Objects.requireNonNull(text); references = List.copyOf(references);
        if (unsafeActionText(text))
            return new Classification(Intent.UNKNOWN, Slot.omitted(), Slot.omitted(), Slot.omitted(), null);
        NamingMatch naming = naming(text);
        if (naming != null) {
            return new Classification(Intent.NAME_CITIZEN, naming.actor(), Slot.omitted(),
                    Slot.omitted(), naming.proposedName());
        }
        Slot actor = actor(text, references);
        var production=ProductionLanguage.describe(text);
        if(production!=null) {
            actor=actor(production.referenceText(),references);
            Intent intent=production.capability()==null ? Intent.UNKNOWN
                    : production.capability().equals(OakPlankProduction.ID) ? Intent.MAKE_OAK_PLANKS
                    : production.capability().equals(BirchPlankProduction.ID) ? Intent.MAKE_BIRCH_PLANKS
                    : production.capability().equals(StickProduction.ID) ? Intent.MAKE_STICKS
                    : production.capability().equals(WoodenAxeProduction.ID) ? Intent.MAKE_WOODEN_AXES : Intent.MAKE_CRAFTING_TABLES;
            if(intent==Intent.UNKNOWN)return new Classification(intent,actor,Slot.unsupported(production.inputEvidence()),Slot.omitted(),null,Slot.omitted(),production);
            Slot input=recipeSource(production.referenceText(),!LogAcquisition.log(production.input()));
            if(input.state()==ReferenceState.OMITTED && production.inputEvidence()!=null)input=Slot.nearest(production.inputEvidence());
            return new Classification(intent,actor,input,plankDestination(production.referenceText()),null,
                    intent==Intent.MAKE_WOODEN_AXES ? table(production.referenceText()) : Slot.omitted(),production);
        }
        if(containsWord(text,"birch")) {
            if(Pattern.compile("\\b(?:oak|wheat|spruce|stripped|wood|leaves|jungle|acacia|dark|mangrove|cherry|pale|crimson|warped|bamboo|stone|ores?|sticks?|axes?|tables?|slabs?|doors?)\\b",Pattern.CASE_INSENSITIVE).matcher(text).find())
                return new Classification(Intent.UNKNOWN,actor,Slot.omitted(),Slot.omitted(),null);
            if(Pattern.compile("\\b(?:make|craft|produce)\\b",Pattern.CASE_INSENSITIVE).matcher(text).find()
                    && Pattern.compile("\\bbirch\\s+planks?\\b",Pattern.CASE_INSENSITIVE).matcher(text).find())
                return new Classification(Intent.MAKE_BIRCH_PLANKS,actor,recipeSource(text,false),plankDestination(text),null);
            if(Pattern.compile("\\b(?:harvest|gather|collect)\\b",Pattern.CASE_INSENSITIVE).matcher(text).find()
                    && Pattern.compile("\\bbirch\\s+logs?\\b",Pattern.CASE_INSENSITIVE).matcher(text).find()
                    && !containsWord(text,"planks")) {
                Slot selected=source(text);
                if(selected.state()==ReferenceState.EXPLICIT_NEAREST && Pattern.compile("\\b(?:field|farm)\\b",Pattern.CASE_INSENSITIVE).matcher(selected.evidence()).find())selected=Slot.unsupported(selected.evidence());
                return new Classification(Intent.GATHER_BIRCH_LOGS,actor,selected,destination(text),null);
            }
            return new Classification(Intent.UNKNOWN,actor,Slot.omitted(),Slot.omitted(),null);
        }

        if(Pattern.compile("\\b(?:make|produce|craft)\\b",Pattern.CASE_INSENSITIVE).matcher(text).find()
                && Pattern.compile("\\bcrafting\\s+tables?\\b",Pattern.CASE_INSENSITIVE).matcher(text).find()
                && !Pattern.compile("\\b(?:place|build|furnaces?|axes?|houses?)\\b",Pattern.CASE_INSENSITIVE).matcher(text).find())
            return new Classification(Intent.MAKE_CRAFTING_TABLES,actor,recipeSource(text,true),
                    plankDestination(text),null);
        if(Pattern.compile("\\b(?:make|produce|craft)\\b",Pattern.CASE_INSENSITIVE).matcher(text).find()
                && Pattern.compile("\\bwooden\\s+axes?\\b",Pattern.CASE_INSENSITIVE).matcher(text).find()
                && !Pattern.compile("\\b(?:birch|spruce|stripped|swords?|stone|iron|diamond)\\b",Pattern.CASE_INSENSITIVE).matcher(text).find())
            return new Classification(Intent.MAKE_WOODEN_AXES,actor,recipeSource(text,true),plankDestination(text),null,table(text));
        if(Pattern.compile("\\b(?:make|produce|craft)\\b",Pattern.CASE_INSENSITIVE).matcher(text).find()
                && Pattern.compile("\\bsticks?\\b",Pattern.CASE_INSENSITIVE).matcher(text).find()
                && !Pattern.compile("\\b(?:birch|spruce|stripped|axes?|tables?|slabs?|doors?)\\b",Pattern.CASE_INSENSITIVE).matcher(text).find())
            return new Classification(Intent.MAKE_STICKS,actor,recipeSource(text,true),plankDestination(text),null);
        if(Pattern.compile("\\b(?:make|craft|produce)\\b",Pattern.CASE_INSENSITIVE).matcher(text).find()
                && Pattern.compile("\\boak\\s+planks?\\b",Pattern.CASE_INSENSITIVE).matcher(text).find()
                && !Pattern.compile("\\b(?:birch|spruce|stripped|sticks?|axes?|tables?|slabs?|doors?)\\b",Pattern.CASE_INSENSITIVE).matcher(text).find())
            return new Classification(Intent.MAKE_OAK_PLANKS,actor,plankSource(text),plankDestination(text),null);
        boolean action=Pattern.compile("\\b(?:"+ACQUISITION_VERBS+")\\b",Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean logAction=action || Pattern.compile("\\b(?:"+LOG_DELIVERY_VERBS+")\\b",Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean oak=Pattern.compile("\\boak\\s+logs?\\b",Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean wheat=containsWord(text,"wheat");
        Intent intent=action && wheat && !oak ? Intent.HARVEST_WHEAT
                : logAction && oak && !wheat && !Pattern.compile("\\b(?:stripped|wood|leaves|birch|spruce|jungle|acacia|dark|mangrove|cherry|pale|crimson|warped|bamboo|stone|ores?|axes?)\\b",
                        Pattern.CASE_INSENSITIVE).matcher(text).find() ? Intent.GATHER_OAK_LOGS : Intent.UNKNOWN;
        Slot source=source(text);
        if(intent==Intent.GATHER_OAK_LOGS && source.state()==ReferenceState.EXPLICIT_NEAREST
                && Pattern.compile("\\b(?:field|farm)\\b",Pattern.CASE_INSENSITIVE).matcher(source.evidence()).find())
            source=Slot.unsupported(source.evidence());
        return new Classification(intent,actor,source,destination(text),null);
    }

    public static Slot table(String text) {
        var role=Pattern.compile("\\b(?:at|using)\\s+((?:(?:the|a|an|this|that)\\s+)?(?:(?:nearest|closest|available|suitable)\\s+)*(?:crafting\\s+)?table\\b.{0,100}?)(?=\\s+(?:and\\s+)?(?:from|store|deposit|put|deliver)\\b|[.!?;]|$)",Pattern.CASE_INSENSITIVE).matcher(text);
        if(!role.find())return containsWord(text,"table") ? Slot.unsupported("unsupported table reference") : Slot.omitted();
        String evidence=stripTerminal(role.group(1));
        if(role.find())return Slot.unsupported("multiple tables");
        return WorldReferenceLanguage.parse(evidence,ObjectType.CRAFTING_TABLE);
    }
    private static Slot plankSource(String text) {return recipeSource(text,false);}
    private static Slot recipeSource(String text,boolean sticks) {
        Matcher role=Pattern.compile("\\bfrom\\s+(.{1,180}?)(?=\\s+(?:and\\s+)?(?:store|deposit|put|deliver)\\b|[.!?]|$)",Pattern.CASE_INSENSITIVE).matcher(text);
        if(!role.find())return containsWord(text,"from") ? Slot.unsupported("unsupported input source") : Slot.omitted();
        String phrase=stripTerminal(role.group(1).replaceFirst("(?i)\\s+(?:at|using)\\s+(?:(?:the|a|an|this|that)\\s+)?(?:(?:nearest|closest)\\s+)?(?:(?:available|suitable)\\s+)?(?:crafting\\s+)?table.*$",""));
        if(role.find())return Slot.unsupported("multiple input sources");
        if(phrase.matches("(?i)(?:the\\s+)?available\\s+(?:oak|birch)\\s+"+(sticks ? "planks?" : "logs?")))return Slot.nearest(sticks ? "available oak planks" : "available oak logs");
        Slot selected=WorldReferenceLanguage.container(phrase);
        return selected.state()==ReferenceState.EXPLICIT_CONCRETE
                ? Slot.concrete(selected.first(),selected.first(),selected.evidence(),selected.objectType()) : selected;
    }
    private static Slot plankDestination(String text) {
        Matcher role=Pattern.compile("\\b(?:store|deposit|put|deliver)\\b",Pattern.CASE_INSENSITIVE).matcher(text);
        if(!role.find()) {
            String tail=harvestTail(text);
            var input=recipeSource(text,false);
            if(input.state()==ReferenceState.EXPLICIT_NEAREST)
                tail=WorldReferenceLanguage.alias(tail,input,"stock","from");
            var facility=table(text);
            if(facility.state()==ReferenceState.EXPLICIT_NEAREST || facility.state()==ReferenceState.EXPLICIT_CONCRETE)
                tail=WorldReferenceLanguage.alias(tail,facility,"facility","at|using");
            Matcher direct=DEST_STANDALONE.matcher(tail);
            if(direct.find())return WorldReferenceLanguage.container(stripTerminal(direct.group(1)));
            if(!containsWord(tail,"from") && Pattern.compile("\\b(?:chests?|barrels?|containers?)\\b",Pattern.CASE_INSENSITIVE).matcher(tail).find())
                return Slot.unsupported(bounded(tail));
            return Slot.omitted();
        }
        int start=role.end();if(role.find())return Slot.unsupported("multiple output destinations");
        String phrase=stripTerminal(text.substring(start).replaceFirst("(?i)\\s+(?:at|using)\\s+(?:(?:the|a|an|this|that)\\s+)?(?:(?:nearest|closest)\\s+)?(?:(?:available|suitable)\\s+)?(?:crafting\\s+)?table.*$",""));
        if(phrase.isBlank() || phrase.matches("(?i)(?:them|it|(?:(?:(?:oak|birch)\\s+)?planks?|sticks?))"))return Slot.omitted();
        String prefix="(?:(?:them|it|(?:(?:(?:oak|birch)\\s+)?planks?|sticks?))\\s+)?(?:to|into|in|at)\\s+";
        if(!phrase.matches("(?is)^"+prefix+".+"))return Slot.unsupported(phrase);
        return WorldReferenceLanguage.container(phrase.replaceFirst("(?i)^"+prefix,""));
    }
    private static Slot actor(String text, List<String> references) {
        Matcher nearest = Pattern.compile("^\\s*(?:the\\s+)?nearest\\s+(?:citizen|villager)\\s*,?\\s*(?:please\\s+)?(?:"+REQUEST_VERBS+")\\b",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (nearest.find()) return Slot.nearest("nearest citizen");
        Matcher leading = Pattern.compile("^\\s*(?:(?:please\\s+have|could|can|would|will|ask)\\s+)?"
                + "([\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N}_ -]{0,127}?)"
                + "\\s*,?\\s+(?:to\\s+)?(?:please\\s+)?(?:(?:can|could|would|will)\\s+you\\s+)?(?:please\\s+)?(?:"+REQUEST_VERBS+")\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        if (leading.find()) {
            String reference = leading.group(1).strip();
            if (!reference.matches("(?i)(please|can|could|will|would|do|let us|you)"))
                return Slot.concrete(reference, null, reference);
        }
        Matcher afterAction = Pattern.compile("\\b(?:"+REQUEST_VERBS+")\\b[^,]{1,80}\\b(?:wheat|(?:oak|birch)\\s+logs?)\\s*,\\s*([^,]{1,128})\\s*,",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (afterAction.find()) {
            String reference = afterAction.group(1).strip();
            return Slot.concrete(reference, null, reference);
        }
        var matches = new ArrayList<String>();
        for (String reference : references)
            if (reference != null && !reference.isBlank() && containsReference(text, reference))
                matches.add(reference);
        matches = new ArrayList<>(matches.stream().distinct().toList());
        if (matches.size() == 1) return Slot.concrete(matches.getFirst(), null, matches.getFirst());
        if (matches.size() > 1)
            return new Slot(ReferenceState.AMBIGUOUS, null, null, "multiple explicit citizen references");
        return Slot.omitted();
    }

    private static String harvestTail(String text) {
        Matcher action = Pattern.compile("\\b(?:"+REQUEST_VERBS+")\\b",Pattern.CASE_INSENSITIVE).matcher(text);
        return action.find() ? text.substring(action.end()) : text;
    }

    private static Slot source(String text) {
        Matcher roles = Pattern.compile("\\bfrom\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!roles.find()) {
            String tail = harvestTail(text).split("(?i)\\b(?:deliver|deposit|put|to|into)\\b",2)[0];
            if (Pattern.compile("\\b(?:fields?|farms?|crops?|house|forests?|groves?|trees?)\\b",Pattern.CASE_INSENSITIVE)
                    .matcher(tail).find()) return Slot.unsupported(bounded(tail));
            return Slot.omitted();
        }
        if (roles.find()) return Slot.unsupported("multiple source references");
        Matcher full = SOURCE_ANY.matcher(text);
        if (!full.find()) return Slot.unsupported("unsupported source reference");
        String phrase = stripTerminal(full.group(1));
        Matcher coords = Pattern.compile("^" + COORD + "\\s+through\\s+" + COORD + "$",
                Pattern.CASE_INSENSITIVE).matcher(phrase);
        if (coords.matches()) return Slot.concrete(compact(coords.group(1)), compact(coords.group(2)), bounded(full.group()));
        Slot selected=WorldReferenceLanguage.parse(bounded(phrase),ObjectType.FIELD,ObjectType.OAK_LOG_AREA,
                ObjectType.BIRCH_LOG_AREA,ObjectType.LOG_AREA);
        // Concrete acquisition areas require both original corners, handled above.
        return selected.state()==ReferenceState.EXPLICIT_NEAREST ? selected : Slot.unsupported(bounded(phrase));
    }

    private static Slot destination(String text) {
        Matcher role = Pattern.compile("\\b(?:deliver|deposit|put)\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        String tail;
        if (role.find()) {
            int start = role.end();
            if (role.find()) return Slot.unsupported("multiple destination actions");
            tail = stripTerminal(text.substring(start).split("[.!?;]", 2)[0]);
            if (tail.isEmpty() || tail.matches("(?i)(it|them|wheat|(?:oak|birch)\\s+logs?)")) return Slot.omitted();
            tail = tail.replaceFirst("(?i)^(?:it|them|wheat|(?:oak|birch)\\s+logs?)\\s+", "");
            if (!tail.matches("(?is)^(?:at|to|into|in)\\s+.+")) return Slot.unsupported(bounded(tail));
            tail = tail.replaceFirst("(?i)^(?:at|to|into|in)\\s+", "");
        } else {
            Matcher action = Pattern.compile("\\b(?:"+REQUEST_VERBS+")\\b", Pattern.CASE_INSENSITIVE).matcher(text);
            String afterAction = action.find() ? text.substring(action.end()) : text;
            var input=source(text);
            if(input.state()==ReferenceState.EXPLICIT_NEAREST)
                afterAction=WorldReferenceLanguage.alias(afterAction,input,"area","from");
            Matcher standalone = DEST_STANDALONE.matcher(afterAction);
            if (!standalone.find()) {
                if (Pattern.compile("\\b(?:chests?|containers?|barrels?)\\b",Pattern.CASE_INSENSITIVE)
                        .matcher(afterAction).find()) return Slot.unsupported(bounded(afterAction));
                return Slot.omitted();
            }
            tail = stripTerminal(standalone.group(1));
        }
        return WorldReferenceLanguage.container(bounded(tail));
    }

    private static String stripTerminal(String text) { return text.strip().replaceFirst("[.!?;]+$", "").strip(); }
    private static String bounded(String text) { return text.length() <= 256 ? text : text.substring(0,256); }

    private static boolean unsafeActionText(String text) {
        if (Pattern.compile("\\b(?:not|never|don't|do\\s+not|cannot)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find())
            return true;
        Matcher harvests = Pattern.compile("\\b(?:"+REQUEST_VERBS+")\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        int actions = 0;
        while (harvests.find()) if (++actions > 1) return true;
        return Pattern.compile("\\b(?:and|then|also)\\s+(?:build|destroy|craft|name|rename|attack|"+ACQUISITION_VERBS+"|"+LOG_DELIVERY_VERBS+")\\b",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private static NamingMatch naming(String text) {
        Matcher explicit = EXPLICIT_NAMING.matcher(text);
        if (explicit.matches()) {
            String target = explicit.group(1).strip();
            String proposed = normalizeProposedName(explicit.group(2).strip());
            return new NamingMatch(Slot.concrete(target, null, target), proposed);
        }
        for (Pattern pattern : NAMING) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.matches())
                return new NamingMatch(Slot.omitted(), normalizeProposedName(matcher.group(1).strip()));
        }
        return null;
    }

    private static String normalizeProposedName(String candidate) {
        try { return CitizenRegistry.normalizeName(candidate); }
        catch (IllegalArgumentException invalid) { return candidate; }
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(word)
                + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }

    private static boolean containsReference(String text, String ref) {
        return Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(ref)
                + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }

    private static String compact(String coordinate) { return coordinate.replaceAll("\\s", ""); }
}
