package org.difftest;

import lombok.extern.slf4j.Slf4j;
import org.difftest.config.DTConfig;
import org.difftest.engine.CompilerDTEngine;
import org.difftest.engine.JvmDTEngine;
import org.difftest.executor.BatchScheduler;
import org.difftest.executor.ProcessExecutor;
import org.difftest.model.DTResult;
import org.difftest.model.DTFailureReason;
import org.difftest.model.DTType;
import org.difftest.strategy.CompilerDTStrategy;
import org.difftest.strategy.JvmDTStrategy;
import org.difftest.analysis.RuntimeOutputAnalyzer;
import org.difftest.performance.PerformanceMetrics;
import org.difftest.util.MainClassResolver;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

   
                               
                                              
   
@Slf4j
public class DTPipeline implements AutoCloseable {

    private final ProcessExecutor processExecutor;
    private final BatchScheduler diffExecutor;

    private final CompilerDTEngine compilerDTEngine;
    private final JvmDTEngine jvmDTEngine;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);

       
                                       
       
    public DTPipeline() {
        this.processExecutor = new ProcessExecutor(
            DTConfig.TIMEOUT_SECONDS,
            DTConfig.OUTPUT_LIMIT_LINES,
            DTConfig.IO_READER_THREADS);
            
        this.diffExecutor = new BatchScheduler(this.processExecutor, DTConfig.TARGET_EXECUTOR_THREADS);

        CompilerDTStrategy compilerStrategy = new CompilerDTStrategy();
        compilerStrategy.setIgnoreTimeoutDifference(DTConfig.IGNORE_TIMEOUT_DIFFERENCE);

        JvmDTStrategy jvmStrategy = new JvmDTStrategy();
        jvmStrategy.setIgnoreTimeoutDifference(DTConfig.IGNORE_TIMEOUT_DIFFERENCE);


        RuntimeOutputAnalyzer analyzer = new RuntimeOutputAnalyzer(DTConfig.OUTPUT_LIMIT_LINES);
        DTConfig.ensureVersions(processExecutor);
        this.compilerDTEngine = new CompilerDTEngine(
            DTConfig.COMPILERS,
            diffExecutor, 
            compilerStrategy,
            DTConfig.USE_COMPILER_DT);
        this.jvmDTEngine = hasRuntimeStage()
                ? new JvmDTEngine(
                    DTConfig.JVMS,
                    diffExecutor,
                    jvmStrategy,
                    analyzer,
                    DTConfig.USE_JVM_DT)
                : null;
    }

       
                       
                                                                                       
                                 
       
    public DTResult runPipeline(Path sourceFile, String mainClassName) {
        lifecycleLock.readLock().lock();
        try (PerformanceMetrics.TimerContext ignored = PerformanceMetrics.global()
                .start(PerformanceMetrics.Metric.PIPELINE_TOTAL)) {
            if (closed.get()) {
                log.warn("[DTPipeline] runPipeline called after pipeline was closed: {}", sourceFile);
                return DTResult.match(DTType.ENVIRONMENT_ERROR,
                        "[DTPipeline] Pipeline was closed before execution.");
            }
            return runPipelineInternal(sourceFile, mainClassName);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private DTResult runPipelineInternal(Path sourceFile, String mainClassName) {
        DTContext context = new DTContext();
        long pipelineStartNanos = System.nanoTime();

        try {
            if (sourceFile == null) {
                return DTResult.match(DTType.ENVIRONMENT_ERROR, "[DTPipeline] Source file is null.");
            }
            Path parent = sourceFile.toAbsolutePath().normalize().getParent();
            if (parent == null) {
                return DTResult.match(DTType.ENVIRONMENT_ERROR,
                        "[DTPipeline] Source file has no parent directory: " + sourceFile);
            }
            context.setSourceDir(parent);
            context.setMainClassName(MainClassResolver.resolve(sourceFile, mainClassName));

            do {
                                       
                log.debug("--- Stage 1: Compiler DT ---");
                long stageStartNanos = System.nanoTime();
                compilerDTEngine.run(context);
                log.info("--- Stage 1: Compiler DT finished in {} ms ({}) ---",
                        elapsedMillis(stageStartNanos), context.getFinalResult().getType());
                if (context.getFinalResult().getFailureReason() == DTFailureReason.COMPILER_SYMBOL_NOT_FOUND) break;
                if (context.getFinalResult().getType() != DTType.MATCH_SUCCESS) break;
                
                                                           
                if (!hasRuntimeStage()) break;
                log.debug("--- Stage 2: JVM DT ---");
                stageStartNanos = System.nanoTime();
                jvmDTEngine.run(context);
                log.info("--- Stage 2: JVM DT finished in {} ms ({}) ---",
                        elapsedMillis(stageStartNanos), context.getFinalResult().getType());
                if (context.getFinalResult().getFailureReason() == DTFailureReason.MAIN_CLASS_NOT_FOUND) break;
                if (context.getFinalResult().hasDiff()
                 || context.getFinalResult().getType() == DTType.MATCH_TIMEOUT) break;

            } while(false);
        } catch (RuntimeException e) {
            log.error("[DTPipeline] Internal failure while running {}", sourceFile, e);
            context.setFinalResult(DTResult.match(DTType.ENVIRONMENT_ERROR,
                    "[DTPipeline] Internal failure: " + e.getMessage()));
        }

        DTResult result = context.getFinalResult();
        if (result == null) {
            result = DTResult.match(DTType.ENVIRONMENT_ERROR,
                    "[DTPipeline] Internal failure: no final result was produced.");
            context.setFinalResult(result);
        }
        log.info("--- DTPipeline finished in {} ms ({}) ---",
                elapsedMillis(pipelineStartNanos), result.getType());
        return result;
    }

    private long elapsedMillis(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private boolean hasRuntimeStage() {
        return DTConfig.USE_JVM_DT;
    }

    @Override
    public void close() {
        lifecycleLock.writeLock().lock();
        try {
            if (closed.compareAndSet(false, true)) {
                compilerDTEngine.close();
                if (jvmDTEngine != null) jvmDTEngine.close();
                diffExecutor.close();
                processExecutor.close();
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }
}
