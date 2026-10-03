package org.fuzz.selector;

import org.fuzz.util.TypeUtils;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;

   
                  
        
                                  
                                               
                                            
   
public class ConstructorCallSelector implements PointSelector<CtConstructorCall<?>> {

    @Override
    public List<CtConstructorCall<?>> collect(CtModel model) {
                                                                       
                                                   
                                          
        @SuppressWarnings("unchecked")
        Class<? super CtConstructorCall<?>> callClazz = (Class<? super CtConstructorCall<?>>) (Class<?>) CtConstructorCall.class;
        TypeFilter<CtConstructorCall<?>> filter = new TypeFilter<>(callClazz);

        List<CtConstructorCall<?>> result = new ArrayList<>();
        for (CtConstructorCall<?> call : model.getElements(filter)) {
            if (isExtendableTarget(call)) {
                result.add(call);
            }
        }
        return result;
    }

    private boolean isExtendableTarget(CtConstructorCall<?> call) {
        if (call.isImplicit() || !call.getPosition().isValidPosition()) return false;

        CtTypeReference<?> type = call.getType();
        if (type == null) return false;

                             
        String qName = type.getQualifiedName();
        if (qName.startsWith("java.") || qName.startsWith("javax.") ||
                qName.startsWith("sun.") || qName.startsWith("jdk.")) {
            return false;
        }

                                                         
        CtType<?> declaration = type.getTypeDeclaration();
        if (declaration == null) return false;

                  
        if (TypeUtils.isFinal(type)) return false;                 
        if (type.isArray()) return false;
        if (type.isInterface()) return false;
        if (type.isAnonymous()) return false;
        if (type.isEnum()) return false;

                                                      
        if (type.getSimpleName().startsWith("_Poly_Sub_")) return false;

        return true;
    }
}