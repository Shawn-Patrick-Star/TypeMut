package org.ASTfeature;

import spoon.reflect.code.*;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.LineFilter;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Legacy method-level binary encoder retained for compatibility.
 *
 * The empirical-study pipeline uses FeatureExtractorEngine and the detector
 * classes. This class is kept compilable with the same SourceCodeFeature enum so
 * older callers do not break when the feature taxonomy evolves.
 */
public class Encode {
    private final int[] code = new int[SourceCodeFeature.values().length];
    private final String methodName;
    Boolean uselessMethod = false;

    private static final Set<String> WRAPPER_NAMES = new HashSet<>(Arrays.asList(
            "Integer", "Long", "Double", "Float",
            "Short", "Byte", "Character", "Boolean"
    ));

    private static final Set<String> METHOD_HANDLE_TYPES = new HashSet<>(Arrays.asList(
            "java.lang.invoke.MethodHandle",
            "java.lang.invoke.MethodHandles",
            "java.lang.invoke.MethodType"
    ));

    public Encode(CtMethod<?> method) {
        methodName = method.getSimpleName();
        CtBlock<?> block = method.getBody();
        if (block == null || block.getElements(new LineFilter()).size() > 100) {
            uselessMethod = true;
            return;
        }

        analyzeStatements(method);
        analyzeOperators(method);
        analyzeTypes(method);
        analyzeArrays(method);
        analyzeLoop(method);
        analyzeHierarchy(method);
        analyzeMethodHandle(method);
        analyzeReflection(method);
        analyzeClassLoader(method);
        analyzeTypeConversions(method);
    }

    public int[] getCodeArray() {
        return Arrays.copyOf(code, code.length);
    }

    public String getMethodName() {
        return methodName;
    }

    private void mark(SourceCodeFeature feature) {
        code[feature.ordinal()] = 1;
    }

    private void analyzeStatements(CtMethod<?> method) {
        if (!method.getElements(new TypeFilter<>(CtAssignment.class)).isEmpty()) mark(SourceCodeFeature.assignmentStmt);
        if (!method.getElements(new TypeFilter<>(CtIf.class)).isEmpty()) mark(SourceCodeFeature.ifStmt);
        if (!method.getElements(new TypeFilter<>(CtInvocation.class)).isEmpty()) mark(SourceCodeFeature.invocationStmt);
        if (!method.getElements(new TypeFilter<>(CtSwitch.class)).isEmpty()) mark(SourceCodeFeature.switchStmt);
        if (!method.getElements(new TypeFilter<>(CtTry.class)).isEmpty() ||
                !method.getElements(new TypeFilter<>(CtCatch.class)).isEmpty() ||
                !method.getElements(new TypeFilter<>(CtThrow.class)).isEmpty()) {
            mark(SourceCodeFeature.tryCatchStmt);
        }
        if (!method.getElements(new TypeFilter<>(CtSynchronized.class)).isEmpty()) mark(SourceCodeFeature.synchronizedAccess);
        if (!method.getElements(new TypeFilter<>(CtLambda.class)).isEmpty()) mark(SourceCodeFeature.lambda);
    }

    private void analyzeOperators(CtMethod<?> method) {
        for (CtBinaryOperator<?> op : method.getElements(new TypeFilter<>(CtBinaryOperator.class))) {
            switch (op.getKind()) {
                case AND:
                case OR:
                    mark(SourceCodeFeature.logicalOperator);
                    break;
                case DIV:
                case MINUS:
                case PLUS:
                case MUL:
                case MOD:
                    mark(SourceCodeFeature.arithmeticOperator);
                    break;
                case SL:
                case SR:
                case USR:
                    mark(SourceCodeFeature.shiftOperator);
                    break;
                case INSTANCEOF:
                    mark(SourceCodeFeature.instanceofOperator);
                    break;
                case EQ:
                case GT:
                case GE:
                case NE:
                case LE:
                case LT:
                    mark(SourceCodeFeature.compareOperator);
                    break;
                default:
                    break;
            }
        }
        if (!method.getElements(new TypeFilter<>(CtUnaryOperator.class)).isEmpty()) {
            mark(SourceCodeFeature.unaryOperator);
        }
    }

    private void analyzeTypes(CtMethod<?> method) {
        for (CtTypeReference<?> type : method.getElements(new TypeFilter<>(CtTypeReference.class))) {
            String name = type.getSimpleName();
            if ("byte".equals(name)) mark(SourceCodeFeature.byteType);
            else if ("boolean".equals(name)) mark(SourceCodeFeature.booleanType);
            else if ("char".equals(name)) mark(SourceCodeFeature.charType);
            else if ("int".equals(name)) mark(SourceCodeFeature.integerType);
            else if ("float".equals(name)) mark(SourceCodeFeature.floatType);
            else if ("double".equals(name)) mark(SourceCodeFeature.doubleType);
            else if ("long".equals(name)) mark(SourceCodeFeature.longType);
            else if ("short".equals(name)) mark(SourceCodeFeature.shortType);
            if ("byte".equals(name) || "boolean".equals(name) || "char".equals(name)
                    || "int".equals(name) || "float".equals(name) || "double".equals(name)
                    || "long".equals(name) || "short".equals(name)) {
                mark(SourceCodeFeature.primitiveType);
            }

            if (WRAPPER_NAMES.contains(name)) mark(SourceCodeFeature.wrapperType);
            if (type instanceof CtArrayTypeReference) mark(SourceCodeFeature.arrayType);
        }

        for (CtLiteral<?> literal : method.getElements(new TypeFilter<>(CtLiteral.class))) {
            if (literal.getType() != null && "<nulltype>".equals(literal.getType().getSimpleName())) {
                mark(SourceCodeFeature.nullType);
            }
        }
    }

    private void analyzeArrays(CtMethod<?> method) {
        if (!method.getElements(new TypeFilter<>(CtArrayAccess.class)).isEmpty()) {
            mark(SourceCodeFeature.arrayAccess);
        }
    }

    private void analyzeLoop(CtMethod<?> method) {
        if (!method.getElements(new TypeFilter<>(CtLoop.class)).isEmpty()) {
            mark(SourceCodeFeature.loop);
        }
    }

    private void analyzeHierarchy(CtMethod<?> method) {
        CtType<?> owner = method.getDeclaringType();
        if (owner == null) return;

        CtTypeReference<?> superClass = owner.getSuperclass();
        if (superClass != null && !"java.lang.Object".equals(superClass.getQualifiedName())) {
            mark(SourceCodeFeature.hierarchy);
        }
        if (owner.getSuperInterfaces() != null && !owner.getSuperInterfaces().isEmpty()) {
            mark(SourceCodeFeature.hierarchy);
        }
    }

    private void analyzeMethodHandle(CtMethod<?> method) {
        for (CtTypeReference<?> ref : method.getElements(new TypeFilter<>(CtTypeReference.class))) {
            if (METHOD_HANDLE_TYPES.contains(ref.getQualifiedName())) {
                mark(SourceCodeFeature.methodHandle);
                return;
            }
        }
    }

    private void analyzeReflection(CtMethod<?> method) {
        for (CtTypeReference<?> ref : method.getElements(new TypeFilter<>(CtTypeReference.class))) {
            String qn = ref.getQualifiedName();
            if (qn != null && qn.startsWith("java.lang.reflect.")) {
                mark(SourceCodeFeature.reflection);
                return;
            }
        }
    }

    private void analyzeClassLoader(CtMethod<?> method) {
        CtType<?> owner = method.getDeclaringType();
        if (owner == null) return;
        try {
            CtTypeReference<?> classLoader = method.getFactory().Type().createReference("java.lang.ClassLoader");
            if (owner.isSubtypeOf(classLoader) && !"java.lang.ClassLoader".equals(owner.getQualifiedName())) {
                mark(SourceCodeFeature.customClassLoader);
            }
        } catch (Exception ignored) {
        }
    }

    private void analyzeTypeConversions(CtMethod<?> method) {
        for (CtExpression<?> expr : method.getElements(new TypeFilter<>(CtExpression.class))) {
            List<CtTypeReference<?>> casts = expr.getTypeCasts();
            if (casts != null && !casts.isEmpty()) {
                mark(SourceCodeFeature.TypeCast);
            }
        }

        for (CtAssignment<?, ?> assignment : method.getElements(new TypeFilter<>(CtAssignment.class))) {
            CtExpression<?> lhs = assignment.getAssigned();
            CtExpression<?> rhs = assignment.getAssignment();
            if (lhs == null || rhs == null || lhs.getType() == null || rhs.getType() == null) continue;
            if (!lhs.getType().getQualifiedName().equals(rhs.getType().getQualifiedName())) {
                mark(SourceCodeFeature.TypeCast);
            }
        }
    }
}
