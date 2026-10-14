import dev.aivillages.core.kernel.*;
import dev.aivillages.providers.NeedleWire;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Actions-only diagnostic using actual prepared tools and pinned interpreter. Never a runtime entry point. */
class AxeWireProbe {
    public static void main(String[] args) throws Exception {
        Path assets=Path.of(args[0]).toAbsolutePath(),out=Path.of(args[1]);Files.createDirectories(out);
        String original="Ada, make one wooden axe using available materials and store it.";
        var prepared=NeedleWire.prepare(new LanguageRequests.Input(original,List.of("Ada")));
        Path tools=out.resolve("axe-tools.json");Files.writeString(tools,prepared.tools());var records=new ArrayList<Object>();
        for(String mode:List.of("original","prepared")) {
            String text=mode.equals("original") ? original : prepared.text();
            ProcessBuilder builder=new ProcessBuilder(assets.resolve("needle").toString(),"--model",assets.resolve("needle3.cact").toString(),
                    "--tools",tools.toAbsolutePath().toString(),"--prompt",text,"--max","384","--threads","1","--fail-input-overflow");
            builder.environment().clear();builder.environment().put("NEEDLE_TELEMETRY","0");builder.environment().put("DO_NOT_TRACK","1");
            builder.redirectError(out.resolve(mode+"-stderr.txt").toFile());long started=System.nanoTime();Process process=builder.start();
            process.getOutputStream().close();
            if(!process.waitFor(30,TimeUnit.SECONDS)) {process.destroyForcibly();throw new IllegalStateException("Bounded probe process");}
            byte[] wire=process.getInputStream().readNBytes(LanguageRequests.MAX_OUTPUT_BYTES+1);
            if(wire.length>LanguageRequests.MAX_OUTPUT_BYTES)throw new IllegalStateException("Bounded probe output");
            var decoded=NeedleWire.decode(wire,prepared);var row=new LinkedHashMap<String,Object>();
            row.put("mode",mode);row.put("prompt",text);row.put("stdout",new String(wire,StandardCharsets.UTF_8));row.put("exit",process.exitValue());
            row.put("decoded",decoded.kind().name());row.put("millis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));records.add(row);System.out.println(StrictJson.canonical(row));
        }
        Files.writeString(out.resolve("needle-wire-probe.json"),StrictJson.canonical(Map.of("records",records,"classification",prepared.classification().intent().name())));
    }
}
