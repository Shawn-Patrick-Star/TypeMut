package org.fuzz.operator.impl.block;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.core.MutationContext;
import org.fuzz.util.AstUtils;
import org.fuzz.util.LoopDepthAnalyzer;
import spoon.reflect.code.*;
import spoon.reflect.factory.Factory;

import java.util.List;

@Slf4j
public class LoopInjectionOperator extends AbstractBlockOperator {

                            
    private static final int[] JIT_CONSTANTS = { 10, 101 };

    @Override
    public String getName() {
        return "LoopInjectionOperator";
    }

    @Override
    protected boolean applyMutation(CtBlock<?> originalBlock,
            List<CtStatement> selectedStatements,
            List<VariableInfo> availableVars,
            MutationContext context) {

        if (selectedStatements.isEmpty()
                || LoopDepthAnalyzer.enclosingForDepth(selectedStatements.getFirst()) >= 3) {
            log.debug("[Mutation] LoopInjection: skipped at for/foreach depth >= 3");
            return false;
        }

                                                                    
                                                         
                                               
        boolean hasControlFlowTerminator = selectedStatements.stream()
                .anyMatch(s -> s instanceof CtReturn || s instanceof CtThrow
                        || s instanceof CtBreak || s instanceof CtContinue);

        if (hasControlFlowTerminator) {
            log.debug("[Mutation] LoopInjection: Skipped — selected range contains control flow terminator");
            return false;                      
        }

        Factory f = context.getFactory();
        String varName = context.getNameGenerator().generate("loopVar");

                            
        CtExpression<Integer> limitExpr;

                    
        List<VariableInfo> numericVars = availableVars.stream()
                .filter(v -> AstUtils.isNumeric(v.type))
                .toList();

                                                
        boolean useVariable = !numericVars.isEmpty() && context.getRandom().nextBoolean();

        if (useVariable) {
                                 
            VariableInfo selectedVar = numericVars.get(context.getRandom().nextInt(numericVars.size()));
            limitExpr = f.Code().createCodeSnippetExpression(selectedVar.name);
        } else {
                     
            int constVal = JIT_CONSTANTS[context.getRandom().nextInt(JIT_CONSTANTS.length)];
            limitExpr = f.Code().createLiteral(constVal);
        }

                       
        CtFor forLoop = AstUtils.createStandardForLoop(f, varName, 0, limitExpr);

                      
        CtBlock<?> body = context.getFactory().Core().createBlock();

                                                  
        wrapStatements(selectedStatements, forLoop, body);

                                         
        ensureBlockNotEmpty(body, f, context);

        forLoop.setBody(body);

             
        int startLine = safeGetLine(selectedStatements.get(0));
        int endLine = safeGetLine(selectedStatements.get(selectedStatements.size() - 1));
        log.debug("[Mutation] Loop Injection: Wrapped lines {}-{} (Limit: {})", startLine, endLine, limitExpr);
        return true;
    }

       
                                                
      
                                          
                                               
                                                     
       
    protected void wrapStatements(List<CtStatement> statements, CtStatement wrapper, CtBlock<?> newBody) {
        if (statements.isEmpty())
            return;

                                                                 
                                          

                                    
        statements.getFirst().replace(wrapper);

                              
        for (int i = 0; i < statements.size(); i++) {
            CtStatement stmt = statements.get(i);
            if (i > 0) {
                                             
                stmt.delete();
            }
                    
            newBody.addStatement(stmt);
        }
    }

}
