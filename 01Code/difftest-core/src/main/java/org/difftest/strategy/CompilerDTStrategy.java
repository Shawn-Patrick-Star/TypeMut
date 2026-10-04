package org.difftest.strategy;

import org.difftest.model.DTResult;
import org.difftest.model.DTFailureReason;
import org.difftest.model.DTType;
import org.difftest.model.exec.CompExecutionResult;
import org.difftest.model.exec.ExecutionStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashMap;

public class CompilerDTStrategy extends AbstractDTStrategy<CompExecutionResult> {

    public DTResult compare(List<CompExecutionResult> results) {
        assert(results != null && results.size() >= 1);

        DTResult interrupted = interruptedResult(results, "CompilerDT");
        if (interrupted != null) {
            return interrupted;
        }

                                         
        boolean hasMissingClass =  results.stream()
                .allMatch(res -> res.getFailureReason() == DTFailureReason.COMPILER_SYMBOL_NOT_FOUND);
        if (hasMissingClass) {
            return DTResult.ofExecution(
                    DTType.ENVIRONMENT_ERROR,
                    collectAllOutputs(results),
                    "Missing class dependency detected by compiler.",
                    results)
                    .withFailureReason(DTFailureReason.COMPILER_SYMBOL_NOT_FOUND);
        }

                         
        List<String> crashingIds = results.stream()
                .filter(res -> res.getStatus() == ExecutionStatus.CRASH)
                .map(CompExecutionResult::getId)
                .toList();
        if (!crashingIds.isEmpty()) {
            return DTResult.ofExecution(
                    DTType.CRASH,
                    collectAllOutputs(results),
                    "Compiler crash detected in: " + String.join(", ", crashingIds),
                    results);
        }

                           
        boolean hasTimeout = results.stream().anyMatch(res -> res.getStatus() == ExecutionStatus.TIMEOUT);
        if (hasTimeout && (results.size() == 1 || ignoreTimeoutDifference)) {
            return DTResult.ofExecution(
                    DTType.MATCH_TIMEOUT,
                    collectAllOutputs(results),
                    results.size() == 1
                            ? "Compilation-only Results: Timeout" 
                            : "At least one compiler timeout",
                    results);
        }

                                            
        List<CompExecutionResult> successRes = new ArrayList<>();
        List<CompExecutionResult> failureRes = new ArrayList<>();
        for (CompExecutionResult res : results) {
            if (res.getStatus() == ExecutionStatus.SUCCESS)         successRes.add(res);
            else if (res.getStatus() == ExecutionStatus.FAILURE)    failureRes.add(res);
        }

        if (!successRes.isEmpty() && !failureRes.isEmpty()) {
            return DTResult.ofExecution(
                    DTType.DIFFERENCE,
                    collectAllOutputs(results),
                    "[COMPILER BUG] Inconsistency: some compilers succeeded while others failed",
                    results);
        }

                             
        do { 

            if (results.size() == 1) {
                break;
            }

            if (!failureRes.isEmpty()) {
                Map<String, Set<String>> allFingerprints = new LinkedHashMap<>();
                for (CompExecutionResult res : failureRes) {
                    allFingerprints.put(res.getId(), res.getErrorFingerprint().keySet());
                }

                boolean hasDiff = false;
                Set<String> gtKeys = allFingerprints.values().iterator().next();

                for (int i = 0; i < failureRes.size(); i++) {
                    CompExecutionResult currentRes = failureRes.get(i);
                    Set<String> currentKeys = allFingerprints.get(currentRes.getId());

                    if (gtKeys.equals(currentKeys)) continue;

                                                   
                    if (!gtKeys.containsAll(currentKeys) && !currentKeys.containsAll(gtKeys)) {
                        
                                                   
                                                                      
                        hasDiff = true;
                        break;
                    }
                }

                if (hasDiff) {
                    StringBuilder msg = new StringBuilder("[COMPILER BUG] Inconsistency: Line fingerprints non-subset. ");
                    allFingerprints.forEach((id, lines) -> {
                        msg.append("\n").append(id).append(": ").append(lines);
                    });

                    return DTResult.ofExecution(
                        DTType.DIFFERENCE,
                        collectAllOutputs(results),
                        msg.toString().trim(),
                        results);
                }
            }
        } while(false);
        

                  
        DTType matchType = !successRes.isEmpty() ? DTType.MATCH_SUCCESS : DTType.MATCH_FAILURE;

        return DTResult.ofExecution(
            matchType, 
            collectAllOutputs(results), 
            results.size() >= 2 ? "Consistent Compiler Behavior: " + matchType 
                                : "Compile-only result: " + matchType, 
            results);
    }
}
