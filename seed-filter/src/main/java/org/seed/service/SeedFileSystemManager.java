package org.seed.service;

import org.seed.model.Seed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

public class SeedFileSystemManager {

    public void prepareOutput(Path outputRoot) {
        try {
            Files.createDirectories(outputRoot);
            deleteDirectory(outputRoot.resolve("accepted"));
            deleteDirectory(outputRoot.resolve("raw-diff"));
            deleteDirectory(outputRoot.resolve("rejected"));
            deleteDirectory(outputRoot.resolve("reports"));
            Files.createDirectories(outputRoot.resolve("accepted"));
            Files.createDirectories(outputRoot.resolve("raw-diff"));
            Files.createDirectories(outputRoot.resolve("reports"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare seed filter output under " + outputRoot, e);
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public Seed copyAcceptedSeed(Seed seed, Path acceptedRoot) {
        Path targetRoot = copySeed(seed, acceptedRoot.resolve(sanitize(seed.getOutputDirectoryName())));
        return new Seed(targetRoot, seed.getMainFileName(), seed.getMainClassName(), seed.getId(), 0,
                seed.getCompanionFileNames());
    }

    public Path copySeed(Seed seed, Path targetRoot) {
        Path sourceRoot = seed.getRootPath().toAbsolutePath().normalize();
        Path normalizedTargetRoot = targetRoot.toAbsolutePath().normalize();
        if (normalizedTargetRoot.startsWith(sourceRoot)) {
            throw new IllegalArgumentException("Seed output directory must not be inside its source directory: "
                    + normalizedTargetRoot);
        }

        try {
            try (var paths = Files.walk(sourceRoot)) {
                for (Path source : paths.toList()) {
                    Path target = normalizedTargetRoot.resolve(sourceRoot.relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else if (shouldCopy(seed, sourceRoot, source)) {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to copy seed " + seed.getId() + " to "
                    + normalizedTargetRoot, e);
        }
        return normalizedTargetRoot;
    }

    private boolean shouldCopy(Seed seed, Path sourceRoot, Path source) {
        Path relative = sourceRoot.relativize(source);
        boolean rootLevelJavaFile = relative.getNameCount() == 1
                && source.getFileName().toString().endsWith(".java");
        if (!rootLevelJavaFile) {
            return true;
        }

        String fileName = source.getFileName().toString();
        return fileName.equals(seed.getMainFileName())
                || seed.getCompanionFileNames().contains(fileName);
    }

    public String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_$.-]", "_");
    }
}
