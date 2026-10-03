package org.seed.loader;

import org.seed.model.Seed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class JavaSeedScanner {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("\\bpackage\\s+([A-Za-z_$][\\w$.]*)\\s*;");
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "\\b(?:public\\s+)?(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern MAIN_PATTERN = Pattern.compile(
            "\\bpublic\\s+static\\s+void\\s+main\\s*\\(\\s*(?:java\\.lang\\.)?String\\s*(?:\\[\\]|\\.\\.\\.)");

    private JavaSeedScanner() {
    }

    static List<Seed> findMainSeedsInDirectory(Path dir) throws IOException {
        List<Seed> seeds = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(JavaSeedScanner::isJavaFile).toList()) {
                Optional<JavaSourceInfo> sourceInfo = inspect(file);
                if (sourceInfo.isPresent() && sourceInfo.get().hasMainMethod()) {
                    JavaSourceInfo info = sourceInfo.get();
                    seeds.add(new Seed(
                            dir,
                            info.fileName(),
                            info.qualifiedClassName(),
                            seedId(dir, info),
                            0,
                            findReferencedSiblingJavaFiles(dir, info.fileName())));
                }
            }
        }
        return excludeOtherMainFilesFromCompanions(seeds);
    }

    static List<Seed> excludeOtherMainFilesFromCompanions(List<Seed> seeds) {
        Map<Path, Set<String>> mainFilesByRoot = new HashMap<>();
        for (Seed seed : seeds) {
            Path root = seed.getRootPath().toAbsolutePath().normalize();
            mainFilesByRoot.computeIfAbsent(root, ignored -> new LinkedHashSet<>())
                    .add(seed.getMainFileName());
        }

        List<Seed> normalized = new ArrayList<>(seeds.size());
        for (Seed seed : seeds) {
            Path root = seed.getRootPath().toAbsolutePath().normalize();
            Set<String> siblingMainFiles = mainFilesByRoot.getOrDefault(root, Set.of());
            List<String> companions = seed.getCompanionFileNames().stream()
                    .filter(fileName -> !siblingMainFiles.contains(fileName))
                    .toList();
            normalized.add(new Seed(
                    seed.getRootPath(),
                    seed.getMainFileName(),
                    seed.getMainClassName(),
                    seed.getId(),
                    seed.getGeneration(),
                    companions));
        }
        return normalized;
    }

    static List<String> findSiblingJavaFiles(Path dir, String excludeFileName) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(JavaSeedScanner::isJavaFile)
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> !fileName.equals(excludeFileName))
                    .toList();
        }
    }

    static List<String> findReferencedSiblingJavaFiles(Path dir, String mainFileName) throws IOException {
        Map<String, JavaSourceInfo> classIndex = new HashMap<>();
        Map<String, Path> fileIndex = new HashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(JavaSeedScanner::isJavaFile).toList()) {
                Optional<JavaSourceInfo> info = inspect(file);
                if (info.isEmpty()) {
                    continue;
                }
                fileIndex.put(info.get().fileName(), file);
                for (String className : info.get().declaredClassNames()) {
                    classIndex.putIfAbsent(className, info.get());
                }
            }
        }

        Set<String> companions = new LinkedHashSet<>();
        Set<String> visitedFiles = new LinkedHashSet<>();
        collectReferencedSiblings(mainFileName, fileIndex, classIndex, companions, visitedFiles);
        return List.copyOf(companions);
    }

    private static void collectReferencedSiblings(
            String sourceFileName,
            Map<String, Path> fileIndex,
            Map<String, JavaSourceInfo> classIndex,
            Set<String> companions,
            Set<String> visitedFiles) throws IOException {
        if (!visitedFiles.add(sourceFileName)) {
            return;
        }
        Path sourceFile = fileIndex.get(sourceFileName);
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            return;
        }
        String content = maskNonCode(Files.readString(sourceFile));
        for (Map.Entry<String, JavaSourceInfo> entry : classIndex.entrySet()) {
            JavaSourceInfo candidate = entry.getValue();
            String candidateFileName = candidate.fileName();
            if (candidateFileName.equals(sourceFileName) || companions.contains(candidateFileName)) {
                continue;
            }
            if (Pattern.compile("\\b" + Pattern.quote(entry.getKey()) + "\\b").matcher(content).find()) {
                companions.add(candidateFileName);
                collectReferencedSiblings(candidateFileName, fileIndex, classIndex, companions, visitedFiles);
            }
        }
    }

    static Optional<JavaSourceInfo> inspect(Path file) {
        try {
            String content = maskNonCode(Files.readString(file));
            String fileName = file.getFileName().toString();
            List<String> declaredClassNames = findClassNames(content);
            String simpleClassName = selectPrimaryClassName(declaredClassNames, fileName);
            if (simpleClassName == null) {
                return Optional.empty();
            }
            String packageName = findPackageName(content);
            boolean hasMain = MAIN_PATTERN.matcher(content).find();
            return Optional.of(new JavaSourceInfo(file, packageName, simpleClassName, declaredClassNames, hasMain));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static boolean isJavaFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
    }

    static String qualify(String packageName, String className) {
        if (packageName == null || packageName.isBlank()) {
            return className;
        }
        return packageName + "." + className;
    }

    private static String findPackageName(String content) {
        Matcher matcher = PACKAGE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<String> findClassNames(String content) {
        List<String> classNames = new ArrayList<>();
        Matcher matcher = CLASS_PATTERN.matcher(content);
        while (matcher.find()) {
            classNames.add(matcher.group(1));
        }
        return classNames;
    }

    private static String selectPrimaryClassName(List<String> classNames, String fileName) {
        for (String candidate : classNames) {
            if (fileName.equals(candidate + ".java")) {
                return candidate;
            }
        }
        return classNames.isEmpty() ? null : classNames.get(0);
    }

    private static String seedId(Path dir, JavaSourceInfo info) {
        String dirName = dir.getFileName().toString();
        String className = info.qualifiedClassName();
        String id = dirName.equals(className) ? className : dirName + "." + className;
        return id.replaceAll("[^A-Za-z0-9_$.-]", "_");
    }

    static String maskNonCode(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        boolean inChar = false;
        boolean inTextBlock = false;
        boolean escaped = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            char third = i + 2 < source.length() ? source.charAt(i + 2) : '\0';

            if (inLineComment) {
                if (isLineBreak(c)) {
                    inLineComment = false;
                    out.append(c);
                } else {
                    out.append(' ');
                }
                continue;
            }

            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    out.append("  ");
                    i++;
                    inBlockComment = false;
                } else {
                    out.append(isLineBreak(c) ? c : ' ');
                }
                continue;
            }

            if (inTextBlock) {
                if (c == '"' && next == '"' && third == '"') {
                    out.append("   ");
                    i += 2;
                    inTextBlock = false;
                } else {
                    out.append(isLineBreak(c) ? c : ' ');
                }
                continue;
            }

            if (inString) {
                out.append(isLineBreak(c) ? c : ' ');
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (inChar) {
                out.append(isLineBreak(c) ? c : ' ');
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                out.append("  ");
                i++;
                inLineComment = true;
            } else if (c == '/' && next == '*') {
                out.append("  ");
                i++;
                inBlockComment = true;
            } else if (c == '"' && next == '"' && third == '"') {
                out.append("   ");
                i += 2;
                inTextBlock = true;
            } else if (c == '"') {
                out.append(' ');
                inString = true;
                escaped = false;
            } else if (c == '\'') {
                out.append(' ');
                inChar = true;
                escaped = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r';
    }

    record JavaSourceInfo(Path file, String packageName, String simpleClassName, List<String> declaredClassNames,
            boolean hasMainMethod) {

        String fileName() {
            return file.getFileName().toString();
        }

        Path sourceDir() {
            return file.getParent();
        }

        String qualifiedClassName() {
            return JavaSeedScanner.qualify(packageName, simpleClassName);
        }
    }
}

