package org.fuzz.operator.impl.method;

import org.fuzz.core.MutationContext;
import org.fuzz.operator.AbstractMutationOperator;
import org.fuzz.selector.CustomMethodInvocationSelector;
import org.fuzz.util.AstUtils;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


public abstract class AbstractMethodOperator extends AbstractMutationOperator<CtInvocation> {

    protected AbstractMethodOperator() {
        super(new CustomMethodInvocationSelector());
    }

                         
                                                
                                                                 
    @FunctionalInterface
    protected interface BodyGenerator {
        String generate(String targetVar, List<String> argVars);
    }

                     

    protected boolean isStatementContext(CtInvocation<?> invocation) {
        CtElement parent = invocation.getParent();
        return parent instanceof CtBlock || parent instanceof CtCase;
    }

    protected String buildArgsCode(List<CtExpression<?>> args) {
        if (args.isEmpty()) return "";
        return args.stream().map(CtElement::toString).collect(Collectors.joining(", "));
    }

    protected CtType<?> getDeclaringType(CtExecutable<?> declaration) {
        if (declaration instanceof CtTypeMember) {
            return ((CtTypeMember) declaration).getDeclaringType();
        }
        return null;
    }

       
                          
                     
                   
                                 
       
    protected String getBoxedTypeName(CtTypeReference<?> type) {
        if (type == null) return "Object";
        if (type.isPrimitive()) {
            switch (type.getSimpleName()) {
                case "int": return "java.lang.Integer";
                case "long": return "java.lang.Long";
                case "double": return "java.lang.Double";
                case "float": return "java.lang.Float";
                case "boolean": return "java.lang.Boolean";
                case "byte": return "java.lang.Byte";
                case "short": return "java.lang.Short";
                case "char": return "java.lang.Character";
                case "void": return "java.lang.Void";
                default: return type.getSimpleName();
            }
        }
        return getCorrectSourceClassName(type);
    }

                     

       
                                  
                                                          
       
    protected void replaceWithTryCatchStatement(CtInvocation<?> invocation, String innerBody, MutationContext context) {
        Factory f = context.getFactory();

        CtStatement innerStmt = f.Code().createCodeSnippetStatement(innerBody);
        CtTry tryBlock = AstUtils.wrapInTryCatchRuntime(f, innerStmt, "Mutation failed");
        invocation.replace(tryBlock);
    }

       
                                           
                                                          
                                       
       
    protected void replaceWithSafeExpression(CtInvocation<?> invocation,
                                             CtTypeReference<?> returnType,
                                             String targetCode,                                    
                                             List<CtExpression<?>> originalArgs,          
                                             BodyGenerator generator,
                                             MutationContext context) {

        String returnTypeName = (returnType == null) ? "Object" : returnType.getQualifiedName();
        boolean isVoid = "void".equals(returnTypeName);
        String boxedReturnType = getBoxedTypeName(returnType);

                                                              
        StringBuilder argsArrayCode = new StringBuilder("new Object[] { ");
        argsArrayCode.append(targetCode);                               
        for (CtExpression<?> arg : originalArgs) {
            argsArrayCode.append(", ").append(arg.toString());
        }
        argsArrayCode.append(" }");

                                
                                 
                                  
        String innerTargetVar = "passedArgs[0]";
        List<String> innerArgVars = new ArrayList<>();
        for (int i = 0; i < originalArgs.size(); i++) {
            innerArgVars.add("passedArgs[" + (i + 1) + "]");
        }

                                                   
        String innerBody = generator.generate(innerTargetVar, innerArgVars);

                             
        StringBuilder snippet = new StringBuilder();

                                         
        snippet.append("((java.util.function.Function<Object[], ").append(boxedReturnType).append(">) (passedArgs) -> {");
        snippet.append("   try {");

        snippet.append(innerBody);

        if (isVoid) snippet.append(" return null;");

        snippet.append("   } catch (Throwable e) {");
        snippet.append("       throw new RuntimeException(e);");
        snippet.append("   }");
        snippet.append("}).apply(").append(argsArrayCode).append(")");              

        if (isStatementContext(invocation)) {
            CtCodeSnippetStatement stmt = context.getFactory().Code().createCodeSnippetStatement(snippet.toString() + ";");
            invocation.replace(stmt);
        } else {
            CtCodeSnippetExpression<?> lambdaExpr = context.getFactory().Code().createCodeSnippetExpression(snippet.toString());
            invocation.replace(lambdaExpr);
        }
    }
}
