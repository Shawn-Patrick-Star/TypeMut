package org.difftest.model.exec;

import lombok.Getter;
import lombok.Setter;
import org.difftest.model.DTFailureReason;

   
           
                    
   
@Getter
public class ExecutionResult {
                              
    public final static int TIMEOUT_EXIT_CODE = -124;
    public final static int EXE_FAIL_EXIT_CODE = -999;
    public final static int SIGINT_EXIT_CODE = 130;
    public final static int SIGTERM_EXIT_CODE = 143;
    public final static int SIGKILL_EXIT_CODE = 137;
    public final static int WINDOWS_CTRL_C_EXIT_CODE = -1073741510;

    protected final String id;
    protected final int exitCode;
    @Setter
    protected String stdout;
    @Setter
    protected String stderr;
    protected final long duration;

                                   
    @Getter
    protected final String rawStdout;
    @Getter
    protected final String rawStderr;
    @Setter
    private DTFailureReason failureReason = DTFailureReason.NONE;

    public ExecutionResult(String id, int exitCode, String stdout, String stderr, long duration) {
        this.id = id;
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.rawStdout = stdout;
        this.rawStderr = stderr;
        this.duration = duration;
    }

    public String getFullOutput() {
        return stdout + "\n" + stderr;
    }

    public ExecutionStatus getStatus() {
        if (exitCode == 0) {
            return ExecutionStatus.SUCCESS;
        }
        if (exitCode == TIMEOUT_EXIT_CODE) {
            return ExecutionStatus.TIMEOUT;
        }
        if (isInterruptedExitCode(exitCode)) {
            return ExecutionStatus.INTERRUPTED;
        }
        if (exitCode == EXE_FAIL_EXIT_CODE) {
            return ExecutionStatus.UNKNOWN;
        }
        if (isCrashExitCode(exitCode)) {
            return ExecutionStatus.CRASH;
        }
        return ExecutionStatus.FAILURE;
    }

    public boolean isInterrupted() {
        return getStatus() == ExecutionStatus.INTERRUPTED;
    }

    protected boolean isInterruptedExitCode(int code) {
        return code == SIGINT_EXIT_CODE
                || code == SIGTERM_EXIT_CODE
                || code == SIGKILL_EXIT_CODE
                || code == WINDOWS_CTRL_C_EXIT_CODE;
    }

    protected boolean isCrashExitCode(int code) {
        if (code == 134 || code == 139 || code == 136 || code == 6 || code == 11) {
            return true;
        }
        if (code < 0 && code != TIMEOUT_EXIT_CODE && code != EXE_FAIL_EXIT_CODE) {
            return true;
        }
        return code > 128 && code != 255;
    }
}
