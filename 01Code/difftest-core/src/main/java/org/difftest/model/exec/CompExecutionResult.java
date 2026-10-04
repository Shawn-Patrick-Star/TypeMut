package org.difftest.model.exec;

import lombok.Getter;

import java.util.Map;
import java.util.TreeMap;

@Getter
public class CompExecutionResult extends ExecutionResult {
    private Map<String, Long> errorFingerprint = new TreeMap<>();

    public CompExecutionResult(String javacId, int exitCode, String stdout, String stderr, long duration) {
        super(javacId, exitCode, stdout, stderr, duration);
    }

    public void setErrorFingerprint(Map<String, Long> errorFingerprint) {
        this.errorFingerprint = errorFingerprint == null ? new TreeMap<>() : new TreeMap<>(errorFingerprint);
    }

    public boolean isSuccess() {
        return getStatus() == ExecutionStatus.SUCCESS;
    }

    @Override
    public String toString() {
        String output = getFullOutput();
        if (output == null || output.isBlank()) {
            return "[" + getStatus() + "] (No Output)";
        }
        return "[" + getStatus() + "] " + output.trim();
    }
}
