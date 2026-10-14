package dev.aivillages.core.kernel;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static dev.aivillages.core.kernel.KnowledgeAccess.*;

/** Checksummed bounded JSON-lines envelope. Exact record fields and primitive types precede decoding. */
final class KnowledgeAccessCodec {
    static final int MAX_BYTES=1_048_576, MAX_ROWS=770;
    private static final Gson JSON=new Gson();
    private KnowledgeAccessCodec(){ }
    static byte[] encode(Snapshot snapshot)throws IOException{
        var lines=new ArrayList<String>();
        lines.add(row("migration",Map.of("migrated",snapshot.migrated(),"citizens",snapshot.legacyCitizens(),"methods",snapshot.legacyMethods())));
        for(var value:snapshot.grants())lines.add(row("grant",value));
        for(var value:snapshot.practices())lines.add(row("practice",value));
        String payload=lines.isEmpty()?"":String.join("\n",lines)+"\n";
        String header=StrictJson.canonical(Map.of("schema",1L,"world",snapshot.world().toString(),"revision",snapshot.revision(),
                "records",(long)lines.size(),"sha256",sha(payload.getBytes(StandardCharsets.UTF_8))));
        byte[] result=(header+"\n"+payload).getBytes(StandardCharsets.UTF_8);
        if(result.length>MAX_BYTES||lines.size()>MAX_ROWS)throw new IOException("Access byte/record bound");return result;
    }
    private static String row(String kind,Object value)throws IOException{
        try{return StrictJson.canonical(Map.of("kind",kind,"value",StrictJson.object(JSON.toJson(value))));}
        catch(StrictJson.Invalid invalid){throw new IOException("Access record bound",invalid);}
    }
    static Snapshot decode(byte[] data,UUID world)throws IOException{
        if(data.length>MAX_BYTES)throw new IOException("Access byte bound");
        try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data)).toString();
            if(!text.endsWith("\n"))throw new IOException("Incomplete access");
            int at=text.indexOf('\n');var header=StrictJson.object(text.substring(0,at));
            Outcomes.exact(header,"schema","world","revision","records","sha256");
            if(!Long.valueOf(1).equals(header.get("schema")))throw new IOException("Unsupported access schema");
            if(!world.toString().equals(Outcomes.string(header,"world","$")))throw new IOException("Foreign access world");
            String payload=text.substring(at+1);
            if(!sha(payload.getBytes(StandardCharsets.UTF_8)).equals(Outcomes.string(header,"sha256","$")))throw new IOException("Access checksum");
            var grants=new ArrayList<Grant>();var practices=new ArrayList<Practice>();
            List<Enrollment> citizens=null;List<Contracts.ArtifactRef> methods=null;boolean migrated=false;
            int rows=0;
            for(String line:payload.split("\n")){
                if(line.isEmpty())continue;if(++rows>MAX_ROWS)throw new IOException("Access row bound");
                var row=StrictJson.object(line);Outcomes.exact(row,"kind","value");var value=Outcomes.object(row,"value","$");
                switch(Outcomes.string(row,"kind","$")){
                    case "migration"->{
                        if(citizens!=null)throw new IOException("Duplicate migration");
                        Outcomes.exact(value,"migrated","citizens","methods");
                        if(!(value.get("migrated") instanceof Boolean flag))throw new IOException("Migration type");migrated=flag;
                        if(!(value.get("citizens") instanceof List<?> es)||!(value.get("methods") instanceof List<?> ms))throw new IOException("Migration lists");
                        citizens=new ArrayList<>();methods=new ArrayList<>();
                        for(Object e:es)citizens.add(record((Map<String,Object>)e,Enrollment.class));
                        for(Object m:ms)methods.add(record((Map<String,Object>)m,Contracts.ArtifactRef.class));
                    }
                    case "grant"->grants.add(record(value,Grant.class));
                    case "practice"->practices.add(record(value,Practice.class));
                    default->throw new IOException("Unknown access record");
                }
            }
            if(Outcomes.number(header,"records","$")!=rows)throw new IOException("Access record count");
            if(citizens==null)throw new IOException("Missing migration");
            return new Snapshot(world,Outcomes.number(header,"revision","$"),migrated,citizens,methods,grants,practices);
        }catch(StrictJson.Invalid|RuntimeException invalid){throw new IOException("Invalid access",invalid);}
    }
    private static <T>T record(Map<String,Object> data,Class<T> type)throws IOException{
        shape(data,type);return JSON.fromJson(StrictJson.canonical(data),type);
    }
    private static void shape(Object value,Type type)throws IOException{
        if(value==null){if(type instanceof Class<?> c&&c.isPrimitive())throw new IOException("Null primitive");return;}
        if(type instanceof ParameterizedType p){
            if(p.getRawType()==List.class){if(!(value instanceof List<?> list))throw new IOException("Expected list");for(Object item:list)shape(item,p.getActualTypeArguments()[0]);return;}
            if(p.getRawType()==Map.class){if(!(value instanceof Map<?,?> map))throw new IOException("Expected map");
                for(var entry:map.entrySet()){shape(entry.getKey(),p.getActualTypeArguments()[0]);shape(entry.getValue(),p.getActualTypeArguments()[1]);}return;}
        }
        if(!(type instanceof Class<?> c))throw new IOException("Unregistered record type");
        if(c.isRecord()){
            if(!(value instanceof Map<?,?> map))throw new IOException("Expected record");
            var names=new HashSet<String>();for(var field:c.getRecordComponents()){
                String name=field.getName();
                if(!map.containsKey(name))throw new IOException("Missing access field");
                names.add(name);shape(map.get(name),field.getGenericType());
            }
            if(!map.keySet().equals(names))throw new IOException("Unknown or missing access field");return;
        }
        if(c==long.class||c==Long.class||c==int.class){if(!(value instanceof Long n)||c==int.class&&(n<Integer.MIN_VALUE||n>Integer.MAX_VALUE))throw new IOException("Expected integer");return;}
        if(c==boolean.class){if(!(value instanceof Boolean))throw new IOException("Expected boolean");return;}
        if(c==String.class){if(!(value instanceof String))throw new IOException("Expected string");return;}
        if(c==UUID.class){if(!(value instanceof String s)||!UUID.fromString(s).toString().equals(s))throw new IOException("Expected UUID");return;}
        if(c.isEnum()){
            if(!(value instanceof String s)||Arrays.stream(c.getEnumConstants()).noneMatch(e->((Enum<?>)e).name().equals(s)))throw new IOException("Unknown enum");return;
        }
        throw new IOException("Unsupported record type");
    }
    static String sha(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
