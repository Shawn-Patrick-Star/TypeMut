package org.difftest.analysis.filter;

import org.difftest.model.DTFailureReason;
import org.difftest.model.exec.CompExecutionResult;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public class CompilerErrFilter {
    private static final Set<DTFailureReason> LAMBDA_MISSING_RETURN_OR_UNREACHABLE = EnumSet.of(
            DTFailureReason.COMPILER_LAMBDA_MISSING_RETURN_VALUE,
            DTFailureReason.COMPILER_UNREACHABLE_CODE);

    public void filte(CompExecutionResult result) {
        if (result == null) {
            return;
        }

        DTFailureReason canonicalReason = canonicalEquivalentReason(result.getFailureReason());
        if (canonicalReason == DTFailureReason.NONE) {
            return;
        }

        Map<String, Long> canonicalFingerprint = Map.of(canonicalReason.name(), 1L);
        result.setErrorFingerprint(canonicalFingerprint);
    }

    private DTFailureReason canonicalEquivalentReason(DTFailureReason reason) {
        if (reason == DTFailureReason.COMPILER_MISSING_RETURN
                || reason == DTFailureReason.COMPILER_LAMBDA_CAPTURE_EFFECTIVELY_FINAL) {
            return reason;
        }
        if (LAMBDA_MISSING_RETURN_OR_UNREACHABLE.contains(reason)) {
            return DTFailureReason.COMPILER_LAMBDA_MISSING_RETURN_VALUE;
        }
        return DTFailureReason.NONE;
    }
}
