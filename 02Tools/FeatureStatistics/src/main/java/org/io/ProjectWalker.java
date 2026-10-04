package org.io;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Responsible for filesystem traversal and filtering strategies.
public class ProjectWalker {

    /**
     * Match a Java main-method declaration conservatively. When a case has no main
     * method, BatchProcessor falls back to analyzing all Java sources in that case.
     * Multiple main files remain ambiguous and are rejected.
     */
    private static final Pattern MAIN_METHOD_PATTERN = Pattern.compile(
            "(?m)^\\s*" +
            "(?:@[A-Za-z_$][A-Za-z0-9_$.]*(?:\\([^\\n]*\\))?\\s*)*" +
            "(?:(?:public|protected|private|static|final|synchronized|strictfp)\\s+)*" +
            "void\\s+main\\s*\\("
    );

    private static final Pattern STATIC_MODIFIER_PATTERN = Pattern.compile("\\bstatic\\b");

    public File[] findValidDirs(String rootPath) {
        File dir = new File(rootPath);
        if (!dir.exists() || !dir.isDirectory()) {
            System.err.println("Invalid directory: " + rootPath);
            return new File[0];
        }

        File[] caseDirs = dir.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        if (caseDirs == null) {
            return new File[0];
        }
        Arrays.sort(caseDirs, Comparator.comparing(File::getName));
        return caseDirs;
    }

    /**
     * Return Java source files directly contained in one bug-case directory.
     * FuzzerUtils.java is excluded because it is shared fuzzing infrastructure.
     *
     * The empirical statistics path prefers the unique main source when present;
     * otherwise these files are analyzed together as one case.
     */
    public File[] findJavaFiles(File caseDir) {
        File[] javaFiles = caseDir.listFiles(f ->
                f.isFile() &&
                f.getName().endsWith(".java") &&
                !isAuxiliaryFile(f));

        if (javaFiles == null) {
            return new File[0];
        }

        Arrays.sort(javaFiles, Comparator.comparing(File::getName));
        return javaFiles;
    }

    /**
     * Locate the unique Java source file containing the case's main method.
     *
     * @return the unique main Java file, or null when the case has no main method
     * @throws IllegalStateException when more than one main file is found
     */
    public File findMainJavaFile(File caseDir) {
        File[] javaFiles = findJavaFiles(caseDir);
        File matched = null;
        int matches = 0;

        for (File javaFile : javaFiles) {
            if (!containsMainMethod(javaFile)) {
                continue;
            }
            matched = javaFile;
            matches++;
        }

        if (matches == 1) {
            return matched;
        }
        if (matches == 0) {
            return null;
        }
        throw new IllegalStateException(
                "Expected exactly one main Java file but found " + matches + " in " + caseDir.getAbsolutePath()
        );
    }

    /** Backward-compatible selector using the same unique-main-file semantics. */
    public File findSpecificJavaFile(File caseDir) {
        return findMainJavaFile(caseDir);
    }

    private boolean containsMainMethod(File file) {
        final String source;
        try {
            source = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Java source: " + file.getAbsolutePath(), e);
        }

        // Remove comments and literal contents before matching so a commented-out
        // `public static void main(...)` in a helper file cannot create a false hit.
        String searchable = maskCommentsAndLiterals(source);
        Matcher matcher = MAIN_METHOD_PATTERN.matcher(searchable);
        while (matcher.find()) {
            if (STATIC_MODIFIER_PATTERN.matcher(matcher.group()).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replace comment/string/char/text-block contents with spaces while preserving
     * newlines and therefore declaration line structure.
     */
    private String maskCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source);
        int i = 0;
        int state = 0; // 0=code, 1=line comment, 2=block comment, 3=string, 4=char, 5=text block

        while (i < source.length()) {
            char ch = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (state == 0) {
                if (i + 2 < source.length() && source.startsWith("\"\"\"", i)) {
                    out.setCharAt(i, ' ');
                    out.setCharAt(i + 1, ' ');
                    out.setCharAt(i + 2, ' ');
                    i += 3;
                    state = 5;
                    continue;
                }
                if (ch == '/' && next == '/') {
                    out.setCharAt(i, ' ');
                    out.setCharAt(i + 1, ' ');
                    i += 2;
                    state = 1;
                    continue;
                }
                if (ch == '/' && next == '*') {
                    out.setCharAt(i, ' ');
                    out.setCharAt(i + 1, ' ');
                    i += 2;
                    state = 2;
                    continue;
                }
                if (ch == '"') {
                    out.setCharAt(i, ' ');
                    i++;
                    state = 3;
                    continue;
                }
                if (ch == '\'') {
                    out.setCharAt(i, ' ');
                    i++;
                    state = 4;
                    continue;
                }
                i++;
                continue;
            }

            if (state == 1) {
                if (ch == '\n' || ch == '\r') {
                    state = 0;
                } else {
                    out.setCharAt(i, ' ');
                }
                i++;
                continue;
            }

            if (state == 2) {
                if (ch == '*' && next == '/') {
                    out.setCharAt(i, ' ');
                    out.setCharAt(i + 1, ' ');
                    i += 2;
                    state = 0;
                } else {
                    if (ch != '\n' && ch != '\r') {
                        out.setCharAt(i, ' ');
                    }
                    i++;
                }
                continue;
            }

            if (state == 3 || state == 4) {
                char terminator = state == 3 ? '"' : '\'';
                if (ch == '\\' && i + 1 < source.length()) {
                    out.setCharAt(i, ' ');
                    if (source.charAt(i + 1) != '\n' && source.charAt(i + 1) != '\r') {
                        out.setCharAt(i + 1, ' ');
                    }
                    i += 2;
                    continue;
                }
                if (ch == terminator) {
                    out.setCharAt(i, ' ');
                    i++;
                    state = 0;
                } else {
                    if (ch != '\n' && ch != '\r') {
                        out.setCharAt(i, ' ');
                    }
                    i++;
                }
                continue;
            }

            // text block
            if (i + 2 < source.length() && source.startsWith("\"\"\"", i)) {
                out.setCharAt(i, ' ');
                out.setCharAt(i + 1, ' ');
                out.setCharAt(i + 2, ' ');
                i += 3;
                state = 0;
            } else {
                if (ch != '\n' && ch != '\r') {
                    out.setCharAt(i, ' ');
                }
                i++;
            }
        }

        return out.toString();
    }

    private boolean isAuxiliaryFile(File file) {
        return "FuzzerUtils.java".equals(file.getName());
    }
}
