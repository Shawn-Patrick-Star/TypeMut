package org.seed.model;

import lombok.Getter;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

   
               
   
@Getter
public class Seed {

    private static final Pattern ISSUE_ID_WITH_CLASS_SUFFIX =
            Pattern.compile("(?i)^((?:JDK|OpenJ9)-\\d+(?:_[a-z]+)?)\\..+");

    private final Path rootPath;
    private final String mainFileName;
    private final String mainClassName;
    private final String id;
    private final int generation;
    private final List<String> companionFileNames;
    private final AtomicInteger mutationCount = new AtomicInteger(0);

    public Seed(Path rootPath, String mainFileName, String mainClassName) {
        this(rootPath, mainFileName, mainClassName, rootPath.getFileName().toString(), 0, List.of());
    }

    public Seed(Path rootPath, String mainFileName, String mainClassName, String id) {
        this(rootPath, mainFileName, mainClassName, id, 0, List.of());
    }

    public Seed(Path rootPath, String mainFileName, String mainClassName, String id, int generation) {
        this(rootPath, mainFileName, mainClassName, id, generation, List.of());
    }

    public Seed(Path rootPath, String mainFileName, String mainClassName, String id, int generation,
            List<String> companionFileNames) {
        this.rootPath = rootPath;
        this.mainFileName = mainFileName;
        this.mainClassName = mainClassName;
        this.id = id;
        this.generation = generation;
        this.companionFileNames = List.copyOf(companionFileNames);
    }

    public int incrementMutationCount() {
        return mutationCount.incrementAndGet();
    }

    public Path getMainFilePath() {
        return rootPath.resolve(mainFileName);
    }

    public List<Path> getCompanionFilePaths() {
        return companionFileNames.stream()
                .map(rootPath::resolve)
                .toList();
    }

    public String getOutputDirectoryName() {
        var matcher = ISSUE_ID_WITH_CLASS_SUFFIX.matcher(id);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return id;
    }

    @Override
    public String toString() {
        return "Seed{id='" + id + "', main='" + mainClassName + "'}";
    }
}

