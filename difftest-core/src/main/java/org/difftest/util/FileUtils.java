package org.difftest.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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

    public static List<Path> collectJavaFiles(Path sourceDir) {
        return collectFiles(sourceDir, ".java");
    }

       
                                      
       
    public static List<Path> collectFiles(Path dir, String extension) {
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(extension))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(Path::toAbsolutePath)
                    .collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to collect files in {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    public static void createJar(Path sourceDir, Path targetJar) {
        Path absoluteTarget = targetJar.toAbsolutePath();
        try {
            Files.deleteIfExists(targetJar);
        } catch (IOException ignored) {}
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(new java.io.FileOutputStream(targetJar.toFile()))) {
            try (Stream<Path> stream = Files.walk(sourceDir)) {
                stream.filter(Files::isRegularFile)
                       .filter(p -> !p.toAbsolutePath().equals(absoluteTarget))
                       .forEach(path -> {
                    try {
                        String entryName = sourceDir.relativize(path).toString().replace('\\', '/');
                        java.util.jar.JarEntry entry = new java.util.jar.JarEntry(entryName);
                        jos.putNextEntry(entry);
                        Files.copy(path, jos);
                        jos.closeEntry();
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to add file to jar: " + path, e);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Failed to create jar: {}", targetJar, e);
        }
    }
}
