package org.difftest.model;

import lombok.Getter;
import lombok.Setter;
import org.difftest.model.artifact.ExecutionArtifact;
import org.difftest.model.exec.ExecutionResult;
import org.difftest.model.exec.RunTimeExecutionResult;
import org.difftest.model.exec.CompExecutionResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Getter
public class DTResult {

    @Setter
    private DTType type;
    private final Map<String, String> outputs;
    @Setter
    private String message;
    @Setter
    private DTFailureReason failureReason = DTFailureReason.NONE;
    
             
    private final List<ExecutionArtifact> artifacts;
    private final List<? extends ExecutionResult> executionResults;

    public DTResult(DTType type, Map<String, String> outputs, String message, List<ExecutionArtifact> artifacts, List<? extends ExecutionResult> executionResults) {
        this.type = type;
        this.outputs = outputs;
        this.message = message;
        this.artifacts = artifacts != null ? artifacts : List.of();
        this.executionResults = executionResults != null ? executionResults : List.of();
    }

    public DTResult withFailureReason(DTFailureReason failureReason) {
        this.failureReason = failureReason == null ? DTFailureReason.NONE : failureReason;
        return this;
    }

       
                   
       
    public static DTResult ofArtifacts(DTType type, Map<String, String> outputs, String message, List<ExecutionArtifact> artifacts) {
        return new DTResult(type, outputs, message, artifacts, null);
    }

       
                   
       
    public static DTResult ofExecution(DTType type, Map<String, String> outputs, String message, List<? extends ExecutionResult> results) {
        return new DTResult(type, outputs, message, null, results);
    }

    public static DTResult match(DTType type, String message) {
        return new DTResult(type, Map.of(), message, null, null);
    }

    public static DTResult bug(DTType type, String message, List<? extends ExecutionResult> results) {
        return new DTResult(type, Map.of(), message, null, results);
    }

    public boolean hasDiff() {
        return type == DTType.DIFFERENCE
                || type == DTType.DIFFERENCE_STDOUT
                || type == DTType.CRASH;
    }

    @Override
    public String toString() {
        return toString(false);
    }

      
                                                   
      
    public String toString(boolean verbose) {
        StringBuilder sb = new StringBuilder();
        String msg = (message != null && !message.isBlank()) ? message : type.toString();
        String typeLabel = "[" + type + "]";

        switch (type) {
            case MATCH_SUCCESS                        -> sb.append("[PASS]  ").append(typeLabel).append(" ").append(msg).append("\n");
            case MATCH_FAILURE, MATCH_TIMEOUT         -> sb.append("[SKIP]  ").append(typeLabel).append(" ").append(msg).append("\n");
            case CRASH, DIFFERENCE, DIFFERENCE_STDOUT -> sb.append("[BUG]   ").append(typeLabel).append(" ").append(msg).append("\n");
            case ENVIRONMENT_ERROR                    -> sb.append("[ERROR] ").append(typeLabel).append(" ").append(msg).append("\n");
        }

        if (executionResults != null && !executionResults.isEmpty()) {
            if (type == DTType.DIFFERENCE_STDOUT) {
                sb.append("\n--- Detailed Comparison (First Inconsistency) ---\n");
                formatStdoutDiff(sb);
                sb.append("---------------------------\n");
            } else if (type == DTType.DIFFERENCE || type == DTType.CRASH || (verbose && type == DTType.MATCH_FAILURE)) {
                sb.append("\n--- Issue Summary (Exceptions/Errors) ---\n");
                for (ExecutionResult res : executionResults) {
                    if (res instanceof RunTimeExecutionResult runRes) {            
                        sb.append(String.format("[ID: %-20s] Status: %s, Exit: %d%n", runRes.getId(), runRes.getStatus(), runRes.getExitCode()));
                        runRes.getIssues().forEach((issueType, map) -> {
                            if (!map.isEmpty()) {
                                map.forEach((detail, count) -> sb.append("  - ").append(issueType).append(": ").append(detail).append(" (").append(count).append(")\n"));
                            }
                        });
                        sb.append("\n");
                        if (verbose) {
                            appendTruncatedOutput(sb, runRes.getFullOutput(), 10);
                        }
                    } else if (res instanceof CompExecutionResult compileRes) {                 
                                        
                        sb.append(String.format("[ID: %-20s] Exit: %d%n", compileRes.getId(), compileRes.getExitCode()));
                        String summary = compileRes.getStderr().isBlank() ? compileRes.getStdout() : compileRes.getStderr();
                        appendTruncatedOutput(sb, summary, 10);
                        sb.append("\n");
                    }
                }
                sb.append("----------------\n");
            }
        }

        return sb.toString();
    }

       
                                     
       
    private void appendTruncatedOutput(StringBuilder sb, String content, int maxLines) {
        if (content == null || content.isBlank()) return;
        List<String> lines = content.lines().toList();
        if (lines.size() <= maxLines) {
            lines.forEach(l -> sb.append("  ").append(l).append("\n"));
        } else {
            int head = maxLines / 2;
            int tail = maxLines - head;
                   
            lines.stream().limit(head).forEach(l -> sb.append("  ").append(l).append("\n"));
                     
            sb.append("  ... (skipped ").append(lines.size() - maxLines).append(" lines) ...\n");
                   
            lines.stream().skip(lines.size() - tail).forEach(l -> sb.append("  ").append(l).append("\n"));
        }
        sb.append("~~~~~~~~ (Full output end) ~~~~~~~~\n\n");
    }

    private void formatStdoutDiff(StringBuilder sb) {
        List<String> ids = outputs.keySet().stream().sorted().toList();
        if (ids.size() < 2) {
            return;
        }

        Map<String, List<String>> linesMap = outputs.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> Arrays.asList(e.getValue().split("\\R"))));

        int diffIndex = -1;
        int maxLines = linesMap.values().stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < maxLines; i++) {
            String baseline = null;
            boolean rowDiff = false;
            for (String id : ids) {
                List<String> lines = linesMap.get(id);
                String line = (i < lines.size()) ? lines.get(i) : "<END_OF_OUTPUT>";
                if (baseline == null) {
                    baseline = line;
                } else if (!baseline.equals(line)) {
                    rowDiff = true;
                    break;
                }
            }
            if (rowDiff) {
                diffIndex = i;
                break;
            }
        }

        if (diffIndex == -1) {
            sb.append("(No visible line difference found, check hidden chars?)\n");
            return;
        }

        int contextSize = 2;
        int start = Math.max(0, diffIndex - contextSize);
        int end = Math.min(maxLines, diffIndex + contextSize + 1);

        for (String id : ids) {
            sb.append(String.format("[ID: %-20s]%n", id));
            List<String> lines = linesMap.get(id);

            if (start > 0) {
                sb.append("    ... (skipped ").append(start).append(" lines)\n");
            }

            for (int i = start; i < end; i++) {
                String prefix = (i == diffIndex) ? ">>> " : "    ";
                String line = (i < lines.size()) ? lines.get(i) : "<END>";
                sb.append(prefix).append(line).append("\n");
            }

            if (end < lines.size()) {
                sb.append("    ... (skipped ").append(lines.size() - end).append(" lines)\n");
            }
            sb.append("\n");
        }
    }
}
