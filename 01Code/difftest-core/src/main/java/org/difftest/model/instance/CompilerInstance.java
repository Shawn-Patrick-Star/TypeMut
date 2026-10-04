package org.difftest.model.instance;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.nio.file.Path;

@Getter
public class CompilerInstance {
    private final String id;
    private final String javacPath;
    private final List<String> defaultArgs;
    @Setter
    private String versionInfo;

    private CompilerInstance(Builder builder) {
        this.id = builder.id;
        this.javacPath = builder.javacPath;
        this.defaultArgs = Collections.unmodifiableList(builder.defaultArgs);
    }

    public String getCommandPath() {
        return javacPath;
    }

       
                                                              
      
                                                        
                                                              
                                                      
       
    public List<String> buildCommand(List<Path> javaFiles, Path outputDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javacPath);
        cmd.addAll(defaultArgs);
        cmd.add("-encoding");
        cmd.add("UTF-8");
        cmd.add("-d");
        cmd.add(outputDir.toAbsolutePath().toString());
        cmd.add("-nowarn");
        for (Path javaFile : javaFiles) {
            cmd.add(javaFile.toAbsolutePath().toString());
        }
        return cmd;
    }

    public static class Builder {
        private final String id;
        private String javacPath = "javac";
        private final List<String> defaultArgs = new ArrayList<>();

        public Builder(String id) {
            this.id = id;
        }

        public Builder setJavacPath(String javacPath) {
            this.javacPath = javacPath;
            return this;
        }

        public Builder addArgs(String... args) {
            this.defaultArgs.addAll(Arrays.asList(args));
            return this;
        }


        public CompilerInstance build() {
            return new CompilerInstance(this);
        }
    }

}
