package org.difftest.model.exec;

import lombok.Getter;
import lombok.Setter;
import org.difftest.model.artifact.ExecutionArtifact;

import java.util.*;
import java.util.stream.Collectors;

   
           
                                                                      
   
@Getter
public class RunTimeExecutionResult extends ExecutionResult {

    public enum IssueType {
        CRASH,
        ERROR,
        EXCEPTION
    }

    private final Map<IssueType, Map<String, Long>> issues = new EnumMap<>(IssueType.class);
    @Setter
    private ExecutionArtifact artifact;
    @Setter
    private String runtimeId;

    public RunTimeExecutionResult(String jvmId, int exitCode, String stdout, String stderr, long duration) {
        super(jvmId, exitCode, stdout, stderr, duration);
        for (IssueType type : IssueType.values()) {
            issues.put(type, new TreeMap<>());                          
        }
    }

    public Map<String, Long> getCrashes() { return issues.get(IssueType.CRASH); }
    public Map<String, Long> getErrors() { return issues.get(IssueType.ERROR); }
    public Map<String, Long> getExceptions() { return issues.get(IssueType.EXCEPTION); }

    @Override
    public ExecutionStatus getStatus() {
        if (exitCode == TIMEOUT_EXIT_CODE) return ExecutionStatus.TIMEOUT;
        if (isInterruptedExitCode(exitCode)) return ExecutionStatus.INTERRUPTED;
        if (!getCrashes().isEmpty() || isCrashExitCode(exitCode)) return ExecutionStatus.CRASH;
        if (!getErrors().isEmpty() || !getExceptions().isEmpty()) return ExecutionStatus.FAILURE;
        return exitCode == 0 ? ExecutionStatus.SUCCESS : ExecutionStatus.UNKNOWN;
    }

    @Override
    protected boolean isCrashExitCode(int code) {
                                                 
                                                  
        if (code == 134 || code == 139 || code == 136 || code == 6 || code == 11) return true;
                                                                                 
                                                                                          
        if (code < 0 && code != TIMEOUT_EXIT_CODE && code != EXE_FAIL_EXIT_CODE && !isInterruptedExitCode(code)) return true;
        return false;
    }

          
    @Override
    public String toString() {
        ExecutionStatus status = this.getStatus();
        List<String> details = new ArrayList<>();

        issues.forEach((type, map) -> {
            if (!map.isEmpty()) {
                details.add(type.name() + ": " + map);
            }
        });

        if (!details.isEmpty()) {
            return "[" + status + "] " + String.join(", ", details);
        }

        if (status == ExecutionStatus.SUCCESS || status == ExecutionStatus.TIMEOUT) {
            return "[" + status + "] " + getSummaryOutput();
        }
        return "[" + status + "] [ExitCode: " + exitCode + "]";
    }

    private String getSummaryOutput() {
        String content = (stdout == null || stdout.isEmpty()) ? stderr : stdout;
        if (content == null || content.isEmpty()) return "(No Output)";
        List<String> lines = content.lines().limit(6).collect(Collectors.toList());
        if (lines.size() > 5) {
            return String.join("\n", lines.subList(0, 5)) + "\n... (truncated)";
        }
        return content;
    }
}
