package dev.aivillages.e2e.paralleld;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Identical native schedule for the unchanged qualified D01 JAR and candidate. No production dependency. */
final class DLifecycleBenchmark {
    static void measure(ServerLevel level,BlockPos pos) {
        BlockState state=level.getBlockState(pos);long[] samples=new long[2000];
        for(int i=0;i<2200;i++) {
            long start=System.nanoTime();level.setBlock(pos,state.setValue(BarrelBlock.OPEN,(i&1)==0),2);long ns=System.nanoTime()-start;
            if(i>=200)samples[i-200]=ns;
        }
        Arrays.sort(samples);var frame=new TreeMap<String,Object>();frame.put("samples",samples);frame.put("warmup",200);
        frame.put("p50_ns",samples[999]);frame.put("p95_ns",samples[1899]);frame.put("p99_ns",samples[1979]);frame.put("max_ns",samples[1999]);
        frame.put("heap_used_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());frame.put("scheduled_tick_tasks",0);
        try {Path path=Path.of(System.getProperty("cognitivecraft.parallelD.evidence")).resolve("performance.json");Files.createDirectories(path.getParent());Files.writeString(path,new Gson().toJson(frame)+"\n");}catch(Exception e){throw new RuntimeException(e);}
    }
}
