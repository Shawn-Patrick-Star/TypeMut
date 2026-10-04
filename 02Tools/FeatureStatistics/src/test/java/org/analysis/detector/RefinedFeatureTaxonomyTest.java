package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.model.FeatureOccurrence;
import spoon.reflect.declaration.CtType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.analysis.detector.DetectorTestSupport.has;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefinedFeatureTaxonomyTest {

    @TempDir
    Path tempDir;

    @Test
    void separatesArrayTypeFromArrayAccess() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class ArraysCase {\n"
                + "  int[] data = new int[4];\n"
                + "  int read(int i) { return data[i]; }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(
                tempDir, "ArraysCase.java", source, "ArraysCase");
        List<FeatureOccurrence> occurrences = new ArrayDetector().detect(type);

        assertTrue(has(occurrences, SourceCodeFeature.arrayType));
        assertTrue(has(occurrences, SourceCodeFeature.arrayAccess));
    }

    @Test
    void doesNotCountMainStringArrayParameterAsArrayType() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class MainOnly {\n"
                + "  public static void main(String[] args) { System.out.println(1); }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(
                tempDir, "MainOnly.java", source, "MainOnly");
        List<FeatureOccurrence> occurrences = new ArrayDetector().detect(type);

        assertFalse(has(occurrences, SourceCodeFeature.arrayType));
    }

    @Test
    void keepsNonBoilerplateStringArrays() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class StringArrayData {\n"
                + "  String[] values = new String[4];\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(
                tempDir, "StringArrayData.java", source, "StringArrayData");
        List<FeatureOccurrence> occurrences = new ArrayDetector().detect(type);

        assertTrue(has(occurrences, SourceCodeFeature.arrayType));
    }

    @Test
    void countsOnlyExplicitPrimitiveTypeSyntax() throws IOException {
        String inferredOnly = ""
                + "package sample;\n"
                + "class InferredOnly {\n"
                + "  Object use(Object a, Object b) {\n"
                + "    if (a == b) { System.out.println(1); }\n"
                + "    return a;\n"
                + "  }\n"
                + "}\n";
        String explicit = ""
                + "package sample;\n"
                + "class ExplicitPrimitive {\n"
                + "  int value;\n"
                + "  boolean flag;\n"
                + "}\n";

        TypeUsageDetector detector = new TypeUsageDetector();
        CtType<?> inferredType = DetectorTestSupport.parseType(
                tempDir, "InferredOnly.java", inferredOnly, "InferredOnly");
        CtType<?> explicitType = DetectorTestSupport.parseType(
                tempDir, "ExplicitPrimitive.java", explicit, "ExplicitPrimitive");

        List<FeatureOccurrence> inferred = detector.detect(inferredType);
        List<FeatureOccurrence> declared = detector.detect(explicitType);

        assertFalse(has(inferred, SourceCodeFeature.booleanType));
        assertFalse(has(inferred, SourceCodeFeature.integerType));
        assertFalse(has(inferred, SourceCodeFeature.primitiveType));
        assertTrue(has(declared, SourceCodeFeature.booleanType));
        assertTrue(has(declared, SourceCodeFeature.integerType));
        assertTrue(has(declared, SourceCodeFeature.primitiveType));
    }

    @Test
    void combinesExplicitCastAndImplicitConversionIntoTypeCast() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class ConversionCase {\n"
                + "  Object convert(String value) {\n"
                + "    Object widened = value;\n"
                + "    String narrowed = (String) widened;\n"
                + "    return narrowed;\n"
                + "  }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(
                tempDir, "ConversionCase.java", source, "ConversionCase");
        List<FeatureOccurrence> occurrences = new TypeCastingDetector().detect(type);

        assertTrue(has(occurrences, SourceCodeFeature.TypeCast));
        assertTrue(occurrences.stream()
                .anyMatch(occurrence -> occurrence.getDetail().startsWith("explicit:")));
        assertTrue(occurrences.stream()
                .anyMatch(occurrence -> occurrence.getDetail().startsWith("implicit:")));
    }

    @Test
    void detectsPrimitiveConversionAsOrthogonalConversionKind() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class PrimitiveConversionCase {\n"
                + "  long widen(int value) { long x = value; return x; }\n"
                + "  int narrow(long value) { return (int) value; }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(
                tempDir, "PrimitiveConversionCase.java", source, "PrimitiveConversionCase");
        List<FeatureOccurrence> occurrences = new TypeCastingDetector().detect(type);

        assertTrue(has(occurrences, SourceCodeFeature.primitiveConversion));
        assertTrue(has(occurrences, SourceCodeFeature.TypeCast));
    }

    @Test
    void detectsBoxingAndUnboxingAsAggregateTransitionFeature() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class BoxingCase {\n"
                + "  int roundTrip(int value) {\n"
                + "    Integer boxed = value;\n"
                + "    int unboxed = boxed;\n"
                + "    return unboxed;\n"
                + "  }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(
                tempDir, "BoxingCase.java", source, "BoxingCase");
        List<FeatureOccurrence> occurrences = new TypeCastingDetector().detect(type);

        assertTrue(has(occurrences, SourceCodeFeature.boxingUnboxing));
        assertTrue(has(occurrences, SourceCodeFeature.TypeCast));
    }

    @Test
    void keepsOnlyAggregateWrapperFeature() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class WrapperCase {\n"
                + "  Integer i; Long l; Double d;\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(
                tempDir, "WrapperCase.java", source, "WrapperCase");
        List<FeatureOccurrence> occurrences = new TypeUsageDetector().detect(type);

        assertTrue(has(occurrences, SourceCodeFeature.wrapperType));
    }

    @Test
    void doesNotTreatVarHandleAsMethodHandleFeature() throws IOException {
        String source = ""
                + "package sample;\n"
                + "import java.lang.invoke.VarHandle;\n"
                + "class VarHandleOnly { VarHandle handle; }\n";

        CtType<?> type = DetectorTestSupport.parseType(
                tempDir, "VarHandleOnly.java", source, "VarHandleOnly");
        List<FeatureOccurrence> occurrences = new MethodHandleDetector().detect(type);

        assertFalse(has(occurrences, SourceCodeFeature.methodHandle));
    }
}
