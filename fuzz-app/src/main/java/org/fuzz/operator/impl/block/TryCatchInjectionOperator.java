package org.fuzz.operator.impl.block;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.core.MutationContext;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtCatch;
import spoon.reflect.code.CtCatchVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtThrow;
import spoon.reflect.code.CtTry;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

                                                                                   
@Slf4j
public class TryCatchInjectionOperator extends AbstractBlockOperator {

    private static final List<Class<? extends RuntimeException>> MULTI_CATCH_TYPES = List.of(
            ArithmeticException.class,
            ClassCastException.class,
            NullPointerException.class,
            ArrayIndexOutOfBoundsException.class,
            IllegalArgumentException.class);

    private final CatchShape forcedShape;
    private final Integer forcedMultiCatchCount;

    public TryCatchInjectionOperator() {
        this(null, null);
    }

    TryCatchInjectionOperator(CatchShape forcedShape, Integer forcedMultiCatchCount) {
        this.forcedShape = forcedShape;
        this.forcedMultiCatchCount = forcedMultiCatchCount;
    }

    @Override
    public String getName() {
        return "TryCatchInjectionOperator";
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

        Random random = context.getRandom();
        CatchShape shape = forcedShape != null
                ? forcedShape
                : (random.nextBoolean() ? CatchShape.SINGLE : CatchShape.MULTI);
        List<Class<? extends RuntimeException>> catchTypes = selectCatchTypes(shape, random);

        CtTry injectedTry;
        try {
            injectedTry = createDetachedTry(
                    selectedStatements,
                    catchTypes,
                    context.getNameGenerator().generate("fuzz_ex"),
                    context.getFactory());
        } catch (RuntimeException e) {
            log.debug("[Mutation] TryCatchInjection: failed to construct detached AST", e);
            return false;
        }

        if (!replaceSelectedRange(originalBlock, selectedStatements, injectedTry)) {
            return false;
        }

        log.debug("[Mutation] TryCatchInjection: catch={}, types={}",
                shape,
                catchTypes.stream().map(Class::getSimpleName).toList());
        return true;
    }

    private List<Class<? extends RuntimeException>> selectCatchTypes(
            CatchShape shape,
            Random random) {
        if (shape == CatchShape.SINGLE) {
            return List.of(RuntimeException.class);
        }

        int count = forcedMultiCatchCount != null
                ? Math.max(2, Math.min(3, forcedMultiCatchCount))
                : 2 + random.nextInt(2);
        List<Class<? extends RuntimeException>> candidates = new ArrayList<>(MULTI_CATCH_TYPES);
        Collections.shuffle(candidates, random);
        return new ArrayList<>(candidates.subList(0, count));
    }

    private CtTry createDetachedTry(
            List<CtStatement> statements,
            List<Class<? extends RuntimeException>> catchTypes,
            String catchVariableName,
            Factory factory) {
        CtTry injectedTry = factory.Core().createTry();
        CtBlock<?> tryBody = factory.Core().createBlock();
        for (CtStatement statement : statements) {
            tryBody.addStatement(statement.clone());
        }
        injectedTry.setBody(tryBody);

        CtCatch catcher = factory.Core().createCatch();
        CtCatchVariable<RuntimeException> catchVariable = createCatchVariable(
                catchTypes, catchVariableName, factory);
        catcher.setParameter(catchVariable);

        CtThrow rethrow = factory.Core().createThrow();
        rethrow.setThrownExpression(factory.Code().createVariableRead(catchVariable.getReference(), false));
        CtBlock<?> catchBody = factory.Core().createBlock();
        catchBody.addStatement(rethrow);
        catcher.setBody(catchBody);
        injectedTry.addCatcher(catcher);
        return injectedTry;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CtCatchVariable<RuntimeException> createCatchVariable(
            List<Class<? extends RuntimeException>> catchTypes,
            String name,
            Factory factory) {
        CtCatchVariable<RuntimeException> catchVariable = factory.Core().createCatchVariable();
        catchVariable.setSimpleName(name);

        List<CtTypeReference<?>> references = new ArrayList<>(catchTypes.size());
        for (Class<? extends RuntimeException> catchType : catchTypes) {
            references.add(factory.Type().createReference(catchType));
        }
        catchVariable.setMultiTypes(references);
        return catchVariable;
    }

    enum CatchShape {
        SINGLE,
        MULTI
    }
}
