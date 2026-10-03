package org.fuzz.operator.impl.block;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.core.MutationContext;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtBreak;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.factory.Factory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

                                                           
@Slf4j
public class SwitchInjectionOperator extends AbstractBlockOperator {

    private static final int MIN_CASES = 2;
    private static final int MAX_CASES = 4;

    private final SwitchSelectorGenerator selectorGenerator = new SwitchSelectorGenerator();
    private final Mode forcedMode;
    private final Layout forcedLayout;
    private final Integer forcedCaseCount;

    public SwitchInjectionOperator() {
        this(null, null, null);
    }

    SwitchInjectionOperator(Mode forcedMode, Layout forcedLayout, Integer forcedCaseCount) {
        this.forcedMode = forcedMode;
        this.forcedLayout = forcedLayout;
        this.forcedCaseCount = forcedCaseCount;
    }

    @Override
    public String getName() {
        return "SwitchInjectionOperator";
    }

    @Override
    protected boolean applyMutation(
            CtBlock<?> originalBlock,
            List<CtStatement> selectedStatements,
            List<VariableInfo> availableVars,
            MutationContext context) {
        if (selectedStatements.isEmpty()) {
            return false;
        }

        GeneratedSwitchSelector selector = selectorGenerator.generate(availableVars, context);
        if (selector == null) {
            return false;
        }

        Random random = context.getRandom();
        Mode mode = forcedMode != null
                ? forcedMode
                : (random.nextInt(10) < 7 ? Mode.TRANSPARENT : Mode.FALL_THROUGH);
        Layout layout = forcedLayout != null
                ? forcedLayout
                : (random.nextBoolean() ? Layout.DENSE : Layout.SPARSE);
        int caseCount = forcedCaseCount != null
                ? Math.max(MIN_CASES, Math.min(MAX_CASES, forcedCaseCount))
                : MIN_CASES + random.nextInt(MAX_CASES - MIN_CASES + 1);

        CtSwitch<?> injectedSwitch;
        try {
            List<Integer> labels = generateCaseLabels(selector.type(), layout, caseCount, random);
            injectedSwitch = createDetachedSwitch(
                    selector, selectedStatements, labels, mode, context.getFactory());
        } catch (RuntimeException e) {
            log.debug("[Mutation] SwitchInjection: failed to construct detached AST", e);
            return false;
        }

        if (!replaceSelectedRange(originalBlock, selectedStatements, injectedSwitch)) {
            return false;
        }

        log.debug("[Mutation] SwitchInjection: mode={}, selector={}({}), layout={}, cases={}",
                mode,
                selector.variableName(),
                selector.type().name().toLowerCase(Locale.ROOT),
                layout,
                caseCount);
        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CtSwitch<?> createDetachedSwitch(
            GeneratedSwitchSelector selector,
            List<CtStatement> selectedStatements,
            List<Integer> labels,
            Mode mode,
            Factory factory) {
        CtSwitch injectedSwitch = factory.Core().createSwitch();
        injectedSwitch.setSelector(selector.expression().clone());

        for (int i = 0; i < labels.size(); i++) {
            CtCase generatedCase = factory.Core().createCase();
            generatedCase.setCaseExpression(caseExpression(selector.type(), labels.get(i), factory));
            generatedCase.addStatement(cloneIntoBlock(selectedStatements, factory));
            if (!(mode == Mode.FALL_THROUGH && i == 0)) {
                generatedCase.addStatement(createBreak(factory));
            }
            injectedSwitch.addCase(generatedCase);
        }

        CtCase defaultCase = factory.Core().createCase();
        defaultCase.addStatement(cloneIntoBlock(selectedStatements, factory));
        defaultCase.addStatement(createBreak(factory));
        injectedSwitch.addCase(defaultCase);
        return injectedSwitch;
    }

    private CtBlock<?> cloneIntoBlock(List<CtStatement> statements, Factory factory) {
        CtBlock<?> block = factory.Core().createBlock();
        for (CtStatement statement : statements) {
            block.addStatement(statement.clone());
        }
        return block;
    }

    private CtBreak createBreak(Factory factory) {
        return factory.Core().createBreak();
    }

    private CtExpression<?> caseExpression(
            SwitchSelectorType selectorType,
            int value,
            Factory factory) {
        if (selectorType == SwitchSelectorType.CHAR) {
            return factory.Code().createLiteral((char) value);
        }
        return factory.Code().createLiteral(value);
    }

    private List<Integer> generateCaseLabels(
            SwitchSelectorType selectorType,
            Layout layout,
            int caseCount,
            Random random) {
        if (layout == Layout.DENSE) {
            int start = selectorType == SwitchSelectorType.CHAR
                    ? random.nextInt(4)
                    : random.nextInt(5) - 2;
            List<Integer> labels = new ArrayList<>(caseCount);
            for (int i = 0; i < caseCount; i++) {
                labels.add(start + i);
            }
            return labels;
        }

        List<Integer> labels = new ArrayList<>(sparsePool(selectorType));
        Collections.shuffle(labels, random);
        return new ArrayList<>(labels.subList(0, caseCount));
    }

    private List<Integer> sparsePool(SwitchSelectorType selectorType) {
        return switch (selectorType) {
            case BYTE -> List.of(-127, -31, 0, 47, 126);
            case SHORT -> List.of(-30000, -101, 7, 1024, 30000);
            case CHAR -> List.of(1, 97, 1024, 32768, 65534);
            case INT -> List.of(-1000003, -101, 7, 4096, 1000003);
        };
    }

    enum Mode {
        TRANSPARENT,
        FALL_THROUGH
    }

    enum Layout {
        DENSE,
        SPARSE
    }
}
