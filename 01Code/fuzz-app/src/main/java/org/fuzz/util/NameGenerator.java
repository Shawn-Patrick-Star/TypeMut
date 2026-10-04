package org.fuzz.util;

import java.util.Random;

   
          
                      
                          
   
public class NameGenerator {
    private final Random random;
    private int counter = 0;

    public NameGenerator(Random random) {
        this.random = random;
    }

       
                  
                                            
       
    public String generate(String prefix) {
        counter++;
                                          
        String suffix = Integer.toHexString(random.nextInt(0xFFFFF));
        return "_" + prefix + "_" + counter + "_" + suffix;
    }

    public String generateClassName(String baseName) {
        return "_Mutated_" + baseName + "_" + Integer.toHexString(random.nextInt(0xFFFF));
    }
}