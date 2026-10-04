package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.model.FeatureOccurrence;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;

/** Detect an actual method call performed through java.lang.reflect.Method. */
public class ReflectiveMethodInvocationDetector implements FeatureDetector {

    private static final String REFLECT_METHOD_TYPE = "java.lang.reflect.Method";

    @Override
    public SourceCodeFeature getTargetFeature() {
        return SourceCodeFeature.reflectiveMethodInvocation;
    }

    @Override
    public List<FeatureOccurrence> detect(CtType<?> type) {
        List<FeatureOccurrence> results = new ArrayList<>();

        for (CtInvocation<?> invocation : type.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!hasSourcePosition(invocation) || !isReflectiveMethodInvocation(invocation)) {
                continue;
            }
            CtElement target = findMeaningfulStatement(invocation);
            results.add(new FeatureOccurrence(
                    SourceCodeFeature.reflectiveMethodInvocation,
                    target
            ));
        }

        return results;
    }

    private boolean isReflectiveMethodInvocation(CtInvocation<?> invocation) {
        CtExecutableReference<?> executable = invocation.getExecutable();
        if (executable == null || !"invoke".equals(executable.getSimpleName())) {
            return false;
        }

        // Prefer the executable's declaring type when Spoon resolves it.
        if (isReflectMethodType(executable.getDeclaringType())) {
            return true;
        }

        // In a noClasspath model the declaring type can be absent. The receiver
        // still commonly retains its explicit java.lang.reflect.Method type.
        CtExpression<?> receiver = invocation.getTarget();
        return receiver != null && isReflectMethodType(receiver.getType());
    }

    private boolean isReflectMethodType(CtTypeReference<?> type) {
        if (type == null) {
            return false;
        }
        try {
            return REFLECT_METHOD_TYPE.equals(type.getQualifiedName());
        } catch (Exception ignored) {
            return false;
        }
    }
}
