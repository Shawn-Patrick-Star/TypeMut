package org.fuzz.operator.impl.block;

import org.fuzz.core.MutationContext;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

                                                                   
final class SwitchSelectorGenerator {

    GeneratedSwitchSelector generate(
            List<AbstractBlockOperator.VariableInfo> availableVariables,
            MutationContext context) {
        List<AbstractBlockOperator.VariableInfo> candidates = collectCandidates(availableVariables);
        if (candidates.isEmpty()) {
            return null;
        }

        AbstractBlockOperator.VariableInfo selected = candidates.get(
                context.getRandom().nextInt(candidates.size()));
        CtExpression<?> read = createRead(selected, context);
        SwitchSelectorType type = selectorType(selected.type);
        return read == null || type == null
                ? null
                : new GeneratedSwitchSelector(read, type, selected.name);
    }

    List<AbstractBlockOperator.VariableInfo> collectCandidates(
            List<AbstractBlockOperator.VariableInfo> availableVariables) {
        List<AbstractBlockOperator.VariableInfo> candidates = new ArrayList<>();
        Set<CtVariable<?>> declarations = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> names = new LinkedHashSet<>();

        for (AbstractBlockOperator.VariableInfo variable : availableVariables) {
            if (variable == null
                    || variable.name == null
                    || variable.type == null
                    || variable.declaration == null
                    || selectorType(variable.type) == null
                    || isVolatileField(variable.declaration)
                    || !names.add(variable.name)
                    || !declarations.add(variable.declaration)) {
                continue;
            }
            candidates.add(variable);
        }
        return candidates;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CtExpression<?> createRead(
            AbstractBlockOperator.VariableInfo variable,
            MutationContext context) {
        try {
            return context.getFactory().Code().createVariableRead(
                    variable.declaration.getReference(), false);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean isVolatileField(CtVariable<?> declaration) {
        return declaration instanceof CtField<?> field && field.isVolatile();
    }

    private SwitchSelectorType selectorType(CtTypeReference<?> type) {
        if (type == null || !type.isPrimitive()) {
            return null;
        }
        return switch (type.getSimpleName()) {
            case "byte" -> SwitchSelectorType.BYTE;
            case "short" -> SwitchSelectorType.SHORT;
            case "char" -> SwitchSelectorType.CHAR;
            case "int" -> SwitchSelectorType.INT;
            default -> null;
        };
    }
}
