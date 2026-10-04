package org.fuzz.operator;

import org.fuzz.core.MutationContext;

public interface MutOP {
    String getName();
    boolean apply(MutationContext context);
    default double getWeight() { return 1.0; }
}
