package org.fuzz.operator.impl.method;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.core.MutationContext;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;

import java.util.List;
import java.util.stream.Collectors;


   
                     
                                                              
                               
   
@Slf4j
public class MethodToReflectionOperator extends AbstractMethodOperator {

    @Override
    public String getName() {
        return "MethodToReflectionOperator";
    }

    @Override
    protected boolean attemptMutate(CtInvocation candidate, MutationContext context) {
        CtExecutableReference<?> execRef = candidate.getExecutable();
        CtExecutable<?> declaration = execRef.getExecutableDeclaration();
        if (declaration == null) return false;

        CtType<?> declaringType = getDeclaringType(declaration);
        if (declaringType == null) return false;

        String methodName = execRef.getSimpleName();
        String paramTypesCode = buildParamTypesCode(declaration);
        List<CtExpression<?>> args = candidate.getArguments();

        boolean isStatic = execRef.isStatic();
        String declaringClassName = getCorrectSourceClassName(declaringType.getReference());
        String classSource;
        String invokeTarget;                                     

        if (isStatic) {
            classSource = declaringClassName + ".class";
            invokeTarget = "null";                      
        } else {
            CtExpression<?> target = candidate.getTarget();

                                               
                                                      
                                                       
                                                   
            boolean isImplicitThis = (target == null) || target.isImplicit() || target.toString().trim().isEmpty();
            if (isImplicitThis) {
                classSource = "this.getClass()";
                invokeTarget = "this";
            } else {
                if ("super".equals(target.toString().trim())) {
                    classSource = declaringClassName + ".class";
                    invokeTarget = "this";
                } else {
                    classSource = target.toString() + ".getClass()";
                    invokeTarget = target.toString();
                }
            }
        }

        String varMethod = context.getNameGenerator().generate("method");
                                   
        String methodSetup = "       java.lang.reflect.Method " + varMethod + " = " + classSource + ".getMethod(\"" + methodName + "\", " + paramTypesCode + ");";
        if (isStatementContext(candidate)) {
                                                   
                                                         
            String argsCode = buildArgsCode(args);
            if (argsCode.isEmpty()) argsCode = "new Object[0]";

            replaceWithTryCatchStatement(candidate,
                    methodSetup +
                            "       " + varMethod + ".invoke(" + invokeTarget + ", " + argsCode + ")",
                    context);
            log.debug("[Mutation] Reflected (Statement): {}", methodName);

        } else {
                                       
            CtTypeReference<?> returnType = candidate.getType();
            boolean isVoid = (returnType == null) || "void".equals(returnType.getQualifiedName());
            String returnPrefix = isVoid ? "" : "return ";

                                       
            String castCode = isVoid ? "" : "(" + getBoxedTypeName(returnType) + ") ";

            replaceWithSafeExpression(candidate, returnType, invokeTarget, args,
                    (targetVar, argVars) -> {
                                                           
                        String innerArgs = String.join(", ", argVars);
                        if (innerArgs.isEmpty()) innerArgs = "new Object[0]";

                        return methodSetup +
                                                                                                                  
                                "       " + returnPrefix + castCode + varMethod + ".invoke(" + targetVar + ", " + innerArgs + ");";
                    },
                    context);
            log.debug("[Mutation] Reflected (Expression): {}", methodName);
        }

        return true;
    }


    private String buildParamTypesCode(CtExecutable<?> method) {
        if (method.getParameters().isEmpty()) return "new Class[0]";
        String types = method.getParameters().stream()
                                
                .map(p -> getCorrectSourceClassName(p.getType()) + ".class")
                .collect(Collectors.joining(", "));
        return "new Class[]{" + types + "}";
    }
}
