package org.fuzz.selector;

import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtElement;

import java.util.List;

   
           
                            
   
public interface PointSelector<T extends CtElement> {
    List<T> collect(CtModel model);
}