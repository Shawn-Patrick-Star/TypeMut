package org.difftest.analysis.normalizer;

import java.util.ArrayList;
import java.util.List;

   
                      
                                                
   
public class NoiseNormalizer {

    private final List<String> ignoredPatterns = new ArrayList<>();

    public NoiseNormalizer() {
        initRules();
    }

       
                           
       
    private void initRules() {
                         
        ignoredPatterns.add("Picked up JAVA_TOOL_OPTIONS");
        
                                
        ignoredPatterns.add("java.lang.Class.throwException");
        ignoredPatterns.add("java.lang.J9VMInternals.ensureError");
        ignoredPatterns.add("ConvertHandle.throwWrongMethodTypeException");
        ignoredPatterns.add("InliningHandle.throwException");

                                    
        ignoredPatterns.add("JVMDUMP");
        ignoredPatterns.add("JVMPORT");

                                                
        ignoredPatterns.add("JFR: ");

    }

    public String normalize(String line) {
        if (line == null) return null;

                    
        if (line.startsWith("[warning]")) return null;

                                
        for (String pattern : ignoredPatterns) {
            if (line.contains(pattern)) {
                return null;
            }
        }

                                     
        return line.replaceAll("@[0-9a-fA-F]{4,}", "@ADDR");
    }
}
