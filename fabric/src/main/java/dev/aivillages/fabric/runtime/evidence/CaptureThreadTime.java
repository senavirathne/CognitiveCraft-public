package dev.aivillages.fabric.runtime.evidence;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.List;

/** Diagnostic CPU time for the same capture calls measured by the wall clock. */
final class CaptureThreadTime {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private final ArrayDeque<Long> samples = new ArrayDeque<>();

    long start() {
        if (!THREADS.isCurrentThreadCpuTimeSupported() || !THREADS.isThreadCpuTimeEnabled()) return -1;
        return THREADS.getCurrentThreadCpuTime();
    }

    void record(long started) {
        long finished = start();
        if (samples.size() == 128) samples.removeFirst();
        samples.addLast(started < 0 || finished < started ? -1 : finished - started);
    }

    List<Long> nanos() {
        return List.copyOf(samples);
    }
}
