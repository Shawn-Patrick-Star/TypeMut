package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.model.FeatureOccurrence;
import spoon.reflect.declaration.CtType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.analysis.detector.DetectorTestSupport.count;
import static org.analysis.detector.DetectorTestSupport.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralSyntaxDetectorTest {

    private final GeneralSyntaxDetector detector = new GeneralSyntaxDetector();

    @TempDir
    Path tempDir;

    @Test
    void detectsTryCatchStructure() throws IOException {
        String source = ""
                + "package sample;\n"
                + "import java.io.ByteArrayInputStream;\n"
                + "class ResourceUse {\n"
                + "  int read() throws Exception {\n"
                + "    try (ByteArrayInputStream in = new ByteArrayInputStream(new byte[] {1})) {\n"
                + "      return in.read();\n"
                + "    } catch (Exception ex) {\n"
                + "      throw ex;\n"
                + "    }\n"
                + "  }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(tempDir, "ResourceUse.java", source, "ResourceUse");
        List<FeatureOccurrence> occurrences = detector.detect(type);

        assertTrue(has(occurrences, SourceCodeFeature.tryCatchStmt));
    }

    @Test
    void detectsPlainTryCatch() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class PlainTry {\n"
                + "  void use() {\n"
                + "    try {\n"
                + "      int value = 1 + 2;\n"
                + "    } catch (RuntimeException ex) {\n"
                + "      ex.getMessage();\n"
                + "    }\n"
                + "  }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(tempDir, "PlainTry.java", source, "PlainTry");
        List<FeatureOccurrence> occurrences = detector.detect(type);

        assertTrue(has(occurrences, SourceCodeFeature.tryCatchStmt));
    }

    @Test
    void standaloneThrowDoesNotCountAsTryCatch() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class ThrowOnly {\n"
                + "  void fail() { throw new RuntimeException(); }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(tempDir, "ThrowOnly.java", source, "ThrowOnly");
        List<FeatureOccurrence> occurrences = detector.detect(type);

        assertFalse(has(occurrences, SourceCodeFeature.tryCatchStmt));
    }

    @Test
    void marksInstanceofOperatorOnlyForInstanceofExpression() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class TypeCheck {\n"
                + "  boolean use(Object input) {\n"
                + "    boolean same = input == null;\n"
                + "    return input instanceof String && !same;\n"
                + "  }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(tempDir, "TypeCheck.java", source, "TypeCheck");
        List<FeatureOccurrence> occurrences = detector.detect(type);

        assertEquals(1, count(occurrences, SourceCodeFeature.instanceofOperator));
        assertTrue(has(occurrences, SourceCodeFeature.compareOperator));
        assertTrue(has(occurrences, SourceCodeFeature.logicalOperator));
    }

    @Test
    void unrelatedMethodNamesDoNotCountAsCustomClassLoader() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class OrdinaryApi {\n"
                + "  Helper helper = new Helper();\n"
                + "  void use() { helper.getParent(); helper.getResource(); }\n"
                + "  static class Helper {\n"
                + "    Object getParent() { return null; }\n"
                + "    Object getResource() { return null; }\n"
                + "  }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(tempDir, "OrdinaryApi.java", source, "OrdinaryApi");
        List<FeatureOccurrence> occurrences = detector.detect(type);

        assertFalse(has(occurrences, SourceCodeFeature.customClassLoader));
    }
}
