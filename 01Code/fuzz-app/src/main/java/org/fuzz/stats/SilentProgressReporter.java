package org.fuzz.stats;

import org.difftest.util.ThreadUtils;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

                                                                                     
public final class SilentProgressReporter implements AutoCloseable {
    static final long REFRESH_INTERVAL_MILLIS = 10_000L;

    private final FuzzingStatistics statistics;
    private final PrintStream output;
    private final IntSupplier workerSupplier;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread reporterThread;

    public SilentProgressReporter(FuzzingStatistics statistics) {
        this(statistics, System.out, () -> -1);
    }

    SilentProgressReporter(FuzzingStatistics statistics, PrintStream output) {
        this(statistics, output, () -> -1);
    }

    public SilentProgressReporter(FuzzingStatistics statistics, IntSupplier workerSupplier) {
        this(statistics, System.out, workerSupplier);
    }

    SilentProgressReporter(FuzzingStatistics statistics, PrintStream output,
            IntSupplier workerSupplier) {
        this.statistics = Objects.requireNonNull(statistics);
        this.output = Objects.requireNonNull(output);
        this.workerSupplier = Objects.requireNonNull(workerSupplier);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        renderNow("RUNNING");
        reporterThread = ThreadUtils.namedThreadFactory("fuzz-silent-progress", true)
                .newThread(this::runLoop);
        reporterThread.start();
    }

    public void printFinal(String status, Path performanceReport) {
        stopReporterThread();
        render(status, performanceReport);
        output.println();
    }

    @Override
    public void close() {
        stopReporterThread();
    }

    void renderNow(String status) {
        render(status, null);
    }

    private void render(String status, Path performanceReport) {
        String performanceSuffix = performanceReport == null ? "" : " performance=" + performanceReport;
        int workers = workerSupplier.getAsInt();
        String workerSuffix = workers < 0 ? "" : " workers=" + workers;
        output.printf("\r%s mutations=%d valid=%d bugs=%d timeouts=%d duration=%ds%s%s",
                status,
                statistics.getMutationCount(),
                statistics.getValidCount(),
                statistics.getBugCount(),
                statistics.getTimeoutCount(),
                statistics.getElapsedSeconds(),
                workerSuffix,
                performanceSuffix);
        output.flush();
    }

    private void runLoop() {
        while (running.get()) {
            try {
                Thread.sleep(REFRESH_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (running.get()) {
                renderNow("RUNNING");
            }
        }
    }

    private void stopReporterThread() {
        running.set(false);
        if (reporterThread == null) {
            return;
        }
        reporterThread.interrupt();
        try {
            reporterThread.join(1_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
