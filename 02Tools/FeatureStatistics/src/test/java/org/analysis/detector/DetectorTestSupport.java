package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.model.FeatureOccurrence;
import spoon.Launcher;
import spoon.reflect.declaration.CtType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class DetectorTestSupport {

    private DetectorTestSupport() {
    }

    static CtType<?> parseType(Path tempDir, String fileName, String source, String simpleName) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, source);

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.addInputResource(file.toString());
        launcher.buildModel();

        return launcher.getModel().getAllTypes().stream()
                .filter(type -> simpleName.equals(type.getSimpleName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Type not found: " + simpleName));
    }

    static long count(List<FeatureOccurrence> occurrences, SourceCodeFeature feature) {
        return occurrences.stream()
                .filter(occurrence -> occurrence.getFeature() == feature)
                .count();
    }

    static boolean has(List<FeatureOccurrence> occurrences, SourceCodeFeature feature) {
        return count(occurrences, feature) > 0;
    }
}
