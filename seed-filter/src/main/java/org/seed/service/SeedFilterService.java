package org.seed.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.log4j.Appender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.difftest.DTPipeline;
import org.difftest.config.DTConfig;
import org.difftest.model.DTResult;
import org.seed.cache.SeedFilterCache;
import org.seed.config.SeedFilterConfig;
import org.seed.loader.SeedLoader;
import org.seed.loader.SeedLoaders;
import org.seed.model.RejectedSeed;
import org.seed.model.RejectionReason;
import org.seed.model.Seed;
import org.seed.model.SeedFilterResult;
import org.seed.rules.StaticSeedRuleFilter;
import org.seed.util.LoggingUtils;
import org.seed.validation.RawDiffEvaluator;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class SeedFilterService {

    private final StaticSeedRuleFilter staticFilter = new StaticSeedRuleFilter();
    private final SeedFileSystemManager fileSystemManager = new SeedFileSystemManager();
    private final SeedFilterReporter reporter = new SeedFilterReporter();
    private final RawDiffEvaluator diffEvaluator = new RawDiffEvaluator();
    private final boolean inheritHostLogging;

    public SeedFilterService() {
        this(false);
    }

    public SeedFilterService(boolean inheritHostLogging) {
        this.inheritHostLogging = inheritHostLogging;
    }

    public SeedFilterResult filter(Path inputRoot, Path outputRoot, SeedFilterConfig config) {
        SeedFilterConfig effectiveConfig = config == null ? SeedFilterConfig.defaults() : config;
        try (SeedFilterLogRouting ignored = SeedFilterLogRouting.install(inheritHostLogging, effectiveConfig)) {
            return filterInternal(inputRoot, outputRoot, effectiveConfig);
        }
    }

    private SeedFilterResult filterInternal(Path inputRoot, Path outputRoot, SeedFilterConfig effectiveConfig) {
        applyDifftestModeOverrides(effectiveConfig);
        Path acceptedRoot = outputRoot.resolve("accepted");
        Path rawDiffRoot = outputRoot.resolve("raw-diff");
        Path rejectedRoot = outputRoot.resolve("rejected");
        Path reportsRoot = outputRoot.resolve("reports");

        fileSystemManager.prepareOutput(outputRoot);
        reporter.writeReports(reportsRoot, new SeedFilterResult(List.of(), List.of()), 0, false);
        warmUpTargetVersions(effectiveConfig);

        List<Seed> scannedSeeds = loadSeeds(inputRoot);
        int parallelism = effectiveParallelism(effectiveConfig, scannedSeeds.size());
        SeedFilterReporter.RunInfo runInfo = buildRunInfo(effectiveConfig, outputRoot, reportsRoot,
                parallelism);
        Map<Seed, String> outputDirectoryNames = assignOutputDirectoryNames(scannedSeeds);
        log.info("[SeedFilter] Loaded {} candidate seeds from {}", scannedSeeds.size(), inputRoot);
        ResultSink sink = new ResultSink(scannedSeeds.size(), acceptedRoot, reportsRoot);
        reporter.start(runInfo, scannedSeeds.size());
        reporter.updateStatus("RUNNING");
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread shutdownHook = new Thread(() -> {
            interrupted.set(true);
            SeedFilterResult partial = sink.snapshot();
            reporter.writeReports(reportsRoot, partial, scannedSeeds.size(), true);
            reporter.stopWithoutFinalReport(partial);
            log.info("[SeedFilter] Interrupted. Partial output kept: accepted={}, rejected={}",
                    partial.acceptedCount(), partial.rejectedCount());
        }, "seed-filter-report-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            try (SeedFilterCache cache = new SeedFilterCache(
                    effectiveConfig.cacheFile(),
                    effectiveConfig.useCache(),
                    cacheScope(effectiveConfig))) {
                evaluateSeeds(scannedSeeds, effectiveConfig, cache, sink, rawDiffRoot, rejectedRoot,
                        outputDirectoryNames, runInfo);
            }
        } catch (RuntimeException e) {
            reporter.writeReports(reportsRoot, sink.snapshot(), scannedSeeds.size(), true);
            reporter.updateStatus("FAILED");
            reporter.stopWithoutFinalReport(sink.snapshot());
            reporter.close();
            throw e;
        } finally {
            removeShutdownHook(shutdownHook);
        }

        SeedFilterResult result = sink.snapshot();
        boolean interruptedRun = interrupted.get() || Thread.currentThread().isInterrupted();
        reporter.writeReports(reportsRoot, result, scannedSeeds.size(), interruptedRun);
        if (interruptedRun) {
            reporter.stopWithoutFinalReport(result);
        } else {
            reporter.printFinal(result);
        }
        reporter.close();
        log.info("[SeedFilter] total={}, accepted={}, rejected={}, static={}, compile={}, rawDiff={}",
                scannedSeeds.size(),
                result.acceptedCount(),
                result.rejectedCount(),
                result.staticFilteredCount(),
                result.compileFailedCount(),
                result.rawDiffFailedCount());
        return result;
    }

    private void evaluateSeeds(
            List<Seed> scannedSeeds,
            SeedFilterConfig config,
            SeedFilterCache cache,
            ResultSink sink,
            Path rawDiffRoot,
            Path rejectedRoot,
            Map<Seed, String> outputDirectoryNames,
            SeedFilterReporter.RunInfo runInfo) {
        if (scannedSeeds.isEmpty()) {
            return;
        }
        int parallelism = Math.max(1, Math.min(config.parallelism(), scannedSeeds.size()));
        parallelism = effectiveParallelism(config, scannedSeeds.size());
        if (parallelism == 1) {
            evaluateSeedsSerial(scannedSeeds, config, cache, sink, rawDiffRoot, rejectedRoot, outputDirectoryNames,
                    runInfo);
            return;
        }
        evaluateSeedsParallel(scannedSeeds, config, cache, sink, rawDiffRoot, rejectedRoot, outputDirectoryNames,
                runInfo, parallelism);
    }

    private void evaluateSeedsSerial(
            List<Seed> scannedSeeds,
            SeedFilterConfig config,
            SeedFilterCache cache,
            ResultSink sink,
            Path rawDiffRoot,
            Path rejectedRoot,
            Map<Seed, String> outputDirectoryNames,
            SeedFilterReporter.RunInfo runInfo) {
        try (DTPipeline pipeline = createValidationPipeline(config)) {
            for (int i = 0; i < scannedSeeds.size(); i++) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                Seed seed = scannedSeeds.get(i);
                persistEvaluation(new EvaluationResult(i, seed, safeEvaluateSeed(seed, config, pipeline, cache)),
                        sink, rawDiffRoot, rejectedRoot, config.keepRejectedCase(),
                        config.copyAcceptedCase(), outputDirectoryNames);
                reporter.update(sink.snapshot());
            }
        }
    }

    private void evaluateSeedsParallel(
            List<Seed> scannedSeeds,
            SeedFilterConfig config,
            SeedFilterCache cache,
            ResultSink sink,
            Path rawDiffRoot,
            Path rejectedRoot,
            Map<Seed, String> outputDirectoryNames,
            SeedFilterReporter.RunInfo runInfo,
            int parallelism) {
        log.info("[SeedFilter] Filtering with {} worker threads.", parallelism);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        CompletionService<EvaluationResult> completionService = new ExecutorCompletionService<>(executor);
        try (PipelinePool pipelines = new PipelinePool(validationMode(config))) {
            for (int i = 0; i < scannedSeeds.size(); i++) {
                Seed seed = scannedSeeds.get(i);
                int index = i;
                completionService.submit(evaluationTask(index, seed, config, cache, pipelines));
            }

            for (int done = 1; done <= scannedSeeds.size(); done++) {
                try {
                    Future<EvaluationResult> future = completionService.take();
                    persistEvaluation(future.get(), sink, rawDiffRoot, rejectedRoot, config.keepRejectedCase(),
                            config.copyAcceptedCase(), outputDirectoryNames);
                    reporter.update(sink.snapshot());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    reporter.updateStatus("INTERRUPTED");
                    log.warn("[SeedFilter] Interrupted. Keeping partial output.");
                    return;
                } catch (Exception e) {
                    throw new IllegalStateException("Seed filtering failed", e);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void persistEvaluation(
            EvaluationResult evaluation,
            ResultSink sink,
            Path rawDiffRoot,
            Path rejectedRoot,
            boolean keepRejectedCase,
            boolean copyAcceptedCase,
            Map<Seed, String> outputDirectoryNames) {
        if (evaluation.rejected() != null) {
            sink.addRejected(keepRejectedIfRequested(evaluation.seed(), evaluation.rejected(),
                    rawDiffRoot, rejectedRoot, keepRejectedCase, outputDirectoryNames));
        } else {
            sink.addAccepted(copyAcceptedSeed(evaluation.seed(), sink.acceptedRoot(),
                    copyAcceptedCase, outputDirectoryNames));
        }
    }

    private Callable<EvaluationResult> evaluationTask(
            int index,
            Seed seed,
            SeedFilterConfig config,
            SeedFilterCache cache,
            PipelinePool pipelines) {
        return () -> new EvaluationResult(index, seed, safeEvaluateSeed(seed, config, pipelines.get(), cache));
    }

    private RejectedSeed safeEvaluateSeed(
            Seed seed,
            SeedFilterConfig config,
            DTPipeline pipeline,
            SeedFilterCache cache) {
        try {
            return evaluateSeed(seed, config, pipeline, cache);
        } catch (InvalidPathException e) {
            RejectedSeed rejected = reject(seed, RejectionReason.INVALID_PATH, "invalid-path",
                    e.getMessage());
            cache.putRejected(seed, rejected.reason(), rejected.ruleName(), rejected.detail());
            log.warn("[SeedFilter] Invalid seed path skipped: {} ({})", seed.getId(), e.getMessage());
            return rejected;
        } catch (RuntimeException e) {
            RejectedSeed rejected = reject(seed, RejectionReason.RAW_ENVIRONMENT_ERROR, "seed-filter-internal-error",
                    e.getClass().getSimpleName() + ": " + nullToEmpty(e.getMessage()));
            cache.putRejected(seed, rejected.reason(), rejected.ruleName(), rejected.detail());
            log.warn("[SeedFilter] Seed {} rejected after internal evaluation error: {}",
                    seed.getId(), rejected.detail(), e);
            return rejected;
        }
    }

    private RejectedSeed evaluateSeed(
            Seed seed,
            SeedFilterConfig config,
            DTPipeline pipeline,
            SeedFilterCache cache) {
        var cached = cache.get(seed);
        if (cached.isPresent()) {
            SeedFilterCache.CachedDecision decision = cached.get();
            if (decision.accepted()) {
                return null;
            }
            return reject(seed, decision.reason(), decision.ruleName(), decision.detail());
        }

        if (config.staticFilterEnabled()) {
            var staticMatch = staticFilter.firstMatch(seed);
            if (staticMatch.isPresent()) {
                RejectedSeed rejected = reject(seed, staticMatch.get().reason(), staticMatch.get().ruleName(),
                        staticMatch.get().detail());
                cache.putRejected(seed, rejected.reason(), rejected.ruleName(), rejected.detail());
                return rejected;
            }
        }

        if (shouldRunDTPipeline(config)) {
            DTResult result = pipeline.runPipeline(seed.getMainFilePath(), seed.getMainClassName());
            RejectionReason reason = diffEvaluator.evaluateRejectionReason(
                    result,
                    config.compileFilterEnabled(),
                    config.jvmDtFilterEnabled());
            if (reason != null) {
                RejectedSeed rejected = reject(seed, reason, "raw-diff-validation",
                        diffEvaluator.buildDetail(result));
                cache.putRejected(seed, rejected.reason(), rejected.ruleName(), rejected.detail());
                return rejected;
            }
        }

        cache.putAccepted(seed);
        return null;
    }

    private RejectedSeed reject(Seed seed, RejectionReason reason, String ruleName, String detail) {
        return new RejectedSeed(seed.getId(), seed.getMainFileName(), reason, ruleName, detail, null);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private RejectedSeed keepRejectedIfRequested(
            Seed seed,
            RejectedSeed rejected,
            Path rawDiffRoot,
            Path rejectedRoot,
            boolean keepRejectedCase,
            Map<Seed, String> outputDirectoryNames) {
        Path copiedPath = null;
        if (rejected.reason() == RejectionReason.INVALID_PATH) {
            return new RejectedSeed(rejected.seedId(), rejected.mainFile(), rejected.reason(),
                    rejected.ruleName(), rejected.detail(), null);
        }
        if (shouldAlwaysCopyRejected(rejected.reason())) {
            copiedPath = copySeedIfPossible(seed,
                    rawDiffRoot.resolve(fileSystemManager.sanitize(outputDirectoryName(seed, outputDirectoryNames))));
        } else if (keepRejectedCase) {
            copiedPath = copySeedIfPossible(seed,
                    rejectedRoot.resolve(rejected.reason().directoryName())
                            .resolve(fileSystemManager.sanitize(outputDirectoryName(seed, outputDirectoryNames))));
        }
        return new RejectedSeed(rejected.seedId(), rejected.mainFile(), rejected.reason(),
                rejected.ruleName(), rejected.detail(), copiedPath);
    }

    private Path copySeedIfPossible(Seed seed, Path targetRoot) {
        try {
            return fileSystemManager.copySeed(seed, targetRoot);
        } catch (RuntimeException e) {
            log.warn("[SeedFilter] Failed to copy rejected seed {} to {}: {}",
                    seed.getId(), targetRoot, e.getMessage());
            return null;
        }
    }

    private Seed copyAcceptedSeed(
            Seed seed,
            Path acceptedRoot,
            boolean copyAcceptedCase,
            Map<Seed, String> outputDirectoryNames) {
        if (!copyAcceptedCase) {
            return seed;
        }
        Path targetRoot = fileSystemManager.copySeed(seed,
                acceptedRoot.resolve(fileSystemManager.sanitize(outputDirectoryName(seed, outputDirectoryNames))));
        return new Seed(targetRoot, seed.getMainFileName(), seed.getMainClassName(), seed.getId(), 0,
                seed.getCompanionFileNames());
    }

    private String outputDirectoryName(Seed seed, Map<Seed, String> outputDirectoryNames) {
        return outputDirectoryNames.getOrDefault(seed, seed.getOutputDirectoryName());
    }

    private Map<Seed, String> assignOutputDirectoryNames(List<Seed> seeds) {
        Map<String, Integer> counts = new HashMap<>();
        for (Seed seed : seeds) {
            counts.merge(seed.getOutputDirectoryName(), 1, Integer::sum);
        }

        Map<String, Integer> indexes = new HashMap<>();
        Map<Seed, String> names = new IdentityHashMap<>();
        for (Seed seed : seeds) {
            String baseName = seed.getOutputDirectoryName();
            int count = counts.getOrDefault(baseName, 0);
            if (count <= 1) {
                names.put(seed, baseName);
                continue;
            }
            int index = indexes.merge(baseName, 1, Integer::sum) - 1;
            names.put(seed, baseName + "_" + alphabeticSuffix(index));
        }
        return names;
    }

    private String alphabeticSuffix(int index) {
        StringBuilder suffix = new StringBuilder();
        int value = index;
        do {
            suffix.insert(0, (char) ('a' + (value % 26)));
            value = value / 26 - 1;
        } while (value >= 0);
        return suffix.toString();
    }

    private boolean shouldAlwaysCopyRejected(RejectionReason reason) {
        return reason == RejectionReason.RAW_DIFF_FAILURE;
    }

    private void warmUpTargetVersions(SeedFilterConfig config) {
        if (!shouldRunDTPipeline(config)) {
            return;
        }
        try (DTPipeline ignored = createValidationPipeline(config)) {
                                                                                         
        }
    }

    private SeedFilterReporter.RunInfo buildRunInfo(
            SeedFilterConfig config,
            Path outputRoot,
            Path reportsRoot,
            int parallelism) {
        return new SeedFilterReporter.RunInfo(
                DTConfig.USE_COMPILER_DT,
                DTConfig.USE_JVM_DT,
                DTConfig.PHYSICAL_CORES,
                DTConfig.TIMEOUT_SECONDS,
                parallelism,
                config.staticFilterEnabled(),
                config.compileFilterEnabled(),
                config.jvmDtFilterEnabled(),
                config.copyAcceptedCase(),
                config.keepRejectedCase(),
                DTConfig.COMPILERS,
                DTConfig.JVMS,
                outputRoot,
                effectiveLogFile(config),
                config.cacheFile(),
                reportsRoot,
                inheritHostLogging);
    }

    private Path effectiveLogFile(SeedFilterConfig config) {
        return config.logFile();
    }

    private boolean shouldRunDTPipeline(SeedFilterConfig config) {
        return config.compileFilterEnabled();
    }

    private int effectiveParallelism(SeedFilterConfig config, int seedCount) {
        int base = shouldRunDTPipeline(config)
            ? DTConfig.INITIAL_WORKERS
                : config.parallelism();
        return Math.max(1, Math.min(base, Math.max(1, seedCount)));
    }

    private String cacheScope(SeedFilterConfig config) {
        return "static=" + config.staticFilterEnabled()
                + ";compile=" + config.compileFilterEnabled()
                + ";jvmdt=" + config.jvmDtFilterEnabled()
                + ";keepRejected=" + config.keepRejectedCase()
                + ";copyAccepted=" + config.copyAcceptedCase();
    }

    private ValidationMode validationMode(SeedFilterConfig config) {
        if (config.compileFilterEnabled()) {
            return ValidationMode.FULL_DT;
        }
        return ValidationMode.DISABLED;
    }

    private DTPipeline createValidationPipeline(SeedFilterConfig config) {
        return switch (validationMode(config)) {
            case DISABLED -> null;
            case FULL_DT -> new DTPipeline();
        };
    }

    private void applyDifftestModeOverrides(SeedFilterConfig config) {
        System.setProperty("difftest.use_compiler_dt", Boolean.toString(config.compileFilterEnabled()));
        System.setProperty("difftest.use_jvm_dt", Boolean.toString(config.jvmDtFilterEnabled()));
        if (!config.compileFilterEnabled() && !config.jvmDtFilterEnabled()) {
            return;
        }
        if (!config.jvmDtFilterEnabled()) {
        }
    }

    private SeedFilterResult loadWithoutFiltering(Path inputRoot, Path outputRoot, SeedFilterConfig config) {
        List<Seed> accepted = new ArrayList<>();
        Path acceptedRoot = outputRoot.resolve("accepted");
        Path reportsRoot = outputRoot.resolve("reports");
        fileSystemManager.prepareOutput(outputRoot);
        List<Seed> loaded = loadSeeds(inputRoot);
        SeedFilterReporter.RunInfo runInfo = buildRunInfo(config, outputRoot, reportsRoot, 1);
        reporter.start(runInfo, loaded.size());
        reporter.updateStatus("FILTER DISABLED");
        for (Seed seed : loaded) {
            accepted.add(fileSystemManager.copyAcceptedSeed(seed, acceptedRoot));
            reporter.update(new SeedFilterResult(accepted, List.of()));
        }
        SeedFilterResult result = new SeedFilterResult(accepted, List.of());
        reporter.writeReports(reportsRoot, result, accepted.size(), false);
        reporter.printFinal(result);
        reporter.close();
        return result;
    }

    private List<Seed> loadSeeds(Path inputRoot) {
        SeedLoader loader = SeedLoaders.choose(inputRoot);
        try {
            return loader.load(inputRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load seeds from " + inputRoot, e);
        }
    }

    private record EvaluationResult(int index, Seed seed, RejectedSeed rejected) {
    }

    private void removeShutdownHook(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
                                                                                    
        }
    }

    private static final class SeedFilterLogRouting implements AutoCloseable {
        private static final String SEED_FILTER_FILE_APPENDER = "seed-filter-embedded-file";

        private final boolean enabled;
        private final Logger seedLogger;
        private final Logger diffLogger;
        private final Logger reportLogger;
        private final boolean seedAdditivity;
        private final boolean diffAdditivity;
        private final Level seedLevel;
        private final Level diffLevel;
        private final Appender fileAppender;

        private SeedFilterLogRouting(
                boolean enabled,
                Logger seedLogger,
                Logger diffLogger,
                Logger reportLogger,
                boolean seedAdditivity,
                boolean diffAdditivity,
                Level seedLevel,
                Level diffLevel,
                Appender fileAppender) {
            this.enabled = enabled;
            this.seedLogger = seedLogger;
            this.diffLogger = diffLogger;
            this.reportLogger = reportLogger;
            this.seedAdditivity = seedAdditivity;
            this.diffAdditivity = diffAdditivity;
            this.seedLevel = seedLevel;
            this.diffLevel = diffLevel;
            this.fileAppender = fileAppender;
        }

        static SeedFilterLogRouting install(boolean hostMode, SeedFilterConfig config) {
            if (!hostMode || config == null || config.logFile() == null
                    || Boolean.getBoolean("fuzz.logging.disabled")) {
                return disabled();
            }

            Logger seedLogger = Logger.getLogger("org.seed");
            Logger diffLogger = Logger.getLogger("org.difftest");
            Logger reportLogger = Logger.getLogger("org.fuzz.seedfilter.Report");
            boolean seedAdditivity = seedLogger.getAdditivity();
            boolean diffAdditivity = diffLogger.getAdditivity();
            Level seedLevel = seedLogger.getLevel();
            Level diffLevel = diffLogger.getLevel();

            Appender fileAppender = LoggingUtils.createFileAppender(config.logFile(), SEED_FILTER_FILE_APPENDER, true);
            if (fileAppender != null) {
                attachAppenderIfAbsent(seedLogger, fileAppender);
                attachAppenderIfAbsent(diffLogger, fileAppender);
            }
            Level level = Level.toLevel(config.logLevel(), Level.INFO);
            seedLogger.setLevel(level);
            diffLogger.setLevel(level);
            seedLogger.setAdditivity(false);
            diffLogger.setAdditivity(false);

            reportLogger.setLevel(Level.INFO);
            reportLogger.setAdditivity(true);

            return new SeedFilterLogRouting(true, seedLogger, diffLogger, reportLogger,
                    seedAdditivity, diffAdditivity, seedLevel, diffLevel, fileAppender);
        }

        private static SeedFilterLogRouting disabled() {
            return new SeedFilterLogRouting(false, null, null, null,
                    true, true, null, null, null);
        }

        private static void attachAppenderIfAbsent(Logger logger, Appender appender) {
            if (logger != null && appender != null && logger.getAppender(appender.getName()) == null) {
                logger.addAppender(appender);
            }
        }

        @Override
        public void close() {
            if (!enabled) {
                return;
            }
            seedLogger.setAdditivity(seedAdditivity);
            diffLogger.setAdditivity(diffAdditivity);
            seedLogger.setLevel(seedLevel);
            diffLogger.setLevel(diffLevel);
            seedLogger.removeAppender(SEED_FILTER_FILE_APPENDER);
            diffLogger.removeAppender(SEED_FILTER_FILE_APPENDER);
            if (fileAppender != null) {
                fileAppender.close();
            }
        }
    }

    private static class ResultSink {
        private final int totalCandidates;
        private final Path acceptedRoot;
        private final Path reportsRoot;
        private final List<Seed> acceptedSeeds = new ArrayList<>();
        private final List<RejectedSeed> rejectedSeeds = new ArrayList<>();

        private ResultSink(int totalCandidates, Path acceptedRoot, Path reportsRoot) {
            this.totalCandidates = totalCandidates;
            this.acceptedRoot = acceptedRoot;
            this.reportsRoot = reportsRoot;
        }

        private synchronized void addAccepted(Seed seed) {
            acceptedSeeds.add(seed);
        }

        private synchronized void addRejected(RejectedSeed seed) {
            rejectedSeeds.add(seed);
        }

        private synchronized int completedCount() {
            return acceptedSeeds.size() + rejectedSeeds.size();
        }

        private synchronized SeedFilterResult snapshot() {
            return new SeedFilterResult(new ArrayList<>(acceptedSeeds), new ArrayList<>(rejectedSeeds));
        }

        private Path acceptedRoot() {
            return acceptedRoot;
        }

        @SuppressWarnings("unused")
        private int totalCandidates() {
            return totalCandidates;
        }

        @SuppressWarnings("unused")
        private Path reportsRoot() {
            return reportsRoot;
        }
    }

    private enum ValidationMode {
        DISABLED,
        FULL_DT
    }

    private static class PipelinePool implements AutoCloseable {
        private final ValidationMode mode;
        private final List<DTPipeline> pipelines = java.util.Collections.synchronizedList(new ArrayList<>());
        private final ThreadLocal<DTPipeline> localPipeline;

        private PipelinePool(ValidationMode mode) {
            this.mode = mode == null ? ValidationMode.DISABLED : mode;
            this.localPipeline = ThreadLocal.withInitial(() -> {
                DTPipeline pipeline = switch (this.mode) {
                    case DISABLED -> null;
                    case FULL_DT -> new DTPipeline();
                };
                if (pipeline != null) {
                    pipelines.add(pipeline);
                }
                return pipeline;
            });
        }

        private DTPipeline get() {
            return mode == ValidationMode.DISABLED ? null : localPipeline.get();
        }

        @Override
        public void close() {
            for (DTPipeline pipeline : pipelines) {
                pipeline.close();
            }
        }
    }
}
