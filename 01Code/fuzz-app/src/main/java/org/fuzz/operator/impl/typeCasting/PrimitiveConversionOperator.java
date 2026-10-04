package org.fuzz.operator.impl.typeCasting;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.core.MutationContext;
import org.fuzz.util.TypeUtils;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.List;

                                                                                
@Slf4j
public class PrimitiveConversionOperator extends AbstractTypeCastOperator {

    @Override
    public String getName() {
        return "PrimitiveConversionOperator";
    }

    @Override
    protected CtTypeReference<?> calculateTargetType(CtExpression candidate, MutationContext context) {
        CtTypeReference<?> currentType = candidate.getType();
        if (currentType == null || !currentType.isPrimitive()) {
            return null;
        }

        List<CtTypeReference<?>> narrowingTargets = createNarrowingTargets(candidate, currentType, context);
        List<CtTypeReference<?>> wideningTargets = createWideningTargets(candidate, currentType, context);
        if (narrowingTargets.isEmpty() && wideningTargets.isEmpty()) {
            return null;
        }

        List<ConversionDirection> directions = new ArrayList<>(2);
        if (!narrowingTargets.isEmpty()) {
            directions.add(ConversionDirection.NARROWING);
        }
        if (!wideningTargets.isEmpty()) {
            directions.add(ConversionDirection.WIDENING);
        }

        ConversionDirection direction = directions.get(context.getRandom().nextInt(directions.size()));
        List<CtTypeReference<?>> targets = direction == ConversionDirection.NARROWING
                ? narrowingTargets
                : wideningTargets;
        CtTypeReference<?> target = targets.get(context.getRandom().nextInt(targets.size()));
        log.debug("[Mutation {}] selected {} conversion: {} -> {}",
                getName(), direction, currentType.getSimpleName(), target.getSimpleName());
        return target;
    }

    private List<CtTypeReference<?>> createNarrowingTargets(
            CtExpression<?> candidate,
            CtTypeReference<?> currentType,
            MutationContext context) {
        List<CtTypeReference<?>> targets = new ArrayList<>();
        for (String targetName : TypeUtils.getNarrowingTargets(currentType.getSimpleName())) {
            CtTypeReference<?> target = context.getFactory().Type().createReference(targetName);
            if (!isUnsafeBoundaryNarrowing(candidate, target)) {
                targets.add(target);
            }
        }
        return targets;
    }

    private List<CtTypeReference<?>> createWideningTargets(
            CtExpression<?> candidate,
            CtTypeReference<?> currentType,
            MutationContext context) {
        List<CtTypeReference<?>> targets = new ArrayList<>();
        for (String targetName : TypeUtils.getWideningTargets(currentType.getSimpleName())) {
            CtTypeReference<?> target = context.getFactory().Type().createReference(targetName);
            if (isWideningContextCompatible(candidate, target)) {
                targets.add(target);
            }
        }
        return targets;
    }

    private boolean isWideningContextCompatible(CtExpression<?> candidate, CtTypeReference<?> newType) {
        CtExpression<?> topLevelExpression = getTopLevelExpression(candidate);
        CtTypeReference<?> expectedType = getExpectedType(topLevelExpression);
        if (expectedType == null || "java.lang.String".equals(expectedType.getQualifiedName())) {
            return true;
        }
        if (newType.equals(expectedType)) {
            return true;
        }
        if (!expectedType.isPrimitive() || !newType.isPrimitive()) {
            return false;
        }
        return TypeUtils.getWideningTargets(newType.getSimpleName())
                .contains(expectedType.getSimpleName());
    }

    private CtExpression<?> getTopLevelExpression(CtExpression<?> expression) {
        CtElement parent = expression.getParent();
        if (parent instanceof CtBinaryOperator<?> || parent instanceof CtUnaryOperator<?>) {
            return getTopLevelExpression((CtExpression<?>) parent);
        }
        return expression;
    }

    private enum ConversionDirection {
        NARROWING,
        WIDENING
    }
}
