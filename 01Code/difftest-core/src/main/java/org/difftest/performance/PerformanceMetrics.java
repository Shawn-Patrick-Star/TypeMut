package org.difftest.performance;

import org.difftest.resource.AdaptivePipelineController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

                                                                           
public final class PerformanceMetrics {
    private static final long[] BUCKET_MILLIS = {
            1, 2, 5, 10, 20, 50, 100, 200, 500,
            1_000, 2_000, 5_000, 10_000, 15_000, 30_000,
            45_000, 60_000, 90_000, 120_000, 180_000, Long.MAX_VALUE
    };
    private static final PerformanceMetrics GLOBAL = new PerformanceMetrics();

    public enum Metric {
        SPOON_MODEL_BUILD,
        MUTATION_OPERATOR,
        SOURCE_SAVE,
        COMPILER_QUEUE_WAIT,
        COMPILER_EXECUTION,
        JVM_QUEUE_WAIT,
        JVM_HOTSPOT_EXECUTION,
        JVM_OPENJ9_EXECUTION,
        PIPELINE_TOTAL,
        LOG_GROUPING
    }

    private final EnumMap<Metric, Accumulator> values = new EnumMap<>(Metric.class);

    public PerformanceMetrics() {
        for (Metric metric : Metric.values()) values.put(metric, new Accumulator());
    }

    public static PerformanceMetrics global() { return GLOBAL; }

    public void record(Metric metric, long durationNanos) {
        if (metric != null) values.get(metric).record(Math.max(0L, durationNanos));
    }

    public TimerContext start(Metric metric) {
        return new TimerContext(this, metric, System.nanoTime());
    }

    public Snapshot snapshot() {
        EnumMap<Metric, MetricSnapshot> copy = new EnumMap<>(Metric.class);
        values.forEach((metric, value) -> copy.put(metric, value.snapshot()));
        return new Snapshot(Collections.unmodifiableMap(copy));
    }

    public void reset() { values.values().forEach(Accumulator::reset); }

    public void writeJson(Path outputFile) throws IOException {
        writeJson(outputFile, null);
    }

    public void writeJson(Path outputFile, AdaptivePipelineController.Snapshot scheduling) throws IOException {
        Path absolute = outputFile.toAbsolutePath().normalize();
        if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        Files.writeString(temporary, toJson(snapshot(), scheduling), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String toJson(Snapshot snapshot, AdaptivePipelineController.Snapshot scheduling) {
        StringBuilder json = new StringBuilder("{\n  \"metrics\": {\n");
        int index = 0;
        for (Metric metric : Metric.values()) {
            MetricSnapshot value = snapshot.metrics().get(metric);
            if (index++ > 0) json.append(",\n");
            json.append("    \"").append(metric).append("\": {")
                    .append("\"count\": ").append(value.count()).append(", ")
                    .append("\"totalMillis\": ").append(value.totalMillis()).append(", ")
                    .append("\"averageMillis\": ")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", value.averageMillis())).append(", ")
                    .append("\"minMillis\": ").append(value.minMillis()).append(", ")
                    .append("\"maxMillis\": ").append(value.maxMillis()).append(", ")
                    .append("\"p50Millis\": ").append(value.p50Millis()).append(", ")
                    .append("\"p90Millis\": ").append(value.p90Millis()).append(", ")
                    .append("\"p95Millis\": ").append(value.p95Millis()).append(", ")
                    .append("\"p99Millis\": ").append(value.p99Millis()).append('}');
        }
        json.append("\n  }");
        if (scheduling != null) appendScheduling(json, scheduling);
        return json.append("\n}\n").toString();
    }

    private void appendScheduling(StringBuilder json, AdaptivePipelineController.Snapshot scheduling) {
        json.append(",\n  \"resourceScheduling\": {\n")
                .append("    \"topology\": {")
                .append("\"logicalProcessors\": ").append(scheduling.topology().logicalProcessors()).append(", ")
                .append("\"physicalCores\": ").append(scheduling.topology().physicalCores()).append(", ")
                .append("\"threadsPerCore\": ").append(scheduling.topology().threadsPerCore()).append(", ")
                .append("\"source\": \"").append(scheduling.topology().source()).append("\"},\n")
                .append("    \"initialWorkers\": ").append(scheduling.initialWorkers()).append(",\n")
                .append("    \"currentWorkers\": ").append(scheduling.currentWorkers()).append(",\n")
                .append("    \"workerUpperBound\": ").append(scheduling.workerUpperBound()).append(",\n")
                .append("    \"settled\": ").append(scheduling.settled()).append(",\n")
                .append("    \"adjustmentHistory\": [");
        for (int i = 0; i < scheduling.adjustmentHistory().size(); i++) {
            AdaptivePipelineController.Adjustment adjustment = scheduling.adjustmentHistory().get(i);
            if (i > 0) json.append(',');
            json.append("\n      {")
                    .append("\"elapsedMillis\": ").append(adjustment.elapsedMillis()).append(", ")
                    .append("\"fromWorkers\": ").append(adjustment.fromWorkers()).append(", ")
                    .append("\"toWorkers\": ").append(adjustment.toWorkers()).append(", ")
                    .append("\"reason\": \"").append(adjustment.reason()).append("\", ")
                    .append("\"throughput\": ").append(format(adjustment.throughput())).append(", ")
                    .append("\"cpuLoad\": ").append(format(adjustment.cpuLoad())).append(", ")
                    .append("\"averageQueueWaitMillis\": ").append(adjustment.averageQueueWaitMillis())
                    .append('}');
        }
        if (!scheduling.adjustmentHistory().isEmpty()) json.append('\n').append("    ");
        json.append("]\n  }");
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    public record Snapshot(Map<Metric, MetricSnapshot> metrics) {}

    public record MetricSnapshot(long count, long totalMillis, double averageMillis,
            long minMillis, long maxMillis, long p50Millis, long p90Millis,
            long p95Millis, long p99Millis) {}

    public static final class TimerContext implements AutoCloseable {
        private final PerformanceMetrics owner;
        private final Metric metric;
        private final long startedNanos;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TimerContext(PerformanceMetrics owner, Metric metric, long startedNanos) {
            this.owner = owner;
            this.metric = metric;
            this.startedNanos = startedNanos;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) owner.record(metric, System.nanoTime() - startedNanos);
        }
    }

    private static final class Accumulator {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxNanos = new AtomicLong();
        private final AtomicLongArray buckets = new AtomicLongArray(BUCKET_MILLIS.length);

        void record(long nanos) {
            count.increment();
            totalNanos.add(nanos);
            minNanos.accumulateAndGet(nanos, Math::min);
            maxNanos.accumulateAndGet(nanos, Math::max);
            long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
            for (int i = 0; i < BUCKET_MILLIS.length; i++) {
                if (millis <= BUCKET_MILLIS[i]) { buckets.incrementAndGet(i); break; }
            }
        }

        MetricSnapshot snapshot() {
            long samples = count.sum();
            if (samples == 0) return new MetricSnapshot(0, 0, 0.0d, 0, 0, 0, 0, 0, 0);
            long total = totalNanos.sum();
            return new MetricSnapshot(samples, TimeUnit.NANOSECONDS.toMillis(total),
                    (double) total / samples / 1_000_000.0d,
                    TimeUnit.NANOSECONDS.toMillis(minNanos.get()),
                    TimeUnit.NANOSECONDS.toMillis(maxNanos.get()),
                    percentile(samples, .50d), percentile(samples, .90d),
                    percentile(samples, .95d), percentile(samples, .99d));
        }

        private long percentile(long samples, double p) {
            long target = Math.max(1, (long) Math.ceil(samples * p));
            long cumulative = 0;
            for (int i = 0; i < BUCKET_MILLIS.length; i++) {
                cumulative += buckets.get(i);
                if (cumulative >= target) {
                    return BUCKET_MILLIS[i] == Long.MAX_VALUE
                            ? TimeUnit.NANOSECONDS.toMillis(maxNanos.get()) : BUCKET_MILLIS[i];
                }
            }
            return TimeUnit.NANOSECONDS.toMillis(maxNanos.get());
        }

        void reset() {
            count.reset(); totalNanos.reset(); minNanos.set(Long.MAX_VALUE); maxNanos.set(0L);
            for (int i = 0; i < buckets.length(); i++) buckets.set(i, 0L);
        }
    }
}
