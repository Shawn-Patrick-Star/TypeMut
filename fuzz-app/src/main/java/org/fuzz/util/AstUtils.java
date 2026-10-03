package org.fuzz.util;

import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;


public class AstUtils {

       
                                                                         
      
                             
                           
                               
                                                           
                                      
       
    public static CtFor createStandardForLoop(
            Factory f,
            String varName,
            int start,
            CtExpression<Integer> limit
    ) {
        CtFor loop = f.Core().createFor();

                                  
        CtTypeReference<Integer> intType = f.Type().integerPrimitiveType();
        CtLiteral<Integer> startVal = f.Code().createLiteral(start);
        CtLocalVariable<Integer> init = f.Code().createLocalVariable(intType, varName, startVal);
        loop.addForInit(init);

                                  
                   
        CtVariableAccess<Integer> varRead = f.Code().createVariableRead(init.getReference(), false);

        CtBinaryOperator<Boolean> cond = f.Code().createBinaryOperator(
                varRead,
                limit,
                BinaryOperatorKind.LT
        );
        loop.setExpression(cond);

                         
        CtUnaryOperator<Integer> update = f.Core().createUnaryOperator();
        update.setKind(UnaryOperatorKind.POSTINC);
                                                          
        update.setOperand(f.Code().createVariableRead(init.getReference(), false));
        loop.addForUpdate(update);

        return loop;
    }


       
                                                            
            
                     
                              
                                                        
        
       
    public static CtTry wrapInTryCatchRuntime(Factory f, CtStatement statement, String errorMessage) {
        CtTry tryBlock = f.Core().createTry();

                         
        CtBlock<?> body = f.Core().createBlock();
        body.addStatement(statement);
        tryBlock.setBody(body);

                        
        CtCatch catcher = f.Core().createCatch();

                          
        CtTypeReference<Throwable> throwableType = f.Type().createReference(Throwable.class);
        CtCatchVariable<Throwable> catchVar = f.Code().createCatchVariable(throwableType, "e");
        catcher.setParameter(catchVar);

                                                          
        CtConstructorCall<RuntimeException> newException = f.Core().createConstructorCall();
        newException.setType(f.Type().createReference(RuntimeException.class));

        if (errorMessage != null) {
            newException.addArgument(f.Code().createLiteral(errorMessage));
        }
                      
        newException.addArgument(f.Code().createVariableRead(catchVar.getReference(), false));

        CtThrow throwStmt = f.Core().createThrow();
        throwStmt.setThrownExpression(newException);

        CtBlock<?> catchBlock = f.Core().createBlock();
        catchBlock.addStatement(throwStmt);
        catcher.setBody(catchBlock);
        tryBlock.addCatcher(catcher);

        return tryBlock;
    }

       
                          
       
    public static CtType<?> findMainClassInModel(CtModel model) {
        return model.getAllTypes().stream()
                .filter(t -> t.isTopLevel())
                .filter(t -> t.getMethods().stream().anyMatch(m ->
                        "main".equals(m.getSimpleName()) &&
                                m.isStatic() &&
                                m.isPublic() &&
                                m.getParameters().size() == 1 &&
                                m.getParameters().get(0).getType().isArray()
                ))
                .findFirst()
                .orElse(null);
    }

                                                                                                   
               
                                                                                                   

    public static boolean isNumeric(CtTypeReference<?> type) {
        if (type == null) return false;
        if (type.isPrimitive()) {
            String n = type.getSimpleName();
            return "int".equals(n) || "long".equals(n) || "double".equals(n) || "float".equals(n) || "short".equals(n) || "byte".equals(n);
        }
        String qName = type.getQualifiedName();
        return "java.lang.Integer".equals(qName) || "java.lang.Long".equals(qName) ||
                "java.lang.Double".equals(qName) || "java.lang.Float".equals(qName) ||
                "java.lang.Short".equals(qName) || "java.lang.Byte".equals(qName);
    }

    public static boolean isBoolean(CtTypeReference<?> type) {
        return type != null && (
                (type.isPrimitive() && "boolean".equals(type.getSimpleName())) ||
                        "java.lang.Boolean".equals(type.getQualifiedName())
        );
    }

    public static boolean isArray(CtTypeReference<?> type) {
        return type != null && type.isArray();
    }

    public static boolean isCollection(CtTypeReference<?> type) {
        if (type == null) return false;
        String name = type.getSimpleName();
        return name.contains("List") || name.contains("Set") || name.contains("Map") || name.contains("Collection");
    }


}