package org.analysis.detector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.model.FeatureOccurrence;
import spoon.reflect.declaration.CtType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoxingUnboxingDetectorTest {

    private final BoxingUnboxingDetector detector = new BoxingUnboxingDetector();

    @TempDir
    Path tempDir;

    @Test
    void detectsAssignmentFieldReturnAndWideningConversions() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class AssignmentCases {\n"
                + "  Integer field = 1;\n"
                + "  Object object = 2;\n"
                + "  Number number = 3;\n"
                + "  Byte narrowedConstant = 4;\n"
                + "  long convert(Integer value) {\n"
                + "    long local = value;\n"
                + "    return value;\n"
                + "  }\n"
                + "}\n";

        List<String> details = details(parse(source, "AssignmentCases"));

        assertTrue(details.contains("boxing:int->Integer"));
        assertTrue(details.contains("boxing:int->Object"));
        assertTrue(details.contains("boxing:int->Number"));
        assertTrue(details.contains("boxing:int->Byte"));
        assertTrue(details.contains("unboxing:Integer->long"));
    }

    @Test
    void detectsMethodConstructorGenericAndVarargsConversions() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class InvocationCases {\n"
                + "  InvocationCases(Integer value) {}\n"
                + "  static void takeInteger(Integer value) {}\n"
                + "  static void takeInt(int value) {}\n"
                + "  static void takeInts(int... values) {}\n"
                + "  static <T> void takeGeneric(T value) {}\n"
                + "  void run(Integer value) {\n"
                + "    takeInteger(1);\n"
                + "    takeInt(value);\n"
                + "    takeInts(value);\n"
                + "    takeGeneric(2);\n"
                + "    new InvocationCases(3);\n"
                + "  }\n"
                + "}\n";

        List<String> details = details(parse(source, "InvocationCases"));

        assertTrue(details.contains("boxing:int->Integer"));
        assertTrue(details.contains("unboxing:Integer->int"));
        // Spoon exposes the erased executable parameter type (Object) for this
        // generic call in noClasspath mode. It still represents the required
        // boxing conversion before generic invocation.
        assertTrue(details.contains("boxing:int->Object"));
    }

    @Test
    void detectsOperatorConditionArrayAndEnhancedForConversions() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class ExpressionCases {\n"
                + "  void run(Integer number, Boolean flag, Integer[] values) {\n"
                + "    int sum = number + 1;\n"
                + "    if (flag) { number++; }\n"
                + "    number += 2;\n"
                + "    Integer[] boxed = { 1, 2 };\n"
                + "    int selected = values[number];\n"
                + "    for (int value : values) { sum += value; }\n"
                + "  }\n"
                + "}\n";

        List<String> details = details(parse(source, "ExpressionCases"));

        assertTrue(details.contains("unboxing:Integer->int"));
        assertTrue(details.contains("unboxing:Boolean->boolean"));
        assertTrue(details.contains("boxing:int->Integer"));
    }

    @Test
    void doesNotTreatReferenceEqualityOrStringConcatenationAsUnboxing() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class ReferenceOnly {\n"
                + "  void run(Integer left, Integer right) {\n"
                + "    boolean same = left == right;\n"
                + "    String text = \"value=\" + left;\n"
                + "  }\n"
                + "}\n";

        assertEquals(0, details(parse(source, "ReferenceOnly")).size());
    }

    @Test
    void detectsLambdaReturnAndReferenceCastUnboxing() throws IOException {
        String source = ""
                + "package sample;\n"
                + "import java.util.function.Supplier;\n"
                + "class LessCommonContexts {\n"
                + "  Supplier<Integer> supplier = () -> 1;\n"
                + "  int cast(Object value) { return (int) value; }\n"
                + "}\n";

        List<String> details = details(parse(source, "LessCommonContexts"));

        assertTrue(details.contains("boxing:int->Integer"), details.toString());
        assertTrue(details.contains("unboxing:Object->int"));
    }

    private CtType<?> parse(String source, String simpleName) throws IOException {
        return DetectorTestSupport.parseType(tempDir, simpleName + ".java", source, simpleName);
    }

    private List<String> details(CtType<?> type) {
        return detector.detect(type).stream()
                .map(FeatureOccurrence::getDetail)
                .collect(java.util.stream.Collectors.toList());
    }
}
