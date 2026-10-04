package org.fuzz.operator.impl.block;

import org.fuzz.core.MutationContext;
import org.fuzz.util.TypeUtils;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLoop;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class IfConditionGenerator {
    private static final List<BinaryOperatorKind> NUMERIC_OPERATORS = List.of(
            BinaryOperatorKind.LT,
            BinaryOperatorKind.LE,
            BinaryOperatorKind.GT,
            BinaryOperatorKind.GE,
            BinaryOperatorKind.EQ,
            BinaryOperatorKind.NE);
    private static final List<BinaryOperatorKind> EQUALITY_OPERATORS = List.of(
            BinaryOperatorKind.EQ,
            BinaryOperatorKind.NE);
    private static final Set<String> NUMERIC_PRIMITIVES = Set.of(
            "byte", "short", "char", "int", "long", "float", "double");
    private static final Set<String> BOXED_NUMERIC_TYPES = Set.of(
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Character",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double");

    GeneratedIfCondition generate(
            List<AbstractBlockOperator.VariableInfo> variables,
            CtLoop loop,
            MutationContext context) {
        List<AbstractBlockOperator.VariableInfo> eligible = eligibleVariables(variables);
        if (eligible.isEmpty()) {
            return null;
        }

        if (loop == null) {
            CtExpression<Boolean> expression = buildCondition(eligible, eligible, context);
            return expression == null ? null : new GeneratedIfCondition(expression, IfConditionMode.GENERAL);
        }

        List<AbstractBlockOperator.VariableInfo> invariant = new ArrayList<>();
        List<AbstractBlockOperator.VariableInfo> variant = new ArrayList<>();
        for (AbstractBlockOperator.VariableInfo variable : eligible) {
            if (isLoopVariant(variable, loop)) {
                variant.add(variable);
            } else {
                invariant.add(variable);
            }
        }

        IfConditionMode mode;
        if (!invariant.isEmpty() && !variant.isEmpty()) {
            mode = context.getRandom().nextBoolean()
                    ? IfConditionMode.LOOP_INVARIANT
                    : IfConditionMode.LOOP_VARIANT;
        } else if (!invariant.isEmpty()) {
            mode = IfConditionMode.LOOP_INVARIANT;
        } else if (!variant.isEmpty()) {
            mode = IfConditionMode.LOOP_VARIANT;
        } else {
            return null;
        }

        List<AbstractBlockOperator.VariableInfo> leftCandidates = mode == IfConditionMode.LOOP_INVARIANT
                ? invariant
                : variant;
        List<AbstractBlockOperator.VariableInfo> rightCandidates = mode == IfConditionMode.LOOP_INVARIANT
                ? invariant
                : eligible;
        CtExpression<Boolean> expression = buildCondition(leftCandidates, rightCandidates, context);
        if (expression == null) {
            IfConditionMode fallbackMode = mode == IfConditionMode.LOOP_INVARIANT
                    ? IfConditionMode.LOOP_VARIANT
                    : IfConditionMode.LOOP_INVARIANT;
            List<AbstractBlockOperator.VariableInfo> fallbackLeft = fallbackMode == IfConditionMode.LOOP_INVARIANT
                    ? invariant
                    : variant;
            List<AbstractBlockOperator.VariableInfo> fallbackRight = fallbackMode == IfConditionMode.LOOP_INVARIANT
                    ? invariant
                    : eligible;
            expression = buildCondition(fallbackLeft, fallbackRight, context);
            mode = fallbackMode;
        }
        return expression == null ? null : new GeneratedIfCondition(expression, mode);
    }

    private List<AbstractBlockOperator.VariableInfo> eligibleVariables(
            List<AbstractBlockOperator.VariableInfo> variables) {
        Map<String, AbstractBlockOperator.VariableInfo> byName = new LinkedHashMap<>();
        for (AbstractBlockOperator.VariableInfo variable : variables) {
            if (variable == null || variable.declaration == null || !isSupportedType(variable.type)) {
                continue;
            }
            if (variable.declaration instanceof CtField<?> field
                    && field.hasModifier(ModifierKind.VOLATILE)) {
                continue;
            }
            byName.putIfAbsent(variable.name, variable);
        }
        return new ArrayList<>(byName.values());
    }

    private CtExpression<Boolean> buildCondition(
            List<AbstractBlockOperator.VariableInfo> leftCandidates,
            List<AbstractBlockOperator.VariableInfo> rightCandidates,
            MutationContext context) {
        if (leftCandidates.isEmpty()) {
            return null;
        }

        List<AbstractBlockOperator.VariableInfo> shuffled = new ArrayList<>(leftCandidates);
        Collections.shuffle(shuffled, context.getRandom());
        for (AbstractBlockOperator.VariableInfo left : shuffled) {
            List<AbstractBlockOperator.VariableInfo> compatible = rightCandidates.stream()
                    .filter(right -> right.declaration != left.declaration)
                    .filter(right -> areComparable(left.type, right.type))
                    .toList();
            AbstractBlockOperator.VariableInfo right = compatible.isEmpty()
                    ? null
                    : compatible.get(context.getRandom().nextInt(compatible.size()));
            CtExpression<Boolean> condition = createComparison(left, right, context);
            if (condition != null) {
                return condition;
            }
        }
        return null;
    }

    private CtExpression<Boolean> createComparison(
            AbstractBlockOperator.VariableInfo left,
            AbstractBlockOperator.VariableInfo right,
            MutationContext context) {
        Factory factory = context.getFactory();
        ValueKind kind = valueKind(left.type);
        List<BinaryOperatorKind> operators = kind == ValueKind.NUMERIC
                ? NUMERIC_OPERATORS
                : EQUALITY_OPERATORS;
        BinaryOperatorKind operator = operators.get(context.getRandom().nextInt(operators.size()));

        CtExpression<?> leftOperand = comparisonOperand(left, factory);
        CtExpression<?> rightOperand = right == null
                ? constantOperand(left.type, kind, context)
                : comparisonOperand(right, factory);
        if (leftOperand == null || rightOperand == null) {
            return null;
        }

        CtExpression<Boolean> comparison = booleanBinary(leftOperand, rightOperand, operator, factory);
        List<AbstractBlockOperator.VariableInfo> guarded = new ArrayList<>();
        if (requiresUnboxingGuard(left.type)) {
            guarded.add(left);
        }
        if (right != null && requiresUnboxingGuard(right.type)) {
            guarded.add(right);
        }

        CtExpression<Boolean> result = comparison;
        for (int i = guarded.size() - 1; i >= 0; i--) {
            CtExpression<Boolean> nonNull = booleanBinary(
                    variableRead(guarded.get(i), factory),
                    factory.Code().createLiteral(null),
                    BinaryOperatorKind.NE,
                    factory);
            result = booleanBinary(nonNull, result, BinaryOperatorKind.AND, factory);
        }
        return result;
    }

    private CtExpression<?> comparisonOperand(
            AbstractBlockOperator.VariableInfo variable,
            Factory factory) {
        CtExpression<?> read = variableRead(variable, factory);
        if (requiresUnboxingGuard(variable.type)) {
            String primitive = TypeUtils.getBoxingCounterpart(variable.type);
            if (primitive == null) {
                return null;
            }
            read.addTypeCast(factory.Type().createReference(primitive));
        }
        return read;
    }

    private CtExpression<?> constantOperand(
            CtTypeReference<?> leftType,
            ValueKind kind,
            MutationContext context) {
        Factory factory = context.getFactory();
        if (kind == ValueKind.REFERENCE) {
            return factory.Code().createLiteral(null);
        }
        if (kind == ValueKind.BOOLEAN) {
            return factory.Code().createLiteral(context.getRandom().nextBoolean());
        }

        String primitiveName = primitiveName(leftType);
        int value = context.getRandom().nextInt(33) - 16;
        return switch (primitiveName) {
            case "long" -> factory.Code().createLiteral((long) value);
            case "float" -> factory.Code().createLiteral(value + 0.25F);
            case "double" -> factory.Code().createLiteral(value + 0.25D);
            case "char" -> factory.Code().createLiteral((char) (context.getRandom().nextInt(95) + 32));
            default -> factory.Code().createLiteral(value);
        };
    }

    private boolean isLoopVariant(AbstractBlockOperator.VariableInfo variable, CtLoop loop) {
        if (variable.declaration instanceof CtField<?> field) {
            return !field.hasModifier(ModifierKind.FINAL);
        }
        return isDescendantOf(variable.declaration, loop) || isWrittenInLoop(variable.declaration, loop);
    }

    private boolean isDescendantOf(CtElement element, CtElement ancestor) {
        CtElement current = element;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean isWrittenInLoop(CtVariable<?> declaration, CtLoop loop) {
        return loop.getElements(new TypeFilter<>(CtVariableWrite.class)).stream()
                .anyMatch(write -> sameDeclaration(write.getVariable(), declaration));
    }

    private boolean sameDeclaration(CtVariableReference<?> reference, CtVariable<?> declaration) {
        if (reference == null) {
            return false;
        }
        CtVariable<?> resolved = reference.getDeclaration();
        return resolved == declaration || (resolved != null && resolved.equals(declaration));
    }

    private boolean areComparable(CtTypeReference<?> left, CtTypeReference<?> right) {
        ValueKind leftKind = valueKind(left);
        ValueKind rightKind = valueKind(right);
        if (leftKind != rightKind) {
            return false;
        }
        if (leftKind != ValueKind.REFERENCE) {
            return true;
        }
        if (left.equals(right) || left.getQualifiedName().equals(right.getQualifiedName())) {
            return true;
        }
        try {
            return left.isSubtypeOf(right) || right.isSubtypeOf(left);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isSupportedType(CtTypeReference<?> type) {
        return valueKind(type) != ValueKind.UNSUPPORTED;
    }

    private ValueKind valueKind(CtTypeReference<?> type) {
        if (type == null || "void".equals(type.getSimpleName())) {
            return ValueKind.UNSUPPORTED;
        }
        String qualifiedName = type.getQualifiedName();
        if ((type.isPrimitive() && NUMERIC_PRIMITIVES.contains(type.getSimpleName()))
                || BOXED_NUMERIC_TYPES.contains(qualifiedName)) {
            return ValueKind.NUMERIC;
        }
        if ((type.isPrimitive() && "boolean".equals(type.getSimpleName()))
                || "java.lang.Boolean".equals(qualifiedName)) {
            return ValueKind.BOOLEAN;
        }
        return type.isPrimitive() ? ValueKind.UNSUPPORTED : ValueKind.REFERENCE;
    }

    private boolean requiresUnboxingGuard(CtTypeReference<?> type) {
        String qualifiedName = type.getQualifiedName();
        return BOXED_NUMERIC_TYPES.contains(qualifiedName)
                || "java.lang.Boolean".equals(qualifiedName);
    }

    private String primitiveName(CtTypeReference<?> type) {
        if (type.isPrimitive()) {
            return type.getSimpleName();
        }
        return TypeUtils.getBoxingCounterpart(type);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CtExpression<?> variableRead(
            AbstractBlockOperator.VariableInfo variable,
            Factory factory) {
        boolean isStatic = variable.declaration instanceof CtField<?> field && field.isStatic();
        return factory.Code().createVariableRead(
                (CtVariableReference) variable.declaration.getReference(),
                isStatic);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CtExpression<Boolean> booleanBinary(
            CtExpression<?> left,
            CtExpression<?> right,
            BinaryOperatorKind operator,
            Factory factory) {
        CtBinaryOperator<Boolean> binary = factory.Code().createBinaryOperator(
                (CtExpression) left,
                (CtExpression) right,
                operator);
        binary.setType(factory.Type().booleanPrimitiveType());
        return binary;
    }

    private enum ValueKind {
        NUMERIC,
        BOOLEAN,
        REFERENCE,
        UNSUPPORTED
    }
}
