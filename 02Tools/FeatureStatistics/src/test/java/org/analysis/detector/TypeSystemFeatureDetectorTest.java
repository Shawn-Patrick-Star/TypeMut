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

class TypeSystemFeatureDetectorTest {

    private final TypeSystemFeatureDetector detector = new TypeSystemFeatureDetector();

    @TempDir
    Path tempDir;

    @Test
    void detectsComplexTypeSystemFeaturesWithoutFrameworkSpecificSignals() throws IOException {
        String source = ""
                + "package sample;\n"
                + "import java.lang.reflect.InvocationHandler;\n"
                + "import java.lang.reflect.Proxy;\n"
                + "import java.util.ArrayList;\n"
                + "import java.util.List;\n"
                + "import java.util.Map;\n"
                + "import java.util.Optional;\n"
                + "import java.util.function.Function;\n"
                + "import java.util.stream.Stream;\n"
                + "@Deprecated\n"
                + "public class RichTypes<T extends Number> {\n"
                + "  List<? extends T> values = new ArrayList<>();\n"
                + "  Map<String, List<Integer>> byName;\n"
                + "  Optional<String> optional;\n"
                + "  Stream<Integer> stream;\n"
                + "  interface NestedContract {}\n"
                + "  abstract static class AbstractNested {}\n"
                + "  enum Kind { A }\n"
                + "  @interface Marker {}\n"
                + "  record Pair(int value) {}\n"
                + "  void use(Object input) {\n"
                + "    var inferred = new ArrayList<String>();\n"
                + "    Function<String, String> trim = String::trim;\n"
                + "    Class<?> literal = String.class;\n"
                + "    Object anonymous = new Runnable() { public void run() {} };\n"
                + "    if (input instanceof String text) { text.length(); }\n"
                + "    InvocationHandler handler = (proxy, method, args) -> null;\n"
                + "    Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { NestedContract.class }, handler);\n"
                + "  }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(tempDir, "RichTypes.java", source, "RichTypes");
        List<FeatureOccurrence> occurrences = detector.detect(type);

        assertTrue(has(occurrences, SourceCodeFeature.genericType));
        assertTrue(has(occurrences, SourceCodeFeature.parameterizedType));
        assertTrue(has(occurrences, SourceCodeFeature.typeParameter));
        assertTrue(has(occurrences, SourceCodeFeature.boundedTypeParameter));
        assertTrue(has(occurrences, SourceCodeFeature.wildcardType));
        assertTrue(has(occurrences, SourceCodeFeature.boundedWildcardType));
        assertTrue(has(occurrences, SourceCodeFeature.classLiteral));
        assertTrue(has(occurrences, SourceCodeFeature.instanceofOperator));
        assertTrue(has(occurrences, SourceCodeFeature.interfaceType));
        assertTrue(has(occurrences, SourceCodeFeature.abstractType));
        assertTrue(has(occurrences, SourceCodeFeature.enumType));
        assertTrue(has(occurrences, SourceCodeFeature.nestedType));
        assertEquals(1, count(occurrences, SourceCodeFeature.genericType));
    }

    @Test
    void treatsGenericTypeAsSingleAggregateMarkerForAnyGenericFeature() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class GenericMethodOnly {\n"
                + "  <T extends Number> T id(T value) {\n"
                + "    return value;\n"
                + "  }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(
                tempDir, "GenericMethodOnly.java", source, "GenericMethodOnly");
        List<FeatureOccurrence> occurrences = detector.detect(type);

        assertTrue(has(occurrences, SourceCodeFeature.genericMethod));
        assertTrue(has(occurrences, SourceCodeFeature.typeParameter));
        assertTrue(has(occurrences, SourceCodeFeature.boundedTypeParameter));
        assertEquals(1, count(occurrences, SourceCodeFeature.genericType));
    }

    @Test
    void avoidsOverMatchingStaticAccessAndOrdinaryReflectionTypes() throws IOException {
        String source = ""
                + "package sample;\n"
                + "import java.lang.reflect.Method;\n"
                + "class PlainTypes {\n"
                + "  static final int VALUE = 1;\n"
                + "  void use(Method method) {\n"
                + "    int copied = PlainTypes.VALUE;\n"
                + "    method.getName();\n"
                + "  }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(tempDir, "PlainTypes.java", source, "PlainTypes");
        List<FeatureOccurrence> occurrences = detector.detect(type);

        assertFalse(has(occurrences, SourceCodeFeature.classLiteral));
    }
}
