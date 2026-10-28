package dev.aivillages.core.kernel.interaction.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static dev.aivillages.core.kernel.interaction.contract.InteractionWire.*;

/** Strict, dependency-free implementation of the frozen contract; no runtime side effects. */
public final class InteractionCodec {
    public static final int MAX_INPUT_BYTES = 262_144;
    private static final long MAX_INTEGER = 9_007_199_254_740_991L;
    private static final Map<String, Object> SCHEMA = object(parseJson(InteractionSchema.JSON));
    private static final Set<String> KEYWORDS = Set.of("$schema", "$id", "title", "description",
            "definitions", "$ref", "oneOf", "type", "const", "enum", "properties", "required",
            "additionalProperties", "items", "minItems", "maxItems", "minimum", "maximum",
            "minLength", "maxLength", "pattern");

    static {
        if (!sha256(InteractionSchema.JSON).equals(SCHEMA_SHA256))
            throw new ExceptionInInitializerError("Frozen schema bytes changed");
    }

    public UntrustedMessage decode(String json) {
        Object value = parseJson(json);
        shape(value, SCHEMA);
        Map<String, Object> envelope = object(value);
        Kind kind = Kind.valueOf(text(envelope, "kind"));
        Map<String, Object> body = object(envelope.get("body"));
        semantics(kind.name(), body);
        return new UntrustedMessage(kind, body);
    }

    /** Candidate-only ingress; observations/goals/checkpoints are not language input. */
    public UntrustedMessage decodeCandidate(String json) {
        UntrustedMessage message = decode(json);
        require(message.isCandidateIntent(), Disposition.REFUSED_EXPECTED, "S01 candidate ingress");
        return message;
    }

    public String encode(UntrustedMessage message) {
        return canonical(Map.of("schema", VERSION, "kind", message.kind().name(), "body", message.body()));
    }

    /** Synthetic contract disposition only. The context is never authority or an admission result. */
    public Disposition inspect(String json, String syntheticContextJson) {
        UntrustedMessage message;
        try { message = decode(json); }
        catch (DecodeException failure) { return failure.disposition(); }
        return contextual(message.kind().name(), message.body(), object(parseJson(syntheticContextJson)))
                ? Disposition.VALID : Disposition.REFUSED_EXPECTED;
    }

    /** Immutable JSON tree for evidence tooling; rejects duplicate keys and non-integer numbers. */
    public static Object parseJson(String json) {
        require(json != null && json.length() <= MAX_INPUT_BYTES
                        && json.getBytes(StandardCharsets.UTF_8).length <= MAX_INPUT_BYTES,
                Disposition.INVALID_SCHEMA, "input limit");
        return new Reader(json).read();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?>, Disposition.INVALID_SCHEMA, "object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        require(value instanceof List<?>, Disposition.INVALID_SCHEMA, "array");
        return (List<Object>) value;
    }

    private static String text(Map<String, Object> value, String key) { return (String) value.get(key); }
    private static long number(Map<String, Object> value, String key) { return (Long) value.get(key); }
    private static Map<String, Object> obj(Map<String, Object> value, String key) { return object(value.get(key)); }
    private static List<Object> list(Map<String, Object> value, String key) { return array(value.get(key)); }
    private static boolean eq(Map<String, Object> value, String key, Object expected) {
        return Objects.equals(value.get(key), expected);
    }

    private static void require(boolean ok, Disposition disposition, String rule) {
        if (!ok) throw new DecodeException(disposition, rule);
    }

    private static void semantic(boolean ok, String rule) { require(ok, Disposition.INVALID_SEMANTICS, rule); }

    private static void shape(Object value, Map<String, Object> schema) {
        require(KEYWORDS.containsAll(schema.keySet()), Disposition.INVALID_SCHEMA, "unknown schema keyword");
        if (schema.containsKey("$ref")) {
            String ref = (String) schema.get("$ref");
            shape(value, obj(obj(SCHEMA, "definitions"), ref.substring(ref.lastIndexOf('/') + 1)));
            return;
        }
        if (schema.containsKey("oneOf")) {
            int matches = 0;
            for (Object option : list(schema, "oneOf")) {
                try { shape(value, object(option)); matches++; }
                catch (DecodeException ignored) { /* A different discriminated branch may match. */ }
            }
            require(matches == 1, Disposition.INVALID_SCHEMA, "oneOf");
            return;
        }
        if (schema.containsKey("const"))
            require(Objects.equals(value, schema.get("const")), Disposition.INVALID_SCHEMA, "const");
        if (schema.containsKey("enum"))
            require(list(schema, "enum").contains(value), Disposition.INVALID_SCHEMA, "enum");
        String type = (String) schema.get("type");
        if (type == null) return;
        switch (type) {
            case "null" -> require(value == null, Disposition.INVALID_SCHEMA, "null");
            case "integer" -> {
                require(value instanceof Long, Disposition.INVALID_SCHEMA, "integer");
                long n = (Long) value;
                require(n >= (Long) schema.getOrDefault("minimum", -MAX_INTEGER)
                                && n <= (Long) schema.getOrDefault("maximum", MAX_INTEGER),
                        Disposition.INVALID_SCHEMA, "integer range");
            }
            case "string" -> {
                require(value instanceof String, Disposition.INVALID_SCHEMA, "string");
                String s = (String) value;
                int size = s.codePointCount(0, s.length());
                require(size >= (Long) schema.getOrDefault("minLength", 0L)
                                && size <= (Long) schema.getOrDefault("maxLength", (long) MAX_INPUT_BYTES),
                        Disposition.INVALID_SCHEMA, "string length");
                if (schema.containsKey("pattern")) require(Pattern.compile((String) schema.get("pattern"))
                        .matcher(s).find(), Disposition.INVALID_SCHEMA, "string pattern");
            }
            case "array" -> {
                List<Object> values = array(value);
                require(values.size() >= (Long) schema.getOrDefault("minItems", 0L)
                                && values.size() <= (Long) schema.getOrDefault("maxItems", (long) MAX_INPUT_BYTES),
                        Disposition.INVALID_SCHEMA, "array length");
                for (Object item : values) shape(item, obj(schema, "items"));
            }
            case "object" -> {
                Map<String, Object> values = object(value);
                Map<String, Object> properties = obj(schema, "properties");
                require(values.keySet().containsAll(list(schema, "required")), Disposition.INVALID_SCHEMA, "required");
                require(!Boolean.FALSE.equals(schema.get("additionalProperties"))
                                || properties.keySet().containsAll(values.keySet()),
                        Disposition.INVALID_SCHEMA, "unknown field");
                for (var entry : values.entrySet()) if (properties.containsKey(entry.getKey()))
                    shape(entry.getValue(), object(properties.get(entry.getKey())));
            }
            default -> throw new DecodeException(Disposition.INVALID_SCHEMA, "unsupported schema type");
        }
    }

    private static void semantics(String kind, Map<String, Object> b) {
        switch (kind) {
            case "DialogueIntent" -> {
                String action = text(b, "action");
                if (Set.of("CHAT", "FORGET", "COME", "FOLLOW", "STOP", "STATUS").contains(action))
                    semantic(b.get("source") == null && b.get("destination") == null && list(b, "items").isEmpty()
                            && b.get("label") == null && b.get("role") == null && b.get("requested_access") == null,
                            "S16 unrelated fields");
                semantic(action.equals("DELIVER") || list(b, "items").isEmpty(), "S16 items");
                semantic(action.equals("SET_STORAGE_ACCESS") || b.get("requested_access") == null, "S16 access");
            }
            case "PlayerReplyIntent" -> semantic(eq(b, "reply", "CHOOSE_TARGET") || b.get("selection") == null,
                    "S16 reply selection");
            case "StorageRoleAssignment" -> semantic(!eq(obj(b, "beneficiary"), "kind", "PUBLIC"), "S15 public default");
            case "StorageRecord" -> {
                sortedSet(list(b, "roles"));
                for (Object grant : list(b, "acl")) {
                    Map<String, Object> g = object(grant); sortedSet(list(g, "permissions"));
                    if (eq(obj(g, "subject"), "kind", "PUBLIC")) semantic(
                            Set.of("INSPECT", "DEPOSIT", "WITHDRAW").containsAll(list(g, "permissions")), "S04 public admin");
                }
            }
            case "SelectionWitness" -> semantic(number(b, "captured_tick") <= number(b, "expires_tick")
                    && number(b, "expires_tick") <= number(b, "captured_tick") + 40, "S06 selection expiry");
            case "BlockerEvidence" -> {
                if (!list(b, "requirements").isEmpty()) semantic(eq(b, "knowledge", "KNOWN")
                        && eq(b, "visibility", "AUTHORIZED_FACTS") && eq(b, "continuation", "FEASIBLE_AFTER_ASSISTANCE")
                        && eq(b, "code", "INSUFFICIENT_MATERIAL"), "S02 requirements");
                if (eq(b, "visibility", "REDACTED")) semantic(b.get("storage_id") == null
                        && list(b, "requirements").isEmpty(), "S02 privacy");
            }
            case "AssistanceRequest" -> {
                semantics("BlockerEvidence", obj(b, "blocker"));
                semantic(number(b, "expires_ms") <= number(obj(b, "activity"), "absolute_deadline_ms")
                        && Objects.equals(obj(b, "blocker").get("activity"), b.get("activity")), "S03 root/deadline");
            }
            case "StorageAccessDecision" -> {
                semantic(number(b, "evaluated_tick") <= number(b, "valid_through_tick")
                        && number(b, "valid_through_tick") <= number(b, "evaluated_tick") + 2, "S04 expiry");
                semantic(!eq(b, "decision", "ALLOW") || b.get("reason") == null, "S04 reason");
            }
            case "PhysicalStorageBinding" -> binding(b);
            case "ResolvedEndpoint" -> {
                if (eq(b, "kind", "STORAGE")) {
                    Map<String, Object> binding = obj(b, "binding"), access = obj(b, "access");
                    semantics("PhysicalStorageBinding", binding); semantics("StorageAccessDecision", access);
                    semantic(eq(binding, "storage_id", access.get("storage_id"))
                            && eq(binding, "binding_generation", access.get("binding_generation"))
                            && eq(binding, "world_id", obj(access, "scope").get("world_id")), "S06 endpoint correlation");
                }
            }
            case "MobilityGoal" -> {
                Map<String, Object> actor = obj(b, "actor"), activity = obj(b, "activity");
                semantic(eq(actor, "world_id", obj(activity, "scope").get("world_id"))
                        && eq(actor, "citizen_id", activity.get("responsible_citizen_id")), "S07 activity actor");
                switch (text(b, "mode")) {
                    case "COME_TO_PLAYER" -> semantic(b.get("captured_position") != null && b.get("player_id") != null
                            && b.get("storage_target") == null, "S07 come");
                    case "FOLLOW_PLAYER" -> semantic(b.get("player_id") != null && b.get("captured_position") == null
                            && b.get("storage_target") == null, "S07 follow");
                    case "GO_NAMED_STORAGE" -> semantic(b.get("storage_target") != null
                            && eq(obj(b, "storage_target"), "kind", "STORAGE") && b.get("player_id") == null
                            && b.get("captured_position") == null, "S07 named");
                    default -> throw new DecodeException(Disposition.INVALID_SEMANTICS, "S07 mode");
                }
                semantic(number(b, "follow_min_milliblocks") <= number(b, "follow_max_milliblocks"), "S07 range");
            }
            case "DeliveryGoal" -> delivery(b);
            case "CustodyCheckpoint" -> {
                if (Set.of("PARTICIPANT_SAVES_CONFIRMED", "ORIGINAL_OWNER_SETTLED").contains(text(b, "tier"))) {
                    semantic(b.get("a_proof_reference") != null, "S09 proof");
                    for (Object p : list(b, "participants")) semantic(object(p).get("save_evidence_id") != null, "S09 participant");
                }
                semantic(!eq(b, "tier", "ORIGINAL_OWNER_SETTLED") || eq(b, "original_effect_outcome", "SETTLED"), "S09 settled");
            }
            case "StageManifest" -> {
                for (String name : List.of("unit", "python", "game_tests", "client_e2e")) {
                    Map<String, Object> metric = obj(b, name);
                    semantic(number(metric, "executed") == number(metric, "passed") + number(metric, "failed")
                            + number(metric, "refused_expected") + number(metric, "inconclusive")
                            && number(metric, "executed") <= number(metric, "planned"), "S14 counts");
                    if (eq(b, "gate_status", "BRANCH_READY")) semantic(number(metric, "failed") == 0
                            && number(metric, "inconclusive") == 0 && eq(metric, "executed", metric.get("planned")), "S14 incomplete");
                }
                if (eq(b, "gate_status", "BRANCH_READY")) {
                    for (String name : List.of("source_sha", "tree_sha", "jar_sha256", "docs_sha", "workflow_run", "attempt"))
                        semantic(b.get(name) != null, "S14 pin");
                    semantic(list(b, "regressions").isEmpty(), "S14 regression");
                }
            }
            default -> { /* Other kinds have schema and contextual rules only in cc.cd/1.0.0. */ }
        }
    }

    private static void sortedSet(List<Object> values) {
        TreeSet<String> sorted = new TreeSet<>();
        for (Object value : values) sorted.add((String) value);
        semantic(values.equals(new ArrayList<>(sorted)), "S11 sorted unique set");
    }

    private static void binding(Map<String, Object> b) {
        List<Object> members = list(b, "members");
        boolean doubled = eq(b, "adapter_id", "minecraft:chest_double/v1");
        semantic(members.size() == (doubled ? 2 : 1), "S05 member count");
        Set<Object> ids = new java.util.HashSet<>();
        for (Object m : members) ids.add(object(m).get("incarnation_id"));
        semantic(ids.size() == members.size(), "S05 distinct incarnation");
        Comparator<Object> order = Comparator.comparingLong((Object m) -> number(object(m), "x"))
                .thenComparingLong(m -> number(object(m), "y")).thenComparingLong(m -> number(object(m), "z"))
                .thenComparing(m -> text(object(m), "incarnation_id"));
        List<Object> sorted = new ArrayList<>(members); sorted.sort(order);
        semantic(sorted.equals(members), "S05 member order");
        String facing = text(b, "facing");
        semantic(eq(b, "adapter_id", "minecraft:barrel/v1") || !Set.of("UP", "DOWN").contains(facing), "S05 facing");
        if (doubled) {
            Map<String, Object> a = object(members.get(0)), c = object(members.get(1));
            semantic(eq(a, "y", c.get("y")) && Math.abs(number(a, "x") - number(c, "x"))
                    + Math.abs(number(a, "z") - number(c, "z")) == 1, "S05 adjacency");
            semantic(!(Set.of("NORTH", "SOUTH").contains(facing) && eq(a, "x", c.get("x")))
                    && !(Set.of("EAST", "WEST").contains(facing) && eq(a, "z", c.get("z"))), "S05 alignment");
        }
        Map<String, Object> topology = new TreeMap<>();
        for (String field : List.of("adapter_id", "world_id", "dimension", "members", "facing")) topology.put(field, b.get(field));
        semantic(sha256(canonical(topology)).equals(b.get("topology_sha256")), "S05 digest");
    }

    private static void delivery(Map<String, Object> b) {
        List<Object> identities = new ArrayList<>(), coords = new ArrayList<>();
        Map<String, Object> activity = obj(b, "activity"), carrier = obj(b, "carrier");
        for (String side : List.of("source", "destination")) {
            Map<String, Object> endpoint = obj(b, side); semantics("ResolvedEndpoint", endpoint);
            String operation = side.equals("source") ? "WITHDRAW" : "DEPOSIT";
            Map<String, Object> target;
            if (eq(endpoint, "kind", "STORAGE")) {
                target = obj(endpoint, "binding"); Map<String, Object> access = obj(endpoint, "access");
                semantic(eq(access, "scope", activity.get("scope")) && eq(access, "operation", operation)
                        && eq(access, "decision", "ALLOW") && eq(target, "status", "VERIFIED_LIVE"), "S08 storage access");
                identities.add(target.get("storage_id"));
            } else {
                target = obj(endpoint, "citizen");
                semantic(eq(endpoint, "scope", activity.get("scope")) && eq(endpoint, "permission", operation)
                        && eq(endpoint, "access_decision", "ALLOW"), "S08 recipient");
                identities.add(target.get("citizen_id"));
                if (side.equals("source")) semantic(target.equals(carrier), "S08 third-party donor");
            }
            coords.add(List.of(target.get("world_id"), target.get("dimension")));
        }
        semantic(coords.get(0).equals(coords.get(1)) && coords.get(0).equals(List.of(carrier.get("world_id"), carrier.get("dimension")))
                && !identities.get(0).equals(identities.get(1)), "S08 world/identity");
        semantic(eq(carrier, "world_id", obj(activity, "scope").get("world_id"))
                && eq(carrier, "citizen_id", activity.get("responsible_citizen_id")), "S08 carrier");
    }

    private static boolean contextual(String kind, Map<String, Object> b, Map<String, Object> c) {
        if (kind.equals("ActivityResumeSignal")) {
            if (c.containsKey("trusted_generation") && !eq(c, "trusted_generation", obj(b, "activity").get("execution_generation"))) return false;
            if (Boolean.FALSE.equals(c.get("reobserved_requirements_satisfied"))) return false;
        }
        if (kind.equals("PlayerReplyIntent") && !Objects.equals(c.get("authenticated_principal"), c.get("question_principal"))) return false;
        if (kind.equals("ResolvedEndpoint") && eq(b, "kind", "STORAGE")) {
            if (c.containsKey("current_incarnation") && !eq(c, "current_incarnation", object(list(obj(b, "binding"), "members").get(0)).get("incarnation_id"))) return false;
            if (c.containsKey("current_acl_revision") && !eq(c, "current_acl_revision", obj(b, "access").get("acl_revision"))) return false;
        }
        if (kind.equals("CustodyCheckpoint") && Set.of("PARTICIPANT_SAVES_CONFIRMED", "ORIGINAL_OWNER_SETTLED").contains(text(b, "tier"))
                && !Boolean.TRUE.equals(c.get("a_proof_authenticated"))) return false;
        return !kind.equals("StorageSelector") || !c.containsKey("eligible_registered_count") || eq(c, "eligible_registered_count", 1L);
    }

    public static String canonical(Object value) { StringBuilder out = new StringBuilder(); write(value, out); return out.append('\n').toString(); }
    private static void write(Object v, StringBuilder out) {
        if (v == null) { out.append("null"); return; }
        if (v instanceof String s) { quote(s, out); return; }
        if (v instanceof Boolean || v instanceof Long) { out.append(v); return; }
        if (v instanceof List<?> a) {
            out.append('['); for (int i = 0; i < a.size(); i++) { if (i > 0) out.append(','); write(a.get(i), out); } out.append(']'); return;
        }
        Map<String, Object> map = new TreeMap<>(object(v)); out.append('{'); boolean comma = false;
        for (var e : map.entrySet()) { if (comma) out.append(','); quote(e.getKey(), out); out.append(':'); write(e.getValue(), out); comma = true; } out.append('}');
    }
    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f"); case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        out.append('"');
    }
    private static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Reader {
        private final String input; private int at; private int nodes;
        Reader(String input) { this.input = input; }
        Object read() { Object value = value(0); space(); check(at == input.length(), "trailing input"); return value; }
        private void check(boolean ok, String reason) { require(ok, Disposition.INVALID_SCHEMA, reason); }
        private void space() { while (at < input.length() && " \t\r\n".indexOf(input.charAt(at)) >= 0) at++; }
        private char take() { check(at < input.length(), "unexpected EOF"); return input.charAt(at++); }
        private Object value(int depth) {
            check(depth <= 64 && ++nodes <= 32768, "nesting/node limit"); space(); char c = take();
            if (c == '"') return string();
            if (c == '{') {
                Map<String, Object> map = new LinkedHashMap<>(); space();
                if (at < input.length() && input.charAt(at) == '}') { at++; return Collections.unmodifiableMap(map); }
                do {
                    space(); check(take() == '"', "object key"); String key = string(); space(); check(take() == ':', "colon");
                    check(!map.containsKey(key), "duplicate key"); map.put(key, value(depth + 1)); space(); char end = take();
                    if (end == '}') break; check(end == ',', "object separator");
                } while (true);
                return Collections.unmodifiableMap(map);
            }
            if (c == '[') {
                List<Object> list = new ArrayList<>(); space();
                if (at < input.length() && input.charAt(at) == ']') { at++; return Collections.unmodifiableList(list); }
                do { list.add(value(depth + 1)); space(); char end = take(); if (end == ']') break; check(end == ',', "array separator"); } while (true);
                return Collections.unmodifiableList(list);
            }
            for (String literal : List.of("true", "false", "null")) if (c == literal.charAt(0)) {
                check(input.startsWith(literal.substring(1), at), "literal"); at += literal.length() - 1;
                return literal.equals("null") ? null : literal.equals("true");
            }
            int start = at - 1; if (c == '-') c = take();
            check(c >= '0' && c <= '9', "integer token");
            if (c != '0') while (at < input.length() && input.charAt(at) >= '0' && input.charAt(at) <= '9') at++;
            check(at == input.length() || " \t\n\r,]}".indexOf(input.charAt(at)) >= 0, "non-integer/leading zero");
            try { long n = Long.parseLong(input.substring(start, at)); check(n >= -MAX_INTEGER && n <= MAX_INTEGER, "integer range"); return n; }
            catch (NumberFormatException invalid) { throw new DecodeException(Disposition.INVALID_SCHEMA, "integer range"); }
        }
        private String string() {
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = take(); if (c == '"') break; check(c >= 32, "control character");
                if (c == '\\') {
                    c = take();
                    switch (c) {
                        case '"', '\\', '/' -> out.append(c); case 'b' -> out.append('\b'); case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                        case 'u' -> {
                            int code = 0;
                            for (int i = 0; i < 4; i++) { int digit = Character.digit(take(), 16); check(digit >= 0, "unicode escape"); code = code * 16 + digit; }
                            out.append((char) code);
                        }
                        default -> check(false, "string escape");
                    }
                } else out.append(c);
            }
            String result = out.toString();
            for (int i = 0; i < result.length(); i++) {
                char c = result.charAt(i);
                if (Character.isHighSurrogate(c)) { check(i + 1 < result.length() && Character.isLowSurrogate(result.charAt(++i)), "unpaired surrogate"); }
                else check(!Character.isLowSurrogate(c), "unpaired surrogate");
            }
            check(Normalizer.isNormalized(result, Normalizer.Form.NFC), "not NFC"); return result;
        }
    }
}
