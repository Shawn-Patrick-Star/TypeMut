package org.io;

import org.ASTfeature.SourceCodeFeature;
import org.model.FeatureOccurrence;
import spoon.reflect.code.CtStatement;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

public class SourceCodeExporter {

    private final Set<SourceCodeFeature> exportTargets;
    private final int maxAllowedLines = 10; // Maximum allowed source lines.

    /**
     * @param exportTargets the feature whitelist to export; only features in this set will be written to files.
     */
    public SourceCodeExporter(Set<SourceCodeFeature> exportTargets) {
        this.exportTargets = exportTargets;
    }

    /**
     * Execute the export logic.
     * @param sourceFile the current source file, used as context for the filename.
     * @param occurrences all feature discovery records.
     * @param outputDir output directory (usually the case directory).
     */
    public void export(File sourceFile, List<FeatureOccurrence> occurrences, String outputDir) {
        if (occurrences == null || occurrences.isEmpty()) return;

        // 1. Filter by whitelist.
        List<FeatureOccurrence> filtered = occurrences.stream()
                .filter(o -> exportTargets != null && exportTargets.contains(o.getFeature()))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) return;

        // 2. Prepare the output directory.
        File root = new File(outputDir);
        if (!root.exists()) {
            root.mkdirs();
        }

        // 3. Group by feature.
        /* Structure: Map<feature type, List<occurrence records>>
          eg:{
               "array": [record1, record2],
               "inheritance": [record3]
             }
         */
        String baseName = sourceFile.getName().replace(".java", "");
        Map<SourceCodeFeature, List<FeatureOccurrence>> groupedByFeature = filtered.stream()
                .collect(Collectors.groupingBy(FeatureOccurrence::getFeature));

        // 4. Iterate and write files.
        groupedByFeature.forEach((feature, list) -> {
            // File name: Test-array.txt
            String outputName = baseName + "-" + feature.name() + ".txt";
            File outputFile = new File(root, outputName);
            writeOccurrencesToFile(outputFile, list);
        });
    }

    private void writeOccurrencesToFile(File file, List<FeatureOccurrence> list) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            Set<Integer> writtenLines = new HashSet<>();

            // Sort by line number.
            list.sort(Comparator.comparingInt(this::getValidLineNumber));

            for (FeatureOccurrence o : list) {
                int line = getValidLineNumber(o);

                // Deduplicate and filter invalid lines.
                if (line == -1 || writtenLines.contains(line)) continue;
                writtenLines.add(line);

                String rawContent = extractContent(o.getElement());
                String finalContent = truncateContent(rawContent);
                writer.print("Line" + line + ": ");
                writer.println(finalContent);
            }
            System.out.println("Exported: " + file.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Truncation logic for line count.
     */
    private String truncateContent(String content) {
        if (content == null || content.isEmpty()) return "";

        String[] lines = content.split("\\r?\\n"); // Compatible with Windows/Linux line endings.

        if (lines.length <= maxAllowedLines) {
            return content;
        }

        // If the content exceeds the limit, only keep the first N lines.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxAllowedLines; i++) {
            sb.append(lines[i]).append(System.lineSeparator());
        }
        sb.append("... [Content truncated, exceeded ").append(maxAllowedLines).append(" lines] ...");

        return sb.toString();
    }

    /**
     * Safely extract source code: explicitly block class definitions and method definitions.
     * If the element is a class (CtType), toString() would print the entire file content by default.
     * We need to intercept that and print only the class name and inheritance information.
     */
    private String extractContent(CtElement element) {
        if (element == null) return "";

        // === 1. Block class definitions (CtType) ===
        // Prevent printing the entire file.
        if (element instanceof CtType) {
            CtType<?> type = (CtType<?>) element;
            StringBuilder sb = new StringBuilder();

            if (type.isPublic()) sb.append("public ");
            sb.append(type.isInterface() ? "interface " : "class ");
            sb.append(type.getSimpleName());

            if (type.getSuperclass() != null) {
                sb.append(" extends ").append(type.getSuperclass().getSimpleName());
            }

            if (!type.getSuperInterfaces().isEmpty()) {
                sb.append(" implements ");
                sb.append(type.getSuperInterfaces().stream()
                        .map(CtTypeReference::getSimpleName)
                        .collect(java.util.stream.Collectors.joining(", ")));
            }

            sb.append(" { ... }");
            return sb.toString();
        }

        // === 2. Block method definitions (CtMethod) ===
        // Prevent printing the entire method body if a detector incorrectly targets a Method.
        if (element instanceof CtMethod) {
            return "[Method Signature] " + ((CtMethod<?>) element).getSignature();
        }

        // === 3. Statement extraction logic ===
        // If the current element is not a statement (for example, just an expression), try to find the parent statement.
        // Note: if your detector (for example, ArrayDetector) has already identified the statement, then this element is already a Statement.
        if (!(element instanceof CtStatement)) {
            CtStatement parentStmt = element.getParent(CtStatement.class);
            if (parentStmt != null) {
                return parentStmt.toString();
            }
        }

        // Default: print the element itself.
        try {
            return element.toString();
        } catch (Exception e) {
            return "[Error extracting code]";
        }
    }

    /**
     * Core fix: recursively search upward for a valid line number.
     * Many times a TypeReference has no position, but its parent Statement does.
     */
    private int getValidLineNumber(FeatureOccurrence o) {
        return findValidLine(o.getElement());
    }

    private int findValidLine(CtElement element) {
        if (element == null) return -1;

        SourcePosition pos = element.getPosition();
        // Only trust getLine() when isValidPosition() is true.
        if (pos != null && pos.isValidPosition()) {
            return pos.getLine();
        }

        // If the current node has no position information, try its parent.
        // Example: for "int[] a;", the "int[]" portion may have no position, but the variable declaration does.
        return findValidLine(element.getParent());
    }

}
