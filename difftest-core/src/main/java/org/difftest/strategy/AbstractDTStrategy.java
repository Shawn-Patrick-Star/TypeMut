package org.difftest.strategy;

import org.difftest.model.DTFailureReason;
import org.difftest.model.DTResult;
import org.difftest.model.DTType;
import org.difftest.model.exec.ExecutionResult;
import org.difftest.model.exec.ExecutionStatus;
import org.difftest.model.exec.RunTimeExecutionResult;

import lombok.Setter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;


public abstract class AbstractDTStrategy<T extends ExecutionResult> {

    @Setter
    protected boolean ignoreTimeoutDifference = false;

    protected Map<String, String> collectAllOutputs(List<T> results) {
        return results.stream().collect(Collectors.toMap(
                T::getId,
                T::getFullOutput));
    }

    protected DTResult interruptedResult(List<T> results, String stageName) {
        List<String> interruptedIds = results.stream()
                .filter(result -> result.getStatus() == ExecutionStatus.INTERRUPTED)
                .map(ExecutionResult::getId)
                .toList();
        if (interruptedIds.isEmpty()) {
            return null;
        }
        return DTResult.ofExecution(
                DTType.ENVIRONMENT_ERROR,
                collectAllOutputs(results),
                stageName + " interrupted by external shutdown/Ctrl+C: " + String.join(", ", interruptedIds),
                results)
                .withFailureReason(DTFailureReason.PROCESS_INTERRUPTED);
    }

    protected boolean areEquivalent(RunTimeExecutionResult left, RunTimeExecutionResult right) {
        if (left.getStatus() != right.getStatus()) return false;
        return switch (left.getStatus()) {
            case SUCCESS -> Objects.equals(left.getStdout(), right.getStdout());
            case FAILURE -> Objects.equals(left.getIssues(), right.getIssues());
            case UNKNOWN -> left.getExitCode() == right.getExitCode();
            case TIMEOUT -> true;
            case INTERRUPTED -> true;
            case CRASH -> Objects.equals(left.getIssues(), right.getIssues()) && left.getExitCode() == right.getExitCode();
        };
    }
}
