package org.difftest.resource;

import org.difftest.config.DTResource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

                                                                                     
public final class AdaptivePipelineController {
    private static final long SAMPLE_INTERVAL = TimeUnit.SECONDS.toNanos(10);
    private static final long WINDOW_DURATION = TimeUnit.MINUTES.toNanos(2);
    private static final int MIN_COMPLETIONS = 10;
    private static final int STABLE_WINDOWS_TO_SETTLE = 3;
    private static final int MAX_HISTORY = 128;
    private static final double LOW_CPU = 0.85d;
    private static final double HIGH_CPU = 0.95d;
    private static final double EMERGENCY_CPU = 0.98d;

    private final DTResource resource;
    private final HostLoadProbe hostLoadProbe;
    private final LongSupplier nanoClock;
    private final long sampleIntervalNanos;
    private final long windowDurationNanos;
    private final int minCompletions;
    private final long controllerStartedNanos;
    private final ArrayDeque<Adjustment> history = new ArrayDeque<>();
    private final List<Long> queueWaitMillis = new ArrayList<>();

    private int currentWorkers;
    private int stableWindows;
    private boolean settled;
    private int consecutivePressureSamples;
    private long windowStartedNanos;
    private long lastSampleNanos;
    private long completed;
    private long productive;
    private double cpuLoadSum;
    private long cpuSamples;
    private WindowStats baseline;

    public AdaptivePipelineController(DTResource resource) {
        this(resource, new DefaultHostLoadProbe(), System::nanoTime,
                SAMPLE_INTERVAL, WINDOW_DURATION, MIN_COMPLETIONS);
    }

    AdaptivePipelineController(DTResource resource, HostLoadProbe hostLoadProbe,
            LongSupplier nanoClock, long sampleIntervalNanos,
            long windowDurationNanos, int minCompletions) {
        this.resource = resource;
        this.hostLoadProbe = hostLoadProbe;
        this.nanoClock = nanoClock;
        this.sampleIntervalNanos = Math.max(1L, sampleIntervalNanos);
        this.windowDurationNanos = Math.max(1L, windowDurationNanos);
        this.minCompletions = Math.max(1, minCompletions);
        this.currentWorkers = resource.initialWorkers();
        this.controllerStartedNanos = nanoClock.getAsLong();
        this.windowStartedNanos = controllerStartedNanos;
        this.lastSampleNanos = windowStartedNanos - this.sampleIntervalNanos;
    }

    public synchronized int currentClients() { return currentWorkers; }
    public int maxClients() { return resource.workerUpperBound(); }
    public synchronized boolean settled() { return settled; }

    public synchronized void recordCompletion(boolean isProductive, boolean isTimeout,
            long durationMillis) {
        recordCompletion(isProductive, isTimeout, durationMillis, 0L);
    }

    public synchronized void recordCompletion(boolean isProductive, boolean isTimeout,
            long durationMillis, long queueWaitMillis) {
        completed++;
        if (isProductive) productive++;
        this.queueWaitMillis.add(Math.max(0L, queueWaitMillis));
    }

    public synchronized void recordQueueWait(long queueWaitMillis) {
        this.queueWaitMillis.add(Math.max(0L, queueWaitMillis));
    }

    public synchronized void pulse() {
        long now = nanoClock.getAsLong();
        if (now - lastSampleNanos >= sampleIntervalNanos) sampleHost(now);
        if (!settled && now - windowStartedNanos >= windowDurationNanos
                && completed >= minCompletions) {
            evaluateWindow(now);
        }
    }

    private void sampleHost(long now) {
        HostLoad load = hostLoadProbe.sample();
        if (load.cpuLoad() >= 0.0d) {
            cpuLoadSum += load.cpuLoad();
            cpuSamples++;
        }
        lastSampleNanos = now;

        if (load.cpuLoad() >= EMERGENCY_CPU) {
            consecutivePressureSamples++;
        } else {
            consecutivePressureSamples = 0;
        }
        if (consecutivePressureSamples >= 2 && currentWorkers > 1) {
            adjust(currentWorkers - 1, AdjustmentReason.EMERGENCY_CPU,
                    currentWindow(now), now);
            settled = false;
            stableWindows = 0;
            consecutivePressureSamples = 0;
            resetWindow(now);
        }
    }

    private void evaluateWindow(long now) {
        WindowStats current = currentWindow(now);
        if (baseline == null) {
            baseline = current;
            stableWindows = 0;
        } else if (shouldIncrease(current)) {
            adjust(currentWorkers + 1, AdjustmentReason.THROUGHPUT_INCREASE, current, now);
            baseline = current;
            stableWindows = 0;
        } else if (shouldDecrease(current)) {
            adjust(currentWorkers - 1, AdjustmentReason.PRESSURE, current, now);
            baseline = current;
            stableWindows = 0;
        } else if (isStable(current)) {
            stableWindows++;
            baseline = current;
            if (stableWindows >= STABLE_WINDOWS_TO_SETTLE) settled = true;
        } else {
            stableWindows = 0;
            baseline = current;
        }
        resetWindow(now);
    }

    private boolean shouldIncrease(WindowStats current) {
        return currentWorkers < resource.workerUpperBound()
                && current.averageCpuLoad() >= 0.0d
                && current.averageCpuLoad() < LOW_CPU
                && current.averageQueueWaitMillis() <= queueLimit()
                && baseline != null
                && current.throughput() > baseline.throughput() * 1.03d;
    }

    private boolean shouldDecrease(WindowStats current) {
        return currentWorkers > 1
                && (current.averageCpuLoad() >= HIGH_CPU
                || current.averageQueueWaitMillis() > queueLimit() * 2.0d
                || (baseline != null && current.throughput() < baseline.throughput() * 0.97d));
    }

    private boolean isStable(WindowStats current) {
        return current.averageCpuLoad() < HIGH_CPU
                && current.averageQueueWaitMillis() <= queueLimit()
                && (baseline == null || current.throughput() <= baseline.throughput() * 1.03d);
    }

    private long queueLimit() {
        if (baseline == null || baseline.averageQueueWaitMillis() <= 0L) return 100L;
        return Math.max(100L, Math.round(baseline.averageQueueWaitMillis() * 1.20d));
    }

    private WindowStats currentWindow(long now) {
        long totalQueueWait = queueWaitMillis.stream().mapToLong(Long::longValue).sum();
        double seconds = Math.max(0.001d,
                (now - windowStartedNanos) / 1_000_000_000.0d);
        return new WindowStats(completed, productive, productive / seconds,
                cpuSamples == 0 ? -1.0d : cpuLoadSum / cpuSamples,
                completed == 0 ? 0L : totalQueueWait / completed);
    }

    private void adjust(int requested, AdjustmentReason reason,
            WindowStats stats, long now) {
        int previous = currentWorkers;
        currentWorkers = Math.max(1, Math.min(resource.workerUpperBound(), requested));
        if (previous != currentWorkers) {
            if (history.size() == MAX_HISTORY) history.removeFirst();
            history.addLast(new Adjustment(
                    TimeUnit.NANOSECONDS.toMillis(now - controllerStartedNanos),
                    previous, currentWorkers, reason,
                    stats == null ? 0.0d : stats.throughput(),
                    stats == null ? -1.0d : stats.averageCpuLoad(),
                    stats == null ? 0L : stats.averageQueueWaitMillis()));
        }
    }

    private void resetWindow(long now) {
        windowStartedNanos = now;
        completed = productive = 0L;
        cpuLoadSum = 0.0d;
        cpuSamples = 0L;
        queueWaitMillis.clear();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(resource.topology(), resource.initialWorkers(), currentWorkers,
                resource.workerUpperBound(), settled, List.copyOf(history));
    }

    public enum AdjustmentReason {
        THROUGHPUT_INCREASE,
        PRESSURE,
        EMERGENCY_CPU
    }

    public record WindowStats(long completed, long productive, double throughput,
            double averageCpuLoad, long averageQueueWaitMillis) {}

    public record Adjustment(long elapsedMillis, int fromWorkers, int toWorkers,
            AdjustmentReason reason, double throughput, double cpuLoad,
            long averageQueueWaitMillis) {}

    public record Snapshot(org.difftest.config.CpuTopology topology,
            int initialWorkers, int currentWorkers, int workerUpperBound,
            boolean settled, List<Adjustment> adjustmentHistory) {}
}