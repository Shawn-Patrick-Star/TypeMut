package org.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProjectWalkerTest {

    private final ProjectWalker walker = new ProjectWalker();

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnNullWhenCaseHasNoJavaFiles() {
        assertNull(walker.findSpecificJavaFile(tempDir.toFile()));
    }

    @Test
    void shouldPrioritizeReduceLikeFileNames() throws IOException {
        Files.createFile(tempDir.resolve("Alpha.java"));
        Path preferred = Files.createFile(tempDir.resolve("ReductionCase.java"));
        Files.createFile(tempDir.resolve("Beta.java"));

        assertEquals(preferred.toFile(), walker.findSpecificJavaFile(tempDir.toFile()));
    }
}
