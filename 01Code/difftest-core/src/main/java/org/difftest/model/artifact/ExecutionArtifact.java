package org.difftest.model.artifact;

import lombok.Getter;

import java.nio.file.Path;
import java.util.Objects;

   
                                                                             
   
@Getter
public class ExecutionArtifact {

    private final String id;
    private final String compilerId;
    private final Path rootDir;
    private final String mainClassName;
    private final Path artifactPath;

    public ExecutionArtifact(String id,
            String compilerId,
            Path rootDir,
            String mainClassName,
            Path artifactPath) {
        this.id             = Objects.requireNonNull(id, "id");
        this.compilerId     = Objects.requireNonNull(compilerId, "compilerId");
        this.rootDir        = Objects.requireNonNull(rootDir, "rootDir");
        this.mainClassName  = Objects.requireNonNull(mainClassName, "mainClassName");
        this.artifactPath   = Objects.requireNonNull(artifactPath, "artifactPath");
    }

}
