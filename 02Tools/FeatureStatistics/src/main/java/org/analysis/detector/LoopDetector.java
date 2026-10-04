package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.model.FeatureOccurrence;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;

public class LoopDetector implements FeatureDetector {

    @Override
    public SourceCodeFeature getTargetFeature() {
        return SourceCodeFeature.loop;
    }

    @Override
    public List<FeatureOccurrence> detect(CtType<?> type) {
        List<FeatureOccurrence> results = new ArrayList<>();
        List<CtLoop> loops = type.getElements(new TypeFilter<>(CtLoop.class));

        for (CtLoop loop : loops) {
            if (!hasSourcePosition(loop)) {
                continue;
            }

            int depth = calculateLoopDepth(loop);
            String countLabel = "Unknown";

            if (loop instanceof CtFor) {
                long estimatedCount = estimateForLoopCount((CtFor) loop);
                if (estimatedCount != -1) {
                    countLabel = getCountRangeLabel(estimatedCount);
                }
            }

            String detail = String.format("Depth: %d, Count: %s", depth, countLabel);
            CtElement target = findMeaningfulStatement(loop);
            results.add(new FeatureOccurrence(SourceCodeFeature.loop, target, detail));
        }

        return results;
    }

    private int calculateLoopDepth(CtLoop loop) {
        int depth = 1;
        CtElement parent = loop.getParent();
        while (parent != null) {
            if (parent instanceof CtLoop && hasSourcePosition(parent)) {
                depth++;
            }
            parent = parent.getParent();
        }
        return depth;
    }

    /**
     * Statistically estimate the iteration count of a simple for-loop.
     * Supports incrementing and decrementing patterns when init/end/step are
     * integer literals. Unknown shapes remain Unknown rather than being guessed.
     */
    private long estimateForLoopCount(CtFor ctFor) {
        try {
            List<CtStatement> init = ctFor.getForInit();
            if (init.size() != 1 || !(init.get(0) instanceof CtLocalVariable)) return -1;

            CtLocalVariable<?> var = (CtLocalVariable<?>) init.get(0);
            CtExpression<?> defaultExpr = var.getDefaultExpression();
            if (!(defaultExpr instanceof CtLiteral)) return -1;

            Object startValue = ((CtLiteral<?>) defaultExpr).getValue();
            if (!(startValue instanceof Number)) return -1;
            int start = ((Number) startValue).intValue();
            String varName = var.getSimpleName();

            CtExpression<?> expr = ctFor.getExpression();
            if (!(expr instanceof CtBinaryOperator)) return -1;
            CtBinaryOperator<?> binary = (CtBinaryOperator<?>) expr;

            if (!(binary.getLeftHandOperand() instanceof CtVariableAccess)) return -1;
            if (!((CtVariableAccess<?>) binary.getLeftHandOperand()).getVariable().getSimpleName().equals(varName)) return -1;

            if (!(binary.getRightHandOperand() instanceof CtLiteral)) return -1;
            Object endValue = ((CtLiteral<?>) binary.getRightHandOperand()).getValue();
            if (!(endValue instanceof Number)) return -1;
            int end = ((Number) endValue).intValue();

            BinaryOperatorKind op = binary.getKind();

            int step = 0;
            List<CtStatement> updates = ctFor.getForUpdate();
            if (updates != null && !updates.isEmpty()) {
                CtStatement updateStmt = updates.get(0);
                if (updateStmt instanceof CtUnaryOperator) {
                    UnaryOperatorKind kind = ((CtUnaryOperator<?>) updateStmt).getKind();
                    if (kind == UnaryOperatorKind.POSTINC || kind == UnaryOperatorKind.PREINC) step = 1;
                    else if (kind == UnaryOperatorKind.POSTDEC || kind == UnaryOperatorKind.PREDEC) step = -1;
                } else if (updateStmt instanceof CtOperatorAssignment) {
                    CtOperatorAssignment<?, ?> assign = (CtOperatorAssignment<?, ?>) updateStmt;
                    if (assign.getAssignment() instanceof CtLiteral) {
                        Object stepValue = ((CtLiteral<?>) assign.getAssignment()).getValue();
                        if (!(stepValue instanceof Number)) return -1;
                        int val = ((Number) stepValue).intValue();
                        if (assign.getKind() == BinaryOperatorKind.PLUS) step = val;
                        else if (assign.getKind() == BinaryOperatorKind.MINUS) step = -val;
                    }
                }
            }
            if (step == 0) return -1;

            long count = 0;
            if (step > 0 && start < end) {
                if (op == BinaryOperatorKind.LT) {
                    count = (end - start - 1L) / step + 1;
                } else if (op == BinaryOperatorKind.LE) {
                    count = (end - start) / step + 1L;
                }
            } else if (step < 0 && start > end) {
                long absStep = Math.abs((long) step);
                long range = (long) start - end;
                if (op == BinaryOperatorKind.GT) {
                    count = (range - 1) / absStep + 1;
                } else if (op == BinaryOperatorKind.GE) {
                    count = range / absStep + 1;
                }
            } else {
                if (start == end) {
                    if (op == BinaryOperatorKind.LE || op == BinaryOperatorKind.GE) return 1;
                    return 0;
                }
                return 0;
            }

            return count > 0 ? count : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private String getCountRangeLabel(long count) {
        if (count >= 0 && count <= 10) return "[0, 10]";
        if (count >= 11 && count <= 100) return "[11, 100]";
        if (count >= 101 && count <= 10000) return "[101, 10000]";
        if (count > 10000) return "[10000, inf)";
        return "Unknown";
    }
}
