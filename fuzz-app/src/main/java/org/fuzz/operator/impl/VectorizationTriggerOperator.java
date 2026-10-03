package org.fuzz.operator.impl;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.core.MutationContext;
import org.fuzz.operator.impl.block.AbstractBlockOperator;
import org.fuzz.util.AstUtils;
import org.fuzz.util.LoopDepthAnalyzer;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtStatement;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.List;

   
                                                                      
   
@Slf4j
public class VectorizationTriggerOperator extends AbstractBlockOperator {

    private static final int VECTOR_SIZE = 1024;

    @Override
    public String getName() {
        return "VectorizationTriggerOperator";
    }

    @Override
    protected boolean applyMutation(CtBlock<?> originalBlock,
                                 List<CtStatement> selectedStatements,
                                 List<VariableInfo> availableVars,
                                 MutationContext context) {

        if (LoopDepthAnalyzer.enclosingForDepth(originalBlock) >= 3) {
            log.debug("[Mutation] VectorizationTrigger: skipped at for/foreach depth >= 3");
            return false;
        }

        Factory f = context.getFactory();

        String varA = context.getNameGenerator().generate("vec_a");
        String varB = context.getNameGenerator().generate("vec_b");
        String varI = context.getNameGenerator().generate("i");
        String varRes = context.getNameGenerator().generate("res");

        var hostType = originalBlock.getParent(spoon.reflect.declaration.CtType.class);
        boolean useAssignmentStrategy = getOrCreateGlobalSinkReference(hostType, context) != null;

        List<CtStatement> injectedStmts = new ArrayList<>();

        int size = VECTOR_SIZE + context.getRandom().nextInt(16);
        CtLiteral<Integer> sizeLiteral = f.Code().createLiteral(size);

        injectedStmts.add(createArrayDecl(f, varA, sizeLiteral));
        injectedStmts.add(createArrayDecl(f, varB, sizeLiteral));

        CtFor initLoop = AstUtils.createStandardForLoop(f, varI, 0, sizeLiteral);
        CtBlock<?> initBody = f.Core().createBlock();
        initBody.addStatement(f.Code().createCodeSnippetStatement(varB + "[" + varI + "] = " + varI));
        initBody.addStatement(f.Code().createCodeSnippetStatement(varA + "[" + varI + "] = " + context.getRandom().nextInt(100)));
        initLoop.setBody(initBody);
        injectedStmts.add(initLoop);

        CtFor calcLoop = AstUtils.createStandardForLoop(f, varI, 0, sizeLiteral);
        CtBlock<?> calcBody = f.Core().createBlock();
        CtExpression<Integer> mathExpr = generateASTVectorOperation(f, varA, varB, varI, context);
        calcBody.addStatement(f.Code().createCodeSnippetStatement(varA + "[" + varI + "] = " + mathExpr));
        calcLoop.setBody(calcBody);
        injectedStmts.add(calcLoop);

        CtLocalVariable<Integer> resDecl = f.Code().createLocalVariable(
                f.Type().integerPrimitiveType(),
                varRes,
                f.Code().createLiteral(0)
        );
        injectedStmts.add(resDecl);

        CtIf dceIf = f.Core().createIf();
        dceIf.setCondition(f.Code().createBinaryOperator(sizeLiteral, f.Code().createLiteral(0), BinaryOperatorKind.GT));
        dceIf.setThenStatement(f.Code().createCodeSnippetStatement(
                varRes + " = " + varA + "[" + size + " - 1] + " + varA + "[0]"
        ));
        injectedStmts.add(dceIf);

        injectedStmts.add(f.Code().createCodeSnippetStatement(
                buildAntiDceConsumeSnippet(varRes, hostType, context)
        ));

        int stmtCount = originalBlock.getStatements().size();
        int insertIndex = context.getRandom().nextInt(stmtCount + 1);
        for (int i = injectedStmts.size() - 1; i >= 0; i--) {
            originalBlock.addStatement(insertIndex, injectedStmts.get(i));
        }

        int line = (originalBlock.getPosition() != null && originalBlock.getPosition().isValidPosition())
                ? originalBlock.getPosition().getLine() : -1;
        log.debug("[Mutation] VectorizationTrigger: Injected loop at line {} [Strategy: {}]",
                line,
                useAssignmentStrategy ? "Global Assignment" : "Fake Print");
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private CtLocalVariable<?> createArrayDecl(Factory f, String name, CtExpression<Integer> size) {
        CtArrayTypeReference<?> arrayType = f.Type().createArrayReference(f.Type().integerPrimitiveType());
        CtNewArray<?> newArray = f.Core().createNewArray();
        newArray.setType((CtArrayTypeReference) arrayType);
        newArray.addDimensionExpression(size);

        return f.Code().createLocalVariable(
                (CtTypeReference) arrayType,
                name,
                (CtExpression) newArray
        );
    }

    private CtExpression<Integer> generateASTVectorOperation(Factory f, String varA, String varB, String varI, MutationContext context) {
        CtExpression<Integer> readA = f.Code().createCodeSnippetExpression(varA + "[" + varI + "]");
        CtExpression<Integer> readB = f.Code().createCodeSnippetExpression(varB + "[" + varI + "]");

        int pattern = context.getRandom().nextInt(4);
        int constVal = context.getRandom().nextInt(100) + 1;
        CtLiteral<Integer> constLit = f.Code().createLiteral(constVal);

        return switch (pattern) {
            case 0 -> {
                var mul = f.Code().createBinaryOperator(readB, constLit, BinaryOperatorKind.MUL);
                yield f.Code().createBinaryOperator(mul, readA, BinaryOperatorKind.PLUS);
            }
            case 1 -> {
                var xor = f.Code().createBinaryOperator(readB, constLit, BinaryOperatorKind.BITXOR);
                var and = f.Code().createBinaryOperator(readA, readB, BinaryOperatorKind.BITAND);
                yield f.Code().createBinaryOperator(xor, and, BinaryOperatorKind.BITOR);
            }
            case 2 -> {
                var shr = f.Code().createBinaryOperator(readB, f.Code().createLiteral(2), BinaryOperatorKind.USR);
                var shl = f.Code().createBinaryOperator(readA, f.Code().createLiteral(2), BinaryOperatorKind.SL);
                yield f.Code().createBinaryOperator(shr, shl, BinaryOperatorKind.PLUS);
            }
            default -> f.Code().createBinaryOperator(readA, readB, BinaryOperatorKind.PLUS);
        };
    }
}
