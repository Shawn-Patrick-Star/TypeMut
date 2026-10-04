package org.difftest.analysis.extractor;

import org.difftest.model.DTFailureReason;
import org.difftest.model.exec.CompExecutionResult;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CompilerErrExtractor {
    private static final Pattern ERROR_LINE_PATTERN = Pattern.compile(
            "(?:\\.java:(\\d+): ?(?i)error)|(?:\\(at line (\\d+)\\))");
    private static final Pattern CANNOT_FIND_SYMBOL_CLASS_PATTERN = Pattern.compile(
            "(?is)error:\\s+cannot\\s+find\\s+symbol.*?\\bsymbol:\\s+class\\s+\\S+");

    public Map<String, Long> extractErrorFingerprint(CompExecutionResult result) {
        Map<String, Long> fingerprint = new TreeMap<>();
        Matcher matcher = ERROR_LINE_PATTERN.matcher(combinedOutput(result));
        while (matcher.find()) {
            String lineNum = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            fingerprint.merge("Line-" + lineNum, 1L, Long::sum);
        }
        return fingerprint;
    }

    public DTFailureReason extractFailureReason(CompExecutionResult result) {
        if (hasCannotFindSymbolClassError(result)) {
            return DTFailureReason.COMPILER_SYMBOL_NOT_FOUND;
        }
        if (hasLambdaCaptureEffectivelyFinalError(result)) {
            return DTFailureReason.COMPILER_LAMBDA_CAPTURE_EFFECTIVELY_FINAL;
        }
        if (hasLambdaMissingReturnValueError(result)) {
            return DTFailureReason.COMPILER_LAMBDA_MISSING_RETURN_VALUE;
        }
        if (hasUnreachableCodeError(result)) {
            return DTFailureReason.COMPILER_UNREACHABLE_CODE;
        }
        if (hasMissingReturnError(result)) {
            return DTFailureReason.COMPILER_MISSING_RETURN;
        }
        return DTFailureReason.NONE;
    }

    private boolean hasCannotFindSymbolClassError(CompExecutionResult result) {
        return CANNOT_FIND_SYMBOL_CLASS_PATTERN.matcher(combinedOutput(result)).find();
    }

    private boolean hasMissingReturnError(CompExecutionResult result) {
        String combined = combinedOutput(result);
        return combined.contains("missing return statement")
                || combined.contains("method must return a result");
    }

    private boolean hasLambdaCaptureEffectivelyFinalError(CompExecutionResult result) {
        String combined = combinedOutput(result).toLowerCase(Locale.ROOT);
        return combined.contains("local variables referenced from a lambda expression must be final or effectively final")
                || (combined.contains("local variable")
                && combined.contains("is required to be final or effectively final based on its usage"));
    }

    private boolean hasLambdaMissingReturnValueError(CompExecutionResult result) {
        String combined = combinedOutput(result).toLowerCase(Locale.ROOT);
        return combined.contains("bad return type in lambda expression")
                && combined.contains("missing return value");
    }

    private boolean hasUnreachableCodeError(CompExecutionResult result) {
        return combinedOutput(result).toLowerCase(Locale.ROOT).contains("unreachable code");
    }

    private String combinedOutput(CompExecutionResult result) {
        return (result.getStdout() == null ? "" : result.getStdout()) + "\n"
                + (result.getStderr() == null ? "" : result.getStderr());
    }
}
