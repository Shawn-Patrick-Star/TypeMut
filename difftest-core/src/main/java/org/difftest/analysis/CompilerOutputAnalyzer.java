package org.difftest.analysis;

import org.difftest.analysis.extractor.CompilerErrExtractor;
import org.difftest.analysis.filter.CompilerErrFilter;
import org.difftest.model.DTFailureReason;
import org.difftest.model.exec.CompExecutionResult;
import org.difftest.model.exec.ExecutionStatus;

import java.util.Map;

public class CompilerOutputAnalyzer {
    private final CompilerErrExtractor extractor = new CompilerErrExtractor();
    private final CompilerErrFilter filter = new CompilerErrFilter();

    public void analyze(CompExecutionResult result) {
        extract(result);
        filter.filte(result);
    }

    private void extract(CompExecutionResult result) {
        if (result == null || result.getStatus() != ExecutionStatus.FAILURE) {
            return;
        }

        DTFailureReason reason = extractor.extractFailureReason(result);
        Map<String, Long> fingerprint = extractor.extractErrorFingerprint(result);
        result.setFailureReason(reason);
        result.setErrorFingerprint(fingerprint);
    }
}
