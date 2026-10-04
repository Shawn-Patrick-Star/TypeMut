package org.fuzz.operator.impl.block;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.config.FuzzConfig;
import org.fuzz.core.MutationContext;
import org.fuzz.operator.AbstractMutationOperator;
import org.fuzz.selector.BlockSelector;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtBreak;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtCatch;
import spoon.reflect.code.CtCatchVariable;
import spoon.reflect.code.CtComment;
import spoon.reflect.code.CtContinue;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtForEach;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtLoop;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtThrow;
import spoon.reflect.code.CtTry;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtModifiable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.List;

   
                                                 
   
@Slf4j
public abstract class AbstractBlockOperator extends AbstractMutationOperator<CtBlock> {

    protected AbstractBlockOperator() {
        super(new BlockSelector());
    }

    protected abstract boolean applyMutation(CtBlock<?> originalBlock,
                                             List<CtStatement> selectedStatements,
                                             List<VariableInfo> availableVars,
                                             MutationContext context);

    @Override
    protected boolean attemptMutate(CtBlock candidate, MutationContext context) {
        List<CtStatement> statements = candidate.getStatements();
        if (statements.isEmpty()) {
            return false;
        }

        if (countParentControlStructures(candidate) >= FuzzConfig.MAX_AST_DEPTH) {
            return false;
        }

        int effectiveSize = getEffectiveBlockSize(statements);
        int bestStart = -1;
        int bestEnd = -1;
        boolean found = false;

        for (int i = 0; i < FuzzConfig.MAX_INTERVAL_TRIALS; i++) {
            int start = context.getRandom().nextInt(effectiveSize);
            int end = start + context.getRandom().nextInt(effectiveSize - start);
            if (isRangeSafe(statements, start, end)) {
                bestStart = start;
                bestEnd = end;
                found = true;
                break;
            }
        }

        if (!found) {
            return false;
        }

        List<CtStatement> selectedStmts = new ArrayList<>();
        for (int i = bestStart; i <= bestEnd; i++) {
            selectedStmts.add(statements.get(i));
        }

        List<VariableInfo> contextVars = collectContextVariables(statements.get(bestStart));
        return applyMutation(candidate, selectedStmts, contextVars, context);
    }

    private int getEffectiveBlockSize(List<CtStatement> statements) {
        for (int i = 0; i < statements.size(); i++) {
            if (isControlFlowTerminator(statements.get(i))) {
                return i + 1;
            }
        }
        return statements.size();
    }

    private boolean isControlFlowTerminator(CtStatement stmt) {
        return stmt instanceof CtReturn
                || stmt instanceof CtThrow
                || stmt instanceof CtBreak
                || stmt instanceof CtContinue;
    }

    protected void ensureBlockNotEmpty(CtBlock<?> block, Factory f, MutationContext context) {
        ensureBlockHasLiveCode(block, f, context);
    }

    protected static class VariableInfo {
        public String name;
        public CtTypeReference<?> type;
        public CtVariable<?> declaration;

        public VariableInfo(String name, CtTypeReference<?> type, CtVariable<?> declaration) {
            this.name = name;
            this.type = type;
            this.declaration = declaration;
        }

        @Override
        public String toString() {
            return "Var{" + name + ": " + type.getSimpleName() + "}";
        }
    }

    protected int safeGetLine(CtElement e) {
        return (e.getPosition() != null && e.getPosition().isValidPosition()) ? e.getPosition().getLine() : -1;
    }

                                                                                        
    protected boolean replaceSelectedRange(
            CtBlock<?> originalBlock,
            List<CtStatement> selectedStatements,
            CtStatement replacement) {
        if (selectedStatements.isEmpty() || replacement == null) {
            return false;
        }

        List<CtStatement> currentStatements = originalBlock.getStatements();
        int start = currentStatements.indexOf(selectedStatements.getFirst());
        if (start < 0 || start + selectedStatements.size() > currentStatements.size()) {
            return false;
        }
        for (int i = 0; i < selectedStatements.size(); i++) {
            if (currentStatements.get(start + i) != selectedStatements.get(i)) {
                return false;
            }
        }

        List<CtStatement> updatedStatements = new ArrayList<>(
                currentStatements.size() - selectedStatements.size() + 1);
        updatedStatements.addAll(currentStatements.subList(0, start));
        updatedStatements.add(replacement);
        updatedStatements.addAll(currentStatements.subList(start + selectedStatements.size(), currentStatements.size()));
        originalBlock.setStatements(updatedStatements);
        return true;
    }

    private List<VariableInfo> collectContextVariables(CtStatement startStmt) {
        List<VariableInfo> vars = new ArrayList<>();

        CtElement current = startStmt;
        CtElement parent = startStmt.getParent();

        while (parent != null) {
            if (parent instanceof CtBlock<?> block) {
                for (CtStatement s : block.getStatements()) {
                    if (s == current) {
                        break;
                    }
                    if (s instanceof CtLocalVariable<?> local && local.getDefaultExpression() != null) {
                        vars.add(new VariableInfo(local.getSimpleName(), local.getType(), local));
                    }
                }
            } else if (parent instanceof CtCatch catchBlock) {
                CtCatchVariable<?> param = catchBlock.getParameter();
                if (param != null) {
                    vars.add(new VariableInfo(param.getSimpleName(), param.getType(), param));
                }
            } else if (parent instanceof CtFor ctFor) {
                for (CtStatement init : ctFor.getForInit()) {
                    if (init instanceof CtLocalVariable<?> local && local.getDefaultExpression() != null) {
                        vars.add(new VariableInfo(local.getSimpleName(), local.getType(), local));
                    }
                }
            } else if (parent instanceof CtForEach forEach) {
                CtLocalVariable<?> var = forEach.getVariable();
                if (var != null) {
                    vars.add(new VariableInfo(var.getSimpleName(), var.getType(), var));
                }
            }

            if (parent instanceof CtExecutable<?> executable) {
                for (CtParameter<?> param : executable.getParameters()) {
                    vars.add(new VariableInfo(param.getSimpleName(), param.getType(), param));
                }

                CtType<?> parentClass = parent.getParent(CtType.class);
                if (parentClass != null) {
                    boolean isStaticContext = executable instanceof CtModifiable modifiable && modifiable.isStatic();
                    if (executable instanceof CtConstructor<?>) {
                        isStaticContext = false;
                    }

                    for (CtField<?> field : parentClass.getFields()) {
                        if (isStaticContext && !field.isStatic()) {
                            continue;
                        }
                        vars.add(new VariableInfo(field.getSimpleName(), field.getType(), field));
                    }
                }
                break;
            }

            current = parent;
            parent = parent.getParent();
        }

        return vars;
    }

    private long countParentControlStructures(CtElement element) {
        CtElement current = element.getParent();
        long count = 0;
        while (current != null) {
            if (current instanceof CtLoop
                    || current instanceof CtIf
                    || current instanceof CtSwitch<?>
                    || current instanceof CtTry) {
                count++;
            }
            current = current.getParent();
        }
        return count;
    }

    private boolean isRangeSafe(List<CtStatement> statements, int start, int end) {
        for (int i = start; i <= end; i++) {
            CtStatement stmt = statements.get(i);

            if (stmt instanceof CtLocalVariable) {
                return false;
            }
            if (isControlFlowTerminator(stmt)) {
                return false;
            }
            if (stmt instanceof CtCase || stmt instanceof CtCatch) {
                return false;
            }
            if (stmt instanceof CtComment) {
                return false;
            }
            if (stmt.isImplicit()) {
                return false;
            }
            if (stmt instanceof CtInvocation<?> inv
                    && inv.getExecutable() != null
                    && inv.getExecutable().isConstructor()) {
                return false;
            }
        }
        return true;
    }
}
