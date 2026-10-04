package org.fuzz.util;

import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtForEach;
import spoon.reflect.declaration.CtElement;

public final class LoopDepthAnalyzer {
    private LoopDepthAnalyzer() {}

    public static int enclosingForDepth(CtElement element) {
        int depth = 0;
        CtElement current = element == null ? null : element.getParent();
        while (current != null) {
            if (current instanceof CtFor || current instanceof CtForEach) depth++;
            current = current.getParent();
        }
        return depth;
    }
}
