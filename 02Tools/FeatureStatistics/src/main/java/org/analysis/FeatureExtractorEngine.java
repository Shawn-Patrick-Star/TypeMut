package org.analysis;

import org.analysis.detector.*;
import org.model.FeatureOccurrence;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FeatureExtractorEngine {

    private final List<FeatureDetector> detectors;

    public FeatureExtractorEngine() {
        this.detectors = new ArrayList<>();
        detectors.add(new GeneralSyntaxDetector());
        detectors.add(new TypeUsageDetector());
        detectors.add(new TypeSystemFeatureDetector());
        detectors.add(new ArrayDetector());
        detectors.add(new HierarchyDetector());
        detectors.add(new TypeCastingDetector());
        detectors.add(new ReflectionDetector());
        detectors.add(new ReflectiveMethodInvocationDetector());
        detectors.add(new MethodHandleDetector());
        detectors.add(new LoopDetector());
    }

    /**
     * Main empirical-analysis entry point: scan one selected main source file.
     */
    public List<FeatureOccurrence> scanFile(File javaFile) {
        return scanFiles(Collections.singletonList(javaFile));
    }

    /**
     * Backward-compatible model builder. The production statistics path passes a
     * singleton list containing only the unique main source file.
     *
     * noClasspath mode is important here because the selected main source may
     * reference helper classes that intentionally are not part of the AST sample.
     */
    public List<FeatureOccurrence> scanFiles(List<File> javaFiles) {
        List<FeatureOccurrence> allOccurrences = new ArrayList<>();
        if (javaFiles == null || javaFiles.isEmpty()) {
            return allOccurrences;
        }

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        for (File javaFile : javaFiles) {
            launcher.addInputResource(javaFile.getAbsolutePath());
        }
        launcher.buildModel();
        CtModel model = launcher.getModel();

        // Run detectors once for each top-level type in the selected source. Each
        // detector may inspect nested declarations within that top-level type.
        for (CtType<?> type : model.getAllTypes()) {
            if (!(type.getParent() instanceof CtPackage)) {
                continue;
            }
            for (FeatureDetector detector : detectors) {
                allOccurrences.addAll(detector.detect(type));
            }
        }

        return allOccurrences;
    }
}
