package org.fuzz.core;

import org.difftest.model.DTResult;
import org.fuzz.config.FuzzConfig;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

   
           
                                                         
   
public class BugDeduplicator {

                                
    private final Set<String> knownBugs = Collections.newSetFromMap(new ConcurrentHashMap<>());

       
                             
      
                                       
                                              
                                                         
       
    public boolean isDuplicate(String seedId, DTResult result) {
        return isDuplicate(seedId, result, FuzzConfig.EXPERIMENTAL_MODE);
    }

       
                                                        
       
    public boolean isDuplicate(String seedId, DTResult result, boolean experimentalMode) {
        String signature = generateSignature(seedId, result, experimentalMode);
        
                                                
                                           
        boolean isNewBug = knownBugs.add(signature);
        return !isNewBug;
    }

       
                       
       
    public int getUniqueBugCount() {
        return knownBugs.size();
    }

       
                    
       
                                                    
                            
       
                                              
       
    private String generateSignature(String seedId, DTResult result, boolean experimentalMode) {
                           
                                      
        StringBuilder behaviorSig = new StringBuilder();
        behaviorSig.append(result.getType().name()).append("|");

                                                                
        if (result.getExecutionResults() != null && !result.getExecutionResults().isEmpty()) {
            result.getExecutionResults().stream()
                    .sorted(java.util.Comparator.comparing(org.difftest.model.exec.ExecutionResult::getId))
                    .forEach(res -> {
                        behaviorSig.append(res.getId()).append("[");
                        if (res instanceof org.difftest.model.exec.RunTimeExecutionResult runRes) {
                                                    
                            behaviorSig.append(runRes.getStatus()).append(":");
                            runRes.getIssues().forEach((type, map) -> {
                                if (!map.isEmpty()) {
                                    behaviorSig.append(type).append(map.keySet());
                                }
                            });
                        } else {
                                                      
                            behaviorSig.append(res.getStdout().hashCode())
                                       .append(res.getStderr().hashCode());
                        }
                        behaviorSig.append("]|");
                    });
        }
        
        String behaviorString = behaviorSig.toString();

        if (experimentalMode) {
                                            
            return behaviorString;
        } else {
                                  
            return seedId + "::" + behaviorString;
        }
    }
}
