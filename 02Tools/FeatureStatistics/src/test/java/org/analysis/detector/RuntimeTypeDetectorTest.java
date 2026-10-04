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

class RuntimeTypeDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsReflectionCallsButNotOrdinaryClassReferences() throws IOException {
        String reflectiveSource = ""
                + "package sample;\n"
                + "class ReflectiveUse {\n"
                + "  Object create(String name) throws Exception {\n"
                + "    Class<?> type = Class.forName(name);\n"
                + "    return type.getDeclaredConstructor().newInstance();\n"
                + "  }\n"
                + "}\n";
        String ordinarySource = ""
                + "package sample;\n"
                + "class OrdinaryClassUse {\n"
                + "  Class<?> type() { return String.class; }\n"
                + "}\n";

        ReflectionDetector detector = new ReflectionDetector();

        CtType<?> reflectiveType = DetectorTestSupport.parseType(
                tempDir, "ReflectiveUse.java", reflectiveSource, "ReflectiveUse");
        CtType<?> ordinaryType = DetectorTestSupport.parseType(
                tempDir, "OrdinaryClassUse.java", ordinarySource, "OrdinaryClassUse");

        assertTrue(has(detector.detect(reflectiveType), SourceCodeFeature.reflection));
        assertFalse(has(detector.detect(ordinaryType), SourceCodeFeature.reflection));
    }

    @Test
    void detectsMethodHandleApiButNotSameMethodNamesOnOtherTypes() throws IOException {
        String methodHandleSource = ""
                + "package sample;\n"
                + "import java.lang.invoke.MethodHandle;\n"
                + "import java.lang.invoke.MethodHandles;\n"
                + "import java.lang.invoke.MethodType;\n"
                + "class MethodHandleUse {\n"
                + "  MethodHandle handle() throws Exception {\n"
                + "    return MethodHandles.lookup().findVirtual(String.class, \"trim\", MethodType.methodType(String.class));\n"
                + "  }\n"
                + "}\n";
        String ordinarySource = ""
                + "package sample;\n"
                + "class OrdinaryLookup {\n"
                + "  Helper lookup() { return new Helper(); }\n"
                + "  void use() { lookup().findVirtual(); }\n"
                + "  static class Helper { void findVirtual() {} }\n"
                + "}\n";

        MethodHandleDetector detector = new MethodHandleDetector();

        CtType<?> methodHandleType = DetectorTestSupport.parseType(
                tempDir, "MethodHandleUse.java", methodHandleSource, "MethodHandleUse");
        CtType<?> ordinaryType = DetectorTestSupport.parseType(
                tempDir, "OrdinaryLookup.java", ordinarySource, "OrdinaryLookup");

        assertTrue(has(detector.detect(methodHandleType), SourceCodeFeature.methodHandle));
        assertFalse(has(detector.detect(ordinaryType), SourceCodeFeature.methodHandle));
    }

    @Test
    void detectsClassLoaderTypesAndCallsWithoutMatchingUnrelatedLoadMethods() throws IOException {
        String loaderSource = ""
                + "package sample;\n"
                + "class LoaderUse extends ClassLoader {\n"
                + "  Class<?> load(String name) throws Exception {\n"
                + "    return getParent().loadClass(name);\n"
                + "  }\n"
                + "}\n";
        String ordinarySource = ""
                + "package sample;\n"
                + "class OrdinaryLoaderName {\n"
                + "  Object loadClass(String name) { return name; }\n"
                + "}\n";

        GeneralSyntaxDetector detector = new GeneralSyntaxDetector();

        CtType<?> loaderType = DetectorTestSupport.parseType(tempDir, "LoaderUse.java", loaderSource, "LoaderUse");
        CtType<?> ordinaryType = DetectorTestSupport.parseType(
                tempDir, "OrdinaryLoaderName.java", ordinarySource, "OrdinaryLoaderName");

        assertTrue(has(detector.detect(loaderType), SourceCodeFeature.customClassLoader));
        assertFalse(has(detector.detect(ordinaryType), SourceCodeFeature.customClassLoader));
    }
}
