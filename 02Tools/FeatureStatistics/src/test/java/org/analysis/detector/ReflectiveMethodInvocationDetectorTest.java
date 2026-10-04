package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.reflect.declaration.CtType;

import java.io.IOException;
import java.nio.file.Path;

import static org.analysis.detector.DetectorTestSupport.count;
import static org.analysis.detector.DetectorTestSupport.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectiveMethodInvocationDetectorTest {

    private final ReflectiveMethodInvocationDetector detector =
            new ReflectiveMethodInvocationDetector();

    @TempDir
    Path tempDir;

    @Test
    void detectsDirectAndChainedMethodInvokeCalls() throws IOException {
        String source = ""
                + "package sample;\n"
                + "import java.lang.reflect.Method;\n"
                + "class ReflectiveCalls {\n"
                + "  Object direct(Method method, Object target) throws Exception {\n"
                + "    return method.invoke(target);\n"
                + "  }\n"
                + "  Object chained(Object target) throws Exception {\n"
                + "    return target.getClass().getDeclaredMethod(\"toString\").invoke(target);\n"
                + "  }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(
                tempDir, "ReflectiveCalls.java", source, "ReflectiveCalls");

        assertEquals(2, count(detector.detect(type),
                SourceCodeFeature.reflectiveMethodInvocation));
    }

    @Test
    void methodLookupWithoutInvokeDoesNotCount() throws IOException {
        String source = ""
                + "package sample;\n"
                + "import java.lang.reflect.Method;\n"
                + "class LookupOnly {\n"
                + "  Method lookup() throws Exception {\n"
                + "    return String.class.getDeclaredMethod(\"trim\");\n"
                + "  }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(
                tempDir, "LookupOnly.java", source, "LookupOnly");

        assertFalse(has(detector.detect(type), SourceCodeFeature.reflectiveMethodInvocation));
    }

    @Test
    void constructorFieldAndOrdinaryInvokeMethodsDoNotCount() throws IOException {
        String source = ""
                + "package sample;\n"
                + "import java.lang.reflect.Constructor;\n"
                + "import java.lang.reflect.Field;\n"
                + "class NonMethodReflection {\n"
                + "  void use(Constructor<?> constructor, Field field, Helper helper) throws Exception {\n"
                + "    constructor.newInstance();\n"
                + "    field.get(this);\n"
                + "    helper.invoke(this);\n"
                + "  }\n"
                + "  static class Helper { Object invoke(Object target) { return target; } }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(
                tempDir, "NonMethodReflection.java", source, "NonMethodReflection");

        assertFalse(has(detector.detect(type), SourceCodeFeature.reflectiveMethodInvocation));
    }

    @Test
    void originalReflectionFeatureRemainsIndependent() throws IOException {
        String source = ""
                + "package sample;\n"
                + "class ClassLookup {\n"
                + "  Class<?> lookup(String name) throws Exception { return Class.forName(name); }\n"
                + "}\n";

        CtType<?> type = DetectorTestSupport.parseType(
                tempDir, "ClassLookup.java", source, "ClassLookup");

        assertTrue(has(new ReflectionDetector().detect(type), SourceCodeFeature.reflection));
        assertFalse(has(detector.detect(type), SourceCodeFeature.reflectiveMethodInvocation));
    }
}
