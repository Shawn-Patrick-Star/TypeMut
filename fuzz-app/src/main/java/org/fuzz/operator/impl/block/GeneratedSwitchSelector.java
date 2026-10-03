package org.fuzz.operator.impl.block;

import spoon.reflect.code.CtExpression;

record GeneratedSwitchSelector(
        CtExpression<?> expression,
        SwitchSelectorType type,
        String variableName) {
}
