package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.model.FeatureOccurrence;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtSynchronized;
import spoon.reflect.code.CtTry;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class GeneralSyntaxDetector implements FeatureDetector {

    private static final Set<String> CLASS_LOADER_API_METHODS = new HashSet<>(Arrays.asList(
            "loadClass",
            "defineClass",
            "findClass",
            "resolveClass",
            "findLoadedClass",
            "findSystemClass",
            "getParent",
            "getResource",
            "getResources",
            "getResourceAsStream",
            "getSystemClassLoader",
            "getPlatformClassLoader",
            "getSystemResource",
            "getSystemResources",
            "getSystemResourceAsStream",
            "setDefaultAssertionStatus",
            "setPackageAssertionStatus",
            "setClassAssertionStatus",
            "clearAssertionStatus"
    ));

    private static final Set<String> CONTEXT_CLASS_LOADER_METHODS = new HashSet<>(Arrays.asList(
            "getContextClassLoader",
            "setContextClassLoader"
    ));

    @Override
    public SourceCodeFeature getTargetFeature() {
        return SourceCodeFeature.assignmentStmt;
    }

    @Override
    public List<FeatureOccurrence> detect(CtType<?> type) {
        List<FeatureOccurrence> results = new ArrayList<>();
        detectStatements(type, results);
        detectOperators(type, results);
        detectCustomClassLoaders(type, results);
        return results;
    }

    private void detectStatements(CtType<?> type, List<FeatureOccurrence> results) {
        addAll(type, results, SourceCodeFeature.assignmentStmt, CtAssignment.class);
        addAll(type, results, SourceCodeFeature.ifStmt, CtIf.class);
        addAll(type, results, SourceCodeFeature.invocationStmt, CtInvocation.class);
        addAll(type, results, SourceCodeFeature.switchStmt, CtSwitch.class);

        // tryCatchStmt means an actual try statement. A standalone `throw` must
        // not make a case look like it contains try/catch structure.
        addAll(type, results, SourceCodeFeature.tryCatchStmt, CtTry.class);

        addAll(type, results, SourceCodeFeature.synchronizedAccess, CtSynchronized.class);
        addAll(type, results, SourceCodeFeature.lambda, CtLambda.class);
    }

    private <T extends CtElement> void addAll(
            CtType<?> type,
            List<FeatureOccurrence> results,
            SourceCodeFeature feature,
            Class<T> elementType) {
        for (T element : type.getElements(new TypeFilter<>(elementType))) {
            if (!hasSourcePosition(element)) {
                continue;
            }
            results.add(new FeatureOccurrence(feature, findMeaningfulStatement(element)));
        }
    }

    private void detectOperators(CtType<?> type, List<FeatureOccurrence> results) {
        for (CtBinaryOperator<?> operator : type.getElements(new TypeFilter<>(CtBinaryOperator.class))) {
            if (!hasSourcePosition(operator)) {
                continue;
            }
            SourceCodeFeature feature = mapBinaryOperator(operator.getKind());
            if (feature != null) {
                results.add(new FeatureOccurrence(feature, findMeaningfulStatement(operator)));
            }
        }

        for (CtUnaryOperator<?> operator : type.getElements(new TypeFilter<>(CtUnaryOperator.class))) {
            if (hasSourcePosition(operator)) {
                results.add(new FeatureOccurrence(SourceCodeFeature.unaryOperator, findMeaningfulStatement(operator)));
            }
        }
    }

    private SourceCodeFeature mapBinaryOperator(BinaryOperatorKind kind) {
        switch (kind) {
            case AND:
            case OR:
                return SourceCodeFeature.logicalOperator;
            case DIV:
            case MINUS:
            case PLUS:
            case MUL:
            case MOD:
                return SourceCodeFeature.arithmeticOperator;
            case SL:
            case SR:
            case USR:
                return SourceCodeFeature.shiftOperator;
            case INSTANCEOF:
                return SourceCodeFeature.instanceofOperator;
            case EQ:
            case GT:
            case GE:
            case NE:
            case LE:
            case LT:
                return SourceCodeFeature.compareOperator;
            default:
                return null;
        }
    }

    private void detectCustomClassLoaders(CtType<?> rootType, List<FeatureOccurrence> results) {
        CtTypeReference<?> classLoaderRef = rootType.getFactory().Type().createReference("java.lang.ClassLoader");
        Set<CtElement> matchedElements = new LinkedHashSet<>();
        Set<CtType<?>> allTypes = new LinkedHashSet<>();
        allTypes.add(rootType);
        allTypes.addAll(rootType.getElements(new TypeFilter<>(CtType.class)));

        for (CtType<?> type : allTypes) {
            if (hasSourcePosition(type) && isCustomClassLoader(type, classLoaderRef)) {
                matchedElements.add(type);
            }
        }

        for (CtTypeReference<?> reference : rootType.getElements(new TypeFilter<>(CtTypeReference.class))) {
            if (hasSourcePosition(reference) && isKnownClassLoaderType(reference)) {
                matchedElements.add(findMeaningfulStatement(reference));
            }
        }

        for (CtConstructorCall<?> constructorCall : rootType.getElements(new TypeFilter<>(CtConstructorCall.class))) {
            if (hasSourcePosition(constructorCall) && isKnownClassLoaderType(constructorCall.getType())) {
                matchedElements.add(findMeaningfulStatement(constructorCall));
            }
        }

        for (CtInvocation<?> invocation : rootType.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (hasSourcePosition(invocation) && isClassLoaderInvocation(invocation)) {
                matchedElements.add(findMeaningfulStatement(invocation));
            }
        }

        for (CtElement element : matchedElements) {
            results.add(new FeatureOccurrence(SourceCodeFeature.customClassLoader, element));
        }
    }

    private boolean isCustomClassLoader(CtType<?> type, CtTypeReference<?> classLoaderRef) {
        if ("java.lang.ClassLoader".equals(type.getQualifiedName())) {
            return false;
        }
        try {
            return type.isSubtypeOf(classLoaderRef);
        } catch (Exception ignored) {
            CtTypeReference<?> superclass = type.getSuperclass();
            return isKnownClassLoaderType(superclass);
        }
    }

    private boolean isKnownClassLoaderType(CtTypeReference<?> typeReference) {
        if (typeReference == null) {
            return false;
        }

        String qualifiedName = typeReference.getQualifiedName();
        String simpleName = typeReference.getSimpleName();
        return "java.lang.ClassLoader".equals(qualifiedName) ||
                "java.net.URLClassLoader".equals(qualifiedName) ||
                "java.security.SecureClassLoader".equals(qualifiedName) ||
                "ClassLoader".equals(simpleName) ||
                "URLClassLoader".equals(simpleName) ||
                "SecureClassLoader".equals(simpleName);
    }

    private boolean isClassLoaderInvocation(CtInvocation<?> invocation) {
        CtExecutableReference<?> executable = invocation.getExecutable();
        if (executable == null) {
            return false;
        }

        String methodName = executable.getSimpleName();
        if (methodName == null) {
            return false;
        }

        CtTypeReference<?> declaringType = executable.getDeclaringType();
        if (declaringType != null) {
            String declaringQualifiedName = declaringType.getQualifiedName();
            if (isKnownClassLoaderType(declaringType) && CLASS_LOADER_API_METHODS.contains(methodName)) {
                return true;
            }
            if ("java.lang.Thread".equals(declaringQualifiedName) &&
                    CONTEXT_CLASS_LOADER_METHODS.contains(methodName)) {
                return true;
            }
            if ("java.lang.Class".equals(declaringQualifiedName) && "getClassLoader".equals(methodName)) {
                return true;
            }
            if ("java.lang.Class".equals(declaringQualifiedName) &&
                    "forName".equals(methodName) && invocation.getArguments().size() >= 3) {
                return true;
            }
        }

        // In no-classpath mode the executable declaring type can be absent. Use
        // the receiver's resolved type as a conservative fallback, but never match
        // on a generic method name alone (e.g. unrelated getParent/loadClass).
        CtExpression<?> target = invocation.getTarget();
        CtTypeReference<?> targetType = target == null ? null : target.getType();
        if (isKnownClassLoaderType(targetType) && CLASS_LOADER_API_METHODS.contains(methodName)) {
            return true;
        }
        if (targetType != null && "java.lang.Thread".equals(targetType.getQualifiedName()) &&
                CONTEXT_CLASS_LOADER_METHODS.contains(methodName)) {
            return true;
        }
        if (targetType != null && "java.lang.Class".equals(targetType.getQualifiedName()) &&
                "getClassLoader".equals(methodName)) {
            return true;
        }

        return false;
    }
}
