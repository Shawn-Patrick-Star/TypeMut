package org.fuzz.util;

import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.lang.reflect.Modifier;
import java.util.*;

public class TypeUtils {

                      
    private static final Map<String, List<String>> WIDENING_MAP = new HashMap<>();
                       
    private static final Map<String, List<String>> NARROWING_MAP = new HashMap<>();
                    
    private static final Map<String, String> BOXING_MAP = new HashMap<>();

    static {
                                               
        WIDENING_MAP.put("byte", Arrays.asList("short", "int", "long", "float", "double"));
        WIDENING_MAP.put("short", Arrays.asList("int", "long", "float", "double"));
        WIDENING_MAP.put("char", Arrays.asList("int", "long", "float", "double"));
        WIDENING_MAP.put("int", Arrays.asList("long", "float", "double"));
        WIDENING_MAP.put("long", Arrays.asList("float", "double"));
        WIDENING_MAP.put("float", Collections.singletonList("double"));

                                               
                            
        NARROWING_MAP.put("double", Arrays.asList("float", "long", "int", "char", "short", "byte"));
        NARROWING_MAP.put("float", Arrays.asList("long", "int", "char", "short", "byte"));
        NARROWING_MAP.put("long", Arrays.asList("int", "char", "short", "byte"));
        NARROWING_MAP.put("int", Arrays.asList("char", "short", "byte"));
        NARROWING_MAP.put("short", Arrays.asList("char", "byte"));
        NARROWING_MAP.put("char", Arrays.asList("short", "byte"));
                                                                                                  
        NARROWING_MAP.put("byte", Collections.singletonList("char"));

                          
        BOXING_MAP.put("int", "java.lang.Integer");
        BOXING_MAP.put("long", "java.lang.Long");
        BOXING_MAP.put("double", "java.lang.Double");
        BOXING_MAP.put("float", "java.lang.Float");
        BOXING_MAP.put("short", "java.lang.Short");
        BOXING_MAP.put("byte", "java.lang.Byte");
        BOXING_MAP.put("boolean", "java.lang.Boolean");
        BOXING_MAP.put("char", "java.lang.Character");
    }

       
                      
       
    public static List<String> getWideningTargets(String primitiveName) {
        return WIDENING_MAP.getOrDefault(primitiveName, Collections.emptyList());
    }

       
                      
       
    public static List<String> getNarrowingTargets(String primitiveName) {
        return NARROWING_MAP.getOrDefault(primitiveName, Collections.emptyList());
    }

       
                  
       
    public static String getBoxingCounterpart(CtTypeReference<?> type) {
        if (type.isPrimitive()) {
            return BOXING_MAP.get(type.getSimpleName());
        } else {
            for (Map.Entry<String, String> entry : BOXING_MAP.entrySet()) {
                if (entry.getValue().equals(type.getQualifiedName())) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    public static boolean isBoxedType(CtTypeReference<?> type) {
        return BOXING_MAP.containsValue(type.getQualifiedName());
    }


       
                              
                        
       
    public static boolean isFinal(CtTypeReference<?> type) {
        if (type == null) return false;

                                              
        CtType<?> declaration = type.getTypeDeclaration();
        if (declaration != null) {
            return declaration.isFinal();
        }

                                                             
                                             
        try {
            Class<?> actualClass = type.getActualClass();
            if (actualClass != null) {
                return Modifier.isFinal(actualClass.getModifiers());
            }
        } catch (Exception e) {
                              
        }

                                           
                                                        
        String qName = type.getQualifiedName();
        if (qName.startsWith("java.lang.")) {
            return qName.equals("java.lang.String") ||
                    qName.equals("java.lang.Integer") ||
                    qName.equals("java.lang.Long") ||
                    qName.equals("java.lang.Double") ||
                    qName.equals("java.lang.Float") ||
                    qName.equals("java.lang.Byte") ||
                    qName.equals("java.lang.Short") ||
                    qName.equals("java.lang.Character") ||
                    qName.equals("java.lang.Boolean") ||
                    qName.equals("java.lang.System") ||
                    qName.equals("java.lang.Math");
        }

                                                                    
        return false;
    }
}