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
    private static final java.util.regex.Pattern TOKEN=java.util.regex.Pattern.compile("\\{\\\"\\$a04Raw\\\":\\\"([0-9a-f]{64})\\\"\\}");
    public record Span(int begin,int end) {}
    /** Scan the exact serialized bytes once; never rewrite a whole frame per row. */
    private static final class Frame {
        final String raw;final List<Span> spans=new ArrayList<>();int at;long time=-1,pid=-1;
        Frame(String raw)throws IOException {this.raw=raw;object(0,0);space();if(at!=raw.length()||time<0||pid<0)throw new IOException("Invalid observation frame");}
        void space(){while(at<raw.length()&&Character.isWhitespace(raw.charAt(at)))at++;}
        boolean take(char c){space();if(at<raw.length()&&raw.charAt(at)==c){at++;return true;}return false;}
        void need(char c)throws IOException {if(!take(c))throw new IOException("Invalid observation punctuation");}
        void string()throws IOException {
            need('"');boolean escaped=false;
            while(at<raw.length()){char c=raw.charAt(at++);if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')return;}
            throw new IOException("Incomplete observation string");
        }
        void value(int depth)throws IOException {
            if(depth>128)throw new IOException("Observation nesting quota");space();if(at>=raw.length())throw new IOException("Missing observation value");
            char c=raw.charAt(at);
            if(c=='"'){string();return;}
            if(c=='{'||c=='['){char close=c=='{'?'}':']';at++;if(take(close))return;
                do{if(c=='{'){string();need(':');}value(depth+1);}while(take(','));need(close);return;}
            int begin=at;while(at<raw.length()&&",}] \t\r\n".indexOf(raw.charAt(at))<0)at++;
            if(begin==at)throw new IOException("Empty observation value");
        }
        void reference(int depth)throws IOException {space();int begin=at;value(depth);if(at-begin>=256)spans.add(new Span(begin,at));}
        void array(int depth)throws IOException {need('[');if(take(']'))return;do{reference(depth+1);}while(take(','));need(']');}
        // Modes: complete frame, knownPlayers, one authorized player, jobs/runs map.
        void object(int mode,int depth)throws IOException {
            if(depth>128)throw new IOException("Observation nesting quota");need('{');if(take('}'))return;
            do{space();int begin=at;string();String name=JSON.fromJson(raw.substring(begin,at),String.class);need(':');space();begin=at;
                if(mode==0&&(name.equals("knownPlayers")||name.equals("players")))object(1,depth+1);
                else if(mode==1)object(2,depth+1);
                else if(mode==2&&(name.equals("jobs")||name.equals("runs")))object(3,depth+1);
                else if(mode==2&&name.equals("leases"))array(depth+1);
                else if(mode==3||(mode==2&&name.equals("catalog")))reference(depth+1);
                else {value(depth+1);if(mode==0&&(name.equals("time")||name.equals("pid"))){
                    long number;try{number=Long.parseLong(raw.substring(begin,at));}catch(NumberFormatException bad){throw new IOException("Invalid observation identity",bad);}
                    if(name.equals("time"))time=number;else pid=number;
                }}
            }while(take(','));need('}');
        }
    }
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
        Frame parsed=new Frame(frame);append(path,frame,parsed.spans,parsed.time,parsed.pid);
    }
    public static synchronized void append(Path path,RuntimeAObservationJson.Encoded frame)throws IOException {
        if(frame.time()<0||frame.pid()<0)throw new IOException("Missing encoded frame attribution");
        append(path,frame.data(),frame.spans(),frame.time(),frame.pid());
    }
    private static void append(Path path,String frame,List<Span> spans,long time,long pid)throws IOException {
        State state=STATES.get(path);if(state==null){state=new State(path);STATES.put(path,state);}
        if(TOKEN.matcher(frame).find())throw new IOException("Reserved evidence reference collision");
        var references=new HashMap<String,String>();var template=new StringBuilder(frame.length());int end=0;
        for(Span span:spans) {
            if(span.begin()<end||span.end()<=span.begin()||span.end()>frame.length())throw new IOException("Invalid encoded observation span");
            String literal=frame.substring(span.begin(),span.end()),digest=state.store.body(literal);
            String token="{\"$a04Raw\":\""+digest+"\"}";references.put(digest,literal);
            template.append(frame,end,span.begin()).append(token);end=span.end();
        }
        template.append(frame,end,frame.length());var matcher=TOKEN.matcher(template);var restored=new StringBuilder(frame.length());end=0;
        while(matcher.find()) {String literal=references.get(matcher.group(1));if(literal==null)throw new IOException("Unknown observation body");
            restored.append(template,end,matcher.start()).append(literal);end=matcher.end();}
        restored.append(template,end,template.length());
        if(!restored.toString().equals(frame))throw new IOException("Observation reference round trip changed exact bytes");
        var checked=JsonParser.parseString(template.toString()).getAsJsonObject();
        if(checked.get("time").getAsLong()!=time||checked.get("pid").getAsLong()!=pid)throw new IOException("Encoded frame attribution changed");
        var wrapper=new LinkedHashMap<String,Object>();wrapper.put("schema",1);wrapper.put("time",time);
        wrapper.put("pid",pid);wrapper.put("originalSha256",sha256(frame.getBytes(StandardCharsets.UTF_8)));wrapper.put("template",template.toString());
        String line=JSON.toJson(wrapper)+"\n";long bytes=line.getBytes(StandardCharsets.UTF_8).length;
        if(bytes>SHARD_BYTES)throw new IOException("Observation frame quota");if(state.bytes+bytes>SHARD_BYTES)state.next();
        Files.writeString(state.path(),line,StandardOpenOption.APPEND);state.bytes+=bytes;
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);Path path=root.resolve("observations.jsonl");
        String job="{\"id\":\"actual-test-only-row\",\"text\":\""+"<>& Unicode λ ".repeat(40)+"\",\"number\":0.000001}";
        String first=JSON.toJson(JsonParser.parseString("{\"time\":1,\"pid\":2,\"knownPlayers\":{\"PlayerA\":{\"jobs\":{\"one\":"+job+"},\"runs\":{}}},\"native\":\"unchanged\"}"));
        append(path,first);append(path,first.replace("\"time\":1","\"time\":2"));
        String many="{\"time\":3,\"pid\":2,\"knownPlayers\":{\"PlayerA\":{\"jobs\":{\"a\":"+job+",\"b\":"+job+"},\"runs\":{\"r\":{\"responsibility\":"+job+"}},\"leases\":["+job+"],\"catalog\":"+job+"}},\"native\":{\"escaped\":\"quote \\\" brace } slash \\\\ unicode λ\"}}";
        append(path,many);
        // The live players map aliases the authorized knownPlayers map in native
        // frames. Both serialized copies must be referenced, without changing either.
        var duplicate=JsonParser.parseString(many).getAsJsonObject();duplicate.add("players",duplicate.get("knownPlayers"));duplicate.addProperty("time",4);
        String doubled=JSON.toJson(duplicate);var encoded=RuntimeAObservationJson.encode(duplicate);
        if(!encoded.data().equals(doubled)||!encoded.spans().equals(new Frame(doubled).spans))throw new IOException("Encoding positions differ from the independent full-frame scanner");
        append(path,encoded);
        var compact=JsonParser.parseString(Files.readAllLines(STATES.get(path).path()).getLast()).getAsJsonObject().get("template").getAsString();
        if(compact.length()*2>=doubled.length()||compact.contains("actual-test-only-row"))throw new IOException("Both authorized player maps must use exact references");
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
        if(checked!=4)throw new IOException("Missing exact evidence self-test frames");
        System.out.println("A04_OBSERVATION_REFERENCE_SELF_TEST_PASS "+checked);
    }
}
