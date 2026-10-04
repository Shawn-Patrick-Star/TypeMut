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
public class MethodToMethodHandleOperator extends AbstractMethodOperator {

    @Override
    public String getName() {
        return "MethodToMethodHandleOperator";
    }

    @Override
    protected boolean attemptMutate(CtInvocation candidate, MutationContext context) {
        CtExecutableReference<?> execRef = candidate.getExecutable();
        CtExecutable<?> declaration = execRef.getExecutableDeclaration();
        if (declaration == null) return false;

        CtType<?> declaringType = getDeclaringType(declaration);
        if (declaringType == null) return false;

        String methodName = execRef.getSimpleName();
        CtTypeReference<?> returnType = candidate.getType();
        String methodTypeCode = buildMethodTypeCode(returnType, declaration);
        List<CtExpression<?>> args = candidate.getArguments();

                      
        String varLookup = context.getNameGenerator().generate("lookup");
        String varMt = context.getNameGenerator().generate("mt");
        String varMh = context.getNameGenerator().generate("mh");

        boolean isStatic = execRef.isStatic();
        String declaringClassName = getCorrectSourceClassName(declaringType.getReference());
        String invokeTarget;
        String lookupCall;
        boolean isSuperCall = false;

        if (isStatic) {
            lookupCall = "findStatic(" + declaringClassName + ".class, \"" + methodName + "\", " + varMt + ")";
            invokeTarget = "null";
        } else {
            CtExpression<?> target = candidate.getTarget();
            boolean isImplicitThis = (target == null) || target.isImplicit() || target.toString().trim().isEmpty();
            isSuperCall = !isImplicitThis && "super".equals(target.toString().trim());
            if (isSuperCall) {
                CtType<?> hostType = candidate.getParent(CtType.class);
                if (hostType == null) {
                    return false;
                }
                String hostClassName = getCorrectSourceClassName(hostType.getReference());
                lookupCall = "findSpecial(" + declaringClassName + ".class, \"" + methodName + "\", "
                        + varMt + ", " + hostClassName + ".class)";
                invokeTarget = "this";
            } else {
                lookupCall = "findVirtual(" + declaringClassName + ".class, \"" + methodName + "\", " + varMt + ")";
                invokeTarget = isImplicitThis ? "this" : target.toString();
            }
        }

                        
        String commonSetup =
                "       java.lang.invoke.MethodHandles.Lookup " + varLookup + " = java.lang.invoke.MethodHandles.lookup();" +
                "       java.lang.invoke.MethodType " + varMt + " = " + methodTypeCode + ";" +
                "       java.lang.invoke.MethodHandle " + varMh + " = " + varLookup + "." + lookupCall + ";";

                                                       
        int mode = context.getRandom().nextInt(4);
        String combinatorCode = buildCombinatorCode(mode, varMh, varMt, returnType, declaration, args, isStatic, context);
        String fullSetup = commonSetup + combinatorCode;

                                                       
        String finalMhVar = getFinalMhVar(mode, varMh, context);
        if (finalMhVar == null) {
                                                 
            finalMhVar = varMh;
            fullSetup = commonSetup;
            mode = 0;
        }

        if (isStatementContext(candidate)) {
            String directArgs = buildArgsCode(args, lastBoundSourceArgIndex);
            String mhArgs = isStatic ? directArgs : (invokeTarget + (directArgs.isEmpty() ? "" : ", " + directArgs));

            replaceWithTryCatchStatement(candidate,
                    fullSetup + "       " + finalMhVar + ".invoke(" + mhArgs + ");",
                    context);
            log.debug("[Mutation] MethodHandle (Statement, mode={}): {}", mode, methodName);

        } else {
            boolean isVoid = (returnType == null) || "void".equals(returnType.getQualifiedName());
            String returnPrefix = isVoid ? "" : "return ";
            String castCode = isVoid ? "" : "(" + getBoxedTypeName(returnType) + ") ";

            final String capturedFinalMhVar = finalMhVar;
            final String capturedFullSetup = fullSetup;
            final int capturedMode = mode;
            final Integer capturedBoundSourceArgIndex = lastBoundSourceArgIndex;

            replaceWithSafeExpression(candidate, returnType, invokeTarget, args,
                    (targetVar, argVars) -> {
                        String innerArgs = buildArgsCode(argVars, capturedBoundSourceArgIndex);
                        String mhInvokeArgs;
                        if (isStatic) {
                            mhInvokeArgs = innerArgs;
                        } else {
                            mhInvokeArgs = innerArgs.isEmpty() ? targetVar : targetVar + ", " + innerArgs;
                        }

                        return capturedFullSetup +
                                "       " + returnPrefix + castCode + capturedFinalMhVar + ".invoke(" + mhInvokeArgs + ");";
                    },
                    context);
            log.debug("[Mutation] MethodHandle (Expression, mode={}): {}", capturedMode, methodName);
        }

        return true;
    }

                                 
    private String lastCombinatorMhVar;
    private Integer lastBoundSourceArgIndex;

       
                             
       
    private String buildCombinatorCode(int mode, String varMh, String varMt,
                                        CtTypeReference<?> returnType, CtExecutable<?> declaration,
                                        List<CtExpression<?>> args, boolean isStatic, MutationContext context) {
        lastCombinatorMhVar = varMh;            
        lastBoundSourceArgIndex = null;
        switch (mode) {
            case 1:                                
                return buildModeA(varMh, varMt, context);
            case 2:                                    
                return buildModeB(varMh, returnType, context);
            case 3:                                 
                if (!declaration.getParameters().isEmpty() && !args.isEmpty()) {
                    return buildModeC(varMh, args, isStatic, context);
                }
                return "";                                
            default:           
                return "";
        }
    }

       
                                                       
                               
       
    private String buildModeA(String varMh, String varMt, MutationContext context) {
        String varGeneric = context.getNameGenerator().generate("mh_generic");
        lastCombinatorMhVar = varGeneric;                  
        return "       java.lang.invoke.MethodHandle " + varGeneric + " = " + varMh + ".asType(" + varMt + ".generic());" +
               "       " + varGeneric + " = " + varGeneric + ".asType(" + varMt + ");";
    }

       
                                        
                                                 
       
    private String buildModeB(String varMh, CtTypeReference<?> returnType, MutationContext context) {
        if (returnType == null || "void".equals(returnType.getQualifiedName())) {
            return "";                               
        }
        String boxedReturn = getBoxedTypeName(returnType);
        String varFiltered = context.getNameGenerator().generate("mh_filt");
        lastCombinatorMhVar = varFiltered;                      
        return "       java.lang.invoke.MethodHandle " + varFiltered + " = java.lang.invoke.MethodHandles.filterReturnValue(" +
               varMh + ", java.lang.invoke.MethodHandles.identity(" + boxedReturn + ".class));";
    }

       
                                     
                                       
       
    private String buildModeC(String varMh, List<CtExpression<?>> args, boolean isStatic, MutationContext context) {
        String firstArg = args.get(0).toString();
        String varPartial = context.getNameGenerator().generate("mh_part");
        lastCombinatorMhVar = varPartial;                             
        lastBoundSourceArgIndex = 0;
        int mhParameterIndex = isStatic ? 0 : 1;
        return "       java.lang.invoke.MethodHandle " + varPartial + " = java.lang.invoke.MethodHandles.insertArguments(" +
               varMh + ", " + mhParameterIndex + ", " + firstArg + ");";
    }

       
                                               
                                                                   
       
    private String getFinalMhVar(int mode, String originalVarMh, MutationContext context) {
        return lastCombinatorMhVar != null ? lastCombinatorMhVar : originalVarMh;
    }

    private String buildArgsCode(List<?> args, Integer skipIndex) {
        if (args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (skipIndex != null && i == skipIndex) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(args.get(i).toString());
        }
        return sb.toString();
    }

    private String buildMethodTypeCode(CtTypeReference<?> returnType, CtExecutable<?> method) {
        StringBuilder sb = new StringBuilder();
        sb.append("java.lang.invoke.MethodType.methodType(");

        if (returnType == null || "void".equals(returnType.getQualifiedName())) {
            sb.append("void.class");
        } else {
            sb.append(getCorrectSourceClassName(returnType)).append(".class");
        }

        if (!method.getParameters().isEmpty()) {
            sb.append(", ");
            String params = method.getParameters().stream()
                    .map(p -> getCorrectSourceClassName(p.getType()) + ".class")
                    .collect(Collectors.joining(", "));
            sb.append(params);
        }

        sb.append(")");
        return sb.toString();
    }
}
