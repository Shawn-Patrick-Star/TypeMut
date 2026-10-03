package org.difftest.analysis.filter;

import org.difftest.model.exec.RunTimeExecutionResult;

import java.util.*;

   
                                
      
                     
                                             
   
public class ErrFilter {

                    
    private static final Set<String> UNCONDITIONAL_FILTERS = new HashSet<>(Arrays.asList(
            "ExceptionInInitializerError",
            "ensureError",
            "recordInitializationFailure",
            "ImportError"
    ));

                                            
                                                        
    private static final Map<String, String> EQUIVALENCE_MAP = new HashMap<>();

    static {
        EQUIVALENCE_MAP.put("newNoSuchMethodException", "NoSuchMethodException");
        EQUIVALENCE_MAP.put("NoClassDefFoundError", "ClassNotFoundException");
        EQUIVALENCE_MAP.put("JvmOutput-TIMEOUT", "StackOverflowError");
    }

    public void filte(RunTimeExecutionResult result) {
        if (result == null || result.getIssues() == null) return;

                                                            
        for (Map<String, Long> issueMap : result.getIssues().values()) {
            normalizeMap(issueMap);
        }
    }

       
                                                       
       
    private void normalizeMap(Map<String, Long> issueMap) {
        if (issueMap == null || issueMap.isEmpty()) {
            return;
        }

                                              
        issueMap.keySet().removeAll(UNCONDITIONAL_FILTERS);

                          
                                                                           
        Map<String, Long> canonicalCountsToAdd = new HashMap<>();

        Iterator<Map.Entry<String, Long>> iterator = issueMap.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            String issueName = entry.getKey();
            Long count = entry.getValue();

                                
            if (EQUIVALENCE_MAP.containsKey(issueName)) {
                                    
                iterator.remove();

                             
                String canonicalName = EQUIVALENCE_MAP.get(issueName);

                                                           
                canonicalCountsToAdd.merge(canonicalName, count, Long::sum);
            }
        }

                                            
        canonicalCountsToAdd.forEach((canonicalName, countToAdd) -> {
                                             
            issueMap.merge(canonicalName, countToAdd, Long::sum);
        });
    }
}