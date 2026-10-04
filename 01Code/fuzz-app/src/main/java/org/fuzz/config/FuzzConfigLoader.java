package org.fuzz.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

   
                                    
  
                                                                            
                                                                               
                                           
   
public class FuzzConfigLoader {
    private static final String CONFIG_FILE = "fuzz.yaml";
    private static final String EXTERNAL_CONFIG_ARG = "fuzz.config";
    private static final Set<String> OVERRIDE_PREFIXES = Set.of(
            "log.",
            "project.",
            "FuzzingEngine.",
            "BugReproducer.",
            "AbstractBlockOperator.",
            "ComputationChainOP.",
            "weight.",
            "operator.");

    private final YamlConfigLoader config;

    private FuzzConfigLoader() {
        this(new YamlConfigLoader(CONFIG_FILE, EXTERNAL_CONFIG_ARG, OVERRIDE_PREFIXES));
    }

    FuzzConfigLoader(YamlConfigLoader config) {
        this.config = config;
    }

    private static class Holder {
        private static final FuzzConfigLoader INSTANCE = new FuzzConfigLoader();
    }

    public static FuzzConfigLoader getInstance() {
        return Holder.INSTANCE;
    }

    public Map<String, Double> loadOperatorWeights() {
        Map<String, Double> weights = new HashMap<>();
        String weightPrefix = "weight.";
        String legacyPrefix = "operator.";
        String legacySuffix = ".weight";

        for (String key : config.properties().stringPropertyNames()) {
            if (key.startsWith(weightPrefix)) {
                String opName = key.substring(weightPrefix.length());
                weights.put(opName, getDouble(key, 1.0));
            } else if (key.startsWith(legacyPrefix) && key.endsWith(legacySuffix)) {
                String opName = key.substring(legacyPrefix.length(), key.length() - legacySuffix.length());
                weights.putIfAbsent(opName, getDouble(key, 1.0));
            }
        }
        return Collections.unmodifiableMap(weights);
    }

    public String getString(String key, String defaultValue) {
        return config.getString(key, defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return config.getBoolean(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return config.getInt(key, defaultValue);
    }

    public double getDouble(String key, double defaultValue) {
        return config.getDouble(key, defaultValue);
    }

    public Integer getOptionalInt(String key) {
        return config.getOptionalInt(key);
    }
}
