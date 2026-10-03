package org.seed.service;

import org.apache.log4j.Appender;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.difftest.model.instance.CompilerInstance;
import org.difftest.model.instance.JvmInstance;
import org.seed.model.RejectedSeed;
import org.seed.model.RejectionReason;
import org.seed.model.SeedFilterResult;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class SeedFilterReporter implements AutoCloseable {
    private static final long REFRESH_INTERVAL_MILLIS = 3_000L;
    private static final org.slf4j.Logger DETAIL_LOG = LoggerFactory.getLogger(SeedFilterReporter.class);
    private static final org.slf4j.Logger REPORT_LOG =
            LoggerFactory.getLogger("org.fuzz.seedfilter.Report");

    private final ConsoleFrameRenderer frameRenderer;
    private final List<Appender> detachedConsoleAppenders = new ArrayList<>();
    private final Instant startTime = Instant.now();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<SeedFilterResult> currentResult =
            new AtomicReference<>(new SeedFilterResult(List.of(), List.of()));
    private volatile RunInfo runInfo;
    private volatile int totalCandidates;
    private volatile String status = "INITIALIZING";
    private volatile boolean consoleEnabled = true;
    private Thread reporterThread;

    public SeedFilterReporter() {
        this(System.out);
    }

    SeedFilterReporter(PrintStream out) {
        this.frameRenderer = new ConsoleFrameRenderer(out);
    }

    public void start(RunInfo runInfo, int totalCandidates) {
        this.runInfo = Objects.requireNonNull(runInfo);
        this.totalCandidates = Math.max(0, totalCandidates);
        this.consoleEnabled = !(runInfo.hostLogging() && Boolean.getBoolean("fuzz.logging.disabled"));
        if (!consoleEnabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        detachedConsoleAppenders.addAll(detachLogConsoleAppenders());
        String config = buildConfig();
        frameRenderer.printStatic(config);
        logReport(config);
        reporterThread = new Thread(this::runLoop, "seed-filter-console-reporter");
        reporterThread.setDaemon(true);
        reporterThread.start();
    }

    public void update(SeedFilterResult result) {
        if (result != null) {
            currentResult.set(result);
        }
    }

    public void updateStatus(String status) {
        if (status != null && !status.isBlank()) {
            this.status = status;
        }
    }

    public void printFinal(SeedFilterResult result) {
        update(result);
        if (!consoleEnabled) return;
        stopReporterThread();
        String report = buildReport("FINISHED");
        render(report);
        logReport(report);
    }

    public void stopWithoutFinalReport(SeedFilterResult result) {
        update(result);
        stopReporterThread();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopReporterThread();
        restoreLogConsoleAppenders();
    }

    private void runLoop() {
        while (running.get()) {
            render(buildReport(status));
            try {
                Thread.sleep(REFRESH_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private synchronized void render(String report) {
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

    private String buildConfig() {
        RunInfo info = runInfo;
        return String.format("""
                ============ SEED FILTER CONFIG =============
                 Diff Modes          : compiler=%s, jvm=%s
                 Resources           : physical_cores=%d, workers=%d
                 Timeout             : %ds
                 Filters             : static=%s, compile=%s, jvmdt=%s
                 Case Copy           : accepted=%s, rejected=%s
                 Compilers           : %s
                 JVMs                : %s
                 Output Dir          : %s
                 Cache File          : %s
                 Detailed logs       : %s
                 Reports Dir         : %s""",
                info != null && info.compilerDtEnabled(),
                info != null && info.jvmDtEnabled(),
                info == null ? 0 : info.physicalCores(),
                info == null ? 1 : info.parallelism(),
                info == null ? 0 : info.timeoutSeconds(),
                info != null && info.staticFilterEnabled(),
                info != null && info.compileFilterEnabled(),
                info != null && info.jvmDtFilterEnabled(),
                info != null && info.copyAcceptedCase(),
                info != null && info.keepRejectedCase(),
                info == null ? "pending" : info.formatCompilerIds(),
                info == null ? "pending" : info.formatJvmIds(),
                info == null ? "pending" : info.outputDir(),
                info == null ? "pending" : info.cacheFile(),
                info == null ? "pending" : info.logFile(),
                info == null ? "pending" : info.reportsDir());
    }

    private String buildReport(String displayStatus) {
        SeedFilterResult result = currentResult.get();
        int total = Math.max(0, totalCandidates);
        long accepted = result.acceptedCount();
        long rejected = result.rejectedCount();
        long completed = Math.min((long) total, accepted + rejected);
        long remaining = Math.max(0L, (long) total - completed);
        double progress = total == 0 ? 100.0 : (double) completed / total * 100.0;
        Duration elapsed = Duration.between(startTime, Instant.now());
        double seconds = elapsed.toMillis() / 1000.0;
        double speed = seconds <= 0.0 ? 0.0 : completed / seconds;
        String staticSystemRuleDetails = staticSystemRuleDetails(result);

        return String.format("""
                ============ SEED FILTER REPORT =============
                 Status                : %s
                 Last Update           : %s
                 Total Duration        : %s
                 Total Candidates      : %d
                  Completed            : %d (%.2f%%)
                  Remaining            : %d
                  
                  Accepted Seeds       : %d
                  Rejected Seeds       : %d
                   Static Time         : %d
                   Static Concurrency  : %d
                   Static System       : %d%s
                   Compile Failed      : %d
                   Raw Diff Failure    : %d
                   Raw Timeout         : %d
                   Raw Env Error       : %d
                   Invalid Path        : %d
                 Filtering Speed       : %.2f seeds/s
                ============================================
                 Refresh               : every 3 seconds""",
                displayStatus == null || displayStatus.isBlank() ? "RUNNING" : displayStatus,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                formatDuration(elapsed),
                total,
                completed,
                progress,
                remaining,
                accepted,
                rejected,
                result.count(RejectionReason.STATIC_TIME),
                result.count(RejectionReason.STATIC_THREAD),
                result.count(RejectionReason.STATIC_SYSTEM),
                staticSystemRuleDetails,
                result.compileFailedCount(),
                result.count(RejectionReason.RAW_DIFF_FAILURE),
                result.count(RejectionReason.RAW_TIMEOUT),
                result.count(RejectionReason.RAW_ENVIRONMENT_ERROR),
                result.count(RejectionReason.INVALID_PATH),
                speed);
    }

    private String staticSystemRuleDetails(SeedFilterResult result) {
        Map<String, Long> counts = new TreeMap<>();
        for (RejectedSeed seed : result.rejectedSeeds()) {
            if (seed.reason() != RejectionReason.STATIC_SYSTEM) {
                continue;
            }
            String ruleName = seed.ruleName();
            if (ruleName == null || ruleName.isBlank()) {
                ruleName = "unknown-static-system";
            }
            counts.merge(ruleName, 1L, Long::sum);
        }
        if (counts.isEmpty()) {
            return "";
        }
        StringBuilder details = new StringBuilder();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            details.append('\n')
                    .append("                    - ")
                    .append(String.format("%-27s: %d", entry.getKey(), entry.getValue()));
        }
        return details.toString();
    }

    private void logReport(String report) {
        String text = report.stripTrailing();
        if (runInfo != null && runInfo.hostLogging()) {
            REPORT_LOG.info("\n{}", text);
        }
        DETAIL_LOG.info("\n{}", text);
    }

    public void writeReports(Path reportsRoot, SeedFilterResult result, int totalCandidates, boolean interrupted) {
        try {
            Files.createDirectories(reportsRoot);
            Files.writeString(reportsRoot.resolve("seed-filter-report.json"),
                    jsonReport(result, totalCandidates, interrupted), StandardCharsets.UTF_8);
            Files.writeString(reportsRoot.resolve("seed-filter-report.csv"),
                    csvReport(result), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DETAIL_LOG.warn("Failed to write seed-filter reports: {}", e.getMessage());
        }
    }

    private String jsonReport(SeedFilterResult result, int totalCandidates, boolean interrupted) {
        return """
                {
                  "interrupted": %s,
                  "totalCandidates": %d,
                  "accepted": %d,
                  "rejected": %d,
                  "compileFailed": %d,
                  "staticTime": %d,
                  "staticConcurrency": %d,
                  "staticSystem": %d,
                  "rawDiffFailure": %d,
                  "rawTimeout": %d,
                  "rawEnvironmentError": %d,
                  "invalidPath": %d
                }
                """.formatted(
                interrupted,
                totalCandidates,
                result.acceptedCount(),
                result.rejectedCount(),
                result.compileFailedCount(),
                result.count(RejectionReason.STATIC_TIME),
                result.count(RejectionReason.STATIC_THREAD),
                result.count(RejectionReason.STATIC_SYSTEM),
                result.count(RejectionReason.RAW_DIFF_FAILURE),
                result.count(RejectionReason.RAW_TIMEOUT),
                result.count(RejectionReason.RAW_ENVIRONMENT_ERROR),
                result.count(RejectionReason.INVALID_PATH));
    }

    private String csvReport(SeedFilterResult result) {
        StringBuilder csv = new StringBuilder("seed_id,main_file,reason,rule,detail,copied_path\n");
        for (RejectedSeed seed : result.rejectedSeeds()) {
            csv.append(csv(seed.seedId())).append(',')
                    .append(csv(seed.mainFile())).append(',')
                    .append(csv(seed.reason().name())).append(',')
                    .append(csv(seed.ruleName())).append(',')
                    .append(csv(seed.detail())).append(',')
                    .append(csv(seed.copiedPath() == null ? "" : seed.copiedPath().toString()))
                    .append('\n');
        }
        return csv.toString();
    }

    private String csv(String value) {
        String escaped = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long days = seconds / 86_400;
        seconds %= 86_400;
        long hours = seconds / 3_600;
        seconds %= 3_600;
        long minutes = seconds / 60;
        seconds %= 60;
        return String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds);
    }

    private static List<Appender> detachLogConsoleAppenders() {
        Logger rootLogger = Logger.getRootLogger();
        List<Appender> detached = new ArrayList<>();
        Enumeration<?> appenders = rootLogger.getAllAppenders();
        while (appenders.hasMoreElements()) {
            Object appender = appenders.nextElement();
            if (appender instanceof ConsoleAppender consoleAppender) {
                detached.add(consoleAppender);
            }
        }
        for (Appender appender : detached) {
            rootLogger.removeAppender(appender);
        }
        return detached;
    }

    private void restoreLogConsoleAppenders() {
        if (detachedConsoleAppenders.isEmpty()) {
            return;
        }
        Logger rootLogger = Logger.getRootLogger();
        for (Appender appender : detachedConsoleAppenders) {
            if (appender.getName() == null || rootLogger.getAppender(appender.getName()) == null) {
                rootLogger.addAppender(appender);
            }
        }
    }

    public record RunInfo(
            boolean compilerDtEnabled,
            boolean jvmDtEnabled,
            int physicalCores,
            int timeoutSeconds,
            int parallelism,
            boolean staticFilterEnabled,
            boolean compileFilterEnabled,
            boolean jvmDtFilterEnabled,
            boolean copyAcceptedCase,
            boolean keepRejectedCase,
            List<CompilerInstance> compilers,
            List<JvmInstance> jvms,
            Path outputDir,
            Path logFile,
            Path cacheFile,
            Path reportsDir,
            boolean hostLogging) {

        String formatCompilerIds() {
            if (compilers == null || compilers.isEmpty()) {
                return "none";
            }
            return compilers.stream()
                    .map(compiler -> compiler.getId() + "(" + firstVersionLine(compiler.getVersionInfo()) + ")")
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("none");
        }

        String formatJvmIds() {
            if (jvms == null || jvms.isEmpty()) {
                return "none";
            }
            return jvms.stream()
                    .map(jvm -> jvm.getId() + "(" + firstVersionLine(jvm.getVersionInfo()) + ")")
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("none");
        }

        private String firstVersionLine(String versionInfo) {
            if (versionInfo == null || versionInfo.isBlank()) {
                return "version pending";
            }
            return versionInfo.lines()
                    .map(String::strip)
                    .filter(line -> !line.isBlank())
                    .filter(line -> !line.startsWith("Picked up "))
                    .findFirst()
                    .orElse("version pending");
        }
    }
}
