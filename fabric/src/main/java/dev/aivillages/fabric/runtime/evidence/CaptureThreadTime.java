package dev.aivillages.fabric.runtime.evidence;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Diagnostic CPU time for the same capture calls measured by the wall clock. */
final class CaptureThreadTime {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private final ArrayDeque<Long> samples = new ArrayDeque<>();
    private final ArrayDeque<List<Long>> phases = new ArrayDeque<>();

    long start() {
        if (!THREADS.isCurrentThreadCpuTimeSupported() || !THREADS.isThreadCpuTimeEnabled()) return -1;
        return THREADS.getCurrentThreadCpuTime();
    }

    void record(long started) {
        long finished = start();
        if (samples.size() == 128) samples.removeFirst();
        samples.addLast(started < 0 || finished < started ? -1 : finished - started);
    }

    void record(long started,long wallStarted,long[] completed) {
        record(started);
        var elapsed = new ArrayList<Long>(completed.length);
        for(long instant:completed)elapsed.add(instant==0?0:instant-wallStarted);
        if(phases.size()==128)phases.removeFirst();
        phases.addLast(List.copyOf(elapsed));
    }

    List<Long> nanos() {
        return List.copyOf(samples);
    }

    List<List<Long>> phaseNanos() {
        return List.copyOf(phases);
    }
}
