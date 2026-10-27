package dev.aivillages.fabric;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.aivillages.core.kernel.runtime.persistence.ArchiveFormat.sha256;

/** Test-only exact UTF-8 reference log. The ordinary latest snapshot and all observation semantics stay intact. */
public final class RuntimeAObservationLog {
    private static final Gson JSON=new Gson();
    private static final long SHARD_BYTES=64L*1024*1024, BODY_BYTES=512L*1024*1024;
    private static final int MAX_BODIES=32768, MAX_PARTS=128;
    private static final Map<Path,State> STATES=new HashMap<>();
    private static final Map<Path,Bodies> BODY_STORES=new HashMap<>();
    private static final class State {
        final Path root;final String name;final Bodies store;int part=-1;long bytes;
        State(Path path)throws IOException {
            root=path.getParent();name=path.getFileName().toString().replace(".jsonl","");
            Bodies known=BODY_STORES.get(root);if(known==null){known=new Bodies(root);BODY_STORES.put(root,known);}store=known;next();
        }
        Path path(){return root.resolve(name+".refs-"+String.format(Locale.ROOT,"%03d",part)+".jsonl");}
        void next()throws IOException {
            do{if(++part>=MAX_PARTS)throw new IOException("Bounded observation log quota");}while(Files.exists(path()));
            Files.createFile(path());bytes=0;
        }
    }
    private static final class Bodies {
        final Path root;long bodyBytes;int bodies;
        final Set<String> known=new HashSet<>();
        final LinkedHashMap<String,String> cache=new LinkedHashMap<>(256,.75f,true) {
            protected boolean removeEldestEntry(Map.Entry<String,String> entry){return size()>256;}
        };
        Bodies(Path root)throws IOException {
            this.root=root;
            Path directory=root.resolve("observation-bodies");Files.createDirectories(directory);
            try(var stream=Files.list(directory)) {
                for(Path body:stream.toList()){if(++bodies>MAX_BODIES)throw new IOException("Evidence body quota");bodyBytes+=Files.size(body);}
            }
            if(bodyBytes>BODY_BYTES)throw new IOException("Evidence byte quota");
        }
        String body(String text)throws IOException {
            String digest=cache.get(text);if(digest!=null)return digest;
            byte[] content=text.getBytes(StandardCharsets.UTF_8);if(content.length>4*1024*1024)throw new IOException("Bounded evidence body");
            digest=sha256(content);Path target=root.resolve("observation-bodies").resolve(digest+".json");
            if(!known.contains(digest)) {
                if(Files.exists(target)) {if(!Arrays.equals(content,Files.readAllBytes(target)))throw new IOException("Evidence body collision");}
                else {
                    if(bodies>=MAX_BODIES || bodyBytes+content.length>BODY_BYTES)throw new IOException("Evidence body capacity");
                    Path pending=target.resolveSibling(digest+".next");
                    if(Files.exists(pending))Files.move(pending,pending.resolveSibling(digest+".interrupted-"+UUID.randomUUID()));
                    Files.write(pending,content,StandardOpenOption.CREATE_NEW);
                    Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE);bodies++;bodyBytes+=content.length;
                }
                known.add(digest);
            }
            cache.put(text,digest);return digest;
        }
    }
    public static synchronized void append(Path path,String frame)throws IOException {
        State state=STATES.get(path);if(state==null){state=new State(path);STATES.put(path,state);}
        var parsed=JsonParser.parseString(frame).getAsJsonObject();var literals=new LinkedHashSet<String>();
        var players=parsed.getAsJsonObject("knownPlayers");
        if(players!=null)for(var player:players.entrySet()) {
            var data=player.getValue().getAsJsonObject();
            for(String field:List.of("jobs","runs")) {
                var rows=data.getAsJsonObject(field);if(rows!=null)for(var row:rows.entrySet())literals.add(JSON.toJson(row.getValue()));
            }
            var leases=data.getAsJsonArray("leases");if(leases!=null)for(var lease:leases)literals.add(JSON.toJson(lease));
            if(data.has("catalog"))literals.add(JSON.toJson(data.get("catalog")));
        }
        String template=frame;var references=new LinkedHashMap<String,String>();
        for(String literal:literals)if(literal.length()>=256 && template.contains(literal)) {
            String digest=state.store.body(literal),token="{\"$a04Raw\":\""+digest+"\"}";
            if(frame.contains(token))throw new IOException("Reserved evidence reference collision");
            template=template.replace(literal,token);references.put(token,literal);
        }
        String restored=template;for(var reference:references.entrySet())restored=restored.replace(reference.getKey(),reference.getValue());
        if(!restored.equals(frame))throw new IOException("Observation reference round trip changed exact bytes");
        var wrapper=new LinkedHashMap<String,Object>();wrapper.put("schema",1);wrapper.put("time",parsed.get("time").getAsLong());
        wrapper.put("pid",parsed.get("pid").getAsLong());wrapper.put("originalSha256",sha256(frame.getBytes(StandardCharsets.UTF_8)));wrapper.put("template",template);
        String line=JSON.toJson(wrapper)+"\n";long bytes=line.getBytes(StandardCharsets.UTF_8).length;
        if(bytes>SHARD_BYTES)throw new IOException("Observation frame quota");if(state.bytes+bytes>SHARD_BYTES)state.next();
        Files.writeString(state.path(),line,StandardOpenOption.APPEND);state.bytes+=bytes;
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);Path path=root.resolve("observations.jsonl");
        String job="{\"id\":\"actual-test-only-row\",\"text\":\""+"<>& Unicode λ ".repeat(40)+"\",\"number\":0.000001}";
        String first=JSON.toJson(JsonParser.parseString("{\"time\":1,\"pid\":2,\"knownPlayers\":{\"PlayerA\":{\"jobs\":{\"one\":"+job+"},\"runs\":{}}},\"native\":\"unchanged\"}"));
        append(path,first);append(path,first.replace("\"time\":1","\"time\":2"));
        int checked=0;
        for(String line:Files.readAllLines(STATES.get(path).path())) {
            var row=JsonParser.parseString(line).getAsJsonObject();String raw=row.get("template").getAsString();
            if(!raw.contains("$a04Raw"))throw new IOException("Self-test must exercise actual references");
            var matcher=java.util.regex.Pattern.compile("\\{\\\"\\$a04Raw\\\":\\\"([0-9a-f]{64})\\\"\\}").matcher(raw);
            StringBuffer result=new StringBuffer();while(matcher.find()) {
                String body=Files.readString(root.resolve("observation-bodies").resolve(matcher.group(1)+".json"));
                if(!sha256(body.getBytes(StandardCharsets.UTF_8)).equals(matcher.group(1)))throw new IOException("Invalid self-test body");
                matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(body));
            }
            matcher.appendTail(result);if(!sha256(result.toString().getBytes(StandardCharsets.UTF_8)).equals(row.get("originalSha256").getAsString()))throw new IOException("Self-test raw frame mismatch");checked++;
        }
        if(checked!=2)throw new IOException("Missing exact evidence self-test frames");
        System.out.println("A04_OBSERVATION_REFERENCE_SELF_TEST_PASS "+checked);
    }
}
