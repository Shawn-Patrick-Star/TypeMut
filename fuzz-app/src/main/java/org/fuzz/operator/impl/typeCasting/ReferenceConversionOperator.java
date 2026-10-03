package org.fuzz.operator.impl.typeCasting;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.core.MutationContext;
import org.fuzz.util.TypeUtils;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtThrow;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;

                                                                        
@Slf4j
public class ReferenceConversionOperator extends AbstractTypeCastOperator {

    @Override
    public String getName() {
        return "ReferenceConversionOperator";
    }

    @Override
    protected CtTypeReference<?> calculateTargetType(CtExpression candidate, MutationContext context) {
        CtTypeReference<?> currentType = candidate.getType();
        if (currentType == null || currentType.isPrimitive() || currentType.isArray()) {
            return null;
        }

        CtTypeReference<?> expectedType = getExpectedType(candidate);
        if (expectedType == null && isInvocationArgument(candidate)) {
            return null;
        }

        List<CtTypeReference<?>> downcastTargets = createDowncastTargets(
                candidate, currentType, expectedType, context);
        List<CtTypeReference<?>> upcastTargets = createUpcastTargets(
                candidate, currentType, expectedType, context);
        if (downcastTargets.isEmpty() && upcastTargets.isEmpty()) {
            return null;
        }

        List<ConversionDirection> directions = new ArrayList<>(2);
        if (!downcastTargets.isEmpty()) {
            directions.add(ConversionDirection.DOWNCAST);
        }
        if (!upcastTargets.isEmpty()) {
            directions.add(ConversionDirection.UPCAST);
        }

        ConversionDirection direction = directions.get(context.getRandom().nextInt(directions.size()));
        List<CtTypeReference<?>> targets = direction == ConversionDirection.DOWNCAST
                ? downcastTargets
                : upcastTargets;
        CtTypeReference<?> target = targets.get(context.getRandom().nextInt(targets.size()));
        log.debug("[Mutation {}] selected {} conversion: {} -> {}",
                getName(), direction, currentType.getQualifiedName(), target.getQualifiedName());
        return target;
    }

    private List<CtTypeReference<?>> createDowncastTargets(
            CtExpression<?> candidate,
            CtTypeReference<?> currentType,
            CtTypeReference<?> expectedType,
            MutationContext context) {
        if (TypeUtils.isFinal(currentType)) {
            return List.of();
        }

        List<CtType<?>> modelTypes = candidate.getFactory().getModel()
                .getElements(new TypeFilter<>(CtType.class));
        List<CtTypeReference<?>> candidates = modelTypes.stream()
                .map(CtType::getReference)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if ("java.lang.Object".equals(currentType.getQualifiedName())) {
            candidates.add(context.getFactory().Type().createReference(String.class));
            candidates.add(context.getFactory().Type().createReference(Integer.class));
            candidates.add(context.getFactory().Type().createReference(Boolean.class));
        }

        return candidates.stream()
                .filter(target -> isValidDowncastTarget(target, currentType, expectedType))
                .distinct()
                .toList();
    }

    private boolean isValidDowncastTarget(
            CtTypeReference<?> target,
            CtTypeReference<?> currentType,
            CtTypeReference<?> expectedType) {
        try {
            return !target.equals(currentType)
                    && target.isSubtypeOf(currentType)
                    && (expectedType == null || target.isSubtypeOf(expectedType))
                    && isSafeDowncastType(target);
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<CtTypeReference<?>> createUpcastTargets(
            CtExpression<?> candidate,
            CtTypeReference<?> currentType,
            CtTypeReference<?> expectedType,
            MutationContext context) {
        if (candidate.getParent() instanceof CtThrow
                || "java.lang.Object".equals(currentType.getQualifiedName())) {
            return List.of();
        }

        List<CtTypeReference<?>> candidates = new ArrayList<>();
        CtTypeReference<?> superClass = currentType.getSuperclass();
        if (superClass != null) {
            candidates.add(superClass);
        }
        candidates.addAll(currentType.getSuperInterfaces());
        candidates.add(context.getFactory().Type().createReference(Object.class));

        return candidates.stream()
                .filter(this::isSafeUpcastType)
                .filter(target -> isCompatibleWithExpectedType(target, expectedType))
                .distinct()
                .toList();
    }

    private boolean isCompatibleWithExpectedType(
            CtTypeReference<?> target,
            CtTypeReference<?> expectedType) {
        if (expectedType == null) {
            return true;
        }
        try {
            return target.isSubtypeOf(expectedType);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isInvocationArgument(CtExpression<?> candidate) {
        return candidate.getParent() instanceof CtInvocation<?>
                || candidate.getParent() instanceof CtConstructorCall<?>;
    }

    private boolean isSafeDowncastType(CtTypeReference<?> type) {
        String qualifiedName = type.getQualifiedName();
        if (qualifiedName.startsWith("java.lang.")) {
            return !qualifiedName.substring("java.lang.".length()).contains(".");
        }
        return !qualifiedName.startsWith("java.")
                && !qualifiedName.startsWith("javax.")
                && !qualifiedName.startsWith("sun.")
                && !qualifiedName.startsWith("jdk.");
    }

    private boolean isSafeUpcastType(CtTypeReference<?> type) {
        String qualifiedName = type.getQualifiedName();
        if ("java.lang.Object".equals(qualifiedName)) {
            return true;
        }
        if (qualifiedName.startsWith("java.lang.")) {
            return !qualifiedName.contains(".constant.")
                    && !qualifiedName.contains(".invoke.")
                    && !qualifiedName.contains(".ref.");
        }
        if (qualifiedName.startsWith("sun.")
                || qualifiedName.startsWith("jdk.")
                || qualifiedName.startsWith("com.sun.")) {
            return false;
        }
        return !qualifiedName.startsWith("java.");
    }

    private enum ConversionDirection {
        DOWNCAST,
        UPCAST
    }
}
