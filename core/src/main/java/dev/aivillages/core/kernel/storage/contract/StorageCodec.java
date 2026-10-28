package dev.aivillages.core.kernel.storage.contract;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
import static dev.aivillages.core.kernel.storage.contract.StorageWire.*;

/** Strict frozen wire validation. A decoded ALLOW/proof is still an untrusted projection. */
public final class StorageCodec {
    public enum Disposition { VALID, INVALID_SCHEMA, INVALID_SEMANTICS, REFUSED_EXPECTED }
    public static final class Invalid extends IllegalArgumentException {
        private final Disposition disposition;
        Invalid(Disposition disposition, String rule) { super(rule); this.disposition = disposition; }
        public Disposition disposition() { return disposition; }
    }
    private static final Map<String, Object> SCHEMA = object(StorageJson.parse(StorageSchema.JSON));
    static {
        if (!sha256(StorageSchema.JSON).equals(StorageSchema.SHA256))
            throw new ExceptionInInitializerError("frozen schema digest changed");
    }

    public static Message decode(byte[] bytes) {
        if (bytes == null || bytes.length > StorageJson.MAX_BYTES) throw invalid("wire byte limit");
        try {
            return decode(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException e) { throw invalid("UTF-8 required"); }
    }
    public static Message decode(String input) {
        Map<String, Object> envelope;
        try {
            envelope = object(StorageJson.parse(input));
            if (!StorageJson.canonical(envelope).equals(input)) throw invalid("canonical bytes required");
            shape(envelope, SCHEMA);
        } catch (IllegalArgumentException | ClassCastException e) { throw invalid("invalid wire shape: " + e.getMessage()); }
        Kind kind = Kind.valueOf((String) envelope.get("kind"));
        Map<String, Object> body = object(envelope.get("body"));
        semantics(kind.name(), body);
        return new Message(kind, body);
    }
    public static String encode(Message message) {
        return StorageJson.canonical(Map.of("schema", VERSION, "kind", message.kind().name(), "body", message.body()));
    }
    /** Context is a separate trusted-adapter argument; no wire context is accepted. */
    public static Disposition evaluate(Message message, Context context) {
        Objects.requireNonNull(context);
        var b = message.body();
        boolean accepted = switch (message.kind()) {
            case ActivityResumeSignal -> (context.trustedGeneration() == null
                    || context.trustedGeneration().equals(at(b,"activity").get("execution_generation")))
                    && !Boolean.FALSE.equals(context.requirementsSatisfied());
            case PlayerReplyIntent -> Objects.equals(context.authenticatedPrincipal(), context.questionPrincipal());
            case ResolvedEndpoint -> !is(b,"kind","STORAGE")
                    || ((context.currentIncarnation() == null || context.currentIncarnation().equals(
                            object(list(at(b,"binding"),"members").get(0)).get("incarnation_id")))
                    && (context.currentAclRevision() == null || context.currentAclRevision().equals(at(b,"access").get("acl_revision"))));
            case CustodyCheckpoint -> !highTier(b) || Boolean.TRUE.equals(context.aProofAuthenticated());
            case StorageSelector -> context.eligibleRegisteredCount() == null || context.eligibleRegisteredCount() == 1L;
            default -> true;
        };
        return accepted ? Disposition.VALID : Disposition.REFUSED_EXPECTED;
    }

    private static void shape(Object value, Map<String, Object> s) {
        if (s.containsKey("$ref")) {
            String name = (String)s.get("$ref");
            shape(value, at(at(SCHEMA,"definitions"),name.substring(name.lastIndexOf('/')+1))); return;
        }
        if (s.containsKey("oneOf")) {
            int successes = 0;
            for (Object choice : list(s,"oneOf")) {
                try { shape(value,object(choice)); successes++; } catch (IllegalArgumentException ignored) { }
            }
            if (successes != 1) throw invalid("oneOf"); return;
        }
        if (s.containsKey("const") && !Objects.equals(value,s.get("const"))) throw invalid("const");
        if (s.containsKey("enum") && !list(s,"enum").contains(value)) throw invalid("enum");
        String type = (String)s.get("type");
        if (type == null) return;
        switch (type) {
            case "null" -> { if (value != null) throw invalid("null"); }
            case "integer" -> {
                if (!(value instanceof Long n) || n < bound(s,"minimum",-StorageJson.MAX_INTEGER)
                        || n > bound(s,"maximum",StorageJson.MAX_INTEGER)) throw invalid("integer");
            }
            case "string" -> {
                if (!(value instanceof String text)) throw invalid("string");
                int length = text.codePointCount(0,text.length());
                if (length < bound(s,"minLength",0) || length > bound(s,"maxLength",Integer.MAX_VALUE)) throw invalid("string length");
                if (s.containsKey("pattern") && !Pattern.compile((String)s.get("pattern")).matcher(text).find()) throw invalid("pattern");
            }
            case "array" -> {
                if (!(value instanceof List<?> items) || items.size() < bound(s,"minItems",0)
                        || items.size() > bound(s,"maxItems",Integer.MAX_VALUE)) throw invalid("array");
                for (Object item : items) shape(item,at(s,"items"));
            }
            case "object" -> {
                if (!(value instanceof Map<?, ?>)) throw invalid("object");
                var map = object(value); var properties = at(s,"properties");
                if (!map.keySet().containsAll(list(s,"required"))) throw invalid("required");
                if (Boolean.FALSE.equals(s.get("additionalProperties")) && !properties.keySet().containsAll(map.keySet())) throw invalid("unknown fields");
                map.forEach((key,item) -> { if (properties.containsKey(key)) shape(item,at(properties,key)); });
            }
            default -> throw invalid("unsupported frozen type");
        }
    }

    private static void semantics(String kind, Map<String, Object> b) {
        switch (kind) {
            case "DialogueIntent" -> {
                String action = (String)b.get("action");
                if (Set.of("CHAT","FORGET","COME","FOLLOW","STOP","STATUS").contains(action))
                    require(b.get("source") == null && b.get("destination") == null && list(b,"items").isEmpty()
                            && b.get("label") == null && b.get("role") == null && b.get("requested_access") == null,"S16 unrelated fields");
                require(action.equals("DELIVER") || list(b,"items").isEmpty(),"S16 items");
                require(action.equals("SET_STORAGE_ACCESS") || b.get("requested_access") == null,"S16 access");
            }
            case "PlayerReplyIntent" -> require(is(b,"reply","CHOOSE_TARGET") || b.get("selection") == null,"S16 reply selection");
            case "StorageRoleAssignment" -> require(!is(at(b,"beneficiary"),"kind","PUBLIC"),"S15 public beneficiary");
            case "StorageRecord" -> {
                sortedSet(list(b,"roles"));
                for (Object grant : list(b,"acl")) {
                    var g = object(grant); sortedSet(list(g,"permissions"));
                    if (is(at(g,"subject"),"kind","PUBLIC"))
                        require(Set.of("INSPECT","DEPOSIT","WITHDRAW").containsAll(list(g,"permissions")),"S04 public admin");
                }
            }
            case "SelectionWitness" -> require(num(b,"captured_tick") <= num(b,"expires_tick")
                    && num(b,"expires_tick") - num(b,"captured_tick") <= 40,"S06 selection expiry");
            case "BlockerEvidence" -> {
                if (!list(b,"requirements").isEmpty()) require(is(b,"knowledge","KNOWN") && is(b,"visibility","AUTHORIZED_FACTS")
                        && is(b,"continuation","FEASIBLE_AFTER_ASSISTANCE") && is(b,"code","INSUFFICIENT_MATERIAL"),"S02 material provenance");
                if (is(b,"visibility","REDACTED")) require(b.get("storage_id") == null && list(b,"requirements").isEmpty(),"S02 privacy");
            }
            case "AssistanceRequest" -> {
                semantics("BlockerEvidence",at(b,"blocker"));
                require(num(b,"expires_ms") <= num(at(b,"activity"),"absolute_deadline_ms")
                        && Objects.equals(at(b,"blocker").get("activity"),b.get("activity")),"S03 root/deadline");
            }
            case "StorageAccessDecision" -> {
                require(num(b,"evaluated_tick") <= num(b,"valid_through_tick")
                        && num(b,"valid_through_tick") - num(b,"evaluated_tick") <= 2,"S04 expiry");
                require(!is(b,"decision","ALLOW") || b.get("reason") == null,"S04 allow reason");
            }
            case "PhysicalStorageBinding" -> binding(b);
            case "ResolvedEndpoint" -> {
                if (is(b,"kind","STORAGE")) {
                    var binding = at(b,"binding"); var access = at(b,"access");
                    semantics("PhysicalStorageBinding",binding); semantics("StorageAccessDecision",access);
                    require(binding.get("storage_id").equals(access.get("storage_id"))
                            && binding.get("binding_generation").equals(access.get("binding_generation"))
                            && binding.get("world_id").equals(at(access,"scope").get("world_id")),"S06 endpoint identity");
                }
            }
            case "MobilityGoal" -> {
                var actor = at(b,"actor"); var activity = at(b,"activity");
                require(actor.get("world_id").equals(at(activity,"scope").get("world_id"))
                        && actor.get("citizen_id").equals(activity.get("responsible_citizen_id")),"S07 actor");
                switch ((String)b.get("mode")) {
                    case "COME_TO_PLAYER" -> require(b.get("captured_position") != null && b.get("player_id") != null && b.get("storage_target") == null,"S07 come");
                    case "FOLLOW_PLAYER" -> require(b.get("player_id") != null && b.get("captured_position") == null && b.get("storage_target") == null,"S07 follow");
                    case "GO_NAMED_STORAGE" -> require(b.get("storage_target") != null && is(at(b,"storage_target"),"kind","STORAGE")
                            && b.get("player_id") == null && b.get("captured_position") == null,"S07 named");
                    default -> throw new AssertionError("validated mode");
                }
                require(num(b,"follow_min_milliblocks") <= num(b,"follow_max_milliblocks"),"S07 range");
            }
            case "DeliveryGoal" -> delivery(b);
            case "CustodyCheckpoint" -> {
                if (highTier(b)) {
                    require(b.get("a_proof_reference") != null,"S09 proof");
                    for (Object p : list(b,"participants")) require(object(p).get("save_evidence_id") != null,"S09 participant saves");
                }
                if (is(b,"tier","ORIGINAL_OWNER_SETTLED")) require(is(b,"original_effect_outcome","SETTLED"),"S09 settled");
            }
            case "StageManifest" -> {
                for (String name : List.of("unit","python","game_tests","client_e2e")) {
                    var m = at(b,name);
                    require(num(m,"executed") == num(m,"passed") + num(m,"failed") + num(m,"refused_expected") + num(m,"inconclusive")
                            && num(m,"executed") <= num(m,"planned"),"S14 counts");
                    if (is(b,"gate_status","BRANCH_READY")) require(num(m,"failed") == 0 && num(m,"inconclusive") == 0
                            && num(m,"executed") == num(m,"planned"),"S14 incomplete");
                }
                if (is(b,"gate_status","BRANCH_READY")) {
                    for (String pin : List.of("source_sha","tree_sha","jar_sha256","docs_sha","workflow_run","attempt"))
                        require(b.get(pin) != null,"S14 pin");
                    require(list(b,"regressions").isEmpty(),"S14 regressions");
                }
            }
            default -> { }
        }
    }
    private static void binding(Map<String, Object> b) {
        var members = list(b,"members"); boolean twice = is(b,"adapter_id","minecraft:chest_double/v1");
        require(members.size() == (twice ? 2 : 1),"S05 member count");
        var ids = new HashSet<>();
        for (Object member : members) require(ids.add(object(member).get("incarnation_id")),"S05 duplicate incarnation");
        var sorted = new ArrayList<>(members);
        sorted.sort(Comparator.<Object>comparingLong(m -> num(object(m),"x")).thenComparingLong(m -> num(object(m),"y"))
                .thenComparingLong(m -> num(object(m),"z")).thenComparing(m -> (String)object(m).get("incarnation_id")));
        require(sorted.equals(members),"S05 member order");
        String facing = (String)b.get("facing");
        require(is(b,"adapter_id","minecraft:barrel/v1") || !Set.of("UP","DOWN").contains(facing),"S05 facing");
        if (twice) {
            var a = object(members.get(0)); var z = object(members.get(1));
            require(num(a,"y") == num(z,"y") && Math.abs(num(a,"x")-num(z,"x")) + Math.abs(num(a,"z")-num(z,"z")) == 1,"S05 adjacency");
            require(!(Set.of("NORTH","SOUTH").contains(facing) && num(a,"x") == num(z,"x"))
                    && !(Set.of("EAST","WEST").contains(facing) && num(a,"z") == num(z,"z")),"S05 alignment");
        }
        var topology = new LinkedHashMap<String,Object>();
        for (String key : List.of("adapter_id","world_id","dimension","members","facing")) topology.put(key,b.get(key));
        require(sha256(StorageJson.canonical(topology)).equals(b.get("topology_sha256")),"S05 topology digest");
    }
    private static void delivery(Map<String, Object> b) {
        var worlds = new ArrayList<List<Object>>(); var ids = new ArrayList<Object>();
        String[] endpoints = {"source","destination"}; String[] permissions = {"WITHDRAW","DEPOSIT"};
        for (int i = 0; i < 2; i++) {
            var ep = at(b,endpoints[i]); semantics("ResolvedEndpoint",ep); Map<String,Object> identity;
            if (is(ep,"kind","STORAGE")) {
                identity = at(ep,"binding"); var access = at(ep,"access");
                require(access.get("scope").equals(at(b,"activity").get("scope")),"S08 scope");
                require(is(access,"operation",permissions[i]) && is(access,"decision","ALLOW") && is(identity,"status","VERIFIED_LIVE"),"S08 storage permission");
                ids.add(identity.get("storage_id"));
            } else {
                identity = at(ep,"citizen");
                require(ep.get("scope").equals(at(b,"activity").get("scope")) && is(ep,"permission",permissions[i])
                        && is(ep,"access_decision","ALLOW"),"S08 recipient");
                ids.add(identity.get("citizen_id"));
            }
            worlds.add(List.of(identity.get("world_id"),identity.get("dimension")));
        }
        var carrier = at(b,"carrier"); var activity = at(b,"activity");
        require(worlds.get(0).equals(worlds.get(1)) && worlds.get(0).equals(List.of(carrier.get("world_id"),carrier.get("dimension")))
                && !ids.get(0).equals(ids.get(1)),"S08 world/distinct identity");
        require(carrier.get("world_id").equals(at(activity,"scope").get("world_id"))
                && carrier.get("citizen_id").equals(activity.get("responsible_citizen_id")),"S08 carrier");
        require(!is(at(b,"source"),"kind","CITIZEN") || at(b,"source").get("citizen").equals(carrier),"S08 third-party donor");
    }
    private static void sortedSet(List<Object> values) {
        var expected = new TreeSet<String>(); values.forEach(v -> expected.add((String)v));
        require(new ArrayList<>(expected).equals(values),"S11 canonical set");
    }
    private static boolean highTier(Map<String,Object> b) { return Set.of("PARTICIPANT_SAVES_CONFIRMED","ORIGINAL_OWNER_SETTLED").contains(b.get("tier")); }
    private static boolean is(Map<String,Object> b,String key,String value) { return value.equals(b.get(key)); }
    private static long num(Map<String,Object> b,String key) { return (Long)b.get(key); }
    private static long bound(Map<String,Object> b,String key,long fallback) { return b.containsKey(key) ? num(b,key) : fallback; }
    private static Map<String,Object> at(Map<String,Object> b,String key) { return object(b.get(key)); }
    @SuppressWarnings("unchecked") private static List<Object> list(Map<String,Object> b,String key) { return (List<Object>)b.get(key); }
    private static void require(boolean valid,String rule) { if (!valid) throw new Invalid(Disposition.INVALID_SEMANTICS,rule); }
    private static Invalid invalid(String reason) { return new Invalid(Disposition.INVALID_SCHEMA,reason); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private StorageCodec() { }
}
