package org.fuzz.operator.impl.typeCasting;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.core.MutationContext;
import org.fuzz.util.TypeUtils;
import spoon.reflect.code.*;
import spoon.reflect.reference.CtTypeReference;

@Slf4j
public class BoxedConversionOperator extends AbstractTypeCastOperator {

    @Override
    public String getName() {
        return "BoxedConversionOperator";
    }

    @Override
    protected CtTypeReference<?> calculateTargetType(CtExpression candidate, MutationContext context) {
        CtTypeReference<?> type = candidate.getType();
        if (type == null) return null;
        if ("void".equals(type.getQualifiedName())) return null;

        boolean canConvert = (type.isPrimitive() && TypeUtils.getBoxingCounterpart(type) != null) ||
                TypeUtils.isBoxedType(type);

        if (!canConvert) return null;

        String targetTypeName = TypeUtils.getBoxingCounterpart(type);
        return (targetTypeName != null) ?
                context.getFactory().Type().createReference(targetTypeName) : null;
    }

}