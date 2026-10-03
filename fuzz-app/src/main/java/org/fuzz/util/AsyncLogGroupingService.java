package org.fuzz.util;

import lombok.extern.slf4j.Slf4j;
import org.difftest.performance.PerformanceMetrics;
import org.difftest.util.ThreadUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class AsyncLogGroupingService implements AutoCloseable {
    private final LogGrouper.IncrementalSession session;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            ThreadUtils.namedThreadFactory("log-grouping", true));
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AsyncLogGroupingService(Path input, Path groupedOutput) {
        this.session = LogGrouper.incremental(input, groupedOutput);
    }

    public AsyncLogGroupingService(Path input, Path groupedOutput, Path bugsOutput) {
        this.session = LogGrouper.incremental(input, groupedOutput, bugsOutput);
    }

    public void requestGrouping() {
        if (closed.get()) return;
        pending.set(true);
        scheduleIfNeeded();
    }

    private void scheduleIfNeeded() {
        if (scheduled.compareAndSet(false, true)) executor.execute(this::drainRequests);
    }

    private void drainRequests() {
        try {
            do {
                pending.set(false);
                flushOnce();
            } while (pending.get());
        } finally {
            scheduled.set(false);
            if (pending.get() && !closed.get()) scheduleIfNeeded();
        }
    }

    private void flushOnce() {
        try (PerformanceMetrics.TimerContext ignored = PerformanceMetrics.global()
                .start(PerformanceMetrics.Metric.LOG_GROUPING)) {
            session.flush();
        } catch (IOException e) {
            log.warn("Incremental log grouping failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
                                                                                         
                                                                        
        executor.execute(this::flushOnce);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
