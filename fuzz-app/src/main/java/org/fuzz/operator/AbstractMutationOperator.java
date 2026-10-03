package org.fuzz.operator;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.core.MutationContext;
import org.fuzz.selector.PointSelector;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtComment;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

   
                                
   
@Slf4j
public abstract class AbstractMutationOperator<T extends CtElement> implements MutOP {
    private static final String GLOBAL_SINK_FIELD_NAME = "Fuzz_Global_Sink";

    private final PointSelector<T> selector;
    private double weight = 1.0;

    protected AbstractMutationOperator(PointSelector<T> selector) {
        this.selector = selector;
    }

    @Override
    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    @Override
    public abstract String getName();

    @Override
    public boolean apply(MutationContext context) {
        List<T> candidates = new ArrayList<>(selector.collect(context.getModel()));
        if (candidates.isEmpty()) {
            return false;
        }

        Collections.shuffle(candidates, context.getRandom());
        for (T candidate : candidates) {
            if (attemptMutate(candidate, context)) {
                int line = (candidate.getPosition() != null && candidate.getPosition().isValidPosition())
                        ? candidate.getPosition().getLine() : -1;
                log.debug("[Mutation] Applied {} at line {}", getName(), line);
                return true;
            }
        }
        return false;
    }

    protected abstract boolean attemptMutate(T candidate, MutationContext context);

    protected String getCorrectSourceClassName(CtTypeReference<?> type) {
        if (type == null) {
            return "java.lang.Object";
        }
        if (type.isPrimitive()) {
            return type.getQualifiedName();
        }
        if (type.isArray()) {
            return type.toString();
        }
        return type.getQualifiedName().replace('$', '.');
    }

    protected String getOrCreateGlobalSinkReference(CtType<?> hostType, MutationContext context) {
        if (!supportsGlobalSink(hostType)) {
            return null;
        }
        try {
            ensureStaticIntSinkField(hostType, context);
            return GLOBAL_SINK_FIELD_NAME;
        } catch (Exception e) {
            log.debug("[AntiDce] Failed to ensure global sink: {}", e.getMessage());
            return null;
        }
    }

    protected String buildAntiDceConsumeSnippet(String valueExpression, CtType<?> hostType, MutationContext context) {
        String sinkReference = getOrCreateGlobalSinkReference(hostType, context);
        if (sinkReference != null) {
            return sinkReference + " += (" + valueExpression + ")";
        }
        return "if ((" + valueExpression + ") == -999999) System.out.print(\"Anti-DCE\")";
    }

    protected String buildNeverTrueReferenceGuard(String referenceExpression) {
        return "(" + referenceExpression + " == null && " + referenceExpression + " != null)";
    }

    protected void ensureBlockHasLiveCode(CtBlock<?> block, Factory factory, MutationContext context) {
        boolean hasEffectiveCode = block.getStatements().stream()
                .anyMatch(stmt -> !(stmt instanceof CtComment) && !stmt.isImplicit());
        if (hasEffectiveCode) {
            return;
        }

        String fillVar = context.getNameGenerator().generate("fill");
        CtLocalVariable<Integer> variable = factory.Code().createLocalVariable(
                factory.Type().integerPrimitiveType(),
                fillVar,
                factory.Code().createLiteral(context.getRandom().nextInt(100))
        );

        CtVariableAccess<Integer> read = factory.Code().createVariableRead(variable.getReference(), false);
        CtBinaryOperator<Integer> addOp = factory.Code().createBinaryOperator(
                read,
                factory.Code().createLiteral(1),
                BinaryOperatorKind.PLUS
        );
        addOp.setType(factory.Type().integerPrimitiveType());

        CtAssignment<Integer, Integer> assignment = factory.Code().createVariableAssignment(
                variable.getReference(),
                false,
                addOp
        );

        block.addStatement(variable);
        block.addStatement(assignment);
    }

    private boolean supportsGlobalSink(CtType<?> hostType) {
        return hostType != null
                && !hostType.isInterface()
                && !hostType.isAnnotationType()
                && !hostType.isAnonymous();
    }

    private void ensureStaticIntSinkField(CtType<?> hostType, MutationContext context) {
        if (hostType.getField(GLOBAL_SINK_FIELD_NAME) != null) {
            return;
        }

        CtField<Integer> field = context.getFactory().Core().createField();
        field.setSimpleName(GLOBAL_SINK_FIELD_NAME);
        field.setType(context.getFactory().Type().integerPrimitiveType());
        field.setModifiers(EnumSet.of(ModifierKind.PUBLIC, ModifierKind.STATIC));
        field.setDefaultExpression(context.getFactory().Code().createLiteral(0));
        hostType.addFieldAtTop(field);
    }
}
