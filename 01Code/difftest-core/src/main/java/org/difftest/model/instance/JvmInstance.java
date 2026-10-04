package org.difftest.model.instance;

import lombok.Getter;
import lombok.Setter;
import org.difftest.model.artifact.ExecutionArtifact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Getter
public class JvmInstance {
    private final String id;
    private final String javaPath;
    private final List<String> defaultArgs;
    @Setter
    private String versionInfo;

    private JvmInstance(Builder builder) {
        this.id = builder.id;
        this.javaPath = builder.javaPath;
        this.defaultArgs = Collections.unmodifiableList(builder.defaultArgs);
    }

    public String getCommandPath() {
        return javaPath;
    }

    public List<String> buildCommand(ExecutionArtifact artifact) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath);
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.addAll(defaultArgs);
        cmd.add("-cp");
        cmd.add(artifact.getArtifactPath().toAbsolutePath().toString());
        cmd.add(artifact.getMainClassName());
        return cmd;
    }

    public static class Builder {
        private final String id;
        private String javaPath = "java";
        private final List<String> defaultArgs = new ArrayList<>();

        public Builder(String id) {
            this.id = id;
        }

        public Builder setJavaPath(String path) {
            this.javaPath = path;
            return this;
        }

        public Builder addArgs(String... args) {
            this.defaultArgs.addAll(Arrays.asList(args));
            return this;
        }

        public JvmInstance build() {
            return new JvmInstance(this);
        }
    }

    public boolean isHotspotJvm() {
        String id = this.getId();
        String path = this.getJavaPath();
        return (id != null && id.toLowerCase().contains("hotspot"))
            || (path != null && path.toLowerCase().contains("hotspot"));
    }

    public void validate() {
        
    }
}
