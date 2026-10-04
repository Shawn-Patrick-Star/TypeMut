package org.difftest.config;

import org.difftest.model.instance.CompilerInstance;
import org.difftest.model.instance.JvmInstance;
import org.difftest.model.exec.ExecutionResult;
import org.difftest.executor.ProcessExecutor;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;

@Slf4j
public final class DTConfig {
    private static final DTConfigLoader loader = new DTConfigLoader();

    public static final int TIMEOUT_SECONDS = loader.getInt("difftest.timeout_seconds", 30);
    public static final int OUTPUT_LIMIT_LINES = loader.getInt("difftest.output_limit_line", 1000);

    public static final boolean IGNORE_TIMEOUT_DIFFERENCE = loader.getBoolean("difftest.ignore_timeout_difference", true);
    public static final boolean USE_COMPILER_DT = loader.getBoolean("difftest.use_compiler_dt", true);
    public static final boolean USE_JVM_DT = loader.getBoolean("difftest.use_jvm_dt", true);

    public static final String LOG_LEVEL = loader.getString("difftest.log_level", "INFO");
    public static final String LOG_FILE = loader.getString("difftest.log_file", "logs/difftest.log");


    public static final List<CompilerInstance> COMPILERS = loader.loadCompilerConfigs();
    public static final List<JvmInstance> JVMS = loader.loadJvmConfigs();

    public static final DTResource RESOURCE_POLICY = loader.loadResource(
            USE_COMPILER_DT, USE_JVM_DT, COMPILERS, JVMS);

    public static final CpuTopology CPU_TOPOLOGY = RESOURCE_POLICY.topology();
    public static final int LOGICAL_PROCESSORS = CPU_TOPOLOGY.logicalProcessors();
    public static final int PHYSICAL_CORES = CPU_TOPOLOGY.physicalCores();
    public static final int THREADS_PER_CORE = CPU_TOPOLOGY.threadsPerCore();
    public static final CpuTopology.Source TOPOLOGY_SOURCE = CPU_TOPOLOGY.source();
    public static final int ACTIVE_COMPILER_TARGETS = RESOURCE_POLICY.compilerTargets();
    public static final int ACTIVE_JVM_TARGETS = RESOURCE_POLICY.jvmTargets();
    public static final int WORKER_TARGETS = RESOURCE_POLICY.workerTargets();
    public static final int INITIAL_WORKERS = RESOURCE_POLICY.initialWorkers();
    public static final int WORKER_UPPER_BOUND = RESOURCE_POLICY.workerUpperBound();
    public static final int TARGET_EXECUTOR_THREADS = RESOURCE_POLICY.targetExecutorThreads();
    public static final int IO_READER_THREADS = RESOURCE_POLICY.ioReaderThreads();

    private static boolean versionsInitialized;

    private DTConfig() {
    }

    public static String getString(String key, String defaultValue) {
        return loader.getString(key, defaultValue);
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        return loader.getBoolean(key, defaultValue);
    }

    public static int getInt(String key, int defaultValue) {
        return loader.getInt(key, defaultValue);
    }

    public static synchronized void ensureVersions(ProcessExecutor processExecutor) {
        if (versionsInitialized) {
            return;
        }
        log.info("--- Initializing Version Check (Compilers{}) ---",
                USE_JVM_DT ? " & JVMs" : "");
        for (CompilerInstance compiler : COMPILERS) {
            ExecutionResult result = processExecutor.execute(
                    List.of(compiler.getJavacPath(), "-version"),
                    Path.of("."), compiler.getId(), ExecutionResult::new);
            String version = result.getFullOutput().trim();
            compiler.setVersionInfo(version);
            log.info("[Register] {}: {}", compiler.getId(), firstVersionLine(version));
        }
        for (JvmInstance jvm : JVMS) {
            ExecutionResult result = processExecutor.execute(
                    List.of(jvm.getJavaPath(), "-version"),
                    Path.of("."), jvm.getId(), ExecutionResult::new);
            String version = result.getFullOutput().trim();
            jvm.setVersionInfo(version);
            log.info("[Register] {}: {}", jvm.getId(), firstVersionLine(version));
        }
        versionsInitialized = true;
        log.info("--- Version Check Completed ---");
    }

    private static String firstVersionLine(String version) {
        return version == null || version.isBlank()
                ? "Unknown"
                : version.lines().findFirst().orElse("Unknown");
    }

    public static void logResourceSummary() {
        log.debug("\n{}", buildResourceSummary());
    }

    public static String buildResourceSummary() {
        return """
                ============ DIFFTEST CONFIG SUMMARY ============
                 Diff Modes          : compiler=%s, jvm=%s
                 Timeout             : run=%ds
                 Output Limit        : %d lines
                 Timeout Difference  : ignore=%s
                 Logging             : level=%s, file=%s
                 
                 Physical Cores      : %d
                 Worker Targets      : %d
                 Workers             : initial=%d, max=%d
                 
                 Compilers           : %d [%s]
                 JVMs                : %d [%s]
                =================================================""".formatted(
                USE_COMPILER_DT,
                USE_JVM_DT,
                TIMEOUT_SECONDS,
                OUTPUT_LIMIT_LINES,
                IGNORE_TIMEOUT_DIFFERENCE,
                LOG_LEVEL,
                emptyAsNone(LOG_FILE),
                PHYSICAL_CORES,
                WORKER_TARGETS,
                INITIAL_WORKERS,
                WORKER_UPPER_BOUND,
                COMPILERS.size(),
                compilerSummary(),
                JVMS.size(),
                jvmSummary());
    }

    private static String compilerSummary() {
        return COMPILERS.stream()
                .map(compiler -> compiler.getId() + "=" + compiler.getCommandPath())
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    private static String jvmSummary() {
        return JVMS.stream()
                .map(jvm -> jvm.getId() + "=" + jvm.getCommandPath())
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    private static String emptyAsNone(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
