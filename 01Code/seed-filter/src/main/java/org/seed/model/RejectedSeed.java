package org.seed.model;

import java.nio.file.Path;

public record RejectedSeed(
        String seedId,
        String mainFile,
        RejectionReason reason,
        String ruleName,
        String detail,
        Path copiedPath) {
}

