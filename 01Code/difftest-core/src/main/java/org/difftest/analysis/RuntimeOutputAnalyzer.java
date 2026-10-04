package org.difftest.analysis;

import org.difftest.analysis.extractor.ErrExtractor;
import org.difftest.analysis.normalizer.LargeOutputNormalizer;
import org.difftest.analysis.normalizer.NoiseNormalizer;
import org.difftest.analysis.filter.ErrFilter;
import org.difftest.model.exec.ExecutionResult;
import org.difftest.model.exec.RunTimeExecutionResult;

import java.util.Objects;
import java.util.stream.Collectors;

public class RuntimeOutputAnalyzer {

    private final LargeOutputNormalizer textNormalizer;
    private final NoiseNormalizer lineNormalizer = new NoiseNormalizer();
    private final ErrExtractor extractor = new ErrExtractor();
    private final ErrFilter filter = new ErrFilter();

    public RuntimeOutputAnalyzer(int outputLimitLines) {
        this.textNormalizer = new LargeOutputNormalizer(outputLimitLines);
    }

    public void analyze(RunTimeExecutionResult result) {
        if (result.getExitCode() == ExecutionResult.TIMEOUT_EXIT_CODE) return;
        result.setStdout(normalize(result.getStdout()));
        result.setStderr(normalize(result.getStderr()));
        extractor.extract(result);
        filter.filte(result);
    }

       
                   
       
    private String normalize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        String current = input;
                                       
        current = textNormalizer.normalize(current);

                       
        current = current.lines()
                .map(lineNormalizer::normalize)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));

        return current;
    }
}
