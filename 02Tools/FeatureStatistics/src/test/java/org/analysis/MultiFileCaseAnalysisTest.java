package org.analysis;

import org.ASTfeature.SourceCodeFeature;
import org.io.ProjectWalker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.model.FeatureOccurrence;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiFileCaseAnalysisTest {

    @TempDir
    Path tempDir;

    @Test
    void analyzesAllJavaFilesWhenCaseHasNoMainMethod() throws Exception {
        Path caseDir = Files.createDirectory(tempDir.resolve("JDK-NO-MAIN"));

        Files.writeString(
                caseDir.resolve("Alpha.java"),
                "class Alpha { double value; }\n"
        );
        Files.writeString(
                caseDir.resolve("Beta.java"),
                "class Beta { void helper() { for (int i = 0; i < 10; i++) {} } }\n"
        );
        Files.writeString(
                caseDir.resolve("FuzzerUtils.java"),
                "class FuzzerUtils { long infrastructureOnly; }\n"
        );

        ProjectWalker walker = new ProjectWalker();
        assertEquals(null, walker.findMainJavaFile(caseDir.toFile()));

        new BatchProcessor(Set.of(), 5).run(tempDir.toString());

        String featuresByCase = Files.readString(tempDir.resolve("features_by_case.json"));
        assertTrue(featuresByCase.contains("\"JDK-NO-MAIN\""));
        assertTrue(featuresByCase.contains("\"doubleType\""));
        assertTrue(featuresByCase.contains("\"loop\""));
        assertFalse(featuresByCase.contains("\"longType\""));
    }


    @Test
    void analyzesOnlyUniqueMainFileAndIgnoresHelpers() throws Exception {
        Path caseDir = Files.createDirectory(tempDir.resolve("JDK-TEST"));

        Files.writeString(
                caseDir.resolve("Test.java"),
                "class Test {\n" +
                "  public static void main(String[] args) {\n" +
                "    int value = 1;\n" +
                "  }\n" +
                "}\n"
        );
        Files.writeString(
                caseDir.resolve("Helper.java"),
                "class Helper {\n" +
                "  double value;\n" +
                "  void helper() { for (int i = 0; i < 10; i++) {} }\n" +
                "}\n"
        );
        Files.writeString(
                caseDir.resolve("FuzzerUtils.java"),
                "class FuzzerUtils { long infrastructureOnly; }\n"
        );

        ProjectWalker walker = new ProjectWalker();
        File[] javaFiles = walker.findJavaFiles(caseDir.toFile());
        assertEquals(2, javaFiles.length);

        File mainFile = walker.findMainJavaFile(caseDir.toFile());
        assertEquals("Test.java", mainFile.getName());

        FeatureExtractorEngine engine = new FeatureExtractorEngine();
        Set<SourceCodeFeature> features = engine.scanFile(mainFile)
                .stream()
                .map(FeatureOccurrence::getFeature)
                .collect(Collectors.toSet());

        assertTrue(features.contains(SourceCodeFeature.integerType));
        assertFalse(features.contains(SourceCodeFeature.doubleType));
        assertFalse(features.contains(SourceCodeFeature.longType));
        assertFalse(features.contains(SourceCodeFeature.loop));

        // The conventional main(String[] args) parameter is boilerplate and must
        // not make every case appear to use arrays.
        assertFalse(features.contains(SourceCodeFeature.arrayType));
    }
}
