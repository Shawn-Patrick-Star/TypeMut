package org.seed.model;

public enum RejectionReason {
    STATIC_TIME("static-time"),
    STATIC_THREAD("static-thread"),
    STATIC_SYSTEM("static-system"),
    COMPILE_FAILED("compile-failed"),
    RAW_DIFF_FAILURE("raw-diff"),
    RAW_TIMEOUT("raw-timeout"),
    RAW_MATCH_FAILURE("raw-match-failure"),
    RAW_ENVIRONMENT_ERROR("raw-environment-error"),
    INVALID_PATH("invalid-path");

    private final String directoryName;

    RejectionReason(String directoryName) {
        this.directoryName = directoryName;
    }

    public String directoryName() {
        return directoryName;
    }
}

