package org.fuzz.selector;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.UnaryOperatorKind;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;
import java.util.Set;

                                                                                      
public class PrimitiveVariableReadSelector implements PointSelector<CtVariableRead<?>> {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "byte", "short", "char", "int", "long", "float", "double", "boolean");

    @Override
    public List<CtVariableRead<?>> collect(CtModel model) {
        List<CtVariableRead<?>> reads = model.getElements(new TypeFilter<>(CtVariableRead.class));
        return reads.stream().filter(this::isSafe).toList();
    }

    private boolean isSafe(CtVariableRead<?> read) {
        if (read.isImplicit() || !hasValidPosition(read) || !isSupportedPrimitive(read.getType())) {
            return false;
        }

        InsertionPoint insertionPoint = findInsertionPoint(read);
        if (insertionPoint == null) {
            return false;
        }

        CtMethod<?> method = read.getParent(CtMethod.class);
        if (method == null || method.getBody() == null || read.getParent(CtLambda.class) != null
                || !belongsTo(read, method.getBody())) {
            return false;
        }

        if (isWrittenValue(read)) {
            return false;
        }

        CtVariable<?> declaration = read.getVariable() == null ? null : read.getVariable().getDeclaration();
        if (declaration instanceof CtParameter<?>) {
            return declaration.getParent(CtMethod.class) == method;
        }
        if (!(declaration instanceof CtLocalVariable<?> local)
                || local.getDefaultExpression() == null
                || !hasValidPosition(local)) {
            return false;
        }

        return local.getPosition().getSourceStart() < read.getPosition().getSourceStart()
                && isAncestor(local.getParent(CtBlock.class), insertionPoint.block());
    }

    public static InsertionPoint findInsertionPoint(CtElement element) {
        CtElement current = element;
        while (current != null && !(current instanceof CtMethod<?>)) {
            CtElement parent = safeParent(current);
            if (current instanceof CtStatement statement && parent instanceof CtBlock<?> block) {
                int index = block.getStatements().indexOf(statement);
                return index >= 0 ? new InsertionPoint(block, statement, index) : null;
            }
            current = parent;
        }
        return null;
    }

    public static boolean isSupportedPrimitive(CtTypeReference<?> type) {
        return type != null && type.isPrimitive() && SUPPORTED_TYPES.contains(type.getSimpleName());
    }

    private boolean isWrittenValue(CtVariableRead<?> read) {
        CtElement parent = safeParent(read);
        if (parent instanceof CtAssignment<?, ?> assignment && assignment.getAssigned() == read) {
            return true;
        }
        if (parent instanceof CtUnaryOperator<?> unary) {
            UnaryOperatorKind kind = unary.getKind();
            return kind == UnaryOperatorKind.PREINC
                    || kind == UnaryOperatorKind.POSTINC
                    || kind == UnaryOperatorKind.PREDEC
                    || kind == UnaryOperatorKind.POSTDEC;
        }
        return false;
    }

    private boolean hasValidPosition(CtElement element) {
        return element.getPosition() != null && element.getPosition().isValidPosition();
    }

    private boolean belongsTo(CtElement element, CtElement ancestor) {
        CtElement current = element;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = safeParent(current);
        }
        return false;
    }

    private boolean isAncestor(CtElement possibleAncestor, CtElement element) {
        if (possibleAncestor == null) {
            return false;
        }
        CtElement current = element;
        while (current != null) {
            if (current == possibleAncestor) {
                return true;
            }
            current = safeParent(current);
        }
        return false;
    }

    private static CtElement safeParent(CtElement element) {
        try {
            return element == null || !element.isParentInitialized() ? null : element.getParent();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public record InsertionPoint(CtBlock<?> block, CtStatement anchor, int index) {
    }
}
