package dev.aivillages.core.kernel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.aivillages.core.kernel.WorldReferenceResolver.ReferenceState;

/** Deterministic raw-text provenance. It never supplies world facts or executable authority. */
public final class LanguageReferenceClassifier {
    private LanguageReferenceClassifier() { }

    public enum Intent { HARVEST_WHEAT, NAME_CITIZEN, UNKNOWN }

    public record Slot(ReferenceState state, String first, String second, String evidence) {
        public Slot {
            Objects.requireNonNull(state);
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
        public static Slot concrete(String first, String second, String evidence) {
            return new Slot(ReferenceState.EXPLICIT_CONCRETE, first, second, evidence);
        }
        public static Slot unsupported(String evidence) {
            return new Slot(ReferenceState.EXPLICIT_UNSUPPORTED, null, null, evidence);
        }
    }

    public record Classification(Intent intent, Slot actor, Slot source, Slot destination,
                                 String proposedName) {
        public Classification {
            Objects.requireNonNull(intent); Objects.requireNonNull(actor);
            Objects.requireNonNull(source); Objects.requireNonNull(destination);
            if (proposedName != null && proposedName.length() > CitizenRegistry.MAX_NAME_BYTES)
                throw new IllegalArgumentException("Name evidence limit");
        }
    }

    private static final String COORD = "(-?[0-9]+\\s*,\\s*-?[0-9]+\\s*,\\s*-?[0-9]+)";
    private static final Pattern SOURCE_COORD = Pattern.compile(
            "\\bfrom\\s+" + COORD + "\\s+through\\s+" + COORD, Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_NEAREST = Pattern.compile(
            "\\bfrom\\s+(?:the\\s+)?nearest\\s+(?:field|farm)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_ANY = Pattern.compile(
            "\\bfrom\\s+(.{1,160}?)(?=\\s+(?:and\\s+)?(?:deliver|deposit|put)\\b|[.!?]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DEST_COORD = Pattern.compile(
            "\\b(?:deliver|deposit|put)\\b.{0,200}?\\b(?:at|to|into)\\s+"
                    + "(?:the\\s+)?(?:(?:chest|container|barrel)\\s+(?:at\\s+)?)?" + COORD,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DEST_NEAREST = Pattern.compile(
            "\\b(?:deliver|deposit|put)\\b.{0,200}?\\b(?:to|into)\\s+(?:the\\s+)?nearest\\s+"
                    + "(?:chest|container|barrel)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEST_ANY = Pattern.compile(
            "\\b(?:deliver|deposit|put)\\b(.{0,220})", Pattern.CASE_INSENSITIVE);
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
        return new Classification(containsWord(text, "wheat") && Pattern.compile("\\b(harvest|gather|collect)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find() ? Intent.HARVEST_WHEAT : Intent.UNKNOWN,
                actor, source(text), destination(text), null);
    }

    private static Slot actor(String text, List<String> references) {
        Matcher nearest = Pattern.compile("^\\s*(?:the\\s+)?nearest\\s+(?:citizen|villager)\\s*,?\\s*(?:please\\s+)?(?:harvest|gather|collect)\\b",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (nearest.find()) return Slot.nearest("nearest citizen");
        Matcher leading = Pattern.compile("^\\s*(?:(?:please\\s+have|could|ask)\\s+)?"
                + "([\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N}_ -]{0,127}?)"
                + "\\s*,?\\s+(?:to\\s+)?(?:please\\s+)?(?:harvest|gather|collect)\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        if (leading.find()) {
            String reference = leading.group(1).strip();
            if (!reference.matches("(?i)(please|can|could|will|would|do|let us)"))
                return Slot.concrete(reference, null, reference);
        }
        Matcher afterAction = Pattern.compile("\\b(?:harvest|gather|collect)\\b[^,]{1,80}\\bwheat\\s*,\\s*([^,]{1,128})\\s*,",
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
        Matcher action = Pattern.compile("\\b(?:harvest|gather|collect)\\b",Pattern.CASE_INSENSITIVE).matcher(text);
        return action.find() ? text.substring(action.end()) : text;
    }

    private static Slot source(String text) {
        Matcher roles = Pattern.compile("\\bfrom\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!roles.find()) {
            String tail = harvestTail(text).split("(?i)\\b(?:deliver|deposit|put|to|into)\\b",2)[0];
            if (Pattern.compile("\\b(?:fields?|farms?|crops?|house)\\b",Pattern.CASE_INSENSITIVE)
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
        if (phrase.matches("(?i)(?:the\\s+)?nearest\\s+(?:field|farm)"))
            return Slot.nearest(bounded(full.group()));
        return Slot.unsupported(bounded(phrase));
    }

    private static Slot destination(String text) {
        Matcher role = Pattern.compile("\\b(?:deliver|deposit|put)\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        String tail;
        if (role.find()) {
            int start = role.end();
            if (role.find()) return Slot.unsupported("multiple destination actions");
            tail = stripTerminal(text.substring(start).split("[.!?;]", 2)[0]);
            if (tail.isEmpty() || tail.matches("(?i)(it|them|wheat)")) return Slot.omitted();
            tail = tail.replaceFirst("(?i)^(?:it|them|wheat)\\s+", "");
            if (!tail.matches("(?is)^(?:at|to|into|in)\\s+.+")) return Slot.unsupported(bounded(tail));
            tail = tail.replaceFirst("(?i)^(?:at|to|into|in)\\s+", "");
        } else {
            Matcher action = Pattern.compile("\\b(?:harvest|gather|collect)\\b", Pattern.CASE_INSENSITIVE).matcher(text);
            String afterAction = action.find() ? text.substring(action.end()) : text;
            Matcher standalone = DEST_STANDALONE.matcher(afterAction);
            if (!standalone.find()) {
                if (Pattern.compile("\\b(?:chests?|containers?|barrels?)\\b",Pattern.CASE_INSENSITIVE)
                        .matcher(afterAction).find()) return Slot.unsupported(bounded(afterAction));
                return Slot.omitted();
            }
            tail = stripTerminal(standalone.group(1));
        }
        if (tail.matches("(?i)(?:the\\s+)?nearest\\s+(?:chest|container|barrel)"))
            return Slot.nearest(bounded(tail));
        Matcher coords = Pattern.compile("^(?:the\\s+)?(?:(?:chest|container|barrel)\\s+(?:at\\s+)?)?" + COORD + "$",
                Pattern.CASE_INSENSITIVE).matcher(tail);
        if (coords.matches()) return Slot.concrete(compact(coords.group(1)), null, bounded(tail));
        return Slot.unsupported(bounded(tail));
    }

    private static String stripTerminal(String text) { return text.strip().replaceFirst("[.!?;]+$", "").strip(); }
    private static String bounded(String text) { return text.length() <= 256 ? text : text.substring(0,256); }

    private static boolean unsafeActionText(String text) {
        if (Pattern.compile("\\b(?:not|never|don't|do\\s+not|cannot)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find())
            return true;
        Matcher harvests = Pattern.compile("\\b(?:harvest|gather|collect)\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        int actions = 0;
        while (harvests.find()) if (++actions > 1) return true;
        return Pattern.compile("\\b(?:and|then|also)\\s+(?:build|destroy|craft|name|rename|attack|harvest|gather|collect)\\b",
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
