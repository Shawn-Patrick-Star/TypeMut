package org.fuzz.operator.impl.typeCasting;


import lombok.extern.slf4j.Slf4j;
import org.fuzz.core.MutationContext;
import org.fuzz.operator.AbstractMutationOperator;
import org.fuzz.selector.ExpressionSelector;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;

import java.util.List;
import java.util.Locale;

   
               
       
                                       
                                  
                                     
                
   
@Slf4j
public abstract class AbstractTypeCastOperator extends AbstractMutationOperator<CtExpression> {

    protected AbstractTypeCastOperator() {
        super(new ExpressionSelector());
    }

       
                                    
                              
       
    protected abstract CtTypeReference<?> calculateTargetType(CtExpression candidate, MutationContext context);

    @Override
    protected boolean attemptMutate(CtExpression candidate, MutationContext context) {
                                     
        if (!candidate.getTypeCasts().isEmpty()) {
            return false;
        }


                              
        CtTypeReference<?> targetType = calculateTargetType(candidate, context);

        if (targetType == null) {
            return false;
        }

                  
        candidate.addTypeCast(targetType);
        log.debug("[Mutation {}] : targetType-({}); mutateRes-{}", getName(), targetType.getSimpleName(), candidate);
        return true;
    }

       
                             
                          
       
    protected CtTypeReference<?> getExpectedType(CtExpression<?> expr) {
        CtElement parent = expr.getParent();

                
        if (parent instanceof CtAssignment) {
            return ((CtAssignment<?, ?>) parent).getAssigned().getType();
        }

                  
        if (parent instanceof CtLocalVariable) {
            return ((CtLocalVariable<?>) parent).getType();
        }

        if (parent instanceof CtField) {
            return ((CtField<?>) parent).getType();
        }

                    
        if (parent instanceof CtReturn) {
            CtMethod<?> method = parent.getParent(CtMethod.class);
            if (method != null) return method.getType();
        }

                   
        if (parent instanceof CtNewArray) {
            CtNewArray<?> newArray = (CtNewArray<?>) parent;
            CtTypeReference<?> arrayType = newArray.getType();
            if (arrayType instanceof CtArrayTypeReference) {
                return ((CtArrayTypeReference<?>) arrayType).getComponentType();
            }
        }

                          
        if (parent instanceof CtBinaryOperator) {
            CtBinaryOperator<?> binOp = (CtBinaryOperator<?>) parent;
            if (binOp.getKind() == BinaryOperatorKind.PLUS) {
                CtExpression<?> other = (binOp.getLeftHandOperand() == expr) ?
                        binOp.getRightHandOperand() : binOp.getLeftHandOperand();

                                                         
                if (other != null && other.getType() != null &&
                        "java.lang.String".equals(other.getType().getQualifiedName())) {
                    return expr.getFactory().Type().createReference(Object.class);
                } else {
                                 
                    return expr.getType();
                }
            }
        }

                    
        if (parent instanceof CtInvocation) {
            return getInvocationExpectedType((CtInvocation<?>) parent, expr);
        }

        if (parent instanceof CtConstructorCall) {
            return getConstructorExpectedType((CtConstructorCall<?>) parent, expr);
        }

                                 
                                                               
        if (parent instanceof CtFieldAccess) {
            CtFieldAccess<?> fieldAccess = (CtFieldAccess<?>) parent;
                                      
                                         
            if (fieldAccess.getTarget() == expr) {
                CtFieldReference<?> fieldRef = fieldAccess.getVariable();
                if (fieldRef != null) {
                                                      
                                                                          
                    return fieldRef.getDeclaringType();
                }
                                          
                return expr.getType();
            }
        }

                                   
                                                  
        if (parent instanceof CtArrayAccess) {
            CtArrayAccess<?, ?> arrayAccess = (CtArrayAccess<?, ?>) parent;
                                     
            if (arrayAccess.getTarget() == expr) {
                                                   
                                       
                return expr.getType();
            }
                                  
            if (arrayAccess.getIndexExpression() == expr) {
                            
                return expr.getFactory().Type().integerPrimitiveType();
            }
        }

        return null;
    }

    protected boolean isUnsafeBoundaryNarrowing(CtExpression<?> expr, CtTypeReference<?> targetType) {
        if (targetType == null || !targetType.isPrimitive()) {
            return false;
        }

        if (isArrayBoundaryExpression(expr)) {
            return true;
        }

        InvocationArgument argument = findInvocationArgument(expr);
        return argument != null && isCriticalInvocationArgument(argument.invocation(), argument.index());
    }

    private boolean isArrayBoundaryExpression(CtExpression<?> expr) {
        CtExpression<?> current = expr;
        while (current != null) {
            CtElement parent = current.getParent();
            if (parent instanceof CtArrayAccess<?, ?> arrayAccess
                    && arrayAccess.getIndexExpression() == current) {
                return true;
            }
            if (parent instanceof CtNewArray<?> newArray
                    && newArray.getDimensionExpressions().contains(current)) {
                return true;
            }
            if (parent instanceof CtExpression<?> parentExpression) {
                current = parentExpression;
                continue;
            }
            return false;
        }
        return false;
    }

    private InvocationArgument findInvocationArgument(CtExpression<?> expr) {
        CtExpression<?> current = expr;
        while (current != null) {
            CtElement parent = current.getParent();
            if (parent instanceof CtInvocation<?> invocation) {
                int index = invocation.getArguments().indexOf(current);
                return index >= 0 ? new InvocationArgument(invocation, index) : null;
            }
            if (parent instanceof CtExpression<?> parentExpression) {
                current = parentExpression;
                continue;
            }
            return null;
        }
        return null;
    }

    private boolean isCriticalInvocationArgument(CtInvocation<?> invocation, int index) {
        if (invocation == null || invocation.getExecutable() == null || index < 0) {
            return false;
        }

        String methodName = invocation.getExecutable().getSimpleName();
        String declaringType = invocation.getExecutable().getDeclaringType() == null
                ? ""
                : invocation.getExecutable().getDeclaringType().getQualifiedName();
        String normalizedOwner = declaringType.toLowerCase(Locale.ROOT);

        if ("defineClass".equals(methodName) && index >= 2) {
            return true;
        }
        if ("arraycopy".equals(methodName)
                && "java.lang.System".equals(declaringType)
                && (index == 1 || index == 3 || index == 4)) {
            return true;
        }
        if (("copyOf".equals(methodName) || "copyOfRange".equals(methodName))
                && "java.util.Arrays".equals(declaringType)
                && index >= 1) {
            return true;
        }
        if (isIoLikeOwner(normalizedOwner)
                && ("read".equals(methodName) || "write".equals(methodName))
                && index >= 1) {
            return true;
        }
        if (isBufferLikeOwner(normalizedOwner)
                && ("get".equals(methodName) || "put".equals(methodName))
                && index >= 1) {
            return true;
        }
        return false;
    }

    private boolean isIoLikeOwner(String owner) {
        return owner.contains("inputstream")
                || owner.contains("outputstream")
                || owner.contains("reader")
                || owner.contains("writer")
                || owner.contains("channel");
    }

    private boolean isBufferLikeOwner(String owner) {
        return owner.contains("bytebuffer")
                || owner.contains("charbuffer")
                || owner.contains("shortbuffer")
                || owner.contains("intbuffer")
                || owner.contains("longbuffer");
    }

    private record InvocationArgument(CtInvocation<?> invocation, int index) {
    }


       
                       
       
    private CtTypeReference<?> getInvocationExpectedType(CtInvocation<?> invocation, CtExpression<?> arg) {
        List<CtExpression<?>> args = invocation.getArguments();
        int index = args.indexOf(arg);

        if (index >= 0) {
            CtExecutableReference<?> exec = invocation.getExecutable();
            if (exec != null) {
                                            
                CtExecutable<?> decl = exec.getExecutableDeclaration();
                if (decl != null) {
                    List<CtParameter<?>> params = decl.getParameters();
                    if (index < params.size()) {
                        return params.get(index).getType();
                    }
                                                          
                    if (!params.isEmpty() && params.get(params.size() - 1).getType() instanceof CtArrayTypeReference) {
                        return ((CtArrayTypeReference<?>) params.get(params.size() - 1).getType()).getComponentType();
                    }
                }

                                
                List<CtTypeReference<?>> paramTypes = exec.getParameters();
                if (index < paramTypes.size()) {
                    return paramTypes.get(index);
                }
            }
        }
        return null;
    }

    private CtTypeReference<?> getConstructorExpectedType(CtConstructorCall<?> constructorCall, CtExpression<?> arg) {
        List<CtExpression<?>> args = constructorCall.getArguments();
        int index = args.indexOf(arg);

        if (index >= 0 && constructorCall.getExecutable() != null) {
            List<CtTypeReference<?>> paramTypes = constructorCall.getExecutable().getParameters();
            if (index < paramTypes.size()) {
                return paramTypes.get(index);
            }
        }
        return null;
    }
}
