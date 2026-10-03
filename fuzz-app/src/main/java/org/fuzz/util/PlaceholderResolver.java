package org.fuzz.util;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

   
           
                                              
   
public class PlaceholderResolver {
    private static final Pattern PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final int MAX_DEPTH = 5;

       
                                             
       
    public static void resolveAll(Properties props) {
        boolean changed;
        int currentDepth = 0;
        do {
            changed = false;
            List<String> keys = new ArrayList<>(props.stringPropertyNames());
            for (String key : keys) {
                String value = props.getProperty(key);

                           
                String newValue = resolve(value, props::getProperty);
                if (value != null && !value.equals(newValue)) {
                    props.setProperty(key, newValue);
                    changed = true;
                }

                                          
                if (key.contains("${")) {
                    String newKey = resolve(key, props::getProperty);
                    if (!key.equals(newKey)) {
                        props.remove(key);
                        props.setProperty(newKey, newValue != null ? newValue : "");
                        changed = true;
                    }
                }
            }
        } while (changed && ++currentDepth < MAX_DEPTH);
    }

       
                   
                         
                               
                      
       
    private static String resolve(String input, Function<String, String> lookup) {
        if (input == null || !input.contains("${")) {
            return input;
        }

        Matcher matcher = PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
                                           
            String replacement = System.getProperty(placeholder);
                                 
            if (replacement == null && lookup != null) {
                replacement = lookup.apply(placeholder);
            }

            if (replacement != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                                     
                matcher.appendReplacement(sb, "\\$\\{" + placeholder + "\\}");
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
