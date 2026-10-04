package org.difftest.engine;

import lombok.extern.slf4j.Slf4j;
import org.difftest.DTContext;
import org.difftest.analysis.CompilerOutputAnalyzer;
import org.difftest.executor.BatchScheduler;
import org.difftest.performance.PerformanceMetrics;
import org.difftest.model.DTResult;
import org.difftest.model.DTType;
import org.difftest.model.artifact.ExecutionArtifact;
import org.difftest.model.exec.CompExecutionResult;
import org.difftest.model.instance.CompilerInstance;
import org.difftest.strategy.CompilerDTStrategy;
import org.difftest.util.FileUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class CompilerDTEngine implements AutoCloseable {
    private final List<CompilerInstance> compilers;
    private final BatchScheduler diffExecutor;
    private final CompilerDTStrategy compilerStrategy;
    private final CompilerOutputAnalyzer analyzer = new CompilerOutputAnalyzer();
    private final boolean useCompilerDt;

    public CompilerDTEngine(List<CompilerInstance> compilers,
            BatchScheduler diffExecutor,
            CompilerDTStrategy compilerStrategy,
            boolean useCompilerDt) {

        this.compilers = compilers;
        this.diffExecutor = diffExecutor;
        this.compilerStrategy = compilerStrategy;
        this.useCompilerDt = useCompilerDt;
    }

    public void run(DTContext context) {
        if (!useCompilerDt) {
            log.warn("[CompilerDTEngine] Only ({}). NO CompilerDT", 
                    compilers.get(0).getId());
        }

                               
        List<Path> javaFiles = FileUtils.collectJavaFiles(context.getSourceDir());
        Map<String, Path> outputDirs = new HashMap<>();
        for (CompilerInstance compiler : compilers) {
            Path out = context.getSourceDir().resolve(".dt").resolve("compiler").resolve(compiler.getId());
            FileUtils.createDirectory(out);
            outputDirs.put(compiler.getId(), out);
        }

                                 
        List<CompExecutionResult> results = diffExecutor.executeParallel(
                compilers, 
                "CompilerDT",
                CompilerInstance::getId, 
                ignored -> context.getSourceDir(),
                c -> c.buildCommand(javaFiles, outputDirs.get(c.getId())),
                ignored -> PerformanceMetrics.Metric.COMPILER_EXECUTION,
                CompExecutionResult::new);

                             
        results.forEach(analyzer::analyze);

                             
        DTResult compareResult = compilerStrategy.compare(results);

                                  
        if (compareResult.getType() == DTType.MATCH_FAILURE) {
            log.debug(compareResult.toString(true));
        }

                                                    
        if (compareResult.getType() == DTType.MATCH_SUCCESS) {
            
            List<ExecutionArtifact> artifacts = new ArrayList<>();
            for (CompExecutionResult res : results) {
                assert res.isSuccess();
                Path outDir = outputDirs.get(res.getId());
                Path jarPath = outDir.resolve("classes.jar");
                                                                  
                FileUtils.createJar(outDir, jarPath);

                artifacts.add(new ExecutionArtifact(
                        res.getId(),
                        res.getId(),
                        outDir,
                        context.getMainClassName(),
                        jarPath));
            }
            context.setCompilerArtifacts(artifacts);
        }
        
        context.setFinalResult(compareResult);
    }

    @Override
    public void close() {
                                                                                             
    }
}
