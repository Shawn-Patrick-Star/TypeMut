package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.model.FeatureOccurrence;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;

import java.util.List;

public interface FeatureDetector {
    /**
     * Scan a class and return all discovered feature instances.
     */
    List<FeatureOccurrence> detect(CtType<?> type);

    /**
     * The feature type handled by this detector.
     */
    SourceCodeFeature getTargetFeature();

    /**
     * Return true only for AST elements/references that correspond to concrete
     * source text. Spoon also creates inferred/synthetic type references for many
     * expressions; those must not be counted as source-level type features.
     */
    default boolean hasSourcePosition(CtElement element) {
        if (element == null) {
            return false;
        }
        try {
            return element.getPosition() != null && element.getPosition().isValidPosition();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Walk upward to find the nearest meaningful statement or declaration.
     */
    default CtElement findMeaningfulStatement(CtElement element) {
        CtElement current = element;
        int maxDepth = 10;

        while (current != null && maxDepth-- > 0) {
            if (current instanceof CtLocalVariable ||
                current instanceof CtField ||
                current instanceof CtParameter ||
                current instanceof CtAssignment ||
                current instanceof CtReturn) {
                return current;
            }

            if (current instanceof CtStatement && !(current instanceof CtBlock)) {
                if (current instanceof CtInvocation) {
                    CtElement parent = current.getParent();
                    if (parent instanceof CtLocalVariable ||
                            parent instanceof CtAssignment ||
                            parent instanceof CtReturn ||
                            parent instanceof CtField) {
                        current = current.getParent();
                        continue;
                    }
                }
                return current;
            }

            if (current instanceof CtMethod ||
                    current instanceof CtType ||
                    current instanceof CtAnonymousExecutable) {
                return element;
            }

            current = current.getParent();
        }
        return element;
    }
}
