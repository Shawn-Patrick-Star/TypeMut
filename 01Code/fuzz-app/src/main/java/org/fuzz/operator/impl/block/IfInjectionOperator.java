package org.fuzz.operator.impl.block;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.core.MutationContext;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtLoop;
import spoon.reflect.code.CtStatement;
import spoon.reflect.factory.Factory;

import java.util.List;

   
                                                                              
                                                                                 
   
@Slf4j
public class IfInjectionOperator extends AbstractBlockOperator {
    private final IfConditionGenerator conditionGenerator = new IfConditionGenerator();

    @Override
    public String getName() {
        return "IfInjectionOperator";
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

        CtLoop enclosingLoop = originalBlock.getParent(CtLoop.class);
        GeneratedIfCondition condition = conditionGenerator.generate(
                availableVars, enclosingLoop, context);
        if (condition == null) {
            return false;
        }

        CtIf injectedIf;
        try {
            injectedIf = createDetachedIf(selectedStatements, condition, context.getFactory());
        } catch (RuntimeException e) {
            log.debug("[Mutation] IfInjection: failed to construct detached AST", e);
            return false;
        }

        CtStatement firstStatement = selectedStatements.getFirst();
        firstStatement.replace(injectedIf);
        for (int i = 1; i < selectedStatements.size(); i++) {
            selectedStatements.get(i).delete();
        }

        log.debug("[Mutation] IfInjection: mode={}, condition={}",
                condition.mode(), condition.expression());
        return true;
    }

    private CtIf createDetachedIf(
            List<CtStatement> statements,
            GeneratedIfCondition condition,
            Factory factory) {
        CtBlock<?> thenBlock = factory.Core().createBlock();
        CtBlock<?> elseBlock = factory.Core().createBlock();
        for (CtStatement statement : statements) {
            thenBlock.addStatement(statement.clone());
            elseBlock.addStatement(statement.clone());
        }

        CtIf injectedIf = factory.Core().createIf();
        injectedIf.setCondition(condition.expression());
        injectedIf.setThenStatement(thenBlock);
        injectedIf.setElseStatement(elseBlock);
        return injectedIf;
    }
}
