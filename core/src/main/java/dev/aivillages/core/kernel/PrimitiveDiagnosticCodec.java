package dev.aivillages.core.kernel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.PrimitiveDiagnostics.*;

/** Private schema-1 data encoder/validator. No source parsing, execution, or ambient I/O. */
final class PrimitiveDiagnosticCodec {
    static final long DAY = 86_400_000L;
    static final String KIND = "missing_primitive_demand";
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private PrimitiveDiagnosticCodec() { }

    record Representative(String attemptRef, String generationRef, Generation.Role role,
            ProviderMetadata provider, SkillCompiler.Diagnostic diagnostic,
            List<SkillCompiler.ArgumentObservation> arguments, String candidateSha256,
            String requestProjection, String candidateExcerpt, boolean projectionsTruncated) {
        Representative {
            hex(attemptRef); hex(generationRef); hex(candidateSha256);
            Objects.requireNonNull(role); Objects.requireNonNull(provider);
            if (!diagnostic.code().equals("UNKNOWN_PRIMITIVE")
                    || !diagnostic.path().matches("\\$[a-zA-Z0-9_.\\[\\]]{1,255}"))
                throw invalid();
            arguments = List.copyOf(arguments);
            if (arguments.size() > 16) throw invalid();
            for (int i=0;i<arguments.size();i++) if (arguments.get(i).ordinal()!=i) throw invalid();
            projection(requestProjection, 1024); projection(candidateExcerpt, 4096);
        }
    }
    record Snapshot(String aggregateId, long revision, long occurrences, long firstSeen,
            long lastSeen, long bucketStart, String worldRef, String scopeRef, String principalRef,
            CapabilityId capability, String catalog, CapabilityId primitive, String fingerprint,
            String latestEventId, Representative representative, boolean saturated,
            boolean incomplete, Map<Counter, Long> losses) {
        Snapshot {
            hex(aggregateId); hex(worldRef); hex(scopeRef); hex(principalRef); hex(catalog); hex(fingerprint);
            Objects.requireNonNull(capability); Objects.requireNonNull(primitive);
            Objects.requireNonNull(representative);
            if (revision <= 0 || occurrences <= 0 || firstSeen < 0 || lastSeen < firstSeen
                    || bucketStart < 0 || bucketStart % DAY != 0 || firstSeen < bucketStart
                    || lastSeen >= Math.addExact(bucketStart, DAY)
                    || !latestEventId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}:[1-9][0-9]{0,18}")
                    || latestEventId.length() > 64) throw invalid();
            if (revision == Long.MAX_VALUE && (!saturated || !incomplete)) throw invalid();
            if (occurrences == Long.MAX_VALUE && !saturated) throw invalid();
            try { Long.parseLong(latestEventId.substring(37)); }
            catch(NumberFormatException invalid){throw invalid();}
            losses = Map.copyOf(losses);
            if (losses.size() > Counter.values().length
                    || losses.values().stream().anyMatch(n -> n == null || n < 0)) throw invalid();
            String key = key(bucketStart, worldRef, scopeRef, principalRef, capability, catalog,
                    primitive, fingerprint);
            if (!aggregateId.equals(key)) throw invalid();
        }
        Snapshot next(String eventId, long now, Map<Counter,Long> counters) {
            if (revision == Long.MAX_VALUE) return this;
            long revisionNext = revision + 1;
            long count = occurrences == Long.MAX_VALUE ? occurrences : occurrences + 1;
            return new Snapshot(aggregateId, revisionNext, count, firstSeen, Math.max(lastSeen,now),
                    bucketStart,worldRef,scopeRef,principalRef,capability,catalog,primitive,fingerprint,
                    eventId,representative,saturated || count == Long.MAX_VALUE
                    || revisionNext == Long.MAX_VALUE,true,counters);
        }
    }

    static long bucket(long now) { if (now < 0) throw invalid(); return now - now % DAY; }
    static String hmac(byte[] salt, String domain, UUID... ids) {
        if (salt.length != 32) throw invalid();
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt,"HmacSHA256"));
            part(mac,domain);
            for(UUID id:ids) part(mac,id.toString());
            return HexFormat.of().formatHex(mac.doFinal());
        } catch(java.security.GeneralSecurityException impossible) { throw new IllegalStateException("Diagnostic crypto unavailable"); }
    }
    private static void part(Mac mac,String value) {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); mac.update(bytes);
    }
    static String key(long bucket,String world,String scope,String principal,CapabilityId capability,
            String catalog,CapabilityId primitive,String fingerprint) {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            for(String field:List.of("1",Long.toString(bucket),world,scope,principal,capability.name(),
                    Integer.toString(capability.version()),catalog,primitive.name(),
                    Integer.toString(primitive.version()),fingerprint,"UNKNOWN_PRIMITIVE")) {
                byte[] bytes=field.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static Snapshot initial(Observation o,String eventId,long now,byte[] salt,Policy policy,
            Map<Counter,Long> counters) {
        String world=hmac(salt,"world",o.owner().scope().worldId());
        String scope=hmac(salt,"scope",o.owner().scope().worldId(),o.owner().scope().domainId());
        String principal=hmac(salt,"principal",o.owner().principal().id());
        String attempt=hmac(salt,"attempt",o.attemptId());
        String generation=hmac(salt,"generation",o.generationId());
        String request="",excerpt=""; boolean truncated=false;
        if(policy.mode()==Mode.EXCERPTS && policy.excerptScopes().contains(o.owner().scope())) {
            request=o.requestShape();
            excerpt=StrictJson.canonical(Map.of("primitive",o.reference().requested().name(),
                    "version",(long)o.reference().requested().version(),"fingerprint",o.reference().requestedFingerprint(),
                    "arguments",o.reference().arguments().stream().map(a -> (Object)Map.of(
                            "slot","arg"+a.ordinal(),"observedType",a.observedType().name(),"value","[redacted]")).toList(),
                    "bindings","[alpha-renamed]","context","[redacted]"));
            if(encodedStringBytes(request)>policy.limit(Limit.REQUEST_BYTES)) {
                request=marker(policy.limit(Limit.REQUEST_BYTES)); truncated=true;
            }
            if(encodedStringBytes(excerpt)>policy.limit(Limit.EXCERPT_BYTES)) {
                excerpt=marker(policy.limit(Limit.EXCERPT_BYTES)); truncated=true;
            }
        }
        var rep=new Representative(attempt,generation,o.role(),o.provider(),o.reference().diagnostic(),
                o.reference().arguments(),o.candidateSha256(),request,excerpt,truncated);
        var primitive=o.reference().requested();
        long bucket=bucket(now);
        String id=key(bucket,world,scope,principal,o.capability(),o.reference().catalogFingerprint(),
                primitive,o.reference().requestedFingerprint());
        return new Snapshot(id,1,1,now,now,bucket,world,scope,principal,o.capability(),
                o.reference().catalogFingerprint(),primitive,o.reference().requestedFingerprint(),
                eventId,rep,false,true,counters);
    }
    private static String marker(int cap) {
        String result="{\"truncated\":true}";
        return encodedStringBytes(result)<=cap?result:"";
    }
    static int encodedStringBytes(String s) { return JSON.toJson(s).getBytes(StandardCharsets.UTF_8).length; }
    private static void projection(String s,int cap) {
        if(s==null || !s.chars().allMatch(c->c>=32&&c<=126) || encodedStringBytes(s)>cap && !s.isEmpty())
            throw invalid();
    }
    static byte[] encode(Snapshot s,int cap) {
        byte[] result=bytes(map(s,true));
        if(result.length>cap) result=bytes(map(s,false));
        return result.length<=cap?result:null;
    }
    static byte[] bytes(Object o) { return (JSON.toJson(o)+"\n").getBytes(StandardCharsets.UTF_8); }
    static Map<String,Object> map(Snapshot s,boolean optional) {
        var r=s.representative(); var provider=new LinkedHashMap<String,Object>();
        provider.put("protocol",r.provider().protocol());
        // Optional absent descriptors are explicitly marked; no JSON null coercion.
        provider.put("model",r.provider().model());
        provider.put("digest",r.provider().digest());
        provider.put("modelOmitted",r.provider().modelOmitted()); provider.put("digestOmitted",r.provider().digestOmitted());
        Map<String,Object> rep=new LinkedHashMap<>();
        rep.put("attemptRef",r.attemptRef());rep.put("generationRef",r.generationRef());rep.put("role",r.role().name());
        rep.put("provider",provider);rep.put("candidateSha256",r.candidateSha256());
        rep.put("compiler",Map.of("code",r.diagnostic().code(),"path",r.diagnostic().path(),"context","compiler:1"));
        rep.put("requestedArguments",r.arguments().stream().map(a->(Object)Map.of("ordinal",(long)a.ordinal(),"observedType",a.observedType().name())).toList());
        rep.put("expectedTypesOmitted",true);rep.put("resultTypeOmitted",true);
        rep.put("requestProjection",optional?r.requestProjection():"");rep.put("candidateExcerpt",optional?r.candidateExcerpt():"");
        rep.put("requestProjectionBytes",optional&&!r.requestProjection().isEmpty()?(long)encodedStringBytes(r.requestProjection()):0L);
        rep.put("candidateExcerptBytes",optional&&!r.candidateExcerpt().isEmpty()?(long)encodedStringBytes(r.candidateExcerpt()):0L);
        rep.put("requestProjectionOmitted",!optional||r.requestProjection().isEmpty());
        rep.put("candidateExcerptOmitted",!optional||r.candidateExcerpt().isEmpty());
        boolean truncated=r.projectionsTruncated() || !optional
                && (!r.requestProjection().isEmpty()||!r.candidateExcerpt().isEmpty());
        rep.put("projectionsTruncated",truncated);
        var losses=new TreeMap<String,Object>();s.losses().forEach((k,v)->losses.put(k.name(),v));
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("schema",1L);m.put("kind",KIND);m.put("aggregateId",s.aggregateId());m.put("aggregationKey",s.aggregateId());
        m.put("revision",s.revision());m.put("occurrences",s.occurrences());m.put("firstSeen",s.firstSeen());
        m.put("lastSeen",s.lastSeen());m.put("bucketStart",s.bucketStart());m.put("worldRef",s.worldRef());
        m.put("scopeRef",s.scopeRef());m.put("principalRef",s.principalRef());
        m.put("capability",Map.of("id",s.capability().name(),"version",(long)s.capability().version()));
        m.put("origin","generated");m.put("catalog",Map.of("schema",1L,"fingerprint",s.catalog()));
        m.put("primitive",Map.of("id",s.primitive().name(),"version",(long)s.primitive().version(),
                "requestedFingerprint",s.fingerprint(),"status","requested_id_version_absent"));
        m.put("reason","UNSUPPORTED_PRIMITIVE");m.put("representative",rep);m.put("latestEventId",s.latestEventId());
        m.put("saturated",s.saturated());m.put("truncated",truncated);m.put("incomplete",true);
        m.put("collectionMode",!r.requestProjection().isEmpty()||!r.candidateExcerpt().isEmpty()||truncated?"EXCERPTS":"METADATA");
        m.put("lossCounters",losses);m.put("lossCountersKnown",false);
        return m;
    }
    static Snapshot decode(byte[] bytes) {
        if(bytes.length>8192) throw invalid();
        try {
            Map<String,Object> m=parse(bytes,8192);
            keys(m,"schema","kind","aggregateId","aggregationKey","revision","occurrences","firstSeen","lastSeen","bucketStart",
                    "worldRef","scopeRef","principalRef","capability","origin","catalog","primitive","reason","representative",
                    "latestEventId","saturated","truncated","incomplete","lossCounters","lossCountersKnown","collectionMode");
            if(num(m,"schema")!=1 || !str(m,"kind").equals(KIND) || !str(m,"origin").equals("generated")
                    || !str(m,"reason").equals("UNSUPPORTED_PRIMITIVE") || !str(m,"aggregationKey").equals(str(m,"aggregateId"))
                    || !bool(m,"incomplete") || bool(m,"lossCountersKnown")) throw invalid();
            var cat=obj(m,"catalog");keys(cat,"schema","fingerprint");if(num(cat,"schema")!=1)throw invalid();
            var p=obj(m,"primitive");keys(p,"id","version","requestedFingerprint","status");
            if(!str(p,"status").equals("requested_id_version_absent"))throw invalid();
            var r=obj(m,"representative");keys(r,"attemptRef","generationRef","role","provider","candidateSha256","compiler",
                    "requestedArguments","expectedTypesOmitted","resultTypeOmitted","requestProjection","candidateExcerpt","projectionsTruncated",
                    "requestProjectionBytes","candidateExcerptBytes","requestProjectionOmitted","candidateExcerptOmitted");
            if(!bool(r,"expectedTypesOmitted")||!bool(r,"resultTypeOmitted"))throw invalid();
            var pr=obj(r,"provider");keys(pr,"protocol","model","digest","modelOmitted","digestOmitted");
            var c=obj(r,"compiler");keys(c,"code","path","context");if(!str(c,"context").equals("compiler:1"))throw invalid();
            Object args=r.get("requestedArguments");if(!(args instanceof List<?> list)||list.size()>16)throw invalid();
            List<SkillCompiler.ArgumentObservation> observed=new ArrayList<>();
            for(Object value:list) {
                var a=cast(value);keys(a,"ordinal","observedType");
                observed.add(new SkillCompiler.ArgumentObservation(Math.toIntExact(num(a,"ordinal")),Type.valueOf(str(a,"observedType"))));
            }
            String model=nullable(pr,"model"),digest=nullable(pr,"digest");
            var rep=new Representative(str(r,"attemptRef"),str(r,"generationRef"),Generation.Role.valueOf(str(r,"role")),
                    new ProviderMetadata(str(pr,"protocol"),model,digest,
                            bool(pr,"modelOmitted"),bool(pr,"digestOmitted")),
                    new SkillCompiler.Diagnostic(str(c,"path"),str(c,"code")),observed,str(r,"candidateSha256"),
                    str(r,"requestProjection"),str(r,"candidateExcerpt"),bool(r,"projectionsTruncated"));
            if(num(r,"requestProjectionBytes")!=(rep.requestProjection().isEmpty()?0:encodedStringBytes(rep.requestProjection()))
                    || num(r,"candidateExcerptBytes")!=(rep.candidateExcerpt().isEmpty()?0:encodedStringBytes(rep.candidateExcerpt()))
                    || bool(r,"requestProjectionOmitted")!=rep.requestProjection().isEmpty()
                    || bool(r,"candidateExcerptOmitted")!=rep.candidateExcerpt().isEmpty())throw invalid();
            var losses=new EnumMap<Counter,Long>(Counter.class);
            obj(m,"lossCounters").forEach((key,value)->{if(!(value instanceof Long n)||n<0)throw invalid();losses.put(Counter.valueOf(key),n);});
            var result=new Snapshot(str(m,"aggregateId"),num(m,"revision"),num(m,"occurrences"),num(m,"firstSeen"),
                    num(m,"lastSeen"),num(m,"bucketStart"),str(m,"worldRef"),str(m,"scopeRef"),str(m,"principalRef"),
                    capability(obj(m,"capability")),str(cat,"fingerprint"),capabilityPair(p),str(p,"requestedFingerprint"),
                    str(m,"latestEventId"),rep,bool(m,"saturated"),bool(m,"incomplete"),losses);
            if(!str(m,"collectionMode").equals(!rep.requestProjection().isEmpty()||!rep.candidateExcerpt().isEmpty()
                    || rep.projectionsTruncated()?"EXCERPTS":"METADATA")
                    || bool(m,"truncated")!=rep.projectionsTruncated())throw invalid();
            // The known schema's minimum envelope must remain bounded after normalized encoding.
            if(encode(result,8192)==null)throw invalid();
            return result;
        } catch(ArithmeticException e) { throw invalid(); }
    }
    static Map<String,Object> parse(byte[] bytes,int cap) {
        if(bytes.length>cap)throw invalid();
        try {
            String input=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try(JsonReader reader=new JsonReader(new StringReader(input))) {
                reader.setLenient(false);
                int[] nodes={0};
                Map<String,Object> result=cast(read(reader,0,nodes));
                if(reader.peek()!=JsonToken.END_DOCUMENT)throw invalid();
                return result;
            }
        } catch(IOException | NumberFormatException e) { throw invalid(); }
    }
    private static Object read(JsonReader r,int depth,int[] nodes) throws IOException {
        if(depth>16 || ++nodes[0]>4096)throw invalid();
        return switch(r.peek()) {
            case BEGIN_OBJECT -> {
                r.beginObject();var m=new LinkedHashMap<String,Object>();
                while(r.hasNext()){String key=r.nextName();if(key.length()>128||m.containsKey(key))throw invalid();m.put(key,read(r,depth+1,nodes));}
                r.endObject();yield m;
            }
            case BEGIN_ARRAY -> {
                r.beginArray();List<Object> list=new ArrayList<>();
                while(r.hasNext())list.add(read(r,depth+1,nodes));r.endArray();yield list;
            }
            case STRING -> {
                String s=r.nextString();
                if(s.length()>8192)throw invalid();
                for(int i=0;i<s.length();i++) {
                    char c=s.charAt(i);
                    if(Character.isHighSurrogate(c)){if(++i==s.length()||!Character.isLowSurrogate(s.charAt(i)))throw invalid();}
                    else if(Character.isLowSurrogate(c))throw invalid();
                }
                yield s;
            }
            case NUMBER -> {
                String n=r.nextString();if(!n.matches("-?(0|[1-9][0-9]{0,18})")||n.equals("-0"))throw invalid();yield Long.parseLong(n);
            }
            case BOOLEAN -> r.nextBoolean();
            case NULL -> { r.nextNull();yield null; }
            default -> throw invalid();
        };
    }
    private static CapabilityId capability(Map<String,Object> m) { keys(m,"id","version");return capabilityPair(m); }
    private static CapabilityId capabilityPair(Map<String,Object> m) { return new CapabilityId(str(m,"id"),Math.toIntExact(num(m,"version"))); }
    @SuppressWarnings("unchecked") static Map<String,Object> cast(Object o) { if(!(o instanceof Map<?,?>))throw invalid();return (Map<String,Object>)o; }
    static Map<String,Object> obj(Map<String,Object> m,String k) { return cast(m.get(k)); }
    static String str(Map<String,Object> m,String k) { if(!(m.get(k) instanceof String s))throw invalid();return s; }
    private static String nullable(Map<String,Object> m,String k) { return m.get(k)==null?null:str(m,k); }
    static long num(Map<String,Object> m,String k) { if(!(m.get(k) instanceof Long n))throw invalid();return n; }
    static boolean bool(Map<String,Object> m,String k) { if(!(m.get(k) instanceof Boolean b))throw invalid();return b; }
    static void keys(Map<String,Object> m,String...k) { if(!m.keySet().equals(Set.of(k)))throw invalid(); }
    static void hex(String s) { if(s==null||!s.matches("[a-f0-9]{64}"))throw invalid(); }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid diagnostic record"); }
}
