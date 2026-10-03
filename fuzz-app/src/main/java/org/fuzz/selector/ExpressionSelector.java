package org.fuzz.selector;

import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;
import java.util.stream.Collectors;

   
           
                         
   
public class ExpressionSelector implements PointSelector<CtExpression> {

    @Override
    public List<CtExpression> collect(CtModel model) {
        return model.getElements(new TypeFilter<>(CtExpression.class)).stream()
                .filter(this::isSafe)
                .collect(Collectors.toList());
    }

    private boolean isSafe(CtExpression<?> expr) {
                              
        if (expr.isImplicit() || !isValidPosition(expr)) return false;

                                       
        if (isStandaloneStatement(expr)) return false;

                                  
        if (isLhsOfAssignment(expr)) return false;

        return true;
    }

    private boolean isValidPosition(CtElement e) {
        return e.getPosition() != null && e.getPosition().isValidPosition();
    }

    private boolean isStandaloneStatement(CtElement element) {
        CtElement parent = element.getParent();
        return parent instanceof CtBlock
                || parent instanceof CtCase
                || parent instanceof CtIf
                || parent instanceof CtLoop;
    }

    private boolean isLhsOfAssignment(CtElement element) {
        CtElement parent = element.getParent();
        if (parent instanceof CtAssignment) {
            return ((CtAssignment<?, ?>) parent).getAssigned() == element;
        }
        if (parent instanceof CtUnaryOperator) {
            CtUnaryOperator<?> unary = (CtUnaryOperator<?>) parent;
            UnaryOperatorKind kind = unary.getKind();
            return kind == UnaryOperatorKind.POSTINC || kind == UnaryOperatorKind.PREINC ||
                    kind == UnaryOperatorKind.POSTDEC || kind == UnaryOperatorKind.PREDEC;
        }
        return false;
    }
}