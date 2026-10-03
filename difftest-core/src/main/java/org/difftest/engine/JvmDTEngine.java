package org.difftest.engine;

import lombok.extern.slf4j.Slf4j;
import org.difftest.DTContext;
import org.difftest.analysis.RuntimeOutputAnalyzer;
import org.difftest.executor.BatchScheduler;
import org.difftest.performance.PerformanceMetrics;
import org.difftest.model.DTResult;
import org.difftest.model.DTType;
import org.difftest.model.artifact.ExecutionArtifact;
import org.difftest.model.exec.RunTimeExecutionResult;
import org.difftest.model.instance.JvmInstance;
import org.difftest.strategy.JvmDTStrategy;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class JvmDTEngine implements AutoCloseable {
    private final List<JvmInstance> jvms;
    private final BatchScheduler diffExecutor;
    private final JvmDTStrategy jvmStrategy;
    private final RuntimeOutputAnalyzer analyzer;
    private final boolean useJvmDt;

    public JvmDTEngine(List<JvmInstance> jvms,
            BatchScheduler diffExecutor,
            JvmDTStrategy jvmStrategy,
            RuntimeOutputAnalyzer analyzer,
            boolean useJvmDt) {

        this.jvms = jvms;
        this.diffExecutor = diffExecutor;
        this.jvmStrategy = jvmStrategy;
        this.analyzer = analyzer;
        this.useJvmDt = useJvmDt;
    }

    public void run(DTContext context) {
        assert context.getCompilerArtifacts() != null && !context.getCompilerArtifacts().isEmpty();

                                 
        List<JvmPlan> plans = new ArrayList<>();
        for (ExecutionArtifact artifact : effectiveArtifacts(context)) {
            for (JvmInstance jvm : jvms) {
                plans.add(new JvmPlan(artifact, jvm));
            }
        }

        List<RunTimeExecutionResult> results = diffExecutor.executeParallel(
                plans,
                "JVMDT",
                JvmPlan::id,
                p -> p.artifact().getRootDir(),
                p -> p.jvm().buildCommand(p.artifact()),
                p -> p.jvm().getId().toLowerCase(java.util.Locale.ROOT).contains("openj9")
                        ? PerformanceMetrics.Metric.JVM_OPENJ9_EXECUTION
                        : PerformanceMetrics.Metric.JVM_HOTSPOT_EXECUTION,
                (id, exit, out, err, dur) -> {
                    RunTimeExecutionResult r = new RunTimeExecutionResult(id, exit, out, err, dur);
                    JvmPlan plan = plans.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
                    r.setArtifact(plan.artifact());
                    r.setRuntimeId(plan.jvm().getId());
                    return r;
                });

                             
        results.forEach(analyzer::analyze);

                             
        DTResult compareResult = jvmStrategy.compare(results);

                                  
        if (compareResult.getType() == DTType.MATCH_FAILURE) {
            log.debug(compareResult.toString(true));    
        }
        
        
        context.setFinalResult(compareResult);
    }


                                                                  
                                                                      
    private List<ExecutionArtifact> effectiveArtifacts(DTContext context) {
        List<ExecutionArtifact> artifacts = context.getCompilerArtifacts();
        if (useJvmDt) {
            return artifacts;
        }

        ExecutionArtifact baseline = artifacts.stream()
                .filter(artifact -> artifact.getCompilerId().toLowerCase().contains("javac"))
                .findFirst()
                .orElse(artifacts.get(0));
        return List.of(baseline);
    }

    private record JvmPlan(ExecutionArtifact artifact, JvmInstance jvm) {
        String id() {
            return artifact.getCompilerId() + " -> " + jvm.getId();
        }
    }

    @Override
    public void close() {
                                                                                             
    }
}
