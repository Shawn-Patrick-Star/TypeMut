package org.analysis;

import org.ASTfeature.SourceCodeFeature;
import org.io.ProjectWalker;
import org.io.ReportWriter;
import org.io.SourceCodeExporter;
import org.model.FeatureOccurrence;
import org.stats.FeatureStats;
import org.stats.StatsManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

public class BatchProcessor {

    private final ProjectWalker walker;
    private final FeatureExtractorEngine engine;
    private final StatsManager statsManager;
    private final ReportWriter writer;
    private final int scanTimeoutSeconds;
    private final boolean deleteCases;
    private final Set<SourceCodeFeature> exportTargets;

    private final List<File> noFeatureCases = new ArrayList<>();
    private final Map<File, String> skippedCases = new LinkedHashMap<>();

    public BatchProcessor(Set<SourceCodeFeature> exportTargets) {
        this(exportTargets, 10, false);
    }

    public BatchProcessor(Set<SourceCodeFeature> exportTargets, int scanTimeoutSeconds) {
        this(exportTargets, scanTimeoutSeconds, false);
    }

    public BatchProcessor(Set<SourceCodeFeature> exportTargets, int scanTimeoutSeconds, boolean deleteCases) {
        this.walker = new ProjectWalker();
        this.engine = new FeatureExtractorEngine();
        this.statsManager = new StatsManager();
        this.writer = new ReportWriter();
        this.exportTargets = exportTargets;
        this.scanTimeoutSeconds = scanTimeoutSeconds;
        this.deleteCases = deleteCases;
    }

    public void run(String rootPath) {
        File[] caseDirs = walker.findValidDirs(rootPath);
        if (caseDirs == null || caseDirs.length == 0) {
            System.out.println("No cases found in: " + rootPath);
            return;
        }

        for (File caseDir : caseDirs) {
            processSingleCase(caseDir);
        }

        statsManager.genAllSummaries(new File(rootPath), writer);
        printAnalysisSummary();

        if (deleteCases) {
            deleteRecordedCases();
        }
    }

    private void processSingleCase(File caseDir) {
        File[] javaFiles = walker.findJavaFiles(caseDir);
        if (javaFiles.length == 0) {
            skip(caseDir, "no Java source files");
            return;
        }

        final File mainJavaFile;
        try {
            mainJavaFile = walker.findMainJavaFile(caseDir);
        } catch (IllegalStateException e) {
            skip(caseDir, e.getMessage());
            return;
        }

        File[] filesToAnalyze = mainJavaFile == null ? javaFiles : new File[]{mainJavaFile};
        System.out.println("Processing: " + caseDir.getName() +
                (mainJavaFile == null ? " (no main; analyzing all Java files)" :
                        " (main=" + mainJavaFile.getName() + ")"));

        List<FeatureOccurrence> occurrences = new ArrayList<>();
        try {
            for (File javaFile : filesToAnalyze) {
                occurrences.addAll(scanFileWithTimeout(javaFile));
            }
        } catch (RuntimeException e) {
            skip(caseDir, e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        } catch (StackOverflowError e) {
            skip(caseDir, "StackOverflowError while Spoon was resolving recursive type information");
            return;
        }

        boolean hasReportedFeature = occurrences.stream()
                .anyMatch(o -> FeatureStats.isReportedFeature(o.getFeature()));
        if (!hasReportedFeature) {
            noFeatureCases.add(caseDir);
        }

        statsManager.genCaseReport(occurrences, caseDir, writer);

        if (exportTargets != null && !exportTargets.isEmpty()) {
            SourceCodeExporter exporter = new SourceCodeExporter(exportTargets);
            for (File javaFile : filesToAnalyze) {
                exporter.export(javaFile, occurrences, caseDir.getAbsolutePath());
            }
        }
    }

    private void skip(File caseDir, String reason) {
        skippedCases.put(caseDir, reason);
        System.err.println("Skipping: " + caseDir.getName());
        System.err.println("Reason: " + reason);
    }

    private void printAnalysisSummary() {
        System.out.println("\n========== Analysis Summary ==========");
        System.out.println("Cases without features: " + noFeatureCases.size());
        for (File caseDir : noFeatureCases) {
            System.out.println("  " + caseDir.getName());
        }

        System.out.println("\nSkipped cases: " + skippedCases.size());
        for (Map.Entry<File, String> entry : skippedCases.entrySet()) {
            System.out.println("  " + entry.getKey().getName() + " (" + entry.getValue() + ")");
        }
        System.out.println("======================================\n");
    }

    private void deleteRecordedCases() {
        List<File> casesToDelete = new ArrayList<>();
        casesToDelete.addAll(noFeatureCases);
        for (File caseDir : skippedCases.keySet()) {
            if (!casesToDelete.contains(caseDir)) {
                casesToDelete.add(caseDir);
            }
        }

        System.out.println("\n========== Delete Summary ==========");
        System.out.println("Cases selected for deletion: " + casesToDelete.size());

        int deleted = 0;
        int failed = 0;
        for (File caseDir : casesToDelete) {
            try {
                deleteRecursively(caseDir.toPath());
                deleted++;
                System.out.println("  Deleted: " + caseDir.getName());
            } catch (IOException e) {
                failed++;
                System.err.println("  Failed to delete: " + caseDir.getAbsolutePath());
                System.err.println("  Reason: " + e.getMessage());
            }
        }

        System.out.println("Deleted cases: " + deleted);
        System.out.println("Failed deletions: " + failed);
        System.out.println("====================================\n");
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }

        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            Path[] pathsToDelete = paths
                    .sorted(java.util.Comparator.reverseOrder())
                    .toArray(Path[]::new);
            for (Path path : pathsToDelete) {
                Files.delete(path);
            }
        }
    }

    private List<FeatureOccurrence> scanFileWithTimeout(File javaFile) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<List<FeatureOccurrence>> future = executor.submit(() -> engine.scanFile(javaFile));
        try {
            return future.get(scanTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("Scan timed out after " + scanTimeoutSeconds + " seconds");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while scanning case", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        } finally {
            executor.shutdownNow();
        }
    }
}
