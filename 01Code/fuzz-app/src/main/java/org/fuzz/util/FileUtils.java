package org.fuzz.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Comparator;
import java.io.UncheckedIOException;
import java.util.stream.Stream;

@Slf4j
public class FileUtils {

    public static void createDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            log.error("Failed to create directory: {}", path, e);
        }
    }

       
             
       
    public static void copySiblingFiles(Path sourceDir, Path targetDir, String excludeFileName) {
        try (Stream<Path> files = Files.list(sourceDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals(excludeFileName))
                    .forEach(p -> {
                        try {
                            Files.copy(p, targetDir.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            log.error("Copy failed: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.error("List files failed: {}", sourceDir, e);
        }
    }

    public static void copyFiles(Collection<Path> sourceFiles, Path targetDir, String excludeFileName) {
        for (Path sourceFile : sourceFiles) {
            if (!Files.isRegularFile(sourceFile)) {
                continue;
            }
            if (sourceFile.getFileName().toString().equals(excludeFileName)) {
                continue;
            }
            try {
                Files.copy(sourceFile, targetDir.resolve(sourceFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.error("Copy failed: {}", sourceFile, e);
            }
        }
    }

    public static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(sourcePath -> {
                Path targetPath = target.resolve(source.relativize(sourcePath));
                try {
                    if (Files.isDirectory(sourcePath)) {
                        if (!Files.exists(targetPath)) {
                            Files.createDirectory(targetPath);
                        }
                    } else {
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy " + sourcePath, e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw e;
        }
    }

    public static void cleanDirectory(Path path) {
        if (!Files.exists(path)) {
            return;
        }

        log.info("[Prepare] Cleaning output directory");
        try (Stream<Path> files = Files.list(path)) {
            files.filter(p -> !p.getFileName().toString().equals("bugs"))
                    .forEach(FileUtils::deleteRecursivelyQuietly);
        } catch (IOException e) {
            log.warn("Failed to list output directory for cleanup: {}. Continuing with existing files. Reason: {}",
                    path, e.getMessage());
        }
    }

    public static void deleteRecursivelyQuietly(Path root) {
        try {
            deleteRecursively(root);
        } catch (UncheckedIOException e) {
            log.warn("Failed to delete {}. It may be locked by a previous JVM/process; continuing. Reason: {}",
                    root, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Failed to delete {}. Continuing. Reason: {}", root, e.getMessage());
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(FileUtils::deletePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + root, e);
        }
    }

    private static void deletePath(Path path) {
        IOException last = null;
        for (int i = 0; i < 5; i++) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (IOException e) {
                last = e;
                sleepBeforeRetry();
            }
        }
        if (last != null) {
            throw new UncheckedIOException("Failed to delete " + path, last);
        }
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(200L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
