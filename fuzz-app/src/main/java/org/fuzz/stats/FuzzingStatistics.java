package org.fuzz.stats;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

   
         
                       
   
@Slf4j
public class FuzzingStatistics {
    private static final int MAX_REPORTED_GENERATION = 10;

    private volatile Instant startTime;
    private final AtomicBoolean finalReportPrinted = new AtomicBoolean(false);
    private final LongAdder mutationCount = new LongAdder();
    private final LongAdder bugCount = new LongAdder();
    private final LongAdder validCount = new LongAdder();
    private final LongAdder discardedCount = new LongAdder();
    private final LongAdder timeoutCount = new LongAdder();
    private final LongAdder failCompiledCount = new LongAdder();
    private final LongAdder noMainClassCount = new LongAdder();
    private final LongAdder environmentErrorCount = new LongAdder();
    private final LongAdder internalFailureCount = new LongAdder();
    private final LongAdder unmutableSeedCount = new LongAdder();
    private final AtomicInteger seedPoolSize = new AtomicInteger(0);

    public FuzzingStatistics() {
    }

    public void startFuzzing() {
        if (startTime == null) {
            startTime = Instant.now();
        }
    }

                   

    public void incrementMutationCount() {
        mutationCount.increment();
    }

    public void incrementBugCount() {
        bugCount.increment();
    }

    public void incrementValidCount() {
        validCount.increment();
    }

    public void incrementDiscardedCount() {
        discardedCount.increment();
    }

    public void incrementTimeoutCount() {
        timeoutCount.increment();
    }

    public void incrementFailCompiledCount() {
        failCompiledCount.increment();
    }

    public void incrementNoMainClassCount() {
        noMainClassCount.increment();
    }

    public void incrementEnvironmentErrorCount() {
        environmentErrorCount.increment();
    }

    public void incrementInternalFailureCount() {
        internalFailureCount.increment();
    }

    public void incrementUnmutableSeedCount() {
        unmutableSeedCount.increment();
    }

    public void updateSeedPoolSize(int newSize) {
        seedPoolSize.set(newSize);
    }

    public long getMutationCount() {
        return mutationCount.sum();
    }

    public long getBugCount() {
        return bugCount.sum();
    }

    public long getValidCount() {
        return validCount.sum();
    }

    public long getDiscardedCount() {
        return discardedCount.sum();
    }

    public long getTimeoutCount() {
        return timeoutCount.sum();
    }

    public long getFailCompiledCount() {
        return failCompiledCount.sum();
    }

    public long getNoMainClassCount() {
        return noMainClassCount.sum();
    }

    public long getEnvironmentErrorCount() {
        return environmentErrorCount.sum();
    }

    public long getInternalFailureCount() {
        return internalFailureCount.sum();
    }

    public long getAccountedMutationCount() {
        return getValidCount()
                + getBugCount()
                + getFailCompiledCount()
                + getNoMainClassCount()
                + getDiscardedCount()
                + getTimeoutCount()
                + getEnvironmentErrorCount()
                + getInternalFailureCount();
    }

    public long getUnaccountedMutationCount() {
        return Math.max(0, getMutationCount() - getAccountedMutationCount());
    }

    public long getUnmutableSeedCount() {
        return unmutableSeedCount.sum();
    }

    public int getSeedPoolSize() {
        return seedPoolSize.get();
    }

    public long getElapsedSeconds() {
        Instant started = startTime;
        return started == null ? 0L : Math.max(0L, Duration.between(started, Instant.now()).getSeconds());
    }

                   

    public String getStatusLine() {
        Instant started = startTime;
        long seconds = started == null ? 0L : Duration.between(started, Instant.now()).getSeconds();
        if (seconds == 0) {
            seconds = 1;
        }

        long mutationTotal = getMutationCount();
        double speed = mutationTotal == 0 ? 0.0 : (double) seconds / mutationTotal;

        return String.format(
                "[STATS] Time: %ds | Speed: %.1fs/ | Mut: %d | Accounted: %d | Pool: %d | Valid: %d | Bugs: %d | FailCompiled: %d | NoMain: %d | Discarded: %d | Timeouts: %d | EnvErr: %d | InternalFail: %d | UnMut: %d",
                seconds,
                speed,
                mutationTotal,
                getAccountedMutationCount(),
                getSeedPoolSize(),
                getValidCount(),
                getBugCount(),
                getFailCompiledCount(),
                getNoMainClassCount(),
                getDiscardedCount(),
                getTimeoutCount(),
                getEnvironmentErrorCount(),
                getInternalFailureCount(),
                getUnmutableSeedCount()
        );
    }

    public String buildSummaryReport(Map<Integer, Integer> generationalBreakdown, String status) {
        return buildSummaryReport(generationalBreakdown, status, -1);
        }

        public String buildSummaryReport(Map<Integer, Integer> generationalBreakdown,
            String status, int workers) {
        Instant started = startTime;
        Duration elapsed = started == null ? Duration.ZERO : Duration.between(started, Instant.now());
        long millis = elapsed.toMillis();
        long mutationTotal = getMutationCount();
        long validTotal = getValidCount();
        double seconds = millis / 1000.0;
        double secondsPerMutation = mutationTotal == 0 ? 0.0 : seconds / mutationTotal;
        double mutationsPerSecond = seconds <= 0.0 ? 0.0 : mutationTotal / seconds;

        StringBuilder report = new StringBuilder();
        report.append("\n============================================\n");
        report.append("           FUZZING SUMMARY REPORT           \n");
        report.append("============================================\n");
        report.append(String.format(" Status              : %s%n", status == null || status.isBlank() ? "RUNNING" : status));
        report.append(String.format(" Last Update         : %s%n", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
        report.append(String.format(" Total Duration      : %s%n", DurationFormatter.toDaysHoursMinutesSeconds(elapsed)));
        report.append(String.format(" Total Mutations     : %d%n", mutationTotal));
        report.append(String.format(" Mutation Speed      : %.2f s/mut (%.2f mut/s)%n%n", secondsPerMutation, mutationsPerSecond));
        if (workers >= 0) {
            report.append(String.format(" Workers             : %d%n", workers));
        }
        
        report.append(String.format(" Seed Pool Size      : %d%n", getSeedPoolSize()));
        report.append(String.format(" Accounted Mutations : %d%n", getAccountedMutationCount()));
        report.append(String.format(" Unaccounted Mut     : %d%n", getUnaccountedMutationCount()));
        report.append(String.format("  Valid Seeds Gen    : %d (%.2f%%)%n", validTotal, calculateRatio(validTotal)));
        report.append(String.format("  Bugs Found         : %d%n", getBugCount()));
        report.append(String.format("  Fail Compiled Cases: %d (%.2f%%)%n", getFailCompiledCount(), calculateRatio(getFailCompiledCount())));
        report.append(String.format("  No Main Class Cases: %d (%.2f%%)%n", getNoMainClassCount(), calculateRatio(getNoMainClassCount())));
        report.append(String.format("  Discarded Cases    : %d%n", getDiscardedCount()));
        report.append(String.format("  Timeout Cases      : %d%n", getTimeoutCount()));
        report.append(String.format("  Environment Errors : %d%n", getEnvironmentErrorCount()));
        report.append(String.format("  Internal Failures  : %d%n", getInternalFailureCount()));
        report.append(String.format(" Unmutable Cases     : %d%n", getUnmutableSeedCount()));

        report.append("--------------------------------------------\n");
        report.append(" [Pool Breakdown by Generation]\n");
        for (int gen = 0; gen <= MAX_REPORTED_GENERATION; gen++) {
            int count = generationalBreakdown == null ? 0 : generationalBreakdown.getOrDefault(gen, 0);
            report.append(String.format("   Gen-%d : %d seeds%n", gen, count));
        }
        report.append("============================================");
        return report.toString();
    }

    public void printFinalReport(Map<Integer, Integer> generationalBreakdown) {
        printFinalReport(generationalBreakdown, -1);
    }

    public void printFinalReport(Map<Integer, Integer> generationalBreakdown, int workers) {
        if (!finalReportPrinted.compareAndSet(false, true)) {
            return;
        }

        log.info(buildSummaryReport(generationalBreakdown, "FINISHED", workers));
    }

    private double calculateRatio(long numerator) {
        long mutationTotal = getMutationCount();
        if (mutationTotal == 0) {
            return 0.0;
        }
        return (double) numerator / mutationTotal * 100.0;
    }
}
