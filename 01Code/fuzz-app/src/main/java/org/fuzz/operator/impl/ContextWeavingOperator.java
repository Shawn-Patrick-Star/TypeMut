package org.fuzz.operator.impl;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.core.MutationContext;
import org.fuzz.operator.AbstractMutationOperator;
import org.fuzz.selector.LocalVariableSelector;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.*;
import java.util.stream.Collectors;

   
                                     
  
                          
                                  
                                    
  
                               
                                       
  
                              
                          
   
@Slf4j
public class ContextWeavingOperator extends AbstractMutationOperator<CtLocalVariable> {

    public ContextWeavingOperator() {
        super(new LocalVariableSelector());
    }

    @Override
    public String getName() {
        return "ContextWeavingOperator";
    }

    @Override
    protected boolean attemptMutate(CtLocalVariable candidate, MutationContext context) {
                                
        CtExecutable<?> parentMethod = candidate.getParent(CtExecutable.class);
        if (parentMethod == null) return false;

        CtBlock<?> body = parentMethod.getBody();
        if (body == null) return false;

                                                          
        List<CtLocalVariable<?>> allVars = new ArrayList<>();
        for (CtLocalVariable<?> v : body.getElements(new TypeFilter<>(CtLocalVariable.class))) {
            if (!v.hasModifier(spoon.reflect.declaration.ModifierKind.FINAL)
                    && v.getDefaultExpression() != null) {
                allVars.add(v);
            }
        }
        if (allVars.size() < 2) return false;

                          
        CtTypeReference<?> candidateType = candidate.getType();
        if (candidateType == null) return false;

        List<CtLocalVariable<?>> compatibleVars = allVars.stream()
                .filter(v -> v != candidate)
                .filter(v -> v.getType() != null)
                .filter(v -> v.getParent(CtBlock.class) == candidate.getParent(CtBlock.class))
                .filter(v -> isTypeCompatible(candidateType, v.getType()))
                .collect(Collectors.toList());

        if (compatibleVars.isEmpty()) return false;

                       
        CtLocalVariable<?> partner = compatibleVars.get(context.getRandom().nextInt(compatibleVars.size()));

                                             
        CtLocalVariable<?> first, second;
        if (getLineNumber(candidate) <= getLineNumber(partner)) {
            first = candidate;
            second = partner;
        } else {
            first = partner;
            second = candidate;
        }

                                  
        String weavingCode = generateWeavingCode(first.getSimpleName(), second.getSimpleName(), candidateType, context);
        if (weavingCode == null) return false;

        try {
            CtCodeSnippetStatement weaveStmt = context.getFactory().Code().createCodeSnippetStatement(weavingCode);

                                                    
            CtElement secondParent = second.getParent();
            if (secondParent instanceof CtBlock) {
                CtBlock<?> block = (CtBlock<?>) secondParent;
                List<CtStatement> stmts = block.getStatements();
                int index = stmts.indexOf(second);
                if (index >= 0 && index < stmts.size()) {
                    block.addStatement(index + 1, weaveStmt);
                    log.debug("[Mutation] ContextWeaving: linked {} with {} using {}",
                            first.getSimpleName(), second.getSimpleName(),
                            candidateType.isPrimitive() ? "XOR" : "conditional-assign");
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("[ContextWeaving] Failed to insert weaving code: {}", e.getMessage());
        }

        return false;
    }

       
                        
       
    private boolean isTypeCompatible(CtTypeReference<?> typeA, CtTypeReference<?> typeB) {
        if (typeA == null || typeB == null) return false;

        String nameA = typeA.getQualifiedName();
        String nameB = typeB.getQualifiedName();

               
        if (nameA.equals(nameB)) return true;

                                                       
        if (isIntegralType(typeA) && isIntegralType(typeB)) return true;

                             
        if (!typeA.isPrimitive() && !typeB.isPrimitive()) return true;

        return false;
    }

       
                   
       
    private boolean isIntegralType(CtTypeReference<?> type) {
        if (type == null || !type.isPrimitive()) return false;
        String name = type.getSimpleName();
        return "int".equals(name) || "long".equals(name) || "short".equals(name) ||
               "byte".equals(name) || "char".equals(name);
    }

       
                 
       
    private String generateWeavingCode(String varA, String varB, CtTypeReference<?> type, MutationContext context) {
        if (type == null) return null;

        if (isIntegralType(type)) {
                          
                                                                            
            return varA + " ^= " + varB;
        } else if (type.isPrimitive()) {
                               
            String name = type.getSimpleName();
            if ("boolean".equals(name)) {
                return varA + " &= " + varB;
            }
                                     
            return null;
        } else {
                                                                              
                                                                        
            return "if " + buildNeverTrueReferenceGuard(varB) + " { " + varA + " = null; }";
        }
    }

    private int getLineNumber(CtElement element) {
        if (element.getPosition() != null && element.getPosition().isValidPosition()) {
            return element.getPosition().getLine();
        }
        return Integer.MAX_VALUE;
    }
}
