package org.difftest.executor;

import lombok.extern.slf4j.Slf4j;
import org.difftest.model.exec.ExecutionResult;
import org.difftest.performance.PerformanceMetrics;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.difftest.util.ThreadUtils;

  
                                         

  
@Slf4j
public class ProcessExecutor implements AutoCloseable {

    private final long timeoutSeconds;
    private final int maxLines;
    private final ExecutorService ioPool;
    private final Set<Process> activeProcesses = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ProcessExecutor(long timeoutSeconds, int maxLines, int ioParallelism) {
        this.timeoutSeconds = timeoutSeconds;
        this.maxLines = maxLines;
        this.ioPool = createIoPool(ioParallelism);
    }

       
                  
                                                                             
      
                              
                             
                                                 
                                      
                                        
                           
       
    public <T extends ExecutionResult> T execute(List<String> command, 
            Path workingDir, 
            String id,
            ResultBuilder<T> builder) {
        return execute(command, workingDir, null, id, null, builder);
    }

    public <T extends ExecutionResult> T execute(List<String> command,
            Path workingDir,
            Map<String, String> environmentVars,
            String id,
            ResultBuilder<T> builder) {
        return execute(command, workingDir, environmentVars, id, null, builder);
    }

    public <T extends ExecutionResult> T execute(List<String> command,
            Path workingDir,
            String id,
            PerformanceMetrics.Metric metric,
            ResultBuilder<T> builder) {
        return execute(command, workingDir, null, id, metric, builder);
    }

                                                                                 
    public <T extends ExecutionResult> T execute(List<String> command,
            Path workingDir,
            Map<String, String> environmentVars,
            String id,
            PerformanceMetrics.Metric metric,
            ResultBuilder<T> builder) {
        if (closed.get()) {
            return builder.build(id, ExecutionResult.EXE_FAIL_EXIT_CODE, "",
                    "Executor closed before process start", 0);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        if (environmentVars != null && !environmentVars.isEmpty()) {
            pb.environment().putAll(environmentVars);
        }
        pb.redirectErrorStream(false);

        long processStartNanos = 0L;
        Process process = null;
        boolean executionRecorded = false;
        Map<String, String> contextMap = MDC.getCopyOfContextMap();

        try {
            if (closed.get()) {
                return builder.build(id, ExecutionResult.EXE_FAIL_EXIT_CODE, "",
                        "Executor closed before process start", 0);
            }

            process = pb.start();
            processStartNanos = System.nanoTime();
            activeProcesses.add(process);
            process.getOutputStream().close();

            Process finalProcess = process;
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> {
                        if (contextMap != null) {
                            MDC.setContextMap(contextMap);
                        }
                        try {
                            return readAndDrain(finalProcess.getInputStream(), maxLines);
                        } finally {
                            MDC.clear();
                        }
                    },
                    ioPool);
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                    () -> {
                        if (contextMap != null) {
                            MDC.setContextMap(contextMap);
                        }
                        try {
                            return readAndDrain(finalProcess.getErrorStream(), maxLines);
                        } finally {
                            MDC.clear();
                        }
                    },
                    ioPool);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            long executionNanos = System.nanoTime() - processStartNanos;
            PerformanceMetrics.global().record(metric, executionNanos);
            executionRecorded = true;
            long duration = TimeUnit.NANOSECONDS.toMillis(executionNanos);

            if (!finished) {
                killProcessTree(process);
                String out = getFutureSafe(stdoutFuture);
                String err = getFutureSafe(stderrFuture);
                return builder.build(id, ExecutionResult.TIMEOUT_EXIT_CODE, out, err, duration);
            }

            int exitCode = process.exitValue();
            String stdout = stdoutFuture.join();
            String stderr = stderrFuture.join();

            return builder.build(id, exitCode, stdout, stderr, duration);
        } catch (IOException e) {
            return builder.build(id, ExecutionResult.EXE_FAIL_EXIT_CODE, "", "Start Failed: " + e.getMessage(), 0);
        } catch (InterruptedException e) {
            if (process != null) {
                killProcessTree(process);
            }
            Thread.currentThread().interrupt();
            return builder.build(id, ExecutionResult.SIGINT_EXIT_CODE, "", "Interrupted", 0);
        } catch (Exception e) {
            if (process != null) {
                killProcessTree(process);
            }
            return builder.build(id, ExecutionResult.EXE_FAIL_EXIT_CODE, "", "Async Error: " + e.getMessage(), 0);
        } finally {
            if (processStartNanos != 0L && !executionRecorded) {
                PerformanceMetrics.global().record(metric,
                        System.nanoTime() - processStartNanos);
            }
            if (process != null) {
                activeProcesses.remove(process);
            }
        }
    }

       
                   
          
                                                                 
                                                
       
    private static String readAndDrain(InputStream inputStream, int maxLines) {
                                                     
        final int MAX_CHARS = 1024 * 1024;

                                                                          
        try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {

            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            int charsRead;
            int lines = 0;
            boolean truncated = false;

                               
            while ((charsRead = reader.read(buffer)) != -1) {
                if (!truncated) {
                    int appendCount = 0;
                                                  
                    for (int i = 0; i < charsRead; i++) {
                        if (buffer[i] == '\n') {
                            lines++;
                        }
                        appendCount++;

                                                 
                        if (sb.length() + appendCount > MAX_CHARS || lines >= maxLines) {
                            truncated = true;
                            break;
                        }
                    }

                                      
                    sb.append(buffer, 0, appendCount);

                                   
                    if (truncated) {
                        sb.append("\n... [TRUNCATED BY EXECUTOR] ...\n");
                    }
                }
                                                                
                                                      
            }

            return sb.toString();
        } catch (IOException e) {
            return "Error reading stream: " + e.getMessage();
        }
    }

       
                          
                
                  
       
    private static String getFutureSafe(CompletableFuture<String> future) {
        if (future == null) {
            return "(stream future is null)";
        }
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return "(stream read timeout)";
        } catch (ExecutionException e) {
                                 
            Throwable cause = e.getCause();
            String msg = cause != null ? cause.getMessage() : e.getMessage();
            if (msg == null) {
                msg = cause != null ? cause.getClass().getSimpleName() : e.getClass().getSimpleName();
            }
            return "(stream read error: " + msg + ")";
        } catch (Exception e) {
            return "(stream read error: " + e + ")";
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        activeProcesses.forEach(this::killProcessTree);
        ioPool.shutdown();
        try {
            if (!ioPool.awaitTermination(5, TimeUnit.SECONDS)) {
                ioPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService createIoPool(int ioParallelism) {
        int parallelism = Math.max(1, ioParallelism);
        return Executors.newFixedThreadPool(parallelism, ThreadUtils.namedThreadFactory("stdIO-reader"));
    }

    private void killProcessTree(Process process) {
        if (process != null) {
            try {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (Exception e) {
                log.warn("Failed to destroy descendants for translate: {}", e.getMessage());
            } finally {
                process.destroyForcibly();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
