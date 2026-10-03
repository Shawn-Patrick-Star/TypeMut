package org.difftest.executor;

import org.difftest.model.exec.ExecutionResult;

   
           
                           
   
@FunctionalInterface
public interface ResultBuilder<T extends ExecutionResult> {
       
                              
       
    T build(String id, int exitCode, String stdout, String stderr, long duration);
}
