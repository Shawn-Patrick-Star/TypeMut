package org.fuzz.stats;

import org.apache.log4j.Appender;
import org.apache.log4j.Logger;
import org.fuzz.config.FuzzConfig;

import java.io.PrintStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

   
                                                                                
                        
   
public class FuzzingConsoleReporter implements AutoCloseable {
    private static final long REFRESH_INTERVAL_MILLIS = 3_000L;
    private static final String CONSOLE_APPENDER_NAME = "console";

    private final FuzzingStatistics stats;
    private final Supplier<Map<Integer, Integer>> breakdownSupplier;
    private final IntSupplier workerSupplier;
    private final Appender detachedConsoleAppender;
    private final ConsoleFrameRenderer frameRenderer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile String status = "INITIALIZING";
    private Thread reporterThread;

    public FuzzingConsoleReporter(
            FuzzingStatistics stats,
            Supplier<Map<Integer, Integer>> breakdownSupplier,
            Appender detachedConsoleAppender) {
        this(stats, breakdownSupplier, detachedConsoleAppender, System.out, () -> -1);
    }

    FuzzingConsoleReporter(
            FuzzingStatistics stats,
            Supplier<Map<Integer, Integer>> breakdownSupplier,
            Appender detachedConsoleAppender,
            PrintStream out) {
        this(stats, breakdownSupplier, detachedConsoleAppender, out, () -> -1);
    }

    public FuzzingConsoleReporter(
            FuzzingStatistics stats,
            Supplier<Map<Integer, Integer>> breakdownSupplier,
            Appender detachedConsoleAppender,
            IntSupplier workerSupplier) {
        this(stats, breakdownSupplier, detachedConsoleAppender, System.out, workerSupplier);
    }

    FuzzingConsoleReporter(
            FuzzingStatistics stats,
            Supplier<Map<Integer, Integer>> breakdownSupplier,
            Appender detachedConsoleAppender,
            PrintStream out,
            IntSupplier workerSupplier) {
        this.stats = Objects.requireNonNull(stats);
        this.breakdownSupplier = Objects.requireNonNull(breakdownSupplier);
        this.workerSupplier = Objects.requireNonNull(workerSupplier);
        this.detachedConsoleAppender = detachedConsoleAppender;
        this.frameRenderer = new ConsoleFrameRenderer(out);
    }

    public static Appender detachLogConsoleAppender() {
        Logger rootLogger = Logger.getRootLogger();
        Appender consoleAppender = rootLogger.getAppender(CONSOLE_APPENDER_NAME);
        if (consoleAppender != null) {
            rootLogger.removeAppender(consoleAppender);
        }
        return consoleAppender;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        started.set(true);
        reporterThread = new Thread(this::runLoop, "fuzz-console-reporter");
        reporterThread.setDaemon(true);
        reporterThread.start();
    }

    public void updateStatus(String status) {
        if (status != null && !status.isBlank()) {
            this.status = status;
        }
    }

    public void printFinal() {
        if (!started.get()) {
            return;
        }
        stopReporterThread();
        render("FINISHED");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopReporterThread();
        restoreLogConsoleAppender();
    }

    private void runLoop() {
        while (running.get()) {
            render(status);
            try {
                Thread.sleep(REFRESH_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private synchronized void render(String displayStatus) {
        String report = stats.buildSummaryReport(breakdownSupplier.get(), displayStatus, workerSupplier.getAsInt())
                + System.lineSeparator()
                + " Detailed logs: " + FuzzConfig.logPath("app.log")
                + System.lineSeparator()
                + " Refresh      : every 3 seconds";
        frameRenderer.render(report);
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

    private void restoreLogConsoleAppender() {
        if (detachedConsoleAppender == null) {
            return;
        }
        Logger rootLogger = Logger.getRootLogger();
        if (rootLogger.getAppender(CONSOLE_APPENDER_NAME) == null) {
            rootLogger.addAppender(detachedConsoleAppender);
        }
    }
}
