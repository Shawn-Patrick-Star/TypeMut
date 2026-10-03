package org.fuzz.selector;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;
import java.util.stream.Collectors;

   
             
                          
   
public class CustomMethodInvocationSelector implements PointSelector<CtInvocation> {

    @Override
    public List<CtInvocation> collect(CtModel model) {
        return model.getElements(new TypeFilter<>(CtInvocation.class)).stream()
                .filter(this::isCustomMethod)
                .collect(Collectors.toList());
    }

    private boolean isCustomMethod(CtInvocation<?> invocation) {
        if (invocation.isImplicit()) return false;

        CtExecutableReference<?> execRef = invocation.getExecutable();
        if (execRef == null || execRef.isConstructor()) return false;

        CtExecutable<?> declaration = execRef.getExecutableDeclaration();
        if (declaration == null) return false;                  

               
        if (!(declaration instanceof CtTypeMember)) return false;
        CtType<?> declaringType = ((CtTypeMember) declaration).getDeclaringType();
        if (declaringType != null) {
            String typeName = declaringType.getQualifiedName();
            return !typeName.startsWith("java.") && !typeName.startsWith("javax.") &&
                    !typeName.startsWith("sun.") && !typeName.startsWith("jdk.");
        }
        return true;
    }
}