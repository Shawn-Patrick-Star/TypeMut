package org.fuzz.stats;

import org.apache.log4j.Appender;
import org.apache.log4j.Logger;
import org.fuzz.config.FuzzConfig;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

   
                                                               
                                                                           
   
public class ReproducerConsoleReporter implements AutoCloseable {
    private static final long REFRESH_INTERVAL_MILLIS = 3_000L;
    private static final String CONSOLE_APPENDER_NAME = "console";

    private final IntSupplier totalSupplier;
    private final IntSupplier reproducedSupplier;
    private final IntSupplier notReproducedSupplier;
    private final IntSupplier filteredSupplier;
    private final IntSupplier noMainClassSupplier;
    private final IntSupplier uniqueSupplier;
    private final int workerThreads;
    private final ConsoleFrameRenderer frameRenderer;
    private final Appender detachedConsoleAppender;
    private final Instant startTime = Instant.now();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile String status = "INITIALIZING";
    private Thread reporterThread;

    public ReproducerConsoleReporter(
            int workerThreads,
            IntSupplier totalSupplier,
            IntSupplier reproducedSupplier,
            IntSupplier notReproducedSupplier,
            IntSupplier filteredSupplier,
            IntSupplier noMainClassSupplier,
            IntSupplier uniqueSupplier) {
        this(workerThreads, totalSupplier, reproducedSupplier, notReproducedSupplier, filteredSupplier,
                noMainClassSupplier, uniqueSupplier,
                detachLogConsoleAppender(), System.out);
    }

    ReproducerConsoleReporter(
            int workerThreads,
            IntSupplier totalSupplier,
            IntSupplier reproducedSupplier,
            IntSupplier notReproducedSupplier,
            IntSupplier filteredSupplier,
            IntSupplier noMainClassSupplier,
            IntSupplier uniqueSupplier,
            Appender detachedConsoleAppender,
            PrintStream out) {
        this.workerThreads = workerThreads;
        this.totalSupplier = Objects.requireNonNull(totalSupplier);
        this.reproducedSupplier = Objects.requireNonNull(reproducedSupplier);
        this.notReproducedSupplier = Objects.requireNonNull(notReproducedSupplier);
        this.filteredSupplier = Objects.requireNonNull(filteredSupplier);
        this.noMainClassSupplier = Objects.requireNonNull(noMainClassSupplier);
        this.uniqueSupplier = Objects.requireNonNull(uniqueSupplier);
        this.detachedConsoleAppender = detachedConsoleAppender;
        this.frameRenderer = new ConsoleFrameRenderer(out);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        reporterThread = new Thread(this::runLoop, "reproducer-console-reporter");
        reporterThread.setDaemon(true);
        reporterThread.start();
    }

    public void updateStatus(String status) {
        if (status != null && !status.isBlank()) {
            this.status = status;
        }
    }

    public void printFinal() {
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
        frameRenderer.render(buildReport(displayStatus));
    }

    private String buildReport(String displayStatus) {
        int total = Math.max(0, totalSupplier.getAsInt());
        int reproduced = Math.max(0, reproducedSupplier.getAsInt());
        int notReproduced = Math.max(0, notReproducedSupplier.getAsInt());
        int filtered = Math.max(0, filteredSupplier.getAsInt());
        int noMainClass = Math.max(0, noMainClassSupplier.getAsInt());
        int completed = Math.min(total, reproduced + notReproduced + filtered + noMainClass);
        int remaining = Math.max(0, total - completed);
        double progress = total == 0 ? 100.0 : (double) completed / total * 100.0;
        Duration elapsed = Duration.between(startTime, Instant.now());
        double seconds = elapsed.toMillis() / 1000.0;
        double speed = seconds <= 0.0 ? 0.0 : completed / seconds;

        return String.format("""

                ============================================
                          BUG REPRODUCER REPORT
                ============================================
                 Status              : %s
                 Last Update         : %s
                 Total Duration      : %s
                 Worker Threads      : %d

                 Total Bugs          : %d
                 Completed           : %d (%.2f%%)
                 Remaining           : %d
                 Reproduced Bugs     : %d
                 Not Reproduced      : %d
                 Filtered False Pos  : %d
                 No Main Class       : %d
                 Unique Behaviors    : %d
                 Verification Speed  : %.2f bugs/s
                ============================================
                 Detailed logs       : %s
                 Refresh             : every 3 seconds""",
                displayStatus == null || displayStatus.isBlank() ? "RUNNING" : displayStatus,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                DurationFormatter.toDaysHoursMinutesSeconds(elapsed),
                workerThreads,
                total,
                completed,
                progress,
                remaining,
                reproduced,
                notReproduced,
                filtered,
                noMainClass,
                uniqueSupplier.getAsInt(),
                speed,
                FuzzConfig.logPath("reproducer.log"));
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

    private static Appender detachLogConsoleAppender() {
        Logger rootLogger = Logger.getRootLogger();
        Appender consoleAppender = rootLogger.getAppender(CONSOLE_APPENDER_NAME);
        if (consoleAppender != null) {
            rootLogger.removeAppender(consoleAppender);
        }
        return consoleAppender;
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
