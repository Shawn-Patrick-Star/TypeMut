package org.fuzz.selector;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;
import java.util.stream.Collectors;

   
                                     
        
           
         
           
   
public class LocalVariableSelector implements PointSelector<CtLocalVariable> {

    @Override
    public List<CtLocalVariable> collect(CtModel model) {
        return model.getElements(new TypeFilter<>(CtLocalVariable.class)).stream()
                .filter(v -> !v.isImplicit())
                .filter(v -> v.getType() != null)
                .filter(v -> v.getPosition() != null && v.getPosition().isValidPosition())
                .collect(Collectors.toList());
    }
}
