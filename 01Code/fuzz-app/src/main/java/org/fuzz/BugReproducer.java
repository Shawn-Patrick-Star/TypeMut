package org.fuzz;

import org.difftest.DTPipeline;
import org.difftest.config.DTConfig;
import org.difftest.model.DTFailureReason;
import org.difftest.model.DTResult;
import org.difftest.model.DTType;
import org.difftest.performance.PerformanceMetrics;
import org.difftest.resource.AdaptivePipelineController;
import org.fuzz.config.FuzzCliOptions;
import org.fuzz.config.FuzzConfig;
import org.fuzz.core.BugDeduplicator;
import org.fuzz.core.BugFilter;
import org.fuzz.stats.ReproducerConsoleReporter;
import org.slf4j.MDC;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

   
                 
   
public class BugReproducer {

    private static final String REDUCE_DIR = "reproduced_bugs";
    private static final boolean isMoveBug = FuzzConfig.REPRODUCER_MOVE_BUG;

    static {
        org.fuzz.util.LoggingUtils.configure("reproducer", "DEBUG");
                                                                                                      
                                                                                      
        try {
            Class.forName("org.fuzz.util.LogGrouper");
            Class.forName("org.fuzz.util.FileUtils");
        } catch (ClassNotFoundException ignored) {
        }
    }

                                                
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BugReproducer.class);

    private final AtomicBoolean cleanupDone = new AtomicBoolean(false);
    private final AtomicBoolean pipelinesClosed = new AtomicBoolean(false);
    private final AtomicBoolean verificationRunning = new AtomicBoolean(false);
    private final DTPipeline pipeline = new DTPipeline();
    private final AdaptivePipelineController resourceController =
            new AdaptivePipelineController(DTConfig.RESOURCE_POLICY);
    private final BugDeduplicator deduplicator = new BugDeduplicator();
    private final BugFilter bugFilter = new BugFilter();
    private volatile ReproducerConsoleReporter consoleReporter;
    java.util.concurrent.atomic.AtomicInteger totalBugCount = new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger reproducedCount = new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger notReproducedCount = new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger filteredCount = new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger noMainClassCount = new java.util.concurrent.atomic.AtomicInteger();

    private enum VerificationOutcome {
        REPRODUCED,
        NOT_REPRODUCED,
        FILTERED,
        NO_MAIN_CLASS
    }

    private record VerificationRun(VerificationOutcome outcome, boolean pipelineCompleted,
            boolean timeout) {}

    private record TimedVerification(VerificationRun run, long durationMillis) {}

    public BugReproducer() {
        FuzzConfig.REPRODUCE_MODE = true;
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup, "reproducer-shutdown-hook"));
    }

    private void cleanup() {
        if (cleanupDone.compareAndSet(false, true)) {
            ReproducerConsoleReporter reporter = consoleReporter;
            if (reporter != null) {
                reporter.updateStatus("FINISHING");
                reporter.printFinal();
            }

            log.info("\n================ Summary ================");
            log.info("Reproduced bugs: {}", reproducedCount.get());
            log.info("Not reproduced: {}", notReproducedCount.get());
            log.info("Filtered false positives: {}", filteredCount.get());
            log.info("No main class cases: {}", noMainClassCount.get());
            log.info("Unique Behaviors: {}", deduplicator.getUniqueBugCount());

            try {
                org.fuzz.util.LogGrouper.groupLogs(
                        FuzzConfig.logPath("reproducer.log"),
                        FuzzConfig.logPath("reproducer-grouped.log"));
                PerformanceMetrics.global().writeJson(
                    Paths.get(FuzzConfig.logPath("reproducer-performance-report.json")),
                    resourceController.snapshot());
            } catch (Throwable t) {
                                          
                System.err.println("Cleanup error: " + t.getMessage());
            } finally {
                if (!verificationRunning.get()) {
                    closePipelines();
                }
                if (reporter != null) {
                    reporter.close();
                    consoleReporter = null;
                }
            }
        }
    }

    private DTPipeline currentPipeline() {
        if (pipelinesClosed.get()) {
            throw new IllegalStateException("DTPipeline has already been closed.");
        }
        return pipeline;
    }

    private void closePipelines() {
        if (!pipelinesClosed.compareAndSet(false, true)) {
            return;
        }
        try {
            pipeline.close();
        } catch (Exception e) {
            log.error("[Reproducer] Failed to close shared DTPipeline", e);
        }
    }

    public static void main(String[] args) throws IOException {
        FuzzCliOptions options;
        try {
            options = FuzzCliOptions.parseAndApply(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(FuzzCliOptions.usage("org.fuzz.BugReproducer"));
            System.exit(2);
            return;
        }
        if (options.helpRequested()) {
            System.out.println(FuzzCliOptions.usage("org.fuzz.BugReproducer"));
            return;
        }

        BugReproducer reproducer = new BugReproducer();
        try {
                                     
            if ("ONE".equalsIgnoreCase(FuzzConfig.REPRODUCER_MODE)) {
                reproducer.testSpecificBug();
            } else if ("ALL".equalsIgnoreCase(FuzzConfig.REPRODUCER_MODE)) {
                reproducer.testAllBugs();
            } else {
                throw new IllegalArgumentException("Unsupported BugReproducer.mode: " + FuzzConfig.REPRODUCER_MODE);
            }
        } finally {
                                                       
            reproducer.cleanup();
        }
    }

    public static void runFromCli() throws IOException {
        BugReproducer reproducer = new BugReproducer();
        try {
            if ("ONE".equalsIgnoreCase(FuzzConfig.REPRODUCER_MODE)) {
                reproducer.testSpecificBug();
            } else if ("ALL".equalsIgnoreCase(FuzzConfig.REPRODUCER_MODE)) {
                reproducer.testAllBugs();
            } else {
                throw new IllegalArgumentException("Unsupported BugReproducer.mode: " + FuzzConfig.REPRODUCER_MODE);
            }
        } finally {
            reproducer.cleanup();
        }
    }

    public void testSpecificBug() {
        Path bugPath = Paths.get(FuzzConfig.REPRODUCER_BUG_PATH);
        verifyBug(bugPath);
    }

    public void testAllBugs() throws IOException {

        List<Path> bugDirs;
        try (Stream<Path> files = Files.list(Paths.get(FuzzConfig.REPRODUCER_BUG_PATH))) {
            bugDirs = files.filter(Files::isDirectory).collect(Collectors.toList());
        }

        int threads = resourceController.maxClients();
        log.info("[Reproducer] Starting adaptive verification: initial={}, max={}",
                resourceController.currentClients(), threads);
        totalBugCount.set(bugDirs.size());
        reproducedCount.set(0);
        notReproducedCount.set(0);
        filteredCount.set(0);
        noMainClassCount.set(0);
        consoleReporter = new ReproducerConsoleReporter(
                threads,
                totalBugCount::get,
                reproducedCount::get,
                notReproducedCount::get,
                filteredCount::get,
                noMainClassCount::get,
                deduplicator::getUniqueBugCount);
        consoleReporter.updateStatus("RUNNING");
        consoleReporter.start();

                                              
        AtomicInteger workerId = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r);
            t.setDaemon(false);
            t.setName("reproducer-worker-" + workerId.incrementAndGet());
            return t;
        });
        verificationRunning.set(true);

        try {
            CompletionService<TimedVerification> completionService =
                    new ExecutorCompletionService<>(executor);
            int submitted = 0;
            int completed = 0;
            int inFlight = 0;

            while (completed < bugDirs.size()) {
                resourceController.pulse();
                while (submitted < bugDirs.size()
                        && inFlight < resourceController.currentClients()) {
                    Path dir = bugDirs.get(submitted++);
                    completionService.submit(() -> timedVerify(dir));
                    inFlight++;
                }

                Future<TimedVerification> future = completionService.take();
                inFlight--;
                completed++;
                handleTimedVerification(future, bugDirs.size());
            }
            consoleReporter.updateStatus("FINISHED");
        } catch (InterruptedException e) {
            consoleReporter.updateStatus("INTERRUPTED");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            verificationRunning.set(false);
            closePipelines();
            cleanup();
        }
    }

    private TimedVerification timedVerify(Path bugDir) {
        long start = System.nanoTime();
        try {
            return new TimedVerification(verifyBug(bugDir),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } catch (Throwable t) {
            log.error("[Reproducer] Worker failed while verifying {}", bugDir.getFileName(), t);
            return new TimedVerification(
                    new VerificationRun(VerificationOutcome.NOT_REPRODUCED, false, false),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private void handleTimedVerification(Future<TimedVerification> future, int total) {
        try {
            TimedVerification timed = future.get();
            VerificationRun run = timed.run();
            if (run.pipelineCompleted()) {
                resourceController.recordCompletion(!run.timeout(), run.timeout(),
                        timed.durationMillis());
                resourceController.pulse();
            }
            switch (run.outcome()) {
                case REPRODUCED -> reproducedCount.incrementAndGet();
                case FILTERED -> filteredCount.incrementAndGet();
                case NO_MAIN_CLASS -> noMainClassCount.incrementAndGet();
                case NOT_REPRODUCED -> notReproducedCount.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while collecting verification result", e);
        } catch (ExecutionException e) {
            notReproducedCount.incrementAndGet();
            log.error("[Reproducer] Failed to collect verification result ({}/{})",
                    reproducedCount.get() + notReproducedCount.get()
                            + filteredCount.get() + noMainClassCount.get(),
                    total, e);
        }
    }

    private VerificationRun verifyBug(Path bugDir) {
        String workerName = Thread.currentThread().getName();
        MDC.put("workerId", workerName);
        MDC.put("fuzzCtx", String.format("[%s]", bugDir.getFileName()));

        try {
            if (!Files.exists(bugDir)) {
                log.error("Bug directory not found: {}", bugDir.toAbsolutePath());
                return outcome(VerificationOutcome.NOT_REPRODUCED);
            }

            log.info("============================================================");
            log.info("Re-checking Bug: {}", bugDir.getFileName());

            File mainFile = findMainFile(bugDir);
            if (mainFile == null) {
                log.error("Skipped: No valid Main class found in {}", bugDir);
                return outcome(VerificationOutcome.NOT_REPRODUCED);
            }

            String className = mainFile.getName().replace(".java", "");

            try {
                DTResult result = currentPipeline().runPipeline(mainFile.toPath(), className);

                if (result.hasDiff()) {
                    java.util.Optional<BugFilter.FilterMatch> filterMatch = bugFilter.match(result);
                    if (filterMatch.isPresent()) {
                        if (filterMatch.get().category() == BugFilter.FilterCategory.MAIN_CLASS_NOT_FOUND) {
                            log.info("[NO_MAIN_CLASS] Candidate {} has no runnable main class ({}). Skipping.",
                                    bugDir.getFileName(), filterMatch.get().name());
                            return outcome(VerificationOutcome.NO_MAIN_CLASS, result);
                        }
                        log.info("[FILTERED] Candidate {} is a known false positive ({}). Skipping.",
                                bugDir.getFileName(), filterMatch.get().name());
                        return outcome(VerificationOutcome.FILTERED, result);
                    }
                                                                       
                    if (deduplicator.isDuplicate(bugDir.getFileName().toString(), result, false)) {
                        log.info("[DUPLICATE] Bug {} already seen in other seeds. Skipping log.", bugDir.getFileName());
                        return outcome(VerificationOutcome.REPRODUCED, result);
                    }

                    log.info("============================================================");
                    log.info("[REPRODUCED] New Unique Bug found in: {}", bugDir.getFileName());
                    log.info(result.toString(FuzzConfig.REPRODUCE_MODE));
                    if (isMoveBug) {
                        copyReproducedBug(bugDir);
                    }
                    return outcome(VerificationOutcome.REPRODUCED, result);
                } else {
                    if (result.getFailureReason() == DTFailureReason.MAIN_CLASS_NOT_FOUND) {
                        log.info("[NO_MAIN_CLASS] Candidate {} has no runnable main class. Skipping.",
                                bugDir.getFileName());
                        return outcome(VerificationOutcome.NO_MAIN_CLASS, result);
                    }
                    log.info("[Consistent Behavior] Bug disappeared. Type: {}", result.getType());
                    return outcome(VerificationOutcome.NOT_REPRODUCED, result);
                }
            } catch (Exception e) {
                log.error("Execution error for {}", bugDir.getFileName(), e);
                return outcome(VerificationOutcome.NOT_REPRODUCED);
            }
        } finally {
            MDC.clear();
        }
    }

    private VerificationRun outcome(VerificationOutcome outcome) {
        return new VerificationRun(outcome, false, false);
    }

    private VerificationRun outcome(VerificationOutcome outcome, DTResult result) {
        return new VerificationRun(outcome, true, result.getType() == DTType.MATCH_TIMEOUT);
    }

    private File findMainFile(Path dir) {
        try {
            Launcher launcher = new Launcher();
            launcher.addInputResource(dir.toString());
            launcher.getEnvironment().setNoClasspath(true);
            launcher.getEnvironment().setAutoImports(true);

            CtModel model = launcher.buildModel();
                        
            CtType<?> mainType = org.fuzz.util.AstUtils.findMainClassInModel(model);

            if (mainType != null) {
                return dir.resolve(mainType.getSimpleName() + ".java").toFile();
            }
        } catch (Exception ignored) {
        }

                                                    
        File testJava = dir.resolve("Seed.java").toFile();
        if (testJava.exists())
            return testJava;
        testJava = dir.resolve("Main.java").toFile();
        if (testJava.exists())
            return testJava;

        return null;
    }

    private void copyReproducedBug(Path bugDir) {
        try {
            Path targetDir = Paths.get(REDUCE_DIR, bugDir.getFileName().toString());
            org.fuzz.util.FileUtils.createDirectory(targetDir);
            org.fuzz.util.FileUtils.copyDirectory(bugDir, targetDir);
            log.info("[Reproducer] Bug files copied to: {}", targetDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("[Reproducer] Failed to copy Bug directory: {}", bugDir.getFileName(), e);
        }
    }
}
