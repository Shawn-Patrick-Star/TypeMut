package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.model.FeatureOccurrence;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ReflectionDetector implements FeatureDetector {

    private static final Set<String> CLASS_REFLECTION_METHODS = new HashSet<>(Arrays.asList(
            "forName",
            "getMethod", "getDeclaredMethod", "getMethods", "getDeclaredMethods",
            "getField", "getDeclaredField", "getFields", "getDeclaredFields",
            "getConstructor", "getDeclaredConstructor", "getConstructors", "getDeclaredConstructors"
    ));

    private static final Set<String> FIELD_ACCESS_METHODS = new HashSet<>(Arrays.asList(
            "get", "set",
            "getBoolean", "getInt", "getDouble", "getFloat", "getLong", "getShort", "getByte", "getChar",
            "setBoolean", "setInt", "setDouble", "setFloat", "setLong", "setShort", "setByte", "setChar"
    ));

    @Override
    public SourceCodeFeature getTargetFeature() {
        return SourceCodeFeature.reflection;
    }

    @Override
    public List<FeatureOccurrence> detect(CtType<?> type) {
        List<FeatureOccurrence> results = new ArrayList<>();

        for (CtInvocation<?> invocation : type.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!hasSourcePosition(invocation) || !isReflectionCall(invocation)) {
                continue;
            }
            CtElement target = findMeaningfulStatement(invocation);
            results.add(new FeatureOccurrence(SourceCodeFeature.reflection, target));
        }

        return results;
    }

    private boolean isReflectionCall(CtInvocation<?> invocation) {
        CtExecutableReference<?> executable = invocation.getExecutable();
        if (executable == null || executable.getDeclaringType() == null) {
            return false;
        }

        String className = executable.getDeclaringType().getQualifiedName();
        String methodName = executable.getSimpleName();
        if (className == null || methodName == null) {
            return false;
        }

        if ("java.lang.Class".equals(className)) {
            return CLASS_REFLECTION_METHODS.contains(methodName);
        }
        if ("java.lang.reflect.Method".equals(className)) {
            return "invoke".equals(methodName);
        }
        if ("java.lang.reflect.Constructor".equals(className)) {
            return "newInstance".equals(methodName);
        }
        if ("java.lang.reflect.Field".equals(className)) {
            return FIELD_ACCESS_METHODS.contains(methodName);
        }
        if ("java.lang.reflect.Array".equals(className)) {
            return "newInstance".equals(methodName);
        }

        return false;
    }
}
