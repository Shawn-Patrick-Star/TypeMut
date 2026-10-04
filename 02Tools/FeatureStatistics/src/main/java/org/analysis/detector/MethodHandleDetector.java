package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.model.FeatureOccurrence;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MethodHandleDetector implements FeatureDetector {

    private static final Set<String> CORE_TYPES = new HashSet<>(Arrays.asList(
            "java.lang.invoke.MethodHandle",
            "java.lang.invoke.MethodHandles",
            "java.lang.invoke.MethodType"
    ));

    private static final String LOOKUP_TYPE = "java.lang.invoke.MethodHandles.Lookup";

    private static final Set<String> INTERESTING_METHODS = new HashSet<>(Arrays.asList(
            "lookup", "publicLookup",
            "findStatic", "findVirtual", "findConstructor", "findSpecial",
            "unreflect", "unreflectGetter", "unreflectSetter",
            "invoke", "invokeExact", "invokeWithArguments",
            "bindTo", "asType", "type",
            "insertArguments", "filterReturnValue", "guardWithTest",
            "methodType", "genericMethodType"
    ));

    @Override
    public SourceCodeFeature getTargetFeature() {
        return SourceCodeFeature.methodHandle;
    }

    @Override
    public List<FeatureOccurrence> detect(CtType<?> type) {
        List<FeatureOccurrence> results = new ArrayList<>();

        for (CtInvocation<?> invocation : type.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (hasSourcePosition(invocation) && isMethodHandleCall(invocation)) {
                CtElement target = findMeaningfulStatement(invocation);
                results.add(new FeatureOccurrence(SourceCodeFeature.methodHandle, target));
            }
        }

        // Type-reference evidence is restricted to the three core abstractions and
        // must be backed by source text. Inferred return types do not count.
        for (CtTypeReference<?> reference : type.getElements(new TypeFilter<>(CtTypeReference.class))) {
            if (hasSourcePosition(reference) && isCoreMethodHandleType(reference)) {
                CtElement target = findMeaningfulStatement(reference);
                results.add(new FeatureOccurrence(SourceCodeFeature.methodHandle, target));
            }
        }

        return results;
    }

    private boolean isCoreMethodHandleType(CtTypeReference<?> reference) {
        return reference != null && CORE_TYPES.contains(reference.getQualifiedName());
    }

    private boolean isMethodHandleCall(CtInvocation<?> invocation) {
        CtExecutableReference<?> exec = invocation.getExecutable();
        if (exec == null || !INTERESTING_METHODS.contains(exec.getSimpleName())) {
            return false;
        }

        CtTypeReference<?> declaringType = exec.getDeclaringType();
        if (declaringType == null) {
            return false;
        }

        String className = declaringType.getQualifiedName();
        return CORE_TYPES.contains(className) || LOOKUP_TYPE.equals(className);
    }
}
