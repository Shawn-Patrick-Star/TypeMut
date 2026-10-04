package org.seed.validation;

import org.difftest.model.DTResult;
import org.difftest.model.DTType;
import org.difftest.model.exec.ExecutionResult;
import org.seed.model.RejectionReason;

import java.util.ArrayList;
import java.util.List;

public class RawDiffEvaluator {

    public RejectionReason evaluateRejectionReason(DTResult result) {
        return evaluateRejectionReason(result, true, true);
    }

    public RejectionReason evaluateRejectionReason(
            DTResult result,
            boolean compileFilterEnabled,
            boolean jvmDtFilterEnabled) {
        if (result == null) {
            return jvmDtFilterEnabled ? RejectionReason.RAW_ENVIRONMENT_ERROR : null;
        }
        if (result.hasDiff()) {
            return jvmDtFilterEnabled ? RejectionReason.RAW_DIFF_FAILURE : null;
        }
        if (result.getType() == DTType.MATCH_SUCCESS) {
            return null;
        }
        if (result.getType() == DTType.MATCH_FAILURE) {
            if (hasCompilerResults(result)) {
                return compileFilterEnabled ? RejectionReason.COMPILE_FAILED : null;
            }
            return null;
        }
        if (result.getType() == DTType.ENVIRONMENT_ERROR && hasCompilerResults(result)) {
            return compileFilterEnabled ? RejectionReason.COMPILE_FAILED : null;
        }
        return switch (result.getType()) {
            case MATCH_TIMEOUT -> jvmDtFilterEnabled ? RejectionReason.RAW_TIMEOUT : null;
            case ENVIRONMENT_ERROR -> jvmDtFilterEnabled ? RejectionReason.RAW_ENVIRONMENT_ERROR : null;
            default -> jvmDtFilterEnabled ? RejectionReason.RAW_ENVIRONMENT_ERROR : null;
        };
    }

    public String buildDetail(DTResult result) {
        if (result == null) {
            return "DTPipeline returned null.";
        }
        String message = result.getMessage();
        StringBuilder detail = new StringBuilder();
        detail.append(result.getType());
        if (message != null && !message.isBlank()) {
            detail.append(": ").append(message);
        }
        String executionDetail = executionDetail(result);
        if (!executionDetail.isBlank()) {
            detail.append(" | ").append(executionDetail);
        }
        return detail.toString();
    }

    private String executionDetail(DTResult result) {
        List<String> details = new ArrayList<>();
        for (ExecutionResult executionResult : result.getExecutionResults()) {
            if (executionResult.getExitCode() == 0) {
                continue;
            }
            String output = preferredOutput(executionResult);
            String prefix = executionResult.getId() + " exit=" + executionResult.getExitCode();
            if (output.isBlank()) {
                details.add(prefix + " no output");
            } else {
                details.add(prefix + " " + firstLines(output, 10));
            }
        }
        return String.join(" || ", details);
    }

    private boolean hasCompilerResults(DTResult result) {
        return result.getExecutionResults() != null &&
                result.getExecutionResults().stream()
                        .anyMatch(res -> res instanceof org.difftest.model.exec.CompExecutionResult);
    }

    private String preferredOutput(ExecutionResult result) {
        String stderr = nullToEmpty(result.getRawStderr());
        if (!stderr.isBlank()) {
            return stderr;
        }
        return nullToEmpty(result.getRawStdout());
    }

    private String firstLines(String output, int maxLines) {
        return output.lines()
                .limit(maxLines)
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .reduce((left, right) -> left + " / " + right)
                .orElse("");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
