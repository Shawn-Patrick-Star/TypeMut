package org.fuzz.selector;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtBlock;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;

public class BlockSelector implements PointSelector<CtBlock> {
    @Override
    public List<CtBlock> collect(CtModel model) {
                                 
                                                
        return model.getElements(new TypeFilter<>(CtBlock.class));
    }
}