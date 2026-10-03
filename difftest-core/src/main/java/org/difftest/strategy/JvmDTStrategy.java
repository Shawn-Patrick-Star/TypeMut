package org.difftest.strategy;

import org.difftest.model.DTFailureReason;
import org.difftest.model.DTResult;
import org.difftest.model.DTType;
import org.difftest.model.exec.ExecutionStatus;
import org.difftest.model.exec.RunTimeExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

   
                                                                             
   
public class JvmDTStrategy extends AbstractDTStrategy<RunTimeExecutionResult> {

    public DTResult compare(List<RunTimeExecutionResult> results) {
        assert results != null && results.size() >= 1;

        DTResult interrupted = interruptedResult(results, "JVMDT");
        if (interrupted != null) {
            return interrupted;
        }

                                     
        boolean hasMainClassNotFound = results.stream()
                .allMatch(res -> res.getFailureReason() == DTFailureReason.MAIN_CLASS_NOT_FOUND);
        if (hasMainClassNotFound) {
            return DTResult.ofExecution(
                    DTType.ENVIRONMENT_ERROR,
                    collectAllOutputs(results),
                    "Main class not found by JVM.",
                    results)
                    .withFailureReason(DTFailureReason.MAIN_CLASS_NOT_FOUND);
        }

                          
        List<String> crashingIds = results.stream()
                .filter(res -> res.getStatus() == ExecutionStatus.CRASH)
                .map(RunTimeExecutionResult::getId)
                .toList();
        if (!crashingIds.isEmpty()) {
            return DTResult.ofExecution(
                    DTType.CRASH,
                    collectAllOutputs(results),
                    "JVM crash detected in: " + String.join(", ", crashingIds),
                    results);
        }

                           
        boolean hasTimeout = results.stream().anyMatch(res -> res.getStatus() == ExecutionStatus.TIMEOUT);
        if (hasTimeout && (results.size() == 1 || ignoreTimeoutDifference)) {
            return DTResult.ofExecution(
                    DTType.MATCH_TIMEOUT,
                    collectAllOutputs(results),
                    results.size() == 1
                            ? "Execution-only Results: Timeout" 
                            : "At least one JVM runtime timeout",
                    results);
        }

                               
        RunTimeExecutionResult gt = findGroundTruth(results);
        assert gt != null;

        do {
            if (results.size() == 1) {
                break;
            }

            List<String> diffPairs = new ArrayList<>();
            DTType finalType = null;

            for (RunTimeExecutionResult current : results) {
                if (current == gt) continue;
                if (!areEquivalent(gt, current)) {
                    DTType type = (gt.getStatus() == ExecutionStatus.SUCCESS && current.getStatus() == ExecutionStatus.SUCCESS)
                            ? DTType.DIFFERENCE_STDOUT
                            : DTType.DIFFERENCE;
                    finalType = type;
                    diffPairs.add(current.getId());
                }
            }

            if (!diffPairs.isEmpty()) {
                return DTResult.ofExecution(
                        finalType,
                        collectAllOutputs(results),
                        "[JVM BUG] Inconsistency against GT (" + gt.getId() + "):\n"
                                + diffPairs.stream().map(id -> "  - " + id).collect(Collectors.joining("\n")),
                        results);
            }
        } while(false);


        DTType matchType = (gt.getStatus() == ExecutionStatus.SUCCESS) ? DTType.MATCH_SUCCESS : DTType.MATCH_FAILURE;
        return DTResult.ofExecution(
                matchType,
                collectAllOutputs(results),
                results.size() >= 2 ? "Consistent JVM Behavior: Ground Truth is " + gt.getId() 
                                    : "Execution-only Results: " + matchType,
                results);
    }

       
                                                                               
       
    public RunTimeExecutionResult findGroundTruth(List<RunTimeExecutionResult> results) {
                                             
        if (results == null || results.isEmpty()) {
            return null;
        }

        for (RunTimeExecutionResult res : results) {
            if (res.getArtifact() == null || res.getRuntimeId() == null) {
                continue;
            }
            String compilerId = res.getArtifact().getCompilerId().toLowerCase();
            String runtimeId = res.getRuntimeId().toLowerCase();
            if (compilerId.contains("javac") && runtimeId.contains("hotspot")) {
                return res;
            }
        }

                                                     
        for (RunTimeExecutionResult res : results) {
            if (res.getArtifact() != null
                    && res.getArtifact().getCompilerId().toLowerCase().contains("javac")) {
                return res;
            }
        }

                                                  
        return results.get(0);
    }
}
