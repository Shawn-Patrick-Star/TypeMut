package org.fuzz.core;

import lombok.extern.slf4j.Slf4j;
import org.difftest.DTPipeline;
import org.difftest.model.DTResult;
import org.difftest.model.DTFailureReason;
import org.difftest.performance.PerformanceMetrics;
import org.difftest.config.DTConfig;
import org.difftest.resource.AdaptivePipelineController;
import org.fuzz.config.FuzzConfig;
import org.fuzz.operator.MutOP;
import org.fuzz.operator.OperatorRegistry;
import org.fuzz.seed.SeedPool;
import org.seed.model.Seed;
import org.fuzz.stats.FuzzingConsoleReporter;
import org.fuzz.stats.FuzzingStatistics;
import org.fuzz.stats.SilentProgressReporter;
import org.fuzz.util.AstUtils;
import org.fuzz.util.FileUtils;
import org.fuzz.util.AsyncLogGroupingService;
import org.fuzz.util.LoggingUtils;
import org.apache.log4j.Appender;
import org.slf4j.MDC;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.difftest.util.ThreadUtils;

@Slf4j
public class FuzzingEngine {

    private final OperatorRegistry registry;
    private final FuzzingStatistics stats;
    private final SeedPool seedPool;
    private final BugFilter bugFilter;
    private final BugDeduplicator bugDeduplicator;
    private final FuzzingConsoleReporter consoleReporter;
    private final SilentProgressReporter silentProgressReporter;
    private final AsyncLogGroupingService logGroupingService;
    private final DTPipeline pipeline;
    private final AdaptivePipelineController resourceController;
    private final AtomicLong runSequence = new AtomicLong(0);
    private final AtomicBoolean cleanupDone = new AtomicBoolean(false);
    private final AtomicBoolean pipelineClosed = new AtomicBoolean(false);
    private final AtomicBoolean fuzzLoopRunning = new AtomicBoolean(false);
    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    public FuzzingEngine() {
        this.registry = new OperatorRegistry();
        Appender detachedConsoleAppender = FuzzConfig.LOG_ENABLED
                ? FuzzingConsoleReporter.detachLogConsoleAppender()
                : null;

        this.seedPool = new SeedPool();
        this.stats = new FuzzingStatistics();
        this.bugFilter = new BugFilter();
        this.bugDeduplicator = new BugDeduplicator();
        this.pipeline = new DTPipeline();
        this.resourceController = new AdaptivePipelineController(DTConfig.RESOURCE_POLICY);
        this.consoleReporter = new FuzzingConsoleReporter(
                stats,
                seedPool::getGenerationalBreakdown,
            detachedConsoleAppender,
            resourceController::currentClients);
        this.silentProgressReporter = FuzzConfig.LOG_ENABLED
                ? null
            : new SilentProgressReporter(stats, resourceController::currentClients);
        this.logGroupingService = FuzzConfig.LOG_ENABLED
                ? new AsyncLogGroupingService(
                        Paths.get(FuzzConfig.logPath("app.log")),
                        Paths.get(FuzzConfig.logPath("app-grouped.log")))
                : null;
        String configurationSummary = FuzzConfig.buildConfigSummary(registry);
        if (FuzzConfig.LOG_ENABLED) {
            log.info(configurationSummary);
            System.out.println(configurationSummary);
        } else {
            LoggingUtils.logRunSummary(configurationSummary);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));
    }

    private void cleanup() {
        if (cleanupDone.compareAndSet(false, true)) {
            boolean shutdownWhileRunning = fuzzLoopRunning.get();
            String finalStatus = interrupted.get() || shutdownWhileRunning ? "INTERRUPTED" : "FINISHED";
            if (FuzzConfig.LOG_ENABLED) {
                consoleReporter.updateStatus(shutdownWhileRunning ? "INTERRUPTED" : "FINISHING");
                stats.printFinalReport(seedPool.getGenerationalBreakdown(), resourceController.currentClients());
                if (!shutdownWhileRunning) consoleReporter.printFinal();
                if (logGroupingService != null) logGroupingService.close();
            }
            if (!fuzzLoopRunning.get()) {
                closePipelines();
            }
            consoleReporter.close();
            if (silentProgressReporter != null) silentProgressReporter.close();
            Path performanceReport = Paths.get(FuzzConfig.LOG_DIR, "performance-report.json");
            try {
                PerformanceMetrics.global().writeJson(performanceReport,
                    resourceController.snapshot());
            } catch (IOException e) {
                if (FuzzConfig.LOG_ENABLED) log.error("Failed to write performance report", e);
            }
            if (!FuzzConfig.LOG_ENABLED) {
                LoggingUtils.logRunSummary(stats.buildSummaryReport(
                    seedPool.getGenerationalBreakdown(), finalStatus,
                    resourceController.currentClients())
                        + System.lineSeparator()
                        + " Performance Report  : " + performanceReport);
                if (silentProgressReporter != null) {
                    silentProgressReporter.printFinal(finalStatus, performanceReport);
                }
            }
        }
    }

    private DTPipeline currentPipeline() {
        if (pipelineClosed.get()) {
            throw new IllegalStateException("DTPipeline has already been closed.");
        }
        return pipeline;
    }

    private void closePipelines() {
        if (!pipelineClosed.compareAndSet(false, true)) {
            return;
        }
        try {
            pipeline.close();
        } catch (Exception e) {
            log.error("Failed to close shared DTPipeline", e);
        }
    }

       
                     
      
                                      
                                        
       
    public void run(String initialSourceDir, String baseOutputDir) {
                  
        FileUtils.cleanDirectory(Paths.get(baseOutputDir));

        Path startPath = Paths.get(initialSourceDir);
        Path outputRoot = Paths.get(baseOutputDir);
        Path tempRoot = outputRoot.resolve("temp");
        Path bugsRoot = outputRoot.resolve("bugs");

        if (!Files.exists(startPath)) {
            log.error("Input directory not found: {}", initialSourceDir);
            return;
        }

        try {
            Files.createDirectories(tempRoot);
            Files.createDirectories(bugsRoot);
        } catch (IOException e) {
            log.error("[Prepare] Failed to create output directories: {}", e.getMessage());
            return;
        }

        if (!seedPool.initializeFromDirectory(startPath)) {
            return;
        }
        if (seedPool.isEmpty()) {
            return;
        }

        stats.startFuzzing();
        PerformanceMetrics.global().reset();
        stats.updateSeedPoolSize(seedPool.size());
        if (FuzzConfig.LOG_ENABLED) {
            consoleReporter.updateStatus("RUNNING");
            consoleReporter.start();
        } else if (silentProgressReporter != null) {
            silentProgressReporter.start();
        }
        log.info("=== Fuzzing Started. Total Seeds: {} ===", seedPool.size());

        long startTime = System.currentTimeMillis();
                                     
        boolean unlimitedTime = FuzzConfig.MAX_FUZZING_TIME_MINUTES <= 0;
        long maxTimeMillis = unlimitedTime
                ? Long.MAX_VALUE
                : TimeUnit.MINUTES.toMillis(FuzzConfig.MAX_FUZZING_TIME_MINUTES);

                                                     
        ExecutorService workerPool = Executors.newFixedThreadPool(
            FuzzConfig.WORKER_UPPER_BOUND,
            ThreadUtils.namedThreadFactory("fuzz-worker"));
        ExecutorCompletionService<Void> completionService = new ExecutorCompletionService<>(workerPool);
        fuzzLoopRunning.set(true);

        int inFlight = 0;
        boolean accepting = true;
        boolean terminationLogged = false;
        long lastReportedMutations = 0;
        long lastGroupedMutations = 0;

        try {
            while (accepting || inFlight > 0) {
                resourceController.pulse();
                while (accepting && inFlight < resourceController.currentClients()) {
                    if (isTerminationReached(startTime, maxTimeMillis)) {
                        accepting = false;
                        if (!terminationLogged) {
                            logTerminationReason();
                            terminationLogged = true;
                        }
                        break;
                    }

                    int maxAttemptsPerSeed = FuzzConfig.MUTATION_ENABLED ? FuzzConfig.MAX_MUTATIONS_PER_SEED : 1;
                    SeedPool.SeedClaim claim = seedPool.claimNextSeedForMutation(maxAttemptsPerSeed);
                    if (claim == null) {
                        accepting = false;
                        consoleReporter.updateStatus("SEED POOL EXHAUSTED");
                        log.info(
                                "[FuzzingEngine] No more mutatable seeds available (all may have reached max depth or are exhausted). Finishing...");
                        break;
                    }

                    if (claim.exhausted()) {
                        log.debug("[SeedPool] Seed {} reached max mutations ({}), removed from pool.",
                                claim.seed().getId(), FuzzConfig.MAX_MUTATIONS_PER_SEED);
                    }

                    long runId = runSequence.incrementAndGet();
                    long queuedAtNanos = System.nanoTime();
                    completionService.submit(() -> {
                        resourceController.recordQueueWait(
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queuedAtNanos));
                        processSeed(claim.seed(), tempRoot, bugsRoot, runId);
                        return null;
                    });
                    inFlight++;
                    stats.updateSeedPoolSize(seedPool.size());
                }

                long mutationCount = stats.getMutationCount();
                if (mutationCount >= lastReportedMutations + 50) {
                    stats.updateSeedPoolSize(seedPool.size());
                                                       
                    lastReportedMutations = mutationCount - (mutationCount % 50);
                }
                if (mutationCount >= lastGroupedMutations + 1000) {
                    if (logGroupingService != null) logGroupingService.requestGrouping();
                    lastGroupedMutations = mutationCount - (mutationCount % 1000);
                }

                if (inFlight == 0) {
                    if (!accepting || seedPool.isEmpty()) {
                        break;
                    }
                    continue;
                }

                Future<Void> future = completionService.take();
                inFlight--;
                try {
                    future.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.error("[Worker FAILURE] {}", cause.getMessage(), cause);
                }
                resourceController.pulse();
                stats.updateSeedPoolSize(seedPool.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interrupted.set(true);
            consoleReporter.updateStatus("INTERRUPTED");
            log.warn("[FuzzingEngine] Interrupted, stopping worker submission.");
        } finally {
            consoleReporter.updateStatus("SHUTTING DOWN");
            boolean workersTerminated = false;
            workerPool.shutdown();
            try {
                workersTerminated = workerPool.awaitTermination(10, TimeUnit.SECONDS);
                if (!workersTerminated) {
                    workerPool.shutdownNow();
                    workersTerminated = workerPool.awaitTermination(10, TimeUnit.SECONDS);
                    if (!workersTerminated) {
                        log.warn("[FuzzingEngine] Worker pool did not terminate cleanly before DTPipeline close.");
                    }
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                try {
                    workersTerminated = workerPool.awaitTermination(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                Thread.currentThread().interrupt();
            }

            fuzzLoopRunning.set(false);
            if (workersTerminated) {
                closePipelines();
            } else {
                log.warn("[FuzzingEngine] DTPipeline close skipped because worker threads are still active.");
            }
            stats.updateSeedPoolSize(seedPool.size());
            cleanup();
        }
    }

       
                                                       
                            
                           
                                      
       
    private void processSeed(Seed seed, Path tempRoot, Path bugsRoot, long runId) {
        String workerName = Thread.currentThread().getName();
        String runName = runDirectoryName(seed, runId);
        MDC.put("workerId", workerName);
        MDC.put("fuzzCtx", String.format("[%s-gen%d]", runName, seed.getGeneration()));

        try {
                                                             
            Path runDir = tempRoot.resolve(workerName).resolve(runName);

            if (!FuzzConfig.MUTATION_ENABLED) {
                runSeedWithoutMutation(seed, runDir, bugsRoot, runId);
                return;
            }

            log.info("Starting mutation attempt");
                                                
            Launcher launcher;
            MutationContext context;
            try (PerformanceMetrics.TimerContext ignored = PerformanceMetrics.global()
                    .start(PerformanceMetrics.Metric.SPOON_MODEL_BUILD)) {
                launcher = setupLauncher(seed.getMainFilePath().toString());
                if (FuzzConfig.EXPERIMENTAL_MODE) {
                    context = new MutationContext(launcher, FuzzConfig.FIXED_RANDOM_SEED + runId);
                } else {
                    context = new MutationContext(launcher);
                }
            } catch (Exception e) {
                log.warn("Spoon model build failed: {}", e.getMessage());
                return;
            }
                               
            MutOP op;
            try (PerformanceMetrics.TimerContext ignored = PerformanceMetrics.global()
                    .start(PerformanceMetrics.Metric.MUTATION_OPERATOR)) {
                op = registry.applyRandomMutationStable(context);
            }
            if (op == null) {
                log.warn("[Mutation FAILURE] No applicable mutation found. Removing from pool: {}", seed.getId());
                seedPool.remove(seed);
                stats.incrementUnmutableSeedCount();
                stats.updateSeedPoolSize(seedPool.size());
                return;
            }
            stats.incrementMutationCount();
            try {
                          
            Path mutatedFilePath;
            try (PerformanceMetrics.TimerContext ignored = PerformanceMetrics.global()
                    .start(PerformanceMetrics.Metric.SOURCE_SAVE)) {
                mutatedFilePath = saveResult(context.getModel(), runDir);
            }
            if (mutatedFilePath == null) {
                stats.incrementDiscardedCount();
                return;
            }
                               
            FileUtils.copyFiles(seed.getCompanionFilePaths(), runDir, mutatedFilePath.getFileName().toString());
                                    
            DTResult result = runMeasuredPipeline(mutatedFilePath, seed.getMainClassName());

            boolean keepRunDir = handlePipelineResult(result, seed, mutatedFilePath.getParent(), bugsRoot, runId, runDir);
            if (!keepRunDir) {
                FileUtils.deleteRecursivelyQuietly(runDir);
            }
            } catch (Exception e) {
                stats.incrementInternalFailureCount();
                log.error("[Pipeline Internal Failure] {}", e.getMessage(), e);
                FileUtils.deleteRecursivelyQuietly(runDir);
            }

        } finally {
            MDC.clear();
        }
    }

    private boolean handlePipelineResult(
            DTResult result, Seed seed, 
            Path sourceDir, Path bugsRoot, long runId, Path runDir) {
        if (result == null) {
            stats.incrementInternalFailureCount();
            return false;
        }

        switch (result.getType()) {
            case MATCH_SUCCESS:
                stats.incrementValidCount();
                if (!FuzzConfig.MUTATION_ENABLED) {
                    log.info("[Pipeline] SUCCESS. Mutation disabled; seed will not be added back to pool.");
                    return false;
                }
                int nextGen = seed.getGeneration() + 1;
                if (nextGen <= FuzzConfig.MAX_MUTATION_DEPTH) {
                    log.info("[Pipeline] SUCCESS. Adding to seed pool (Gen: {}).", nextGen);
                    seedPool.add(
                            new Seed(runDir, seed.getMainFileName(), seed.getMainClassName(), seed.getId(), nextGen,
                                    seed.getCompanionFileNames()));
                    return true;
                }
                return false;

            case MATCH_FAILURE:
                                    
                if (isMainClassNotFound(result)) {
                    stats.incrementNoMainClassCount();
                    log.info("[Pipeline] Main class not found. Discarded as seed/entry-quality issue.");
                } else if (result.getMessage().contains("Compiler")) {
                    stats.incrementFailCompiledCount();
                } else {
                    stats.incrementDiscardedCount();
                }
                log.info("[Pipeline] Consistent Failure: {}. Discarded.", result.getType());
                return false;

            case MATCH_TIMEOUT:
                stats.incrementTimeoutCount();
                log.info("[Pipeline] Timeout. Discarded.");
                return false;

            case CRASH:
            case DIFFERENCE:
            case DIFFERENCE_STDOUT:
                                      
                java.util.Optional<BugFilter.FilterMatch> filterMatch = bugFilter.match(result);
                if (filterMatch.isPresent()) {
                    if (filterMatch.get().category() == BugFilter.FilterCategory.MAIN_CLASS_NOT_FOUND) {
                        stats.incrementNoMainClassCount();
                    } else {
                        stats.incrementDiscardedCount();
                    }
                    return false;
                }

                if (bugDeduplicator.isDuplicate(seed.getId(), result)) {
                    log.info("[Pipeline] Duplicate BUG found. Skipped.");
                    stats.incrementDiscardedCount();
                    return false;
                }
                stats.incrementBugCount();
                log.error("================ BUG FOUND ================");
                log.error(result.toString(FuzzConfig.REPRODUCE_MODE));
                LoggingUtils.logBug("================ BUG FOUND ================\n"
                        + result.toString(FuzzConfig.REPRODUCE_MODE));
                markAsBug(sourceDir, bugsRoot, seed, runId);
                return false;

            case ENVIRONMENT_ERROR:
                stats.incrementEnvironmentErrorCount();
                log.error("[Pipeline Error] {}", result.getMessage());
                return false;
        }
        return false;
    }

    private boolean isMainClassNotFound(DTResult result) {
        return result.getFailureReason() == DTFailureReason.MAIN_CLASS_NOT_FOUND;
    }

    private void runSeedWithoutMutation(Seed seed, Path runDir, Path bugsRoot, long runId) {
        log.info("Mutation disabled. Running differential test on seed as-is.");
        stats.incrementMutationCount();
        try {
            Path sourceFile = copySeedToRunDir(seed, runDir);
            DTResult result = runMeasuredPipeline(sourceFile, seed.getMainClassName());
            boolean keepRunDir = handlePipelineResult(result, seed, sourceFile.getParent(), bugsRoot, runId, runDir);
            if (!keepRunDir) {
                FileUtils.deleteRecursivelyQuietly(runDir);
            }
        } catch (Exception e) {
            stats.incrementInternalFailureCount();
            log.error("[DiffOnly Internal Failure] {}", e.getMessage(), e);
            FileUtils.deleteRecursivelyQuietly(runDir);
        }
    }

    private DTResult runMeasuredPipeline(Path sourceFile, String mainClassName) {
        long startedNanos = System.nanoTime();
        DTResult result = null;
        try {
            result = currentPipeline().runPipeline(sourceFile, mainClassName);
            return result;
        } finally {
            long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            resourceController.recordCompletion(
                    isProductivePipelineResult(result),
                    result != null && result.getType() == org.difftest.model.DTType.MATCH_TIMEOUT,
                    durationMillis);
            resourceController.pulse();
        }
    }

    private boolean isProductivePipelineResult(DTResult result) {
        if (result == null) return false;
        return switch (result.getType()) {
            case MATCH_SUCCESS, CRASH, DIFFERENCE, DIFFERENCE_STDOUT -> true;
            default -> false;
        };
    }

    private Path copySeedToRunDir(Seed seed, Path runDir) throws IOException {
        Files.createDirectories(runDir);
        Path sourceFile = seed.getMainFilePath();
        Path targetFile = runDir.resolve(seed.getMainFileName());
        Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        FileUtils.copyFiles(seed.getCompanionFilePaths(), runDir, seed.getMainFileName());
        return targetFile;
    }

    private Launcher setupLauncher(String sourceFile) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(sourceFile);
        launcher.getEnvironment().setAutoImports(true);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(true);
        return launcher;
    }

    private Path saveResult(CtModel model, Path dirPath) {
                                                       
                                               
        CtType<?> mainType = AstUtils.findMainClassInModel(model);
        if (mainType == null) {
            log.error("[Save FAILURE] Main type not found");
            return null;
        }

        try {
            Files.createDirectories(dirPath);
                                                         
            String fileName = mainType.getSimpleName() + ".java";
            Path filePath = dirPath.resolve(fileName);
                                           
                                                            
            CtCompilationUnit cu = mainType.getPosition().getCompilationUnit();

            String sourceCode;
            if (cu != null) {
                                                                  
                sourceCode = mainType.getFactory().getEnvironment().createPrettyPrinter().prettyprint(cu);
            } else {
                                                                
                StringBuilder sb = new StringBuilder();
                if (mainType.getPackage() != null && !mainType.getPackage().isUnnamedPackage()) {
                             
                    sb.append("package ").append(mainType.getPackage().getQualifiedName()).append(";\n\n");
                }
                for (CtType<?> type : model.getAllTypes()) {
                    if (type.isTopLevel()) {
                                  
                        sb.append(type).append("\n\n");
                    }
                }
                sourceCode = sb.toString();
            }

                          
            if (hasInvalidUtf8Character(sourceCode)) {
                log.warn("[Save SKIPPED] Source contains invalid Unicode character(s): {}", filePath.getFileName());
                return null;
            }
            Files.writeString(filePath, sourceCode, StandardCharsets.UTF_8);
            log.info("[Save] Saved to: {}", filePath);
            return filePath;
        } catch (IOException e) {
            log.error("[Save FAILURE] Error writing file: {}", e.getMessage(), e);
            return null;
        }
    }

    private boolean hasInvalidUtf8Character(String sourceCode) {
        for (int i = 0; i < sourceCode.length(); i++) {
            char ch = sourceCode.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < sourceCode.length() && Character.isLowSurrogate(sourceCode.charAt(i + 1))) {
                    i++;
                    continue;
                }
                return true;
            } else if (Character.isLowSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }

    private void markAsBug(Path sourceDir, Path bugsRootDir, Seed seed, long runId) {
        try {
                                                        
            Files.createDirectories(bugsRootDir);
            Path targetDir = bugsRootDir.resolve(runDirectoryName(seed, runId));
            FileUtils.copyDirectory(sourceDir, targetDir);
            log.info("[Report] Bug reproduced saved to: {}", targetDir);
        } catch (IOException e) {
            log.error("[Report FAILURE] Could not save bug folder: {}", e.getMessage(), e);
        }
    }

    private String runDirectoryName(Seed seed, long runId) {
        return seed.getOutputDirectoryName() + "-run-" + runId;
    }

    private boolean isTerminationReached(long startTime, long maxTimeMillis) {
        if ("ROUNDS".equalsIgnoreCase(FuzzConfig.TERMINATION_MODE)) {
            return FuzzConfig.MAX_FUZZING_ROUNDS > 0
                    && stats.getMutationCount() >= FuzzConfig.MAX_FUZZING_ROUNDS;
        }
        return System.currentTimeMillis() - startTime >= maxTimeMillis;
    }

    private void logTerminationReason() {
        if ("ROUNDS".equalsIgnoreCase(FuzzConfig.TERMINATION_MODE)) {
            consoleReporter.updateStatus("MAX ROUNDS REACHED");
            log.info("=== Fuzzing Terminated: Reached Max Rounds ({}) ===", FuzzConfig.MAX_FUZZING_ROUNDS);
        } else if (FuzzConfig.MAX_FUZZING_TIME_MINUTES > 0) {
            consoleReporter.updateStatus("MAX TIME REACHED");
            log.info("=== Fuzzing Terminated: Reached Max Time ({} minutes) ===",
                    FuzzConfig.MAX_FUZZING_TIME_MINUTES);
        } else {
            consoleReporter.updateStatus("SEED POOL EXHAUSTED");
            log.info("=== Fuzzing Terminated: Seed pool exhausted ===");
        }
    }
}
