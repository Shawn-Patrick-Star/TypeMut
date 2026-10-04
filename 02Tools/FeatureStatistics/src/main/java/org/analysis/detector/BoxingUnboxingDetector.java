package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.model.FeatureOccurrence;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtAbstractInvocation;
import spoon.reflect.code.CtArrayAccess;
import spoon.reflect.code.CtAssert;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtDo;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtForEach;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtOperatorAssignment;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtSwitchExpression;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.CtWhile;
import spoon.reflect.code.UnaryOperatorKind;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtWildcardReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detect Java boxing and unboxing conversions in all common conversion and
 * operator contexts. This detector deliberately models conversions rather than
 * merely checking whether primitive and wrapper type names occur in one case.
 */
public class BoxingUnboxingDetector implements FeatureDetector {

    private static final Map<String, String> WRAPPER_TO_PRIMITIVE = new HashMap<>();
    private static final Map<String, String> PRIMITIVE_TO_WRAPPER = new HashMap<>();
    private static final Set<String> NUMERIC_PRIMITIVES = new HashSet<>(Arrays.asList(
            "byte", "short", "char", "int", "long", "float", "double"
    ));

    static {
        register("java.lang.Boolean", "boolean");
        register("java.lang.Byte", "byte");
        register("java.lang.Short", "short");
        register("java.lang.Character", "char");
        register("java.lang.Integer", "int");
        register("java.lang.Long", "long");
        register("java.lang.Float", "float");
        register("java.lang.Double", "double");
    }

    private static void register(String wrapper, String primitive) {
        WRAPPER_TO_PRIMITIVE.put(wrapper, primitive);
        PRIMITIVE_TO_WRAPPER.put(primitive, wrapper);
    }

    @Override
    public SourceCodeFeature getTargetFeature() {
        return SourceCodeFeature.boxingUnboxing;
    }

    @Override
    public List<FeatureOccurrence> detect(CtType<?> type) {
        List<FeatureOccurrence> results = new ArrayList<>();
        detectExplicitCasts(type, results);
        detectAssignmentContexts(type, results);
        detectInvocationContexts(type, results);
        detectArrayContexts(type, results);
        detectEnhancedForContexts(type, results);
        detectLambdaReturnContexts(type, results);
        detectBooleanContexts(type, results);
        detectOperatorContexts(type, results);
        detectConditionalResultContexts(type, results);
        detectSwitchContexts(type, results);
        return results;
    }

    private void detectExplicitCasts(CtType<?> type, List<FeatureOccurrence> results) {
        for (CtExpression<?> expression : type.getElements(new TypeFilter<>(CtExpression.class))) {
            if (!hasSourcePosition(expression)) {
                continue;
            }
            List<CtTypeReference<?>> casts = expression.getTypeCasts();
            if (casts == null) {
                continue;
            }
            for (CtTypeReference<?> castType : casts) {
                if (castType != null && !castType.isImplicit()) {
                    if (!recordConversion(results, expression, expression.getType(), castType)) {
                        recordCastingUnboxing(results, expression, expression.getType(), castType);
                    }
                }
            }
        }
    }

    private void detectAssignmentContexts(CtType<?> type, List<FeatureOccurrence> results) {
        // CtVariable covers fields as well as local variables. Parameters have no
        // default expression, so they naturally drop out here.
        for (CtVariable<?> variable : type.getElements(new TypeFilter<>(CtVariable.class))) {
            CtExpression<?> initializer = variable.getDefaultExpression();
            if (hasSourcePosition(variable) && initializer != null && !hasExplicitCast(initializer)) {
                recordAssignmentConversion(results, variable, initializer, variable.getType());
            }
        }

        for (CtAssignment<?, ?> assignment : type.getElements(new TypeFilter<>(CtAssignment.class))) {
            if (!hasSourcePosition(assignment)) {
                continue;
            }
            CtExpression<?> lhs = assignment.getAssigned();
            CtExpression<?> rhs = assignment.getAssignment();
            if (lhs == null || rhs == null) {
                continue;
            }

            if (assignment instanceof CtOperatorAssignment) {
                // A compound assignment to a wrapper behaves like unbox, apply
                // the operator, then box the result back into the wrapper.
                String primitive = unboxedPrimitive(lhs.getType());
                if (primitive != null) {
                    recordUnboxing(results, assignment, lhs.getType(), primitive);
                    recordBoxing(results, assignment, primitive, lhs.getType());
                }
            } else if (!hasExplicitCast(rhs)) {
                recordAssignmentConversion(results, assignment, rhs, lhs.getType());
            }
        }

        for (CtReturn<?> returnStatement : type.getElements(new TypeFilter<>(CtReturn.class))) {
            if (!hasSourcePosition(returnStatement)) {
                continue;
            }
            CtExpression<?> expression = returnStatement.getReturnedExpression();
            CtMethod<?> method = returnStatement.getParent(CtMethod.class);
            if (expression != null && method != null && !hasExplicitCast(expression)) {
                recordAssignmentConversion(results, returnStatement, expression, method.getType());
            }
        }
    }

    private void detectInvocationContexts(CtType<?> type, List<FeatureOccurrence> results) {
        for (CtInvocation<?> invocation : type.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (hasSourcePosition(invocation)) {
                recordArguments(results, invocation, invocation.getArguments(), invocation.getExecutable());
            }
        }
        for (CtConstructorCall<?> call : type.getElements(new TypeFilter<>(CtConstructorCall.class))) {
            if (hasSourcePosition(call)) {
                recordArguments(results, call, call.getArguments(), call.getExecutable());
            }
        }
    }

    private void recordArguments(List<FeatureOccurrence> results,
                                 CtAbstractInvocation<?> invocation,
                                 List<CtExpression<?>> arguments,
                                 CtExecutableReference<?> executable) {
        if (executable == null || arguments == null || arguments.isEmpty()) {
            return;
        }

        List<CtTypeReference<?>> parameterTypes = executable.getParameters();
        boolean varArgs = false;
        try {
            CtExecutable<?> declaration = executable.getExecutableDeclaration();
            if (declaration != null && !declaration.getParameters().isEmpty()) {
                if (parameterTypes == null || parameterTypes.isEmpty()) {
                    parameterTypes = new ArrayList<>();
                    for (CtParameter<?> parameter : declaration.getParameters()) {
                        parameterTypes.add(parameter.getType());
                    }
                }
                varArgs = declaration.getParameters()
                        .get(declaration.getParameters().size() - 1)
                        .isVarArgs();
            }
        } catch (Exception ignored) {
            // noClasspath models may not resolve external executable declarations.
        }

        if (parameterTypes == null || parameterTypes.isEmpty()) {
            return;
        }

        // Executable references for external APIs can retain the trailing array
        // type while losing the declaration's varargs flag in noClasspath mode.
        // For valid Java, a non-array actual argument at that position proves the
        // array parameter is being used in variable-arity form.
        CtTypeReference<?> finalParameter = parameterTypes.get(parameterTypes.size() - 1);
        if (!varArgs && finalParameter instanceof CtArrayTypeReference &&
                arguments.size() >= parameterTypes.size()) {
            CtExpression<?> finalFixedArgument = arguments.get(parameterTypes.size() - 1);
            varArgs = arguments.size() > parameterTypes.size() ||
                    !(finalFixedArgument.getType() instanceof CtArrayTypeReference);
        }

        for (int i = 0; i < arguments.size(); i++) {
            int parameterIndex = Math.min(i, parameterTypes.size() - 1);
            if (i >= parameterTypes.size() && !varArgs) {
                break;
            }

            CtTypeReference<?> targetType = parameterTypes.get(parameterIndex);
            CtExpression<?> argument = arguments.get(i);
            if (varArgs && parameterIndex == parameterTypes.size() - 1 &&
                    targetType instanceof CtArrayTypeReference &&
                    !(argument.getType() instanceof CtArrayTypeReference)) {
                targetType = ((CtArrayTypeReference<?>) targetType).getComponentType();
            }
            if (!hasExplicitCast(argument)) {
                recordConversion(results, invocation, argument.getType(), targetType);
            }
        }
    }

    private void detectArrayContexts(CtType<?> type, List<FeatureOccurrence> results) {
        for (CtNewArray<?> array : type.getElements(new TypeFilter<>(CtNewArray.class))) {
            if (!hasSourcePosition(array) || !(array.getType() instanceof CtArrayTypeReference)) {
                continue;
            }
            CtTypeReference<?> componentType =
                    ((CtArrayTypeReference<?>) array.getType()).getComponentType();
            for (CtExpression<?> element : array.getElements()) {
                if (!hasExplicitCast(element)) {
                    recordAssignmentConversion(results, array, element, componentType);
                }
            }
            for (CtExpression<?> dimension : array.getDimensionExpressions()) {
                recordUnboxingUse(results, array, dimension);
            }
        }

        for (CtArrayAccess<?, ?> access : type.getElements(new TypeFilter<>(CtArrayAccess.class))) {
            if (hasSourcePosition(access)) {
                recordUnboxingUse(results, access, access.getIndexExpression());
            }
        }
    }

    private void detectEnhancedForContexts(CtType<?> type, List<FeatureOccurrence> results) {
        for (CtForEach forEach : type.getElements(new TypeFilter<>(CtForEach.class))) {
            if (!hasSourcePosition(forEach) || forEach.getExpression() == null || forEach.getVariable() == null) {
                continue;
            }
            CtTypeReference<?> sourceType = forEach.getExpression().getType();
            CtTypeReference<?> elementType = null;
            if (sourceType instanceof CtArrayTypeReference) {
                elementType = ((CtArrayTypeReference<?>) sourceType).getComponentType();
            } else if (sourceType != null && sourceType.getActualTypeArguments() != null &&
                    !sourceType.getActualTypeArguments().isEmpty()) {
                elementType = sourceType.getActualTypeArguments().get(0);
            }
            recordConversion(results, forEach, elementType, forEach.getVariable().getType());
        }
    }

    private void detectLambdaReturnContexts(CtType<?> type, List<FeatureOccurrence> results) {
        for (CtLambda<?> lambda : type.getElements(new TypeFilter<>(CtLambda.class))) {
            if (!hasSourcePosition(lambda)) {
                continue;
            }
            try {
                CtMethod<?> functionalMethod = lambda.getOverriddenMethod();
                if (functionalMethod == null) {
                    continue;
                }
                CtTypeReference<?> returnType = resolveFunctionalReturnType(lambda, functionalMethod);
                if (lambda.getExpression() != null) {
                    recordAssignmentConversion(results, lambda, lambda.getExpression(), returnType);
                } else if (lambda.getBody() != null) {
                    for (CtReturn<?> returned : lambda.getBody().getElements(new TypeFilter<>(CtReturn.class))) {
                        CtExpression<?> expression = returned.getReturnedExpression();
                        if (expression != null && returned.getParent(CtLambda.class) == lambda) {
                            recordAssignmentConversion(results, returned, expression, returnType);
                        }
                    }
                }
            } catch (Exception ignored) {
                // A noClasspath model may not resolve the target functional method.
            }
        }
    }

    private CtTypeReference<?> resolveFunctionalReturnType(CtLambda<?> lambda,
                                                            CtMethod<?> functionalMethod) {
        CtTypeReference<?> returnType = functionalMethod.getType();
        if (!(returnType instanceof CtTypeParameterReference) || lambda.getType() == null ||
                lambda.getType().getActualTypeArguments() == null) {
            return returnType;
        }
        try {
            CtType<?> functionalInterface = functionalMethod.getDeclaringType();
            if (functionalInterface == null) {
                functionalInterface = lambda.getType().getTypeDeclaration();
            }
            if (functionalInterface == null) {
                return returnType;
            }
            List<spoon.reflect.declaration.CtTypeParameter> formals =
                    functionalInterface.getFormalCtTypeParameters();
            List<CtTypeReference<?>> actuals = lambda.getType().getActualTypeArguments();
            for (int i = 0; i < formals.size() && i < actuals.size(); i++) {
                if (formals.get(i).getSimpleName().equals(returnType.getSimpleName())) {
                    return actuals.get(i);
                }
            }
        } catch (Exception ignored) {
            // Keep the type parameter; it still correctly proves boxing.
        }
        return returnType;
    }

    private void detectBooleanContexts(CtType<?> type, List<FeatureOccurrence> results) {
        for (CtIf statement : type.getElements(new TypeFilter<>(CtIf.class))) {
            recordBooleanUnboxing(results, statement, statement.getCondition());
        }
        for (CtWhile loop : type.getElements(new TypeFilter<>(CtWhile.class))) {
            recordBooleanUnboxing(results, loop, loop.getLoopingExpression());
        }
        for (CtDo loop : type.getElements(new TypeFilter<>(CtDo.class))) {
            recordBooleanUnboxing(results, loop, loop.getLoopingExpression());
        }
        for (CtFor loop : type.getElements(new TypeFilter<>(CtFor.class))) {
            recordBooleanUnboxing(results, loop, loop.getExpression());
        }
        for (CtAssert<?> statement : type.getElements(new TypeFilter<>(CtAssert.class))) {
            recordBooleanUnboxing(results, statement, statement.getAssertExpression());
        }
        for (CtConditional<?> conditional : type.getElements(new TypeFilter<>(CtConditional.class))) {
            recordBooleanUnboxing(results, conditional, conditional.getCondition());
        }
    }

    private void detectOperatorContexts(CtType<?> type, List<FeatureOccurrence> results) {
        for (CtUnaryOperator<?> operator : type.getElements(new TypeFilter<>(CtUnaryOperator.class))) {
            if (!hasSourcePosition(operator) || operator.getOperand() == null) {
                continue;
            }
            UnaryOperatorKind kind = operator.getKind();
            recordUnboxingUse(results, operator, operator.getOperand());
            if (kind == UnaryOperatorKind.PREINC || kind == UnaryOperatorKind.PREDEC ||
                    kind == UnaryOperatorKind.POSTINC || kind == UnaryOperatorKind.POSTDEC) {
                String primitive = unboxedPrimitive(operator.getOperand().getType());
                if (primitive != null) {
                    recordBoxing(results, operator, primitive, operator.getOperand().getType());
                }
            }
        }

        for (CtBinaryOperator<?> operator : type.getElements(new TypeFilter<>(CtBinaryOperator.class))) {
            if (!hasSourcePosition(operator)) {
                continue;
            }
            CtExpression<?> left = operator.getLeftHandOperand();
            CtExpression<?> right = operator.getRightHandOperand();
            BinaryOperatorKind kind = operator.getKind();

            if (kind == BinaryOperatorKind.INSTANCEOF) {
                continue;
            }
            if (kind == BinaryOperatorKind.PLUS && operator.getType() != null &&
                    "java.lang.String".equals(operator.getType().getQualifiedName())) {
                // String concatenation converts wrappers to strings without an
                // unboxing conversion.
                continue;
            }
            if ((kind == BinaryOperatorKind.EQ || kind == BinaryOperatorKind.NE) &&
                    left != null && right != null) {
                // Wrapper-wrapper equality is reference comparison. Unboxing is
                // required only when the opposite operand is primitive.
                if (right.getType() != null && right.getType().isPrimitive()) {
                    recordUnboxingUse(results, operator, left);
                }
                if (left.getType() != null && left.getType().isPrimitive()) {
                    recordUnboxingUse(results, operator, right);
                }
                continue;
            }

            recordUnboxingUse(results, operator, left);
            recordUnboxingUse(results, operator, right);
        }
    }

    private void detectConditionalResultContexts(CtType<?> type, List<FeatureOccurrence> results) {
        for (CtConditional<?> conditional : type.getElements(new TypeFilter<>(CtConditional.class))) {
            if (!hasSourcePosition(conditional)) {
                continue;
            }
            CtTypeReference<?> resultType = conditional.getType();
            CtExpression<?> thenExpression = conditional.getThenExpression();
            CtExpression<?> elseExpression = conditional.getElseExpression();
            if (thenExpression != null) {
                recordConversion(results, conditional, thenExpression.getType(), resultType);
            }
            if (elseExpression != null) {
                recordConversion(results, conditional, elseExpression.getType(), resultType);
            }
        }
    }

    private void detectSwitchContexts(CtType<?> type, List<FeatureOccurrence> results) {
        for (CtSwitch<?> statement : type.getElements(new TypeFilter<>(CtSwitch.class))) {
            if (hasSourcePosition(statement)) {
                recordUnboxingUse(results, statement, statement.getSelector());
            }
        }
        for (CtSwitchExpression<?, ?> expression :
                type.getElements(new TypeFilter<>(CtSwitchExpression.class))) {
            if (hasSourcePosition(expression)) {
                recordUnboxingUse(results, expression, expression.getSelector());
            }
        }
    }

    private void recordBooleanUnboxing(List<FeatureOccurrence> results,
                                       CtElement location,
                                       CtExpression<?> expression) {
        if (expression != null && "boolean".equals(unboxedPrimitive(expression.getType()))) {
            recordUnboxing(results, location, expression.getType(), "boolean");
        }
    }

    private void recordUnboxingUse(List<FeatureOccurrence> results,
                                   CtElement location,
                                   CtExpression<?> expression) {
        if (expression == null) {
            return;
        }
        String primitive = unboxedPrimitive(expression.getType());
        if (primitive != null) {
            recordUnboxing(results, location, expression.getType(), primitive);
        }
    }

    private boolean recordConversion(List<FeatureOccurrence> results,
                                     CtElement location,
                                     CtTypeReference<?> sourceType,
                                     CtTypeReference<?> targetType) {
        if (sourceType == null || targetType == null) {
            return false;
        }
        if (isBoxingConversion(sourceType, targetType)) {
            recordBoxing(results, location, sourceType.getSimpleName(), targetType);
            return true;
        } else if (isUnboxingConversion(sourceType, targetType)) {
            recordUnboxing(results, location, sourceType, targetType.getSimpleName());
            return true;
        }
        return false;
    }

    private void recordAssignmentConversion(List<FeatureOccurrence> results,
                                            CtElement location,
                                            CtExpression<?> sourceExpression,
                                            CtTypeReference<?> targetType) {
        if (sourceExpression == null || targetType == null ||
                recordConversion(results, location, sourceExpression.getType(), targetType)) {
            return;
        }
        if (isNarrowConstantBoxing(sourceExpression, targetType)) {
            recordBoxing(results, location, "int", targetType);
        }
    }

    /** Assignment conversion permits an int constant to narrow and then box. */
    private boolean isNarrowConstantBoxing(CtExpression<?> expression,
                                           CtTypeReference<?> targetType) {
        if (expression.getType() == null || !expression.getType().isPrimitive() ||
                !"int".equals(expression.getType().getSimpleName())) {
            return false;
        }
        String target = safeQualifiedName(targetType);
        if (!"java.lang.Byte".equals(target) && !"java.lang.Short".equals(target) &&
                !"java.lang.Character".equals(target)) {
            return false;
        }
        try {
            CtElement evaluated = expression.partiallyEvaluate();
            if (!(evaluated instanceof CtLiteral)) {
                return false;
            }
            Object value = ((CtLiteral<?>) evaluated).getValue();
            if (!(value instanceof Number)) {
                return false;
            }
            long number = ((Number) value).longValue();
            if ("java.lang.Byte".equals(target)) return number >= Byte.MIN_VALUE && number <= Byte.MAX_VALUE;
            if ("java.lang.Short".equals(target)) return number >= Short.MIN_VALUE && number <= Short.MAX_VALUE;
            return number >= Character.MIN_VALUE && number <= Character.MAX_VALUE;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Casting may narrow a reference to a wrapper and immediately unbox it. */
    private void recordCastingUnboxing(List<FeatureOccurrence> results,
                                       CtElement location,
                                       CtTypeReference<?> sourceType,
                                       CtTypeReference<?> targetType) {
        if (sourceType == null || targetType == null || sourceType.isPrimitive() ||
                !targetType.isPrimitive()) {
            return;
        }
        String wrapperName = PRIMITIVE_TO_WRAPPER.get(targetType.getSimpleName());
        if (wrapperName == null) {
            return;
        }
        try {
            CtTypeReference<?> wrapper = targetType.getFactory().Type().createReference(wrapperName);
            if (wrapper.isSubtypeOf(sourceType)) {
                recordUnboxing(results, location, sourceType, targetType.getSimpleName());
            }
        } catch (Exception ignored) {
            if ("java.lang.Object".equals(safeQualifiedName(sourceType))) {
                recordUnboxing(results, location, sourceType, targetType.getSimpleName());
            }
        }
    }

    private boolean isBoxingConversion(CtTypeReference<?> sourceType,
                                       CtTypeReference<?> targetType) {
        if (!sourceType.isPrimitive() || targetType.isPrimitive()) {
            return false;
        }
        String wrapperName = PRIMITIVE_TO_WRAPPER.get(sourceType.getSimpleName());
        if (wrapperName == null) {
            return false;
        }
        if (targetType instanceof CtTypeParameterReference) {
            // Generic type arguments cannot be primitive; inference boxes the
            // primitive argument before it is passed as T.
            return true;
        }
        if (wrapperName.equals(safeQualifiedName(targetType))) {
            return true;
        }
        try {
            CtTypeReference<?> wrapper = sourceType.getFactory().Type().createReference(wrapperName);
            return wrapper.isSubtypeOf(targetType);
        } catch (Exception ignored) {
            // java.lang.Object is the most common boxing + widening-reference
            // target and remains reliable in a noClasspath model.
            return "java.lang.Object".equals(safeQualifiedName(targetType));
        }
    }

    private boolean isUnboxingConversion(CtTypeReference<?> sourceType,
                                         CtTypeReference<?> targetType) {
        if (sourceType.isPrimitive() || !targetType.isPrimitive()) {
            return false;
        }
        String sourcePrimitive = unboxedPrimitive(sourceType);
        String targetPrimitive = targetType.getSimpleName();
        return sourcePrimitive != null && canWidenPrimitive(sourcePrimitive, targetPrimitive);
    }

    private String unboxedPrimitive(CtTypeReference<?> type) {
        if (type == null || type.isPrimitive()) {
            return null;
        }
        String primitive = WRAPPER_TO_PRIMITIVE.get(safeQualifiedName(type));
        if (primitive != null) {
            return primitive;
        }
        if (type instanceof CtTypeParameterReference) {
            CtTypeReference<?> bound = ((CtTypeParameterReference) type).getBoundingType();
            if (bound != null && bound != type) {
                return unboxedPrimitive(bound);
            }
        }
        if (type instanceof CtWildcardReference) {
            CtTypeReference<?> bound = ((CtWildcardReference) type).getBoundingType();
            if (bound != null && bound != type && !bound.isImplicit()) {
                return unboxedPrimitive(bound);
            }
        }
        return null;
    }

    private boolean canWidenPrimitive(String source, String target) {
        if (source.equals(target)) {
            return true;
        }
        if (!NUMERIC_PRIMITIVES.contains(source) || !NUMERIC_PRIMITIVES.contains(target)) {
            return false;
        }
        if ("byte".equals(source)) return Arrays.asList("short", "int", "long", "float", "double").contains(target);
        if ("short".equals(source)) return Arrays.asList("int", "long", "float", "double").contains(target);
        if ("char".equals(source)) return Arrays.asList("int", "long", "float", "double").contains(target);
        if ("int".equals(source)) return Arrays.asList("long", "float", "double").contains(target);
        if ("long".equals(source)) return Arrays.asList("float", "double").contains(target);
        return "float".equals(source) && "double".equals(target);
    }

    private void recordBoxing(List<FeatureOccurrence> results,
                              CtElement location,
                              String sourcePrimitive,
                              CtTypeReference<?> targetType) {
        addOccurrence(results, location, "boxing:" + sourcePrimitive + "->" + displayName(targetType));
    }

    private void recordUnboxing(List<FeatureOccurrence> results,
                                CtElement location,
                                CtTypeReference<?> sourceType,
                                String targetPrimitive) {
        addOccurrence(results, location, "unboxing:" + displayName(sourceType) + "->" + targetPrimitive);
    }

    private void addOccurrence(List<FeatureOccurrence> results, CtElement location, String detail) {
        results.add(new FeatureOccurrence(
                SourceCodeFeature.boxingUnboxing,
                findMeaningfulStatement(location),
                detail
        ));
    }

    private String displayName(CtTypeReference<?> type) {
        if (type == null) {
            return "Unknown";
        }
        return type.getSimpleName();
    }

    private String safeQualifiedName(CtTypeReference<?> type) {
        if (type == null) {
            return null;
        }
        try {
            return type.getQualifiedName();
        } catch (Exception ignored) {
            return type.getSimpleName();
        }
    }

    private boolean hasExplicitCast(CtExpression<?> expression) {
        if (expression == null || expression.getTypeCasts() == null) {
            return false;
        }
        for (CtTypeReference<?> cast : expression.getTypeCasts()) {
            if (cast != null && !cast.isImplicit()) {
                return true;
            }
        }
        return false;
    }
}
