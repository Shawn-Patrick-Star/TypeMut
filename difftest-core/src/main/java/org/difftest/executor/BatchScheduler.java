package org.difftest.executor;

import lombok.extern.slf4j.Slf4j;
import org.difftest.model.exec.ExecutionResult;
import org.difftest.performance.PerformanceMetrics;
import org.slf4j.MDC;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.difftest.util.ThreadUtils;

   
                                    
                                                              
   
@Slf4j
public class BatchScheduler implements AutoCloseable {

    private final ProcessExecutor processExecutor;
    private final ExecutorService targetExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public BatchScheduler(ProcessExecutor processExecutor, int targetParallelism) {
        this.processExecutor = processExecutor;
        this.targetExecutor = Executors.newFixedThreadPool(
                Math.max(1, targetParallelism),
                ThreadUtils.namedThreadFactory("batch-scheduler"));
    }

       
                      
       
    public <I, R extends ExecutionResult> List<R> executeParallel(
            Collection<I> inputs,
            String logLabel,
            Function<I, String> idExtractor,
            Function<I, Path> workingDirectoryBuilder,
            Function<I, List<String>> commandBuilder,
            Function<I, PerformanceMetrics.Metric> metricExtractor,
            ResultBuilder<R> resultBuilder) {
        if (closed.get()) {
            throw new IllegalStateException("BatchScheduler is already closed.");
        }

                                   
        Map<String, String> contextMap = MDC.getCopyOfContextMap();

        List<CompletableFuture<R>> futures = inputs.stream()
                .map(input -> CompletableFuture.supplyAsync(() -> {
                    if (contextMap != null) MDC.setContextMap(contextMap);
                    try {
                        String id = idExtractor.apply(input);
                        List<String> command = commandBuilder.apply(input);
                        Path workingDirectory = workingDirectoryBuilder.apply(input);

                        log.debug("[{}] Starting [{}]...", logLabel, id);
                        log.debug("[{}] [{}] Command:\n{}", logLabel, id, String.join(" ", command));

                        R result = processExecutor.execute(command, workingDirectory, id,
                            metricExtractor.apply(input), resultBuilder);
                        log.debug("[{}] [{}] finished in {} ms (Exit: {})", logLabel, id, result.getDuration(),
                                result.getExitCode());
                        return result;
                    } finally {
                        MDC.clear();
                    }
                }, targetExecutor))
                .collect(Collectors.toList());

        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        targetExecutor.shutdownNow();
        try {
            if (!targetExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[BatchScheduler] Timed out waiting for target executor shutdown.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
