package org.difftest.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class MainClassResolver {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");

    private MainClassResolver() {
    }

    public static String resolve(Path sourceFile, String mainClassName) {
        String className = mainClassName == null || mainClassName.isBlank()
                ? inferSimpleClassName(sourceFile)
                : mainClassName.trim();

        if (className.isBlank() || className.contains(".")) {
            return className;
        }

        return packageName(sourceFile)
                .map(pkg -> pkg + "." + className)
                .orElse(className);
    }

    public static String inferSimpleClassName(Path sourceFile) {
        if (sourceFile == null || sourceFile.getFileName() == null) {
            return "";
        }
        String fileName = sourceFile.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    public static Optional<String> packageName(Path sourceFile) {
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            return Optional.empty();
        }
        try {
            String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
            Matcher matcher = PACKAGE_PATTERN.matcher(stripComments(source));
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
        } catch (IOException e) {
            log.debug("[MainClassResolver] Failed to read package declaration from {}: {}",
                    sourceFile, e.getMessage());
        }
        return Optional.empty();
    }

    private static String stripComments(String source) {
        String withoutBlockComments = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return withoutBlockComments.replaceAll("(?m)//.*$", "");
    }
}
